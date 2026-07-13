package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.influence.InfluenceSimulationService;

public final class InfluenceSupervisor {
  private final SchedulerFacade schedulers;
  private final InfluenceSimulationService simulation;
  private final Duration interval;
  private final int maximumSettlements;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  public InfluenceSupervisor(
      SchedulerFacade schedulers,
      InfluenceSimulationService simulation,
      Duration interval,
      int maximumSettlements,
      Logger logger) {
    this.schedulers = Objects.requireNonNull(schedulers);
    this.simulation = Objects.requireNonNull(simulation);
    this.interval = Objects.requireNonNull(interval);
    this.maximumSettlements = maximumSettlements;
    this.logger = Objects.requireNonNull(logger);
  }

  public void start() {
    if (!active.compareAndSet(false, true)) return;
    schedulers
        .async(
            () -> {
              simulation.rebuildCache();
              return null;
            })
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null)
                logger.log(Level.SEVERE, "Could not rebuild influence cache", failure);
              schedule();
            });
  }

  public void stop() {
    active.set(false);
  }

  private void schedule() {
    if (!active.get()) return;
    schedulers.later(
        interval,
        () -> {
          if (!active.get()) return;
          schedulers
              .async(() -> simulation.cycle(maximumSettlements, Instant.now()))
              .whenComplete(
                  (report, failure) -> {
                    if (failure != null)
                      logger.log(Level.SEVERE, "Influence cycle failed", failure);
                    else if (report.changedChunks() > 0)
                      logger.fine(
                          "Influence cycle processed "
                              + report.settlements()
                              + " settlements and changed "
                              + report.changedChunks()
                              + " chunks");
                    schedule();
                  });
        });
  }
}
