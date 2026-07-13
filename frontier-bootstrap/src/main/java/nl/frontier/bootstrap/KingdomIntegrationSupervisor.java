package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.world.CivilizationGateway;
import nl.frontier.world.KingdomIntegrationService;

final class KingdomIntegrationSupervisor {
  private final SchedulerFacade schedulers;
  private final CivilizationGateway civilization;
  private final KingdomIntegrationService kingdoms;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  KingdomIntegrationSupervisor(
      SchedulerFacade schedulers,
      CivilizationGateway civilization,
      KingdomIntegrationService kingdoms,
      Logger logger) {
    this.schedulers = schedulers;
    this.civilization = civilization;
    this.kingdoms = kingdoms;
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
        .async(
            () -> {
              Instant now = Instant.now();
              LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
              for (CivilizationGateway.KingdomSnapshot kingdom : civilization.kingdoms())
                kingdoms.collectTaxes(kingdom.id(), date, now);
              kingdoms.cycle(now);
              return null;
            })
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) logger.log(Level.WARNING, "Kingdom tax cycle failed", failure);
              schedulers.later(Duration.ofHours(1), this::cycle);
            });
  }
}
