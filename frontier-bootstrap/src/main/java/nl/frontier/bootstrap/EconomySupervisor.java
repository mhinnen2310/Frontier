package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.EconomyGateway;

final class EconomySupervisor {
  private final SchedulerFacade schedulers;
  private final EconomyGateway economy;
  private final Duration interval;
  private final int maximumTrades;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  EconomySupervisor(
      SchedulerFacade schedulers,
      EconomyGateway economy,
      Duration interval,
      int maximumTrades,
      Logger logger) {
    this.schedulers = schedulers;
    this.economy = economy;
    this.interval = interval;
    this.maximumTrades = maximumTrades;
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
                .async(() -> economy.match(maximumTrades, Instant.now()))
                .whenComplete(
                    (trades, failure) -> {
                      if (failure != null)
                        logger.log(
                            Level.WARNING, "Market cycle failed and was rolled back", failure);
                      else if (trades > 0)
                        logger.info("Market cycle committed " + trades + " trade(s).");
                      schedule();
                    }));
  }
}
