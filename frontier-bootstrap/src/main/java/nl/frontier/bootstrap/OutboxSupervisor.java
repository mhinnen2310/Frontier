package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.OutboxDispatcher;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.observability.FrontierMetrics;

final class OutboxSupervisor {
  private final SchedulerFacade schedulers;
  private final OutboxDispatcher dispatcher;
  private final FrontierMetrics metrics;
  private final Duration interval;
  private final int batchSize;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  OutboxSupervisor(
      SchedulerFacade schedulers,
      OutboxDispatcher dispatcher,
      FrontierMetrics metrics,
      Duration interval,
      int batchSize,
      Logger logger) {
    this.schedulers = Objects.requireNonNull(schedulers);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.metrics = Objects.requireNonNull(metrics);
    this.interval = Objects.requireNonNull(interval);
    this.batchSize = batchSize;
    this.logger = Objects.requireNonNull(logger);
  }

  void start() {
    if (active.compareAndSet(false, true)) cycle();
  }

  void stop() {
    active.set(false);
  }

  private void cycle() {
    if (!active.get()) return;
    schedulers
        .asyncNamed("database-outbox", () -> dispatcher.dispatch(batchSize, Instant.now()))
        .whenComplete(
            (report, failure) -> {
              if (failure != null) {
                metrics.failure();
                logger.log(Level.WARNING, "Outbox dispatch cycle failed", failure);
              } else {
                metrics.outbox(report.published(), report.remaining(), report.oldestLagSeconds());
              }
              schedulers.later(interval, this::cycle);
            });
  }
}
