package nl.frontier.city;

import java.time.Instant;
import java.util.UUID;

public interface BuildingValidationGateway {
  BuildingValidator.ValidationContext context(
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
      BuildingValidator.ValidationResult validation,
      Instant now);

  record RegisteredBuilding(
      UUID id,
      UUID city,
      BuildingType type,
      String state,
      int integrity,
      java.util.List<String> validationMessages) {}
}
