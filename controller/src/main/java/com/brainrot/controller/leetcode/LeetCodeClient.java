package com.brainrot.controller.leetcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads a user's public recent accepted submissions from LeetCode's unofficial GraphQL API. */
public final class LeetCodeClient {
    private static final URI ENDPOINT = URI.create("https://leetcode.com/graphql");
    private static final String QUERY = "query recentAcSubmissions($username: String!, $limit: Int!) "
            + "{ recentAcSubmissionList(username: $username, limit: $limit) { title titleSlug timestamp } }";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Newest first. Throws IOException on network failure, a bad response, or an unknown user. */
    public List<AcceptedSubmission> fetchRecentAccepted(String username, int limit)
            throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(Map.of(
                "query", QUERY,
                "variables", Map.of("username", username, "limit", limit)));

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Referer", "https://leetcode.com")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("LeetCode returned HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    static List<AcceptedSubmission> parse(String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IOException("LeetCode error: " + errors.get(0).path("message").asText("unknown"));
        }
        JsonNode list = root.path("data").path("recentAcSubmissionList");
        if (!list.isArray()) {
            throw new IOException("Unexpected LeetCode response (user missing or profile private?)");
        }

        List<AcceptedSubmission> result = new ArrayList<>();
        for (JsonNode item : list) {
            result.add(new AcceptedSubmission(
                    item.path("title").asText(),
                    item.path("titleSlug").asText(),
                    Instant.ofEpochSecond(item.path("timestamp").asLong())));
        }
        return result;
    }
}
