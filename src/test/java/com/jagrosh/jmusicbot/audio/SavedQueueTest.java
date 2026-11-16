package com.jagrosh.jmusicbot.audio;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class SavedQueueTest
{
    private static final SavedQueue.Entry A = new SavedQueue.Entry("https://www.youtube.com/watch?v=a", "Song A", 42, "gunna", null);
    private static final SavedQueue.Entry B = new SavedQueue.Entry("https://www.youtube.com/watch?v=b", "Song B", 0, null, "https://x/y.png");

    @Test
    public void roundTrips()
    {
        SavedQueue.Snapshot s = new SavedQueue.Snapshot(1000, A, 61_500, List.of(B, A));
        SavedQueue.Snapshot back = SavedQueue.fromJson(SavedQueue.toJson(s));
        assertEquals(s, back);
    }

    @Test
    public void sameAsIgnoresSaveTimeAndSubSecondPosition()
    {
        SavedQueue.Snapshot s = new SavedQueue.Snapshot(1000, A, 61_100, List.of(B));
        assertTrue(s.sameAs(new SavedQueue.Snapshot(9000, A, 61_900, List.of(B))));
        assertFalse(s.sameAs(new SavedQueue.Snapshot(1000, A, 62_000, List.of(B))));
        assertFalse(s.sameAs(new SavedQueue.Snapshot(1000, A, 61_100, List.of())));
        assertFalse(s.sameAs(null));
    }

    @Test
    public void describesWhatComesBack()
    {
        assertEquals("Song A", SavedQueue.describe(new SavedQueue.Snapshot(0, A, 0, List.of())));
        assertEquals("Song A, and one more after it", SavedQueue.describe(new SavedQueue.Snapshot(0, A, 0, List.of(B))));
        assertEquals("Song A, and 2 more after it", SavedQueue.describe(new SavedQueue.Snapshot(0, A, 0, List.of(B, B))));
    }
}
