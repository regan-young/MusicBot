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

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the chat LLM's JSON answer ({"action": ..., "query": ..., "reply":
 * "..."}) as it streams in, and passes each finished sentence of the reply
 * on as soon as it's complete, so the bot can start speaking before the
 * model has finished writing. Only chat replies stream: if the action isn't
 * "chat", or comes after the reply, nothing is passed on and the caller uses
 * the full answer.
 */
class ReplyStream
{
    private static final Pattern ACTION = Pattern.compile("\"action\"\\s*:\\s*\"([a-zA-Z_]*)\"");
    private static final Pattern REPLY = Pattern.compile("\"reply\"\\s*:\\s*\"");
    // a sentence ends at . ! or ? (and any closing quote or bracket) followed by a space,
    // but not after a title like "Mr." in "Mr. Brightside"
    private static final Pattern END = Pattern.compile("(?<!\\b(?:Mr|Mrs|Ms|Dr|St|vs))[.!?]+[\"')\\]]*\\s");
    // a sentence shorter than this joins the next, as in VoiceManager.sentences
    static final int MIN_SENTENCE = 24;

    private final Consumer<String> onSentence;
    private final StringBuilder raw = new StringBuilder();
    private final StringBuilder reply = new StringBuilder(); // decoded reply text not yet passed on
    private int pos = -1;          // where to keep decoding the reply in raw; -1 before it starts
    private boolean streaming, ended;
    private int sentences;

    ReplyStream(Consumer<String> onSentence)
    {
        this.onSentence = onSentence;
    }

    /** Adds the next piece of the model's output. */
    void feed(String delta)
    {
        raw.append(delta);
        if (pos < 0)
        {
            Matcher r = REPLY.matcher(raw);
            if (!r.find())
                return;
            Matcher a = ACTION.matcher(raw);
            streaming = onSentence != null && a.find() && a.end() <= r.start() && a.group(1).equalsIgnoreCase("chat");
            pos = r.end();
        }
        if (!streaming || ended)
            return;
        decode();
        emitSentences(false);
    }

    /** The stream ended: passes on whatever is left of the reply. */
    void finish()
    {
        if (streaming)
            emitSentences(true);
    }

    /** Everything the model wrote. */
    String content()
    {
        return raw.toString();
    }

    /** How many sentences were passed on (spoken). */
    int sentences()
    {
        return sentences;
    }

    /** Decodes the JSON string from pos up to the end of what's arrived, stopping at its closing quote. */
    private void decode()
    {
        while (pos < raw.length())
        {
            char c = raw.charAt(pos);
            if (c == '"')
            {
                ended = true;
                pos++;
                return;
            }
            if (c != '\\')
            {
                reply.append(c);
                pos++;
                continue;
            }
            if (pos + 1 >= raw.length())
                return; // the escape continues in the next piece
            char e = raw.charAt(pos + 1);
            if (e == 'u')
            {
                if (pos + 6 > raw.length())
                    return;
                reply.append((char) Integer.parseInt(raw.substring(pos + 2, pos + 6), 16));
                pos += 6;
                continue;
            }
            switch (e)
            {
                case 'n': case 'r': case 't': reply.append(' '); break;
                case 'b': case 'f': break;
                default: reply.append(e); // \" \\ \/
            }
            pos += 2;
        }
    }

    private void emitSentences(boolean all)
    {
        int from = 0;
        Matcher m = END.matcher(reply);
        while (m.find())
        {
            if (m.end() - from < MIN_SENTENCE)
                continue;
            emit(reply.substring(from, m.end()));
            from = m.end();
        }
        if ((all || ended) && from < reply.length())
        {
            emit(reply.substring(from));
            from = reply.length();
        }
        reply.delete(0, from);
    }

    private void emit(String sentence)
    {
        String s = LlmClient.forSpeech(sentence);
        if (s.isEmpty())
            return;
        sentences++;
        onSentence.accept(s);
    }
}
