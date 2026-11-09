package com.brainrot.controller.leetcode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeetCodeClientTest {
    @Test
    void parsesSubmissionsAndStringTimestamps() throws IOException {
        String json = """
                {"data":{"recentAcSubmissionList":[
                  {"title":"Can Place Flowers","titleSlug":"can-place-flowers","timestamp":"1793168840"}
                ]}}""";

        List<AcceptedSubmission> result = LeetCodeClient.parse(json);

        assertEquals(1, result.size());
        assertEquals("Can Place Flowers", result.get(0).title());
        assertEquals(Instant.ofEpochSecond(1793168840L), result.get(0).solvedAt());
    }

    @Test
    void emptyListIsValid() throws IOException {
        assertTrue(LeetCodeClient.parse("{\"data\":{\"recentAcSubmissionList\":[]}}").isEmpty());
    }

    @Test
    void graphqlErrorsBecomeIOException() {
        String json = "{\"errors\":[{\"message\":\"That user does not exist.\"}],\"data\":{\"recentAcSubmissionList\":null}}";
        assertThrows(IOException.class, () -> LeetCodeClient.parse(json));
    }

    @Test
    void nullListBecomesIOException() {
        assertThrows(IOException.class, () -> LeetCodeClient.parse("{\"data\":{\"recentAcSubmissionList\":null}}"));
    }

    @Test
    void anExistingUserIsRecognised() throws IOException {
        assertTrue(LeetCodeClient.parseUserExists("{\"data\":{\"matchedUser\":{\"username\":\"TheMrVince\"}}}"));
    }

    @Test
    void aMissingUserIsNotMistakenForAnError() throws IOException {
        String json = "{\"errors\":[{\"message\":\"That user does not exist.\"}],\"data\":{\"matchedUser\":null}}";
        assertEquals(false, LeetCodeClient.parseUserExists(json));
    }

    @Test
    void aResponseWithoutAnAnswerIsAnError() {
        assertThrows(IOException.class, () -> LeetCodeClient.parseUserExists("{\"data\":{}}"));
        assertThrows(IOException.class, () -> LeetCodeClient.parseUserExists("{}"));
    }
}
