package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.world.DynamicEventService;

final class DynamicEventSupervisor {
  private final SchedulerFacade schedulers;
  private final DynamicEventService events;
  private final Duration interval;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  DynamicEventSupervisor(
      SchedulerFacade schedulers, DynamicEventService events, Duration interval, Logger logger) {
    this.schedulers = schedulers;
    this.events = events;
    this.interval = interval;
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
        .asyncNamed("events", () -> events.cycle(32, Instant.now()))
        .whenComplete(
            (report, failure) -> {
              if (failure != null)
                logger.log(Level.WARNING, "Dynamic event detection failed", failure);
              else if (report.detected() > 0)
                logger.info("Detected " + report.detected() + " dynamic event(s).");
              schedulers.later(interval, this::cycle);
            });
  }
}
