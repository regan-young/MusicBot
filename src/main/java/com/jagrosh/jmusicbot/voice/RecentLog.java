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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Set;

/**
 * Keeps the bot's most recent log lines in memory (registered in
 * logback.xml), so an introspect question like "why are you so slow?" can
 * hand them to the coder sidecar along with the code.
 */
public class RecentLog extends AppenderBase<ILoggingEvent>
{
    private static final int MAX_LINES = 3000;
    // DAVE decrypt failures arrive several times a second and say nothing about the bot
    private static final Set<String> NOISE = Set.of("DaveDecryptor", "AudioPacket");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final ArrayDeque<String> lines = new ArrayDeque<>();

    @Override
    protected void append(ILoggingEvent event)
    {
        String logger = event.getLoggerName();
        logger = logger.substring(logger.lastIndexOf('.') + 1);
        if (NOISE.contains(logger))
            return;
        StringBuilder line = new StringBuilder(TIME.format(Instant.ofEpochMilli(event.getTimeStamp())))
                .append(" [").append(event.getThreadName()).append("] ").append(event.getLevel())
                .append(' ').append(logger).append(" - ").append(event.getFormattedMessage());
        // the exception chain without stack frames: enough to see what failed
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause())
            line.append("\n  caused by ").append(t.getClassName()).append(": ").append(t.getMessage());
        synchronized (lines)
        {
            lines.addLast(line.toString());
            if (lines.size() > MAX_LINES)
                lines.removeFirst();
        }
    }

    /** @return the newest lines, oldest first, at most maxChars long */
    public static String text(int maxChars)
    {
        ArrayDeque<String> newest = new ArrayDeque<>();
        int length = 0;
        synchronized (lines)
        {
            var it = lines.descendingIterator();
            while (it.hasNext())
            {
                String line = it.next();
                if (length + line.length() + 1 > maxChars)
                    break;
                newest.addFirst(line);
                length += line.length() + 1;
            }
        }
        return String.join("\n", newest) + "\n";
    }
}
