package io.github.neareststep.nexusai.dialogue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GreetingCacheTest {

    @Test
    void keyIsSha256OfTheSystemText() throws Exception {
        String one = GreetingCache.key("bram", "system-one");
        String two = GreetingCache.key("bram", "system-two");
        assertNotEquals(one, two);
        assertEquals(one, GreetingCache.key("bram", "system-one"));
        assertNotEquals(GreetingCache.key("bram", "system-one"), GreetingCache.key("other", "system-one"));

        String hex = one.substring(one.indexOf('\u0000') + 1);
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("system-one".getBytes(StandardCharsets.UTF_8)));
        assertEquals(64, hex.length());
        assertEquals(expected, hex);
        assertNotEquals(Integer.toUnsignedString("system-one".hashCode()), hex);
    }
}
