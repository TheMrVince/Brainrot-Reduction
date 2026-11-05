package com.brainrot.controller.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyJsonTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aSetPolicyMessageMatchesTheProtocol() throws Exception {
        Policy policy = new Policy(true, Instant.ofEpochSecond(1793000000L), 10, false, List.of(
                new AppRule("Discord", "com.hnc.Discord", null, null, null),
                new AppRule("Game", null, "java", "/opt/games/", List.of("com.example.game", "--user"))));

        String line = PolicyJson.toSetPolicyMessage(policy);
        JsonNode message = JSON.readTree(line);

        assertFalse(line.contains("\n"), "one message per line");
        assertEquals("set_policy", message.get("cmd").asText());
        assertTrue(message.get("locked").asBoolean());
        assertEquals(1793000000L, message.get("unlockedUntil").asLong());
        assertEquals(10, message.get("closeWarningMinutes").asInt());
        assertFalse(message.get("dryRun").asBoolean());

        JsonNode discord = message.get("apps").get(0);
        assertEquals("com.hnc.Discord", discord.get("bundleId").asText());
        assertFalse(discord.has("processName"), "unset criteria are left out");
        assertFalse(discord.has("cmdlineContains"));

        JsonNode game = message.get("apps").get(1);
        assertEquals("java", game.get("processName").asText());
        assertEquals("/opt/games/", game.get("pathPrefix").asText());
        assertEquals("--user", game.get("cmdlineContains").get(1).asText());
    }

    @Test
    void noUnlockIsAnExplicitNull() throws Exception {
        Policy policy = new Policy(true, null, 0, true, List.of());

        JsonNode message = JSON.readTree(PolicyJson.toSetPolicyMessage(policy));

        assertTrue(message.get("unlockedUntil").isNull());
        assertTrue(message.get("apps").isEmpty());
        assertTrue(message.get("dryRun").asBoolean());
    }
}
