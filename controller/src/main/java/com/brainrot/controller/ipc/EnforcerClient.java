package com.brainrot.controller.ipc;

import com.brainrot.controller.policy.Policy;
import com.brainrot.controller.policy.PolicyJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Talks to the enforcer over its local socket (shared/protocol.md): one JSON line out, one reply
 * line back, in order.
 *
 * <ul>
 *   <li>Every call is bounded by the timeout, so a hung enforcer cannot hang the controller.</li>
 *   <li>If the connection went stale (the enforcer restarted since the last call) the call
 *       reconnects and sends once more. That is safe because every message is idempotent.
 *       A fresh connection that fails is reported as is, not retried.</li>
 *   <li>Lines that are not a reply (events such as {@code blocked}) go to the event listener.</li>
 * </ul>
 */
public final class EnforcerClient implements AutoCloseable {

    public sealed interface Reply {
        record Accepted() implements Reply {}

        /** The enforcer understood the message and refused it; {@code message} says why. */
        record Rejected(String message) implements Reply {}
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LINE_BYTES = 1 << 20;

    private final Path socketPath;
    private final Duration timeout;
    private final Consumer<String> onEvent;

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private SocketChannel channel;
    private Selector selector;
    private SelectionKey key;

    public EnforcerClient(Path socketPath, Duration timeout, Consumer<String> onEvent) {
        this.socketPath = socketPath;
        this.timeout = timeout;
        this.onEvent = onEvent;
    }

    public EnforcerClient(Path socketPath) {
        this(socketPath, Duration.ofSeconds(5), line -> {});
    }

    public synchronized Reply sendPolicy(Policy policy) throws IOException {
        return send(PolicyJson.toSetPolicyMessage(policy));
    }

    public synchronized Reply ping() throws IOException {
        return send("{\"cmd\":\"ping\"}");
    }

    public synchronized Reply send(String line) throws IOException {
        if (line.indexOf('\n') >= 0) throw new IllegalArgumentException("a message must be a single line");

        boolean reusedConnection = channel != null;
        try {
            return exchange(line);
        } catch (IOException first) {
            disconnect();
            if (!reusedConnection) throw first;
        }
        return exchange(line);  // the old connection was stale; try once on a fresh one
    }

    @Override
    public synchronized void close() {
        disconnect();
    }

    private Reply exchange(String line) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (channel == null) connect();
            writeLine(line, deadline);
            while (true) {
                String reply = readLine(deadline);
                JsonNode node = parse(reply);
                String event = node.path("event").asText("");
                if (event.equals("ack")) return new Reply.Accepted();
                if (event.equals("error")) return new Reply.Rejected(node.path("message").asText("(no message)"));
                if (event.isEmpty()) throw new IOException("Unexpected data from the enforcer: " + abbreviate(reply));
                onEvent.accept(reply);
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    private void connect() throws IOException {
        SocketChannel opened = null;
        Selector newSelector = null;
        try {
            opened = SocketChannel.open(StandardProtocolFamily.UNIX);
            opened.connect(UnixDomainSocketAddress.of(socketPath));
            opened.configureBlocking(false);
            newSelector = Selector.open();
            key = opened.register(newSelector, SelectionKey.OP_READ);
        } catch (IOException e) {
            closeQuietly(opened);
            closeQuietly(newSelector);
            throw new IOException("Cannot reach the enforcer at " + socketPath
                    + " (is `enforcer serve` running?): " + e.getMessage(), e);
        }
        channel = opened;
        selector = newSelector;
        pending.reset();
    }

    private void disconnect() {
        closeQuietly(channel);
        closeQuietly(selector);
        channel = null;
        selector = null;
        key = null;
        pending.reset();
    }

    private void writeLine(String line, long deadline) throws IOException {
        ByteBuffer out = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
        while (out.hasRemaining()) {
            if (channel.write(out) > 0) continue;
            key.interestOps(SelectionKey.OP_WRITE);
            selector.select(remainingMillis(deadline));
            selector.selectedKeys().clear();
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private String readLine(long deadline) throws IOException {
        ByteBuffer chunk = ByteBuffer.allocate(4096);
        while (true) {
            String line = takeLine();
            if (line != null) return line;

            selector.select(remainingMillis(deadline));
            selector.selectedKeys().clear();

            chunk.clear();
            int read = channel.read(chunk);
            if (read < 0) throw new IOException("The enforcer closed the connection");
            pending.write(chunk.array(), 0, read);
            if (pending.size() > MAX_LINE_BYTES) throw new IOException("The enforcer sent an oversized line");
        }
    }

    /** Removes and returns the first complete line from the buffer, or null if there is none yet. */
    private String takeLine() {
        byte[] all = pending.toByteArray();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == '\n') {
                pending.reset();
                pending.write(all, i + 1, all.length - i - 1);
                return new String(all, 0, i, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private long remainingMillis(long deadline) throws IOException {
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (left <= 0) throw new IOException("The enforcer did not reply within " + timeout.toMillis() + " ms");
        return left;
    }

    private static JsonNode parse(String line) throws IOException {
        try {
            JsonNode node = JSON.readTree(line);
            if (node == null || !node.isObject()) throw new IOException("Unexpected data from the enforcer: " + abbreviate(line));
            return node;
        } catch (JsonProcessingException e) {
            throw new IOException("Unexpected data from the enforcer: " + abbreviate(line));
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Nothing useful to do about a failed close.
        }
    }
}
