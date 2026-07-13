package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.warfare.CampaignOutcomeService;

final class CampaignOutcomeSupervisor {
  private final SchedulerFacade schedulers;
  private final CampaignOutcomeService outcomes;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  CampaignOutcomeSupervisor(
      SchedulerFacade schedulers, CampaignOutcomeService outcomes, Logger logger) {
    this.schedulers = schedulers;
    this.outcomes = outcomes;
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
        .asyncNamed("campaign-outcomes", () -> outcomes.cycle(128, Instant.now()))
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null)
                logger.log(Level.WARNING, "Campaign tribute cycle failed", failure);
              schedulers.later(Duration.ofMinutes(5), this::cycle);
            });
  }
}
