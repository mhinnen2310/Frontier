package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.InfrastructureHealthService;
import nl.frontier.economy.InfrastructurePathAnalyzer;

final class InfrastructureHealthSupervisor {
  private final SchedulerFacade schedulers;
  private final PaperInfrastructureSurveyor surveyor;
  private final InfrastructurePathAnalyzer analyzer;
  private final InfrastructureHealthService health;
  private final Duration interval;
  private final Duration lease;
  private final int maximum;
  private final Logger logger;
  private final UUID worker = UUID.randomUUID();
  private final AtomicBoolean active = new AtomicBoolean();

  InfrastructureHealthSupervisor(
      SchedulerFacade schedulers,
      PaperInfrastructureSurveyor surveyor,
      InfrastructurePathAnalyzer analyzer,
      InfrastructureHealthService health,
      Duration interval,
      Duration lease,
      int maximum,
      Logger logger) {
    this.schedulers = schedulers;
    this.surveyor = surveyor;
    this.analyzer = analyzer;
    this.health = health;
    this.interval = interval;
    this.lease = lease;
    this.maximum = maximum;
    this.logger = logger;
  }

  void start() {
    if (active.compareAndSet(false, true)) schedule();
  }

  void stop() {
    active.set(false);
  }

  private void schedule() {
    if (!active.get()) return;
    schedulers.later(interval, this::cycle);
  }

  private void cycle() {
    if (!active.get()) return;
    Instant now = Instant.now();
    schedulers
        .asyncNamed(
            "infrastructure-health-lease",
            () -> health.lease(worker, maximum, now, now.plus(lease)))
        .whenComplete(
            (routes, failure) -> {
              if (failure != null) {
                logger.log(Level.WARNING, "Infrastructure health lease failed", failure);
                schedule();
                return;
              }
              if (routes.isEmpty()) {
                refreshAndSchedule();
                return;
              }
              AtomicInteger remaining = new AtomicInteger(routes.size());
              for (var route : routes)
                surveyor.snapshot(
                    route.context(),
                    schedulers,
                    snapshot ->
                        schedulers
                            .asyncNamed(
                                "infrastructure-health-analysis", () -> analyzer.analyze(snapshot))
                            .thenCompose(
                                survey ->
                                    schedulers.asyncNamed(
                                        "infrastructure-health-commit",
                                        () -> health.resolve(route, worker, survey, Instant.now())))
                            .whenComplete(
                                (resolution, error) -> {
                                  if (error != null) release(route.edge(), error, remaining);
                                  else {
                                    if (!resolution.state().equals("VALID"))
                                      logger.warning(
                                          "Settlement infrastructure warning: edge "
                                              + resolution.edge()
                                              + " is "
                                              + resolution.state());
                                    finished(remaining);
                                  }
                                }),
                    error -> release(route.edge(), error, remaining));
            });
  }

  private void release(UUID edge, Throwable failure, AtomicInteger remaining) {
    logger.log(Level.WARNING, "Infrastructure health inspection failed for " + edge, failure);
    schedulers
        .asyncNamed(
            "infrastructure-health-release",
            () -> {
              health.release(edge, worker, rootMessage(failure), Instant.now());
              return true;
            })
        .whenComplete((ignored, releaseFailure) -> finished(remaining));
  }

  private void finished(AtomicInteger remaining) {
    if (remaining.decrementAndGet() == 0) refreshAndSchedule();
  }

  private void refreshAndSchedule() {
    schedulers
        .asyncNamed(
            "infrastructure-critical-paths",
            () -> {
              health.refreshCriticalPaths(Instant.now());
              return true;
            })
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null)
                logger.log(Level.WARNING, "Infrastructure critical-path refresh failed", failure);
              schedule();
            });
  }

  private static String rootMessage(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null) root = root.getCause();
    String message = root.getMessage();
    return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
  }
}
