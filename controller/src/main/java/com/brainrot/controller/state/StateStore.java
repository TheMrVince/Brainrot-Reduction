package com.brainrot.controller.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Keeps {@link State} in a small JSON file. Times are stored as Unix seconds. */
public final class StateStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    public StateStore(Path file) {
        this.file = file;
    }

    /** A missing file is a fresh start. A damaged file is an error, not a silent reset. */
    public State load() throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return State.empty();
        }

        JsonNode root;
        try {
            root = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IOException(file + " is not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) throw new IOException(file + " must hold a JSON object");

        List<Instant> unlocks = new ArrayList<>();
        JsonNode history = root.get("emergencyUnlocks");
        if (history != null) {
            if (!history.isArray()) throw new IOException(file + ": emergencyUnlocks must be an array");
            for (JsonNode item : history) unlocks.add(seconds(item, "emergencyUnlocks"));
        }
        return new State(optionalSeconds(root, "lastSolvedAt"), unlocks, optionalSeconds(root, "emergencyUnlockUntil"));
    }

    /** Writes to a temporary file and renames it over the real one, so a crash cannot leave half a file. */
    public void save(State state) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        put(root, "lastSolvedAt", state.lastSolvedAt());
        ArrayNode history = root.putArray("emergencyUnlocks");
        state.emergencyUnlocks().forEach(at -> history.add(at.getEpochSecond()));
        put(root, "emergencyUnlockUntil", state.emergencyUnlockUntil());

        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve(file.getFileName() + ".tmp");
        Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Not a POSIX file system (Windows); the user's profile folder is already private.
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void put(ObjectNode root, String key, Instant value) {
        if (value == null) root.putNull(key);
        else root.put(key, value.getEpochSecond());
    }

    private Instant optionalSeconds(JsonNode root, String key) throws IOException {
        JsonNode node = root.get(key);
        return (node == null || node.isNull()) ? null : seconds(node, key);
    }

    private Instant seconds(JsonNode node, String key) throws IOException {
        if (!node.isIntegralNumber()) throw new IOException(file + ": " + key + " must be Unix seconds");
        return Instant.ofEpochSecond(node.asLong());
    }
}
