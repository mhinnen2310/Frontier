package nl.frontier.bootstrap;

import java.time.Instant;
import java.util.function.Consumer;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.city.BuildingType;
import nl.frontier.city.BuildingValidationGateway;
import nl.frontier.city.BuildingValidationService;
import nl.frontier.city.SettlementGateway;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import org.bukkit.entity.Player;

final class BuildingRegistrationCoordinator {
  private final SchedulerFacade schedulers;
  private final PaperBuildingSurveyor surveyor;
  private final BuildingValidationService buildings;

  BuildingRegistrationCoordinator(
      SchedulerFacade schedulers,
      PaperBuildingSurveyor surveyor,
      BuildingValidationService buildings) {
    this.schedulers = schedulers;
    this.surveyor = surveyor;
    this.buildings = buildings;
  }

  void register(
      Player player,
      java.util.UUID city,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String district,
      Consumer<BuildingValidationGateway.RegisteredBuilding> success,
      Consumer<Throwable> failure) {
    try {
      schedulers.at(
          new BlockPos(new WorldId(bounds.world()), bounds.minX(), bounds.minY(), bounds.minZ()),
          () -> {
            try {
              var survey = surveyor.survey(bounds);
              schedulers
                  .async(
                      () ->
                          buildings.validateAndRegister(
                              city,
                              player.getUniqueId(),
                              type,
                              bounds,
                              district,
                              survey,
                              Instant.now()))
                  .whenComplete(
                      (result, error) ->
                          schedulers.forEntity(
                              player.getUniqueId(),
                              () -> {
                                if (error == null) success.accept(result);
                                else failure.accept(error);
                              },
                              () -> {}));
            } catch (RuntimeException error) {
              schedulers.forEntity(player.getUniqueId(), () -> failure.accept(error), () -> {});
            }
          });
    } catch (RuntimeException error) {
      schedulers.forEntity(player.getUniqueId(), () -> failure.accept(error), () -> {});
    }
  }
}
