package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.LogisticsGateway;

final class LogisticsSupervisor {
  private final SchedulerFacade schedulers;
  private final LogisticsGateway logistics;
  private final Duration interval;
  private final int maximum;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  LogisticsSupervisor(
      SchedulerFacade schedulers,
      LogisticsGateway logistics,
      Duration interval,
      int maximum,
      Logger logger) {
    this.schedulers = schedulers;
    this.logistics = logistics;
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
        () ->
            schedulers
                .asyncNamed("logistics", () -> logistics.cycle(maximum, Instant.now()))
                .whenComplete(
                    (report, failure) -> {
                      if (failure != null)
                        logger.log(Level.WARNING, "Logistics cycle failed", failure);
                      else if (report.delivered() > 0)
                        logger.info("Logistics delivered " + report.delivered() + " shipment(s).");
                      schedule();
                    }));
  }
}
