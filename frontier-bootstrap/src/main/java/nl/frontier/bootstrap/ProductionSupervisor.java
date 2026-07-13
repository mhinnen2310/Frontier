package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.ProductionGateway;

final class ProductionSupervisor {
  private final SchedulerFacade schedulers;
  private final ProductionGateway production;
  private final Duration interval;
  private final int maximumOrders;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  ProductionSupervisor(
      SchedulerFacade schedulers,
      ProductionGateway production,
      Duration interval,
      int maximumOrders,
      Logger logger) {
    this.schedulers = schedulers;
    this.production = production;
    this.interval = interval;
    this.maximumOrders = maximumOrders;
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
                .asyncNamed(
                    "workers-production", () -> production.cycle(maximumOrders, Instant.now()))
                .whenComplete(
                    (report, failure) -> {
                      if (failure != null)
                        logger.log(Level.WARNING, "Production cycle failed", failure);
                      else if (report.completed() > 0)
                        logger.info("Production completed " + report.completed() + " order(s).");
                      schedule();
                    }));
  }
}
