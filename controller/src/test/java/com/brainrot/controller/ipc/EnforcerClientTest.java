package com.brainrot.controller.ipc;

import com.brainrot.controller.ipc.EnforcerClient.Reply;
import com.brainrot.controller.policy.AppRule;
import com.brainrot.controller.policy.Policy;
import com.brainrot.controller.policy.PolicyJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcerClientTest {
    private static final Policy POLICY = new Policy(true, Instant.ofEpochSecond(1793880000L), 10, true,
            List.of(new AppRule("Discord", "com.hnc.Discord", null, null, null)));

    private Path dir;
    private Path socket;

    @BeforeEach
    void makeShortSocketPath() throws IOException {
        // Unix socket paths are limited to about 100 bytes, which JUnit's temp folders can exceed.
        dir = Files.createTempDirectory(Path.of("/tmp"), "brt");
        socket = dir.resolve("e.sock");
    }

    @AfterEach
    void cleanUp() throws IOException {
        Files.deleteIfExists(socket);
        Files.deleteIfExists(dir);
    }

    private EnforcerClient client() {
        return new EnforcerClient(socket, Duration.ofSeconds(2), line -> {});
    }

    @Test
    void deliversThePolicyExactlyAsTheProtocolDefinesIt() throws Exception {
        try (FakeEnforcer enforcer = FakeEnforcer.alwaysAcks(socket); EnforcerClient client = client()) {
            Reply reply = client.sendPolicy(POLICY);

            assertInstanceOf(Reply.Accepted.class, reply);
            assertEquals(List.of(PolicyJson.toSetPolicyMessage(POLICY)), enforcer.received());
        }
    }

    @Test
    void severalMessagesShareOneConnection() throws Exception {
        try (FakeEnforcer enforcer = FakeEnforcer.alwaysAcks(socket); EnforcerClient client = client()) {
            assertInstanceOf(Reply.Accepted.class, client.ping());
            assertInstanceOf(Reply.Accepted.class, client.sendPolicy(POLICY));
            assertInstanceOf(Reply.Accepted.class, client.ping());

            assertEquals(3, enforcer.received().size());
        }
    }

    @Test
    void aRefusalIsAReplyNotAnException() throws Exception {
        FakeEnforcer.Responder refuse = (line, c) ->
                c.write("{\"event\":\"error\",\"message\":\"apps[0] has no matching criteria\"}\n");
        try (FakeEnforcer enforcer = new FakeEnforcer(socket, refuse); EnforcerClient client = client()) {
            Reply reply = client.sendPolicy(POLICY);

            assertEquals("apps[0] has no matching criteria", assertInstanceOf(Reply.Rejected.class, reply).message());
        }
    }

    @Test
    void anEnforcerThatIsNotRunningGivesAHelpfulError() {
        IOException e = assertThrows(IOException.class, () -> client().sendPolicy(POLICY));

        assertTrue(e.getMessage().contains(socket.toString()), e.getMessage());
        assertTrue(e.getMessage().contains("enforcer serve"), e.getMessage());
    }

    @Test
    void reconnectsAfterTheEnforcerRestarts() throws Exception {
        try (EnforcerClient client = client()) {
            try (FakeEnforcer first = FakeEnforcer.alwaysAcks(socket)) {
                assertInstanceOf(Reply.Accepted.class, client.sendPolicy(POLICY));
            }  // the enforcer goes away; the client's connection is now stale

            try (FakeEnforcer second = FakeEnforcer.alwaysAcks(socket)) {
                assertInstanceOf(Reply.Accepted.class, client.sendPolicy(POLICY));
                assertEquals(1, second.received().size(), "the policy reached the new enforcer");
            }
        }
    }

    @Test
    void reconnectsWhenTheEnforcerDropsAnIdleConnection() throws Exception {
        FakeEnforcer.Responder answerOnceThenHangUp = (line, c) -> {
            c.write(FakeEnforcer.ACK);
            c.close();
        };
        try (FakeEnforcer enforcer = new FakeEnforcer(socket, answerOnceThenHangUp); EnforcerClient client = client()) {
            assertInstanceOf(Reply.Accepted.class, client.ping());
            Thread.sleep(100);  // let the hang-up reach us
            assertInstanceOf(Reply.Accepted.class, client.ping());

            assertEquals(2, enforcer.received().size());
        }
    }

    @Test
    void aSilentEnforcerTimesOutInsteadOfHangingTheController() throws Exception {
        FakeEnforcer.Responder silence = (line, c) -> { /* never answers */ };
        try (FakeEnforcer enforcer = new FakeEnforcer(socket, silence);
             EnforcerClient client = new EnforcerClient(socket, Duration.ofMillis(300), l -> {})) {
            long begin = System.nanoTime();

            IOException e = assertThrows(IOException.class, () -> client.sendPolicy(POLICY));

            long tookMs = Duration.ofNanos(System.nanoTime() - begin).toMillis();
            assertTrue(e.getMessage().contains("did not reply"), e.getMessage());
            assertTrue(tookMs < 1500, "took " + tookMs + " ms");
        }
    }

    @Test
    void repliesSplitAcrossSeveralWritesAreReassembled() throws Exception {
        FakeEnforcer.Responder dribble = (line, c) -> {
            c.write("{\"event\":\"ack\",\"cm");
            Thread.sleep(60);
            c.write("d\":\"set_policy\"}");
            Thread.sleep(60);
            c.write("\n");
        };
        try (FakeEnforcer enforcer = new FakeEnforcer(socket, dribble); EnforcerClient client = client()) {
            assertInstanceOf(Reply.Accepted.class, client.sendPolicy(POLICY));
        }
    }

    @Test
    void eventsAreHandedToTheListenerAndDoNotDisturbTheReply() throws Exception {
        String blocked = "{\"event\":\"blocked\",\"app\":\"Discord\",\"pid\":4821,\"dryRun\":false}";
        FakeEnforcer.Responder eventThenAck = (line, c) -> c.write(blocked + "\n" + FakeEnforcer.ACK);
        List<String> events = new CopyOnWriteArrayList<>();

        try (FakeEnforcer enforcer = new FakeEnforcer(socket, eventThenAck);
             EnforcerClient client = new EnforcerClient(socket, Duration.ofSeconds(2), events::add)) {
            assertInstanceOf(Reply.Accepted.class, client.sendPolicy(POLICY));

            assertEquals(List.of(blocked), events);
        }
    }

    @Test
    void anythingThatIsNotOurProtocolIsAnError() throws Exception {
        for (String garbage : List.of("hello\n", "[1,2]\n", "{\"no\":\"event\"}\n")) {
            Files.deleteIfExists(socket);
            try (FakeEnforcer enforcer = new FakeEnforcer(socket, (line, c) -> c.write(garbage));
                 EnforcerClient client = client()) {
                IOException e = assertThrows(IOException.class, () -> client.sendPolicy(POLICY), garbage);
                assertTrue(e.getMessage().contains("Unexpected data"), e.getMessage());
            }
        }
    }

    @Test
    void messagesMustBeASingleLine() {
        EnforcerClient client = client();

        assertThrows(IllegalArgumentException.class, () -> client.send("{\"cmd\":\n\"ping\"}"));
    }

    @Test
    void aClosedClientConnectsAgainOnNextUse() throws Exception {
        try (FakeEnforcer enforcer = FakeEnforcer.alwaysAcks(socket)) {
            EnforcerClient client = client();
            assertInstanceOf(Reply.Accepted.class, client.ping());
            client.close();
            client.close();  // harmless twice

            assertInstanceOf(Reply.Accepted.class, client.ping());
            client.close();
        }
    }
}
