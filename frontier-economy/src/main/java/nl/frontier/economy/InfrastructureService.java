package nl.frontier.economy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class InfrastructureService {
  private final InfrastructureGateway gateway;
  private final InfrastructureValidator validator;

  public InfrastructureService(InfrastructureGateway gateway, InfrastructureValidator validator) {
    this.gateway = Objects.requireNonNull(gateway);
    this.validator = Objects.requireNonNull(validator);
  }

  public InfrastructureGateway.Context context(UUID city, UUID actor, UUID from, UUID to) {
    return gateway.context(city, actor, from, to);
  }

  public InfrastructureGateway.Edge register(
      UUID city,
      UUID actor,
      UUID from,
      UUID to,
      InfrastructureType type,
      int importance,
      InfrastructureSurvey survey,
      Instant now) {
    if (importance < 0 || importance > 100)
      throw new DomainException("infrastructure importance must be 0-100");
    var validation = validator.validate(type, survey);
    if (!validation.valid())
      throw new DomainException(
          "infrastructure validation failed: " + String.join("; ", validation.violations()));
    return gateway.register(city, actor, from, to, importance, validation, now);
  }
}
