package nl.frontier.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class FrontierMetrics {
  private final Counter commands;
  private final Counter failures;
  private final Counter outboxPublished;
  private final Counter rateLimited;
  private final AtomicInteger dirtyQueue = new AtomicInteger();
  private final AtomicInteger outboxPending = new AtomicInteger();
  private final AtomicLong outboxLagSeconds = new AtomicLong();
  private final AtomicInteger databaseActive = new AtomicInteger();
  private final AtomicInteger databaseAwaiting = new AtomicInteger();

  public FrontierMetrics(MeterRegistry registry) {
    Objects.requireNonNull(registry);
    commands = registry.counter("frontier.commands");
    failures = registry.counter("frontier.failures");
    outboxPublished = registry.counter("frontier.outbox.published");
    rateLimited = registry.counter("frontier.commands.rate_limited");
    Gauge.builder("frontier.dirty.queue", dirtyQueue, AtomicInteger::get).register(registry);
    Gauge.builder("frontier.outbox.pending", outboxPending, AtomicInteger::get).register(registry);
    Gauge.builder("frontier.outbox.lag.seconds", outboxLagSeconds, AtomicLong::get)
        .register(registry);
    Gauge.builder("frontier.database.active", databaseActive, AtomicInteger::get)
        .register(registry);
    Gauge.builder("frontier.database.awaiting", databaseAwaiting, AtomicInteger::get)
        .register(registry);
  }

  public void command() {
    commands.increment();
  }

  public void failure() {
    failures.increment();
  }

  public void dirtyQueue(int size) {
    dirtyQueue.set(Math.max(0, size));
  }

  public void outbox(int published, int pending, long lagSeconds) {
    outboxPublished.increment(Math.max(0, published));
    outboxPending.set(Math.max(0, pending));
    outboxLagSeconds.set(Math.max(0, lagSeconds));
  }

  public void rateLimited() {
    rateLimited.increment();
  }

  public void databasePool(int active, int awaiting) {
    databaseActive.set(Math.max(0, active));
    databaseAwaiting.set(Math.max(0, awaiting));
  }

  public Map<String, Number> snapshot() {
    return Map.of(
        "commands", commands.count(),
        "failures", failures.count(),
        "rateLimited", rateLimited.count(),
        "outboxPublished", outboxPublished.count(),
        "outboxPending", outboxPending.get(),
        "outboxLagSeconds", outboxLagSeconds.get(),
        "dirtyQueue", dirtyQueue.get(),
        "databaseActive", databaseActive.get(),
        "databaseAwaiting", databaseAwaiting.get());
  }
}
