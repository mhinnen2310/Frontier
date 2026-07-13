package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.city.PopulationService;

final class PopulationSupervisor {
  private final SchedulerFacade schedulers;
  private final PopulationService population;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  PopulationSupervisor(SchedulerFacade schedulers, PopulationService population, Logger logger) {
    this.schedulers = schedulers;
    this.population = population;
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
        .asyncNamed("population", () -> population.cycle(64, Instant.now()))
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null)
                logger.log(Level.WARNING, "Population simulation failed", failure);
              schedulers.later(Duration.ofMinutes(5), this::cycle);
            });
  }
}
