package nl.frontier.city;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class BuildingValidationService {
  private final BuildingValidationGateway gateway;
  private final BuildingValidator validator;

  public BuildingValidationService(BuildingValidationGateway gateway, BuildingValidator validator) {
    this.gateway = Objects.requireNonNull(gateway);
    this.validator = Objects.requireNonNull(validator);
  }

  public BuildingValidationGateway.RegisteredBuilding validateAndRegister(
      UUID city,
      UUID actor,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String districtKey,
      BuildingSurvey survey,
      Instant now) {
    BuildingValidationContext persisted = gateway.context(city, actor, type, bounds, districtKey);
    BuildingValidationContext context =
        new BuildingValidationContext(
            persisted.controlledBySettlement(),
            persisted.overlap(),
            persisted.districtCompatible(),
            survey.roadBlocks() > 0);
    BuildingValidationResult result = validator.validate(type, survey, context);
    if (!result.valid())
      throw new DomainException(
          "building validation failed: " + String.join("; ", result.violations()));
    return gateway.register(city, actor, bounds, districtKey, result, now);
  }
}
