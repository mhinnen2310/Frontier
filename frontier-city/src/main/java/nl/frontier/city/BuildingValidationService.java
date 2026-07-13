package nl.frontier.city;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class BuildingValidationService {
  private final BuildingValidationGateway gateway;
  private final BuildingValidator validator;
  private final Duration transferProposalLifetime;

  public BuildingValidationService(BuildingValidationGateway gateway, BuildingValidator validator) {
    this(gateway, validator, Duration.ofHours(24));
  }

  public BuildingValidationService(
      BuildingValidationGateway gateway,
      BuildingValidator validator,
      Duration transferProposalLifetime) {
    this.gateway = Objects.requireNonNull(gateway);
    this.validator = Objects.requireNonNull(validator);
    this.transferProposalLifetime = Objects.requireNonNull(transferProposalLifetime);
    if (transferProposalLifetime.isZero() || transferProposalLifetime.isNegative())
      throw new IllegalArgumentException("transfer proposal lifetime must be positive");
  }

  public void authorize(UUID city, UUID actor) {
    gateway.authorize(city, actor);
  }

  public BuildingValidationResult preview(
      UUID city,
      UUID actor,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String districtKey,
      BuildingSurvey survey) {
    BuildingValidationContext persisted = gateway.context(city, actor, type, bounds, districtKey);
    return validator.validate(
        type,
        survey,
        new BuildingValidationContext(
            persisted.controlledBySettlement(),
            persisted.overlap(),
            persisted.districtCompatible(),
            survey.roadBlocks() > 0));
  }

  public BuildingValidationGateway.RegisteredBuilding validateAndRegister(
      UUID city,
      UUID actor,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String districtKey,
      BuildingSurvey survey,
      Instant now) {
    BuildingValidationResult result = preview(city, actor, type, bounds, districtKey, survey);
    if (!result.valid())
      throw new DomainException(
          "building validation failed: " + String.join("; ", result.violations()));
    return gateway.register(city, actor, bounds, districtKey, result, now);
  }

  public BuildingValidationGateway.RegisteredBuilding building(
      UUID city, UUID actor, UUID building) {
    return gateway.building(city, actor, building);
  }

  public BuildingValidationGateway.RegisteredBuilding revalidate(
      UUID city, UUID actor, UUID building, BuildingSurvey survey, Instant now) {
    var registered = gateway.building(city, actor, building);
    BuildingValidationContext persisted =
        gateway.revalidationContext(
            city, actor, building, registered.type(), registered.bounds(), registered.district());
    BuildingValidationResult result =
        validator.validate(
            registered.type(),
            survey,
            new BuildingValidationContext(
                persisted.controlledBySettlement(),
                persisted.overlap(),
                persisted.districtCompatible(),
                survey.roadBlocks() > 0));
    if (!result.valid())
      throw new DomainException(
          "building revalidation failed: " + String.join("; ", result.violations()));
    return gateway.revalidate(city, actor, building, result, now);
  }

  public BuildingValidationGateway.RegisteredBuilding unregister(
      UUID city, UUID actor, UUID building, Instant now) {
    return gateway.unregister(city, actor, building, now);
  }

  public BuildingValidationGateway.TransferProposal proposeTransfer(
      UUID city, UUID actor, UUID building, UUID targetCity, Instant now) {
    return gateway.proposeTransfer(
        city, actor, building, targetCity, now, now.plus(transferProposalLifetime));
  }

  public BuildingValidationGateway.RegisteredBuilding acceptTransfer(
      UUID actor, UUID proposal, Instant now) {
    return gateway.acceptTransfer(actor, proposal, now);
  }

  public List<BuildingValidationGateway.HistoryEntry> history(
      UUID city, UUID actor, UUID building, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("history limit must be 1-100");
    return gateway.history(city, actor, building, limit);
  }
}
