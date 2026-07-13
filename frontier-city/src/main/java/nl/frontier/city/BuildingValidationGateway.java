package nl.frontier.city;

import java.time.Instant;
import java.util.UUID;

public interface BuildingValidationGateway {
  BuildingValidationContext context(
      UUID city,
      UUID actor,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String districtKey);

  RegisteredBuilding register(
      UUID city,
      UUID actor,
      SettlementGateway.Bounds bounds,
      String districtKey,
      BuildingValidationResult validation,
      Instant now);

  record RegisteredBuilding(
      UUID id,
      UUID city,
      BuildingType type,
      BuildingState state,
      int integrity,
      java.util.List<String> validationMessages) {}
}
