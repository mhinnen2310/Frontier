package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.CommercialService;

final class CommercialSupervisor {
  private final SchedulerFacade schedulers;
  private final CommercialService commerce;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  CommercialSupervisor(SchedulerFacade schedulers, CommercialService commerce, Logger logger) {
    this.schedulers = schedulers;
    this.commerce = commerce;
    this.logger = logger;
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
        .asyncNamed("commercial", () -> commerce.cycle(128, Instant.now()))
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) logger.log(Level.WARNING, "Commercial cycle failed", failure);
              schedulers.later(Duration.ofMinutes(5), this::cycle);
            });
  }
}
