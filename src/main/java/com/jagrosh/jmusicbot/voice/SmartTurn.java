/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.voice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Map;

/**
 * Smart Turn v3 (pipecat-ai/smart-turn, BSD-2): from the last 8 seconds of
 * someone's speech, the probability that they've finished talking. It hears
 * intonation and trailing words ("can you, um..."), which a silence timer
 * can't. Features are Whisper's log-mel spectrogram, ported from
 * transformers' WhisperFeatureExtractor (chunk_length=8, do_normalize=True)
 * and checked against it.
 */
public class SmartTurn implements AutoCloseable
{
    static final int RATE = 16_000;
    static final int SAMPLES = 8 * RATE;
    static final int N_FFT = 400, HOP = 160, BINS = N_FFT / 2 + 1, MELS = 80, FRAMES = SAMPLES / HOP;

    // mel scale; before MEL_FILTERS, which uses it during class initialisation
    private static final double LOG_STEP = 27.0 / Math.log(6.4);
    private static final double[] WINDOW = new double[N_FFT];
    private static final double[] COS = new double[BINS * N_FFT], SIN = new double[BINS * N_FFT];
    static final double[][] MEL_FILTERS = melFilters();

    static
    {
        for (int n = 0; n < N_FFT; n++)
            WINDOW[n] = 0.5 - 0.5 * Math.cos(2 * Math.PI * n / N_FFT); // periodic Hann
        for (int k = 0; k < BINS; k++)
            for (int n = 0; n < N_FFT; n++)
            {
                double a = 2 * Math.PI * k * n / N_FFT;
                COS[k * N_FFT + n] = Math.cos(a);
                SIN[k * N_FFT + n] = Math.sin(a);
            }
    }

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;

    public SmartTurn(Path model) throws OrtException
    {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        // one small request at a time; don't take every core from the bot
        options.setIntraOpNumThreads(1);
        options.setInterOpNumThreads(1);
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(model.toString(), options);
    }

    /**
     * @param pcm 16 kHz mono 16-bit little-endian audio, ending with the
     *            silence heard so far
     * @return the probability (0-1) that the speaker has finished their turn
     */
    public synchronized float probability(byte[] pcm) throws OrtException
    {
        float[] features = features(pcm);
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), new long[] { 1, MELS, FRAMES });
             OrtSession.Result result = session.run(Map.of("input_features", input)))
        {
            return ((float[][]) result.get(0).getValue())[0][0];
        }
    }

    @Override
    public void close() throws OrtException
    {
        session.close();
    }

    /** The model's input: an 80 x 800 log-mel spectrogram, row-major. */
    static float[] features(byte[] pcm)
    {
        // the last 8 s, zero-padded at the start
        int n = pcm.length / 2, take = Math.min(n, SAMPLES), skip = n - take, pad = SAMPLES - take;
        double[] x = new double[SAMPLES];
        for (int i = 0; i < take; i++)
        {
            int j = 2 * (skip + i);
            x[pad + i] = (short) ((pcm[j] & 0xff) | (pcm[j + 1] << 8)) / 32768.0;
        }
        // zero mean, unit variance over the whole window
        double mean = 0, var = 0;
        for (double v : x)
            mean += v;
        mean /= SAMPLES;
        for (double v : x)
            var += (v - mean) * (v - mean);
        double scale = 1 / Math.sqrt(var / SAMPLES + 1e-7);
        for (int i = 0; i < SAMPLES; i++)
            x[i] = (x[i] - mean) * scale;

        // centred frames: reflect-pad by half a frame on each side
        int half = N_FFT / 2;
        double[] padded = new double[SAMPLES + N_FFT];
        for (int i = 0; i < padded.length; i++)
        {
            int j = i - half;
            padded[i] = x[j < 0 ? -j : j >= SAMPLES ? 2 * SAMPLES - 2 - j : j];
        }

        double[] logMel = new double[MELS * FRAMES];
        double[] frame = new double[N_FFT], power = new double[BINS], mel = new double[MELS];
        double[] padMel = null; // frames inside the (constant) padding are all the same
        double max = Double.NEGATIVE_INFINITY;
        for (int t = 0; t < FRAMES; t++)
        {
            int start = t * HOP;
            boolean inPadding = start + N_FFT <= half + pad;
            if (!inPadding || padMel == null)
            {
                for (int i = 0; i < N_FFT; i++)
                    frame[i] = padded[start + i] * WINDOW[i];
                for (int k = 0; k < BINS; k++)
                {
                    double re = 0, im = 0;
                    int row = k * N_FFT;
                    for (int i = 0; i < N_FFT; i++)
                    {
                        re += frame[i] * COS[row + i];
                        im -= frame[i] * SIN[row + i];
                    }
                    power[k] = re * re + im * im;
                }
                for (int m = 0; m < MELS; m++)
                {
                    double s = 0;
                    for (int k = 0; k < BINS; k++)
                        s += MEL_FILTERS[k][m] * power[k];
                    mel[m] = Math.log10(Math.max(s, 1e-10));
                }
                if (inPadding)
                    padMel = mel.clone();
            }
            double[] col = inPadding ? padMel : mel;
            for (int m = 0; m < MELS; m++)
            {
                logMel[m * FRAMES + t] = col[m];
                max = Math.max(max, col[m]);
            }
        }
        float[] out = new float[logMel.length];
        for (int i = 0; i < out.length; i++)
            out[i] = (float) ((Math.max(logMel[i], max - 8) + 4) / 4);
        return out;
    }

    /** Slaney-style mel filter bank, 0-8000 Hz, as transformers builds it for Whisper: [bin][mel]. */
    static double[][] melFilters()
    {
        double minMel = hzToMel(0), maxMel = hzToMel(RATE / 2.0);
        double[] edges = new double[MELS + 2];
        for (int i = 0; i < edges.length; i++)
            edges[i] = melToHz(minMel + (maxMel - minMel) * i / (MELS + 1));
        double[][] filters = new double[BINS][MELS];
        for (int k = 0; k < BINS; k++)
        {
            double f = (RATE / 2.0) * k / (BINS - 1);
            for (int m = 0; m < MELS; m++)
            {
                double down = (f - edges[m]) / (edges[m + 1] - edges[m]);
                double up = (edges[m + 2] - f) / (edges[m + 2] - edges[m + 1]);
                double norm = 2.0 / (edges[m + 2] - edges[m]);
                filters[k][m] = Math.max(0, Math.min(down, up)) * norm;
            }
        }
        return filters;
    }

    private static double hzToMel(double hz)
    {
        return hz < 1000 ? 3 * hz / 200 : 15 + Math.log(hz / 1000) * LOG_STEP;
    }

    private static double melToHz(double mel)
    {
        return mel < 15 ? 200 * mel / 3 : 1000 * Math.exp((mel - 15) / LOG_STEP);
    }
}
