package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.world.WorldSimulationGateway;

final class WorldSimulationSupervisor {
  private final SchedulerFacade schedulers;
  private final WorldSimulationGateway simulation;
  private final Duration interval;
  private final int maximumCities;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  WorldSimulationSupervisor(
      SchedulerFacade schedulers,
      WorldSimulationGateway simulation,
      Duration interval,
      int maximumCities,
      Logger logger) {
    this.schedulers = schedulers;
    this.simulation = simulation;
    this.interval = interval;
    this.maximumCities = maximumCities;
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
        .async(() -> simulation.cycle(maximumCities, Instant.now()))
        .whenComplete(
            (report, failure) -> {
              if (failure != null)
                logger.log(Level.WARNING, "World simulation cycle failed", failure);
              else if (report.eventsCreated() + report.eventsAdvanced() > 0)
                logger.info(
                    "World simulation created "
                        + report.eventsCreated()
                        + " and advanced "
                        + report.eventsAdvanced()
                        + " event(s).");
              schedulers.later(interval, this::cycle);
            });
  }
}
