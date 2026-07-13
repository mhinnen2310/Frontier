package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.HarborGateway;

final class HarborSupervisor {
  private final SchedulerFacade schedulers;
  private final HarborGateway harbor;
  private final Duration interval;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  HarborSupervisor(
      SchedulerFacade schedulers, HarborGateway harbor, Duration interval, Logger logger) {
    this.schedulers = Objects.requireNonNull(schedulers);
    this.harbor = Objects.requireNonNull(harbor);
    this.interval = Objects.requireNonNull(interval);
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
        .async(() -> harbor.refresh(Instant.now()))
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null)
                logger.log(Level.WARNING, "Frontier Harbor refresh failed", failure);
              schedulers.later(interval, this::cycle);
            });
  }
}
