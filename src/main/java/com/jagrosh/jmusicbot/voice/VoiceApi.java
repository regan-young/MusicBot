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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Client for an OpenAI-compatible speech server (/v1/audio/speech and
 * /v1/audio/transcriptions). In the homelab that's mlx-audio on the MacBook.
 */
public class VoiceApi
{
    // HTTP/1.1: by default the client asks a plain-http server to upgrade to
    // HTTP/2 (h2c), and uvicorn rejects those requests with 422
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl, sttModel, ttsModel, ttsVoice;

    public VoiceApi(String baseUrl, String sttModel, String ttsModel, String ttsVoice)
    {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.sttModel = sttModel;
        this.ttsModel = ttsModel;
        this.ttsVoice = ttsVoice;
    }

    /** Synthesises speech; returns a WAV file. */
    public byte[] speak(String text) throws IOException, InterruptedException
    {
        JSONObject body = new JSONObject()
                .put("model", ttsModel)
                .put("input", text)
                .put("voice", ttsVoice)
                .put("response_format", "wav");
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/audio/speech"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200 || res.body().length == 0)
            throw new IOException("TTS failed: HTTP " + res.statusCode());
        return res.body();
    }

    /** Transcribes a WAV file; returns the text (possibly empty). */
    public String transcribe(byte[] wav) throws IOException, InterruptedException
    {
        String boundary = "----gunna" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 512);
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                + sttModel + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(wav);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/audio/transcriptions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200)
            throw new IOException("STT failed: HTTP " + res.statusCode());
        return new JSONObject(res.body()).optString("text", "").trim();
    }
}
