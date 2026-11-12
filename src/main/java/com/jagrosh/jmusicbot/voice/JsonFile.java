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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;

/** Saving the bot's small JSON files (memories, reminders, last seen, the coder whitelist). */
final class JsonFile
{
    private JsonFile() {}

    /**
     * Replaces a file in one step, by writing a copy beside it and moving
     * that into place, so a crash or restart mid-write can't leave half a
     * file. A failure is logged, not thrown: the bot carries on with what it
     * has in memory.
     */
    static void save(Path file, String json, Logger log)
    {
        try
        {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            log.warn("Could not save {}", file, e);
        }
    }
}
