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
package com.jagrosh.jmusicbot.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class VarietyTest
{
    private record T(String id, String title, String author) {}

    private static List<T> pick(List<T> items, Set<String> ids, Set<String> keys, int wanted, long seed)
    {
        return Variety.pick(items, T::id, T::title, T::author, ids, keys, wanted, new Random(seed));
    }

    @Test
    public void theSameSongFromAnotherUploadIsTheSameSong()
    {
        assertEquals(Variety.key("Oasis - Wonderwall (Official Video)", "OasisVEVO"), Variety.key("Wonderwall [Remastered]", "Oasis"));
        assertEquals("harry styles", Variety.artist("Watermelon Sugar", "HarryStylesVEVO"));
        assertEquals("harry styles", Variety.artist("Harry Styles & Lizzo - Juice", "x"));
        assertEquals("the beths", Variety.artist("Little Death", "The Beths - Topic"));
    }

    @Test
    public void leavesOutRecentSongsAndCapsEachArtist()
    {
        List<T> mix = new ArrayList<>();
        for (int i = 0; i < 8; i++)
            mix.add(new T("h" + i, "Harry Styles - Song " + i + " (Official Video)", "HarryStylesVEVO"));
        mix.add(new T("o1", "Oasis - Wonderwall (Official Video)", "OasisVEVO"));
        mix.add(new T("b1", "Billie Eilish - BIRDS OF A FEATHER", "BillieEilishVEVO"));
        mix.add(new T("b2", "Billie Eilish - BIRDS OF A FEATHER (Lyrics)", "Lyrics Channel")); // a second upload
        mix.add(new T("t1", "Teddy Swims - Lose Control", "Teddy Swims"));
        mix.add(new T("c1", "Chappell Roan - Good Luck, Babe!", "Chappell Roan"));

        for (long seed = 0; seed < 20; seed++)
        {
            List<T> got = pick(mix, Set.of("o1"), Set.of(Variety.key("Lose Control", "Teddy Swims")), 8, seed);
            assertEquals(2, got.stream().filter(t -> t.author().startsWith("Harry")).count());
            assertFalse(got.stream().anyMatch(t -> t.id().equals("o1") || t.id().equals("t1")));
            assertTrue(got.stream().filter(t -> t.title().contains("BIRDS")).count() <= 1);
            // kept in the mix's order
            for (int i = 1; i < got.size(); i++)
                assertTrue(mix.indexOf(got.get(i - 1)) < mix.indexOf(got.get(i)));
        }
    }

    @Test
    public void variesBetweenPicks()
    {
        List<T> mix = new ArrayList<>();
        for (int i = 0; i < 25; i++)
            mix.add(new T("s" + i, "Artist " + i + " - Song " + i, "Artist " + i));
        assertFalse(pick(mix, Set.of(), Set.of(), 8, 1).equals(pick(mix, Set.of(), Set.of(), 8, 2)));
    }

    @Test
    public void picksFromRecentSongsRatherThanNothing()
    {
        List<T> mix = List.of(new T("a", "A - One", "A"), new T("b", "B - Two", "B"));
        assertEquals(2, pick(mix, Set.of("a", "b"), Set.of(), 8, 0).size());
    }
}
