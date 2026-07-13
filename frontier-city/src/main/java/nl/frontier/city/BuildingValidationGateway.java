package nl.frontier.city;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BuildingValidationGateway {
  BuildingValidationContext context(
      UUID city,
      UUID actor,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String districtKey);

  BuildingValidationContext revalidationContext(
      UUID city,
      UUID actor,
      UUID building,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String districtKey);

  void authorize(UUID city, UUID actor);

  RegisteredBuilding building(UUID city, UUID actor, UUID building);

  RegisteredBuilding register(
      UUID city,
      UUID actor,
      SettlementGateway.Bounds bounds,
      String districtKey,
      BuildingValidationResult validation,
      Instant now);

  RegisteredBuilding revalidate(
      UUID city, UUID actor, UUID building, BuildingValidationResult validation, Instant now);

  RegisteredBuilding unregister(UUID city, UUID actor, UUID building, Instant now);

  TransferProposal proposeTransfer(
      UUID city, UUID actor, UUID building, UUID targetCity, Instant now, Instant expiresAt);

  RegisteredBuilding acceptTransfer(UUID actor, UUID proposal, Instant now);

  List<HistoryEntry> history(UUID city, UUID actor, UUID building, int limit);

  record RegisteredBuilding(
      UUID id,
      UUID city,
      BuildingType type,
      BuildingState state,
      int integrity,
      List<String> validationMessages,
      SettlementGateway.Bounds bounds,
      String district) {}

  record TransferProposal(
      UUID id,
      UUID building,
      UUID sourceCity,
      UUID targetCity,
      UUID requestedBy,
      String status,
      Instant expiresAt) {}

  record HistoryEntry(
      BuildingState from, BuildingState to, String report, UUID actor, Instant occurredAt) {}
}
