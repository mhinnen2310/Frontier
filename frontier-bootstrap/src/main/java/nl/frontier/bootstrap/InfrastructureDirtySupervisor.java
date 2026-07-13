package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.InfrastructureService;

final class InfrastructureDirtySupervisor {
  private final SchedulerFacade schedulers;
  private final InfrastructureDirtyTracker tracker;
  private final InfrastructureService infrastructure;
  private final Duration interval;
  private final int maximum;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  InfrastructureDirtySupervisor(
      SchedulerFacade schedulers,
      InfrastructureDirtyTracker tracker,
      InfrastructureService infrastructure,
      Duration interval,
      int maximum,
      Logger logger) {
    this.schedulers = schedulers;
    this.tracker = tracker;
    this.infrastructure = infrastructure;
    this.interval = interval;
    this.maximum = maximum;
    this.logger = logger;
  }

  void start() {
    if (active.compareAndSet(false, true)) schedule();
  }

  void stop() {
    active.set(false);
  }

  private void schedule() {
    if (!active.get()) return;
    schedulers.later(
        interval,
        () -> {
          var changes = tracker.drain(maximum);
          if (changes.isEmpty()) {
            schedule();
            return;
          }
          schedulers
              .asyncNamed(
                  "infrastructure-dirty", () -> infrastructure.markDirty(changes, Instant.now()))
              .whenComplete(
                  (marked, failure) -> {
                    if (failure != null)
                      logger.log(
                          Level.WARNING, "Infrastructure dirty-segment cycle failed", failure);
                    else if (marked > 0)
                      logger.fine("Marked " + marked + " physical route(s) dirty");
                    schedule();
                  });
        });
  }
}
