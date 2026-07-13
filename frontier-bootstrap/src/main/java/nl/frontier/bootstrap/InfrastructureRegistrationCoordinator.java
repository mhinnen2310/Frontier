package nl.frontier.bootstrap;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.InfrastructureGateway;
import nl.frontier.economy.InfrastructurePathAnalyzer;
import nl.frontier.economy.InfrastructureService;
import nl.frontier.economy.InfrastructureType;
import org.bukkit.entity.Player;

final class InfrastructureRegistrationCoordinator {
  private final SchedulerFacade schedulers;
  private final PaperInfrastructureSurveyor surveyor;
  private final InfrastructurePathAnalyzer analyzer;
  private final InfrastructureService infrastructure;

  InfrastructureRegistrationCoordinator(
      SchedulerFacade schedulers,
      PaperInfrastructureSurveyor surveyor,
      InfrastructurePathAnalyzer analyzer,
      InfrastructureService infrastructure) {
    this.schedulers = schedulers;
    this.surveyor = surveyor;
    this.analyzer = analyzer;
    this.infrastructure = infrastructure;
  }

  void register(
      Player player,
      UUID city,
      UUID from,
      UUID to,
      InfrastructureType type,
      int importance,
      Consumer<InfrastructureGateway.Edge> success,
      Consumer<Throwable> failure) {
    UUID actor = player.getUniqueId();
    schedulers
        .async(() -> infrastructure.context(city, actor, from, to))
        .whenComplete(
            (context, contextError) -> {
              if (contextError != null) {
                respond(player, () -> failure.accept(contextError));
                return;
              }
              surveyor.snapshot(
                  context,
                  schedulers,
                  snapshot ->
                      schedulers
                          .asyncNamed("infrastructure-analysis", () -> analyzer.analyze(snapshot))
                          .thenCompose(
                              survey ->
                                  schedulers.asyncNamed(
                                      "infrastructure-registration",
                                      () ->
                                          infrastructure.register(
                                              city,
                                              actor,
                                              from,
                                              to,
                                              type,
                                              importance,
                                              survey,
                                              Instant.now())))
                          .whenComplete(
                              (edge, error) ->
                                  respond(
                                      player,
                                      () -> {
                                        if (error == null) success.accept(edge);
                                        else failure.accept(error);
                                      })),
                  error -> respond(player, () -> failure.accept(error)));
            });
  }

  private void respond(Player player, Runnable response) {
    schedulers.forEntity(player.getUniqueId(), response, () -> {});
  }
}
