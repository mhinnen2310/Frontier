package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.warfare.CampaignGateway;
import nl.frontier.warfare.WarPolicyCache;

final class CampaignSupervisor {
  private final SchedulerFacade schedulers;
  private final CampaignGateway campaigns;
  private final WarPolicyCache cache;
  private final Duration interval;
  private final int maximum;
  private final Logger logger;
  private final PaperPresentationService presentation;
  private final AtomicBoolean active = new AtomicBoolean();

  CampaignSupervisor(
      SchedulerFacade schedulers,
      CampaignGateway campaigns,
      WarPolicyCache cache,
      Duration interval,
      int maximum,
      Logger logger,
      PaperPresentationService presentation) {
    this.schedulers = schedulers;
    this.campaigns = campaigns;
    this.cache = cache;
    this.interval = interval;
    this.maximum = maximum;
    this.logger = logger;
    this.presentation = presentation;
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
        .asyncNamed(
            "campaigns",
            () -> {
              CampaignGateway.AdvanceReport report = campaigns.advanceDue(maximum, Instant.now());
              cache.replace(campaigns.policySnapshot(Instant.now()));
              return report;
            })
        .whenComplete(
            (report, failure) -> {
              if (failure != null) logger.log(Level.WARNING, "Campaign cycle failed", failure);
              else if (report.activated() + report.resolving() > 0)
                schedulers.global(
                    () -> {
                      presentation.campaignTransition(report.activated(), report.resolving());
                      logger.info(
                          "Campaign cycle activated "
                              + report.activated()
                              + " and moved "
                              + report.resolving()
                              + " to resolution.");
                    });
              schedulers.later(interval, this::cycle);
            });
  }
}
