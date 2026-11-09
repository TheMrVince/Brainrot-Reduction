package com.brainrot.controller.config;

import com.brainrot.controller.policy.AppRule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Loads config.json. Strict on purpose: an unknown key is almost always a typo, and a typo in
 * "dryRun" must not silently turn real closing on or off.
 */
public final class ConfigStore {
    static final String PLACEHOLDER_USERNAME = "your-username-here";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TOP_LEVEL = Set.of("leetcodeUsername", "dayStartHour", "blockedApps",
            "closeWarningMinutes", "pollIntervalSeconds", "emergencyUnlock", "dryRun");
    private static final Set<String> UNLOCK_KEYS = Set.of("perWeek", "durationMinutes", "confirmationPhrase");
    private static final Set<String> RULE_KEYS = Set.of("label", "bundleId", "processName", "pathPrefix",
            "cmdlineContains");

    private ConfigStore() {}

    public static Config load(Path file) throws ConfigException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new ConfigException("Config not found: " + file + " (copy config/config.example.json there and edit it)");
        } catch (IOException e) {
            throw new ConfigException("Cannot read " + file + ": " + e.getMessage());
        }
        return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    public static Config parse(String json) throws ConfigException {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ConfigException("Config is not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) throw new ConfigException("Config must be a JSON object");
        rejectUnknownKeys(root, TOP_LEVEL, "config");

        String username = string(root, "leetcodeUsername", "config", null);
        if (username.isBlank()) throw new ConfigException("leetcodeUsername must not be empty");
        if (username.equals(PLACEHOLDER_USERNAME)) {
            throw new ConfigException("leetcodeUsername is still the placeholder; set your LeetCode username");
        }

        int dayStartHour = integer(root, "dayStartHour", "config", 4);
        if (dayStartHour < 0 || dayStartHour > 23) throw new ConfigException("dayStartHour must be 0 to 23");

        int warning = integer(root, "closeWarningMinutes", "config", 10);
        if (warning < 0) throw new ConfigException("closeWarningMinutes must not be negative");

        int poll = integer(root, "pollIntervalSeconds", "config", 300);
        if (poll < 30) throw new ConfigException("pollIntervalSeconds must be at least 30 (be kind to LeetCode)");

        return new Config(username, dayStartHour, parseApps(root), warning, poll, parseUnlock(root),
                bool(root, "dryRun", "config", true));
    }

    private static List<AppRule> parseApps(JsonNode root) throws ConfigException {
        JsonNode apps = root.get("blockedApps");
        if (apps == null || !apps.isArray()) throw new ConfigException("blockedApps must be an array");

        List<AppRule> rules = new ArrayList<>();
        for (int i = 0; i < apps.size(); i++) {
            JsonNode node = apps.get(i);
            String where = "blockedApps[" + i + "]";
            if (!node.isObject()) throw new ConfigException(where + " must be an object");
            rejectUnknownKeys(node, RULE_KEYS, where);

            List<String> fragments = new ArrayList<>();
            JsonNode cmdline = node.get("cmdlineContains");
            if (cmdline != null) {
                if (!cmdline.isArray()) throw new ConfigException(where + ".cmdlineContains must be an array");
                for (JsonNode item : cmdline) {
                    if (!item.isTextual() || item.asText().isBlank()) {
                        throw new ConfigException(where + ".cmdlineContains must hold non-empty strings");
                    }
                    fragments.add(item.asText());
                }
            }
            AppRule rule = new AppRule(string(node, "label", where, ""), string(node, "bundleId", where, ""),
                    string(node, "processName", where, ""), string(node, "pathPrefix", where, ""), fragments);
            if (!rule.hasCriteria()) {
                throw new ConfigException(where + " needs at least one of bundleId, processName, pathPrefix, cmdlineContains");
            }
            rules.add(rule);
        }
        return rules;
    }

    private static Config.EmergencyUnlock parseUnlock(JsonNode root) throws ConfigException {
        JsonNode node = root.get("emergencyUnlock");
        if (node == null) return new Config.EmergencyUnlock(1, 60, "I choose to waste my time");
        if (!node.isObject()) throw new ConfigException("emergencyUnlock must be an object");
        rejectUnknownKeys(node, UNLOCK_KEYS, "emergencyUnlock");

        int perWeek = integer(node, "perWeek", "emergencyUnlock", 1);
        int minutes = integer(node, "durationMinutes", "emergencyUnlock", 60);
        String phrase = string(node, "confirmationPhrase", "emergencyUnlock", "I choose to waste my time");
        if (perWeek < 0) throw new ConfigException("emergencyUnlock.perWeek must not be negative (0 disables it)");
        if (minutes < 1) throw new ConfigException("emergencyUnlock.durationMinutes must be at least 1");
        if (phrase.isBlank()) throw new ConfigException("emergencyUnlock.confirmationPhrase must not be empty");
        return new Config.EmergencyUnlock(perWeek, minutes, phrase);
    }

    private static void rejectUnknownKeys(JsonNode object, Set<String> allowed, String where) throws ConfigException {
        for (Iterator<String> names = object.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new ConfigException("Unknown setting \"" + name + "\" in " + where + " (typo?)");
            }
        }
    }

    /** {@code fallback == null} means the field is required. */
    private static String string(JsonNode object, String key, String where, String fallback) throws ConfigException {
        JsonNode node = object.get(key);
        if (node == null || node.isNull()) {
            if (fallback == null) throw new ConfigException(key + " is required in " + where);
            return fallback;
        }
        if (!node.isTextual()) throw new ConfigException(key + " in " + where + " must be a string");
        return node.asText();
    }

    private static int integer(JsonNode object, String key, String where, int fallback) throws ConfigException {
        JsonNode node = object.get(key);
        if (node == null || node.isNull()) return fallback;
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new ConfigException(key + " in " + where + " must be a whole number");
        }
        return node.asInt();
    }

    private static boolean bool(JsonNode object, String key, String where, boolean fallback) throws ConfigException {
        JsonNode node = object.get(key);
        if (node == null || node.isNull()) return fallback;
        if (!node.isBoolean()) throw new ConfigException(key + " in " + where + " must be true or false");
        return node.asBoolean();
    }
}
