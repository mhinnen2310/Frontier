package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.world.CivilizationGateway;

final class CivilizationSupervisor {
  private final SchedulerFacade schedulers;
  private final CivilizationGateway civilization;
  private final Duration interval;
  private final int maximumKingdoms;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  CivilizationSupervisor(
      SchedulerFacade schedulers,
      CivilizationGateway civilization,
      Duration interval,
      int maximumKingdoms,
      Logger logger) {
    this.schedulers = schedulers;
    this.civilization = civilization;
    this.interval = interval;
    this.maximumKingdoms = maximumKingdoms;
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
        .asyncNamed("civilization", () -> civilization.cycle(maximumKingdoms, Instant.now()))
        .whenComplete(
            (report, failure) -> {
              if (failure != null) logger.log(Level.WARNING, "Civilization cycle failed", failure);
              else if (report.researchCompleted() + report.erasAdvanced() > 0)
                logger.info(
                    "Civilization cycle completed "
                        + report.researchCompleted()
                        + " research and advanced "
                        + report.erasAdvanced()
                        + " eras.");
              schedulers.later(interval, this::cycle);
            });
  }
}
