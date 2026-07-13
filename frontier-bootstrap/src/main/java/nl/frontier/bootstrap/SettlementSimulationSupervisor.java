package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.city.SettlementDailySimulation;

public final class SettlementSimulationSupervisor {
  private final SchedulerFacade schedulers;
  private final SettlementDailySimulation simulation;
  private final Duration checkInterval;
  private final int limit;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  public SettlementSimulationSupervisor(
      SchedulerFacade schedulers,
      SettlementDailySimulation simulation,
      Duration checkInterval,
      int limit,
      Logger logger) {
    this.schedulers = Objects.requireNonNull(schedulers);
    this.simulation = Objects.requireNonNull(simulation);
    this.checkInterval = Objects.requireNonNull(checkInterval);
    this.limit = limit;
    this.logger = Objects.requireNonNull(logger);
  }

  public void start() {
    if (!active.compareAndSet(false, true)) return;
    run();
  }

  public void stop() {
    active.set(false);
  }

  private void run() {
    if (!active.get()) return;
    schedulers
        .async(() -> simulation.cycle(limit, Instant.now()))
        .whenComplete(
            (report, failure) -> {
              if (failure != null)
                logger.log(Level.SEVERE, "Settlement daily simulation failed", failure);
              else if (report.settlements() > 0)
                logger.fine(
                    "Processed "
                        + report.settlements()
                        + " settlement days; maintenance paid by "
                        + report.maintenancePaid());
              if (active.get()) schedulers.later(checkInterval, this::run);
            });
  }
}
