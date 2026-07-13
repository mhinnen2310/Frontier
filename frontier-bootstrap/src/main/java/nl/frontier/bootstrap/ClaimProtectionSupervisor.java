package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.city.ClaimProtectionCache;
import nl.frontier.city.ClaimProtectionGateway;

final class ClaimProtectionSupervisor {
  private final SchedulerFacade schedulers;
  private final ClaimProtectionGateway gateway;
  private final ClaimProtectionCache cache;
  private final Duration interval;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  ClaimProtectionSupervisor(
      SchedulerFacade schedulers,
      ClaimProtectionGateway gateway,
      ClaimProtectionCache cache,
      Duration interval,
      Logger logger) {
    this.schedulers = schedulers;
    this.gateway = gateway;
    this.cache = cache;
    this.interval = interval;
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
        .async(() -> gateway.load(Instant.now()))
        .whenComplete(
            (snapshot, failure) -> {
              if (failure != null)
                logger.log(Level.WARNING, "Claim protection cache refresh failed", failure);
              else cache.replace(snapshot);
              schedulers.later(interval, this::cycle);
            });
  }
}
