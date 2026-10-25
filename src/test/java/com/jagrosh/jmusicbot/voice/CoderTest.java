package com.jagrosh.jmusicbot.voice;

import com.jagrosh.jmusicbot.voice.VoiceCommand.Type;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.junit.Test;

import static org.junit.Assert.*;

public class CoderTest
{
    private static final long OWNER = 1L;

    /** A Member with just an id and names; anything else throws. */
    private static Member member(long id, String nick, String username)
    {
        User user = (User) Proxy.newProxyInstance(User.class.getClassLoader(), new Class<?>[] { User.class }, (p, m, a) ->
        {
            switch (m.getName())
            {
                case "getName": return username;
                case "getIdLong": return id;
                case "getId": return Long.toString(id);
                default: throw new UnsupportedOperationException(m.getName());
            }
        });
        return (Member) Proxy.newProxyInstance(Member.class.getClassLoader(), new Class<?>[] { Member.class }, (p, m, a) ->
        {
            switch (m.getName())
            {
                case "getEffectiveName": return nick;
                case "getUser": return user;
                case "getIdLong": return id;
                case "getId": return Long.toString(id);
                default: throw new UnsupportedOperationException(m.getName());
            }
        });
    }

    @Test
    public void picksByNameOrNickname()
    {
        Member sam = member(2, "Sam", "samwise"), kfc = member(3, "KFC", "colonel"), sammy = member(4, "Sammy J", "sj");
        List<Member> all = List.of(sam, kfc, sammy);
        assertSame(kfc, Coder.pick("kfc", all));
        assertSame(kfc, Coder.pick("@Colonel", all));
        // "Sam" is exactly Sam, even though it's also the start of Sammy J
        assertSame(sam, Coder.pick("Sam", all));
        assertSame(sammy, Coder.pick("sammy", all));
        assertNull(Coder.pick("nobody", all));
        // one @mention and no name: that's who they meant
        assertSame(kfc, Coder.pick("", List.of(kfc)));
        assertNull(Coder.pick("", all));
    }

    @Test
    public void ambiguousPartialNameIsNobody()
    {
        assertNull(Coder.pick("ja", List.of(member(2, "Jack", "j1"), member(3, "Jade", "j2"))));
    }

    @Test
    public void onlyTheOwnerChangesTheWhitelist() throws Exception
    {
        Path file = Files.createTempFile("coders", ".json");
        Files.delete(file);
        Member owner = member(OWNER, "Regan", "gunna"), sam = member(2, "Sam", "samwise");
        Coder coder = new Coder("http://coder", "k", OWNER, null, file);
        assertTrue(coder.isAllowed(OWNER));
        assertFalse(coder.isAllowed(2));

        assertTrue(coder.setAllowed(sam, "Sam", List.of(sam), true).startsWith("Only the owner"));
        assertFalse(coder.isAllowed(2));

        assertEquals("Done, Sam can now ask me to change my code.", coder.setAllowed(owner, "sam", List.of(sam), true));
        assertTrue(coder.isAllowed(2));
        assertTrue(coder.describeWhitelist().contains("Sam"));
        // it's kept on disk
        assertTrue(new Coder("http://coder", "k", OWNER, null, file).isAllowed(2));

        assertEquals("Done, Sam can't change my code any more.", coder.setAllowed(owner, "Sam", List.of(sam), false));
        assertFalse(coder.isAllowed(2));
        Files.deleteIfExists(file);
    }

    @Test
    public void requestsNeedTheWhitelistAndTheSidecar()
    {
        Member sam = member(2, "Sam", "samwise");
        assertTrue(new Coder("", "", OWNER, null).request(member(OWNER, "Regan", "gunna"), "x", "", null, s -> {})
                .contains("isn't set up"));
        assertTrue(new Coder("http://coder", "k", OWNER, null, Path.of("no-such-coders.json"))
                .request(sam, "add a ?dance command", "", null, s -> {}).startsWith("Sorry, only people"));
    }

    @Test
    public void restartNoteIsReadOnceAndExpires() throws Exception
    {
        Path file = Files.createTempFile("restart", ".json");
        long now = System.currentTimeMillis();
        Files.writeString(file, "{\"time\":" + now + ",\"guild\":\"10\",\"textChannel\":\"11\",\"voiceChannel\":\"12\","
                + "\"listening\":true,\"summary\":\"Added ?dance.\"}");
        Coder.Restart r = Coder.takeRestart(file, now + 60_000);
        assertEquals(new Coder.Restart(10, 11, 12, true, "Added ?dance."), r);
        assertFalse(Files.exists(file));
        assertNull(Coder.takeRestart(file, now));

        Files.writeString(file, "{\"time\":" + now + ",\"guild\":\"10\"}");
        assertNull(Coder.takeRestart(file, now + 2 * 60 * 60_000));
        Files.deleteIfExists(file);
    }

    @Test
    public void parsesCodingActions()
    {
        LlmClient.Decision d = LlmClient.parseDecision("{\"action\":\"code\",\"query\":\"\",\"task\":\"Add a ?dance command "
                + "that posts a dancing GIF\",\"minutes\":0,\"remember\":\"\",\"post\":\"\",\"reply\":\"On it.\"}");
        assertEquals(Type.CODE, d.action);
        assertEquals("Add a ?dance command that posts a dancing GIF", d.task);
        // code with nothing to change is just talk
        assertEquals(Type.CHAT, LlmClient.parseDecision("{\"action\":\"code\",\"task\":\"\",\"reply\":\"Hmm?\"}").action);
        LlmClient.Decision a = LlmClient.parseDecision("{\"action\":\"allow_coder\",\"query\":\"Sam\",\"reply\":\"\"}");
        assertEquals(Type.ALLOW_CODER, a.action);
        assertEquals("Sam", a.query);
        assertEquals(Type.REVOKE_CODER, LlmClient.parseDecision("{\"action\":\"revoke_coder\",\"query\":\"Sam\"}").action);
        assertEquals(Type.LIST_CODERS, LlmClient.parseDecision("{\"action\":\"list_coders\"}").action);
        LlmClient.Decision i = LlmClient.parseDecision("{\"action\":\"introspect\",\"task\":\"How do you decide when "
                + "someone is talking to you?\",\"reply\":\"Let me check my own code.\"}");
        assertEquals(Type.INTROSPECT, i.action);
        assertEquals(Type.CHAT, LlmClient.parseDecision("{\"action\":\"introspect\",\"task\":\"\"}").action);
    }

    @Test
    public void aboutIsInThePersona()
    {
        assertTrue(VoiceManager.ABOUT.contains("github.com/regan-young/MusicBot"));
        assertTrue(VoiceManager.ABOUT.startsWith("\n\n"));
    }
}
