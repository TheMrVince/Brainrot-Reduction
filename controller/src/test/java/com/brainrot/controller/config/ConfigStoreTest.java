package com.brainrot.controller.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {
    private static final String MINIMAL = """
            {"leetcodeUsername":"TheMrVince","blockedApps":[{"label":"Discord","bundleId":"com.hnc.Discord"}]}""";

    private static String messageFor(String json) {
        return assertThrows(ConfigException.class, () -> ConfigStore.parse(json)).getMessage();
    }

    @Test
    void minimalConfigGetsTheAgreedDefaults() throws Exception {
        Config config = ConfigStore.parse(MINIMAL);

        assertEquals("TheMrVince", config.leetcodeUsername());
        assertEquals(4, config.dayStartHour());
        assertEquals(10, config.closeWarningMinutes());
        assertEquals(300, config.pollIntervalSeconds());
        assertTrue(config.dryRun(), "dry run must default to on");
        assertEquals(1, config.emergencyUnlock().perWeek());
        assertEquals(60, config.emergencyUnlock().durationMinutes());
        assertEquals(1, config.blockedApps().size());
        assertEquals("com.hnc.Discord", config.blockedApps().get(0).bundleId());
    }

    @Test
    void fullConfigIsReadFaithfully() throws Exception {
        Config config = ConfigStore.parse("""
                {"leetcodeUsername":"me","dayStartHour":6,"closeWarningMinutes":0,"pollIntervalSeconds":60,
                 "dryRun":false,
                 "emergencyUnlock":{"perWeek":2,"durationMinutes":30,"confirmationPhrase":"let me in"},
                 "blockedApps":[
                   {"label":"Game","processName":"java","pathPrefix":"/opt/games/","cmdlineContains":["com.example.game","--user"]}
                 ]}""");

        assertEquals(6, config.dayStartHour());
        assertEquals(0, config.closeWarningMinutes());
        assertFalse(config.dryRun());
        assertEquals(2, config.emergencyUnlock().perWeek());
        assertEquals("let me in", config.emergencyUnlock().confirmationPhrase());
        assertEquals(java.util.List.of("com.example.game", "--user"), config.blockedApps().get(0).cmdlineContains());
    }

    @Test
    void theShippedExampleMustBeEditedBeforeUse() throws IOException {
        Path example = Path.of("..", "config", "config.example.json");
        if (!Files.exists(example)) example = Path.of("config", "config.example.json");
        String text = Files.readString(example);

        assertTrue(messageFor(text).contains("placeholder"));
        // With only the placeholder replaced, the example must be a valid config.
        assertDoesNotThrow(() -> ConfigStore.parse(text.replace("your-username-here", "TheMrVince")));
    }

    private static void assertDoesNotThrow(org.junit.jupiter.api.function.Executable e) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(e);
    }

    @Test
    void problemsNameTheSettingToFix() {
        assertTrue(messageFor("not json").contains("not valid JSON"));
        assertTrue(messageFor("[]").contains("JSON object"));
        assertTrue(messageFor("{\"blockedApps\":[]}").contains("leetcodeUsername is required"));
        assertTrue(messageFor("{\"leetcodeUsername\":\"  \",\"blockedApps\":[]}").contains("must not be empty"));
        assertTrue(messageFor("{\"leetcodeUsername\":\"me\"}").contains("blockedApps must be an array"));
        assertTrue(messageFor("{\"leetcodeUsername\":5,\"blockedApps\":[]}").contains("must be a string"));
    }

    @Test
    void typosInSettingNamesAreRejected() {
        assertTrue(messageFor("{\"leetcodeUsername\":\"me\",\"blockedApps\":[],\"dryrun\":false}").contains("\"dryrun\""));
        assertTrue(messageFor("{\"leetcodeUsername\":\"me\",\"blockedApps\":[{\"bundleID\":\"x\"}]}").contains("\"bundleID\""));
        assertTrue(messageFor("{\"leetcodeUsername\":\"me\",\"blockedApps\":[],\"emergencyUnlock\":{\"perweek\":1}}").contains("\"perweek\""));
    }

    @Test
    void outOfRangeValuesAreRejected() {
        String head = "{\"leetcodeUsername\":\"me\",\"blockedApps\":[],";
        assertTrue(messageFor(head + "\"dayStartHour\":24}").contains("dayStartHour"));
        assertTrue(messageFor(head + "\"dayStartHour\":-1}").contains("dayStartHour"));
        assertTrue(messageFor(head + "\"dayStartHour\":\"4\"}").contains("whole number"));
        assertTrue(messageFor(head + "\"closeWarningMinutes\":-5}").contains("closeWarningMinutes"));
        assertTrue(messageFor(head + "\"pollIntervalSeconds\":5}").contains("pollIntervalSeconds"));
        assertTrue(messageFor(head + "\"dryRun\":\"yes\"}").contains("true or false"));
        assertTrue(messageFor(head + "\"emergencyUnlock\":{\"perWeek\":-1}}").contains("perWeek"));
        assertTrue(messageFor(head + "\"emergencyUnlock\":{\"durationMinutes\":0}}").contains("durationMinutes"));
        assertTrue(messageFor(head + "\"emergencyUnlock\":{\"confirmationPhrase\":\" \"}}").contains("confirmationPhrase"));
    }

    @Test
    void disablingEmergencyUnlockIsAllowed() throws Exception {
        Config config = ConfigStore.parse(
                "{\"leetcodeUsername\":\"me\",\"blockedApps\":[],\"emergencyUnlock\":{\"perWeek\":0}}");
        assertEquals(0, config.emergencyUnlock().perWeek());
    }

    @Test
    void rulesNeedAtLeastOneCriterionOfTheRightShape() {
        String head = "{\"leetcodeUsername\":\"me\",\"blockedApps\":[";
        assertTrue(messageFor(head + "{\"label\":\"Empty\"}]}").contains("blockedApps[0] needs at least one"));
        assertTrue(messageFor(head + "\"Discord\"]}").contains("must be an object"));
        assertTrue(messageFor(head + "{\"cmdlineContains\":\"x\"}]}").contains("must be an array"));
        assertTrue(messageFor(head + "{\"cmdlineContains\":[\"\"]}]}").contains("non-empty strings"));
        assertTrue(messageFor(head + "{\"bundleId\":\"a\"},{\"label\":\"x\"}]}").contains("blockedApps[1]"));
    }

    @Test
    void loadsFromAFileAndReportsMissingOnes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, MINIMAL);
        assertEquals("TheMrVince", ConfigStore.load(file).leetcodeUsername());

        ConfigException missing = assertThrows(ConfigException.class, () -> ConfigStore.load(dir.resolve("nope.json")));
        assertTrue(missing.getMessage().contains("config.example.json"));
    }
}
