package com.brainrot.controller.ipc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A stand-in for the C++ enforcer that tests can script: it records lines and answers as told. */
final class FakeEnforcer implements AutoCloseable {

    /** What the fake does when a line arrives. */
    interface Responder {
        void onLine(String line, Connection connection) throws Exception;
    }

    static final class Connection {
        private final SocketChannel channel;

        Connection(SocketChannel channel) {
            this.channel = channel;
        }

        void write(String text) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
        }

        void close() throws IOException {
            channel.close();
        }
    }

    static final String ACK = "{\"event\":\"ack\",\"cmd\":\"set_policy\"}\n";

    private final Path path;
    private final ServerSocketChannel server;
    private final Responder responder;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<SocketChannel> clients = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    FakeEnforcer(Path path, Responder responder) throws IOException {
        this.path = path;
        this.responder = responder;
        Files.deleteIfExists(path);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(path));
        acceptor = new Thread(this::acceptLoop, "fake-enforcer");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    static FakeEnforcer alwaysAcks(Path path) throws IOException {
        return new FakeEnforcer(path, (line, connection) -> connection.write(ACK));
    }

    List<String> received() {
        return received;
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            try {
                SocketChannel client = server.accept();
                clients.add(client);
                Thread handler = new Thread(() -> serve(client), "fake-enforcer-connection");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;  // closed
            }
        }
    }

    private void serve(SocketChannel client) {
        Connection connection = new Connection(client);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                received.add(line);
                responder.onLine(line, connection);
            }
        } catch (Exception ignored) {
            // The client went away or the test closed us; nothing to report.
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (SocketChannel client : clients) client.close();  // a real restart drops every connection
        Files.deleteIfExists(path);
    }
}
