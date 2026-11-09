package com.brainrot.controller.service;

import com.brainrot.controller.config.Config;
import com.brainrot.controller.policy.CheckResult;
import com.brainrot.controller.policy.EmergencyUnlockResult;
import com.brainrot.controller.policy.Policy;
import com.brainrot.controller.policy.PolicyEngine;
import com.brainrot.controller.state.State;
import com.brainrot.controller.state.StateStore;
import com.brainrot.controller.time.DayClock;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Keeps the enforcer in step with LeetCode, unattended.
 *
 * <ul>
 *   <li>While locked it asks LeetCode every {@code pollIntervalSeconds}. Once today is solved it stops
 *       asking until the next day starts, and a remembered solve means no network call at all.</li>
 *   <li>It sends the policy the moment it changes, and as a heartbeat otherwise, so an enforcer that
 *       restarted (or is new) is brought up to date. A refused delivery is retried on every tick.</li>
 *   <li>Everything happens in {@link #tick()}, which is deterministic given the clock; a background
 *       thread just calls it regularly.</li>
 * </ul>
 */
public final class ControllerService implements AutoCloseable {

    public record Timing(Duration tick, Duration resend) {
        public static Timing standard() {
            return new Timing(Duration.ofSeconds(10), Duration.ofSeconds(60));
        }
    }

    private final Config config;
    private final StateStore store;
    private final LeetCodeChecker checker;
    private final PolicySink sink;
    private final Clock clock;
    private final Consumer<String> log;
    private final Timing timing;
    private final PolicyEngine engine;
    private final DateTimeFormatter display;

    private State state;
    private Instant nextCheckAt = Instant.MIN;
    private boolean checkRequested;
    private Boolean leetCodeReachable;  // null until the first check; used to log changes only
    private Policy lastDelivered;       // null after a failed delivery, so the next tick retries
    private Instant lastDeliveredAt;
    private boolean deliveryFailing;
    private Boolean enforcerReachable;  // null until the first delivery attempt
    private Instant lastCheckAt;        // last time LeetCode answered
    private StatusSnapshot lastPublished;
    private final List<Consumer<StatusSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService executor;

    public ControllerService(Config config, State initialState, StateStore store, LeetCodeChecker checker,
                             PolicySink sink, Clock clock, Consumer<String> log, Timing timing) {
        this.config = config;
        this.state = initialState;
        this.store = store;
        this.checker = checker;
        this.sink = sink;
        this.clock = clock;
        this.log = log;
        this.timing = timing;
        this.engine = new PolicyEngine(config, new DayClock(clock.getZone(), config.dayStartHour()));
        this.display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(clock.getZone());
    }

    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "controller");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::safeTick, 0, timing.tick().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        ScheduledExecutorService running;
        synchronized (this) {
            running = executor;
            executor = null;
        }
        if (running == null) return;
        running.shutdownNow();
        try {
            running.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Called, on the service's own thread, whenever the status changes. Keep it quick: hand work to
     * another thread. Also called once on registration if a status already exists.
     */
    public void addStatusListener(Consumer<StatusSnapshot> listener) {
        listeners.add(listener);
        StatusSnapshot current;
        synchronized (this) {
            current = lastPublished;
        }
        if (current != null) listener.accept(current);
    }

    public synchronized StatusSnapshot snapshot() {
        return snapshotAt(clock.instant());
    }

    /** Asks LeetCode again as soon as possible instead of waiting for the poll interval. */
    public synchronized void requestCheck() {
        checkRequested = true;
        if (executor != null) executor.execute(this::safeTick);
    }

    public synchronized EmergencyUnlockResult requestEmergencyUnlock(String phrase) {
        Instant now = clock.instant();
        EmergencyUnlockResult result = engine.requestEmergencyUnlock(state, phrase, now);
        if (result instanceof EmergencyUnlockResult.Granted granted) {
            state = granted.state();
            persist();
            log.accept("emergency unlock granted until " + display.format(granted.until()));
            deliver(engine.decide(state, now), now, true);
            publishIfChanged(now);
        }
        return result;
    }

    void safeTick() {
        try {
            tick();
        } catch (Throwable e) {
            log.accept("unexpected error, carrying on: " + e);
        }
    }

    synchronized void tick() {
        Instant now = clock.instant();
        if (shouldCheck(now)) check(now);
        deliver(engine.decide(state, now), now, false);
        publishIfChanged(now);
    }

    private StatusSnapshot snapshotAt(Instant now) {
        Policy policy = engine.decide(state, now);
        StatusSnapshot.Reason reason = !policy.isUnlockedAt(now) ? StatusSnapshot.Reason.LOCKED
                : engine.solvedToday(state, now) ? StatusSnapshot.Reason.SOLVED_TODAY
                : StatusSnapshot.Reason.EMERGENCY_UNLOCK;
        return new StatusSnapshot(reason, policy.isUnlockedAt(now) ? policy.unlockedUntil() : null,
                leetCodeReachable, enforcerReachable, lastCheckAt, config.dryRun());
    }

    private void publishIfChanged(Instant now) {
        StatusSnapshot snapshot = snapshotAt(now);
        if (snapshot.equals(lastPublished)) return;
        lastPublished = snapshot;
        for (Consumer<StatusSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException e) {
                log.accept("a status listener failed: " + e);
            }
        }
    }

    private boolean shouldCheck(Instant now) {
        if (checkRequested) return true;
        return !engine.solvedToday(state, now) && !now.isBefore(nextCheckAt);
    }

    private void check(Instant now) {
        checkRequested = false;
        CheckResult result = checker.check(config.leetcodeUsername());
        nextCheckAt = now.plusSeconds(config.pollIntervalSeconds());

        if (result instanceof CheckResult.Unreachable offline) {
            if (!Boolean.FALSE.equals(leetCodeReachable)) {
                log.accept("LeetCode unreachable (" + offline.reason() + "); relying on what is remembered");
            }
            leetCodeReachable = false;
            return;
        }
        if (Boolean.FALSE.equals(leetCodeReachable)) log.accept("LeetCode reachable again");
        leetCodeReachable = true;
        lastCheckAt = now;

        State updated = engine.observe(state, result);
        if (!updated.equals(state)) {
            state = updated;
            persist();
            log.accept("new accepted submission seen at " + display.format(state.lastSolvedAt()));
        }
    }

    private void deliver(Policy policy, Instant now, boolean force) {
        boolean changed = !policy.equals(lastDelivered);
        boolean heartbeatDue = lastDeliveredAt == null || !now.isBefore(lastDeliveredAt.plus(timing.resend()));
        if (!force && !changed && !heartbeatDue) return;

        try {
            sink.deliver(policy);
        } catch (IOException e) {
            if (!deliveryFailing) log.accept("could not deliver the policy: " + e.getMessage() + "; will keep retrying");
            deliveryFailing = true;
            enforcerReachable = false;
            lastDelivered = null;
            return;
        }

        if (deliveryFailing) log.accept("the enforcer is reachable again");
        deliveryFailing = false;
        enforcerReachable = true;
        if (changed) log.accept("policy delivered: " + describe(policy, now));
        lastDelivered = policy;
        lastDeliveredAt = now;
    }

    private String describe(Policy policy, Instant now) {
        return policy.isUnlockedAt(now) ? "UNLOCKED until " + display.format(policy.unlockedUntil()) : "LOCKED";
    }

    private void persist() {
        try {
            store.save(state);
        } catch (IOException e) {
            log.accept("could not save state: " + e.getMessage());
        }
    }
}
