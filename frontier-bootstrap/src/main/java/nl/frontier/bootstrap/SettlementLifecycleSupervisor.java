package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.city.SettlementLifecycleService;

final class SettlementLifecycleSupervisor {
  private final SchedulerFacade schedulers;
  private final SettlementLifecycleService lifecycle;
  private final SettlementFoundingCoordinator founding;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  SettlementLifecycleSupervisor(
      SchedulerFacade schedulers,
      SettlementLifecycleService lifecycle,
      SettlementFoundingCoordinator founding,
      Logger logger) {
    this.schedulers = schedulers;
    this.lifecycle = lifecycle;
    this.founding = founding;
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
    founding.recoverPending();
    schedulers
        .async(() -> lifecycle.recover(Instant.now(), 32))
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null)
                logger.log(Level.WARNING, "Settlement lifecycle recovery failed", failure);
              schedulers.later(Duration.ofMinutes(1), this::cycle);
            });
  }
}
