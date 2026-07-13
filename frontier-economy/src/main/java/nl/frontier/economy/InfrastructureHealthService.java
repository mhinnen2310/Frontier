package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class InfrastructureHealthService {
  private final InfrastructureGateway gateway;
  private final InfrastructureValidator validator;
  private final CriticalPathAnalyzer criticalPaths;

  public InfrastructureHealthService(
      InfrastructureGateway gateway,
      InfrastructureValidator validator,
      CriticalPathAnalyzer criticalPaths) {
    this.gateway = Objects.requireNonNull(gateway);
    this.validator = Objects.requireNonNull(validator);
    this.criticalPaths = Objects.requireNonNull(criticalPaths);
  }

  public List<InfrastructureGateway.DirtyRoute> lease(
      UUID worker, int maximum, Instant now, Instant leaseUntil) {
    if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
    return gateway.leaseDirty(worker, maximum, now, leaseUntil);
  }

  public InfrastructureGateway.HealthResolution resolve(
      InfrastructureGateway.DirtyRoute route,
      UUID worker,
      InfrastructureSurvey survey,
      Instant now) {
    return gateway.applyInspection(
        route.edge(), worker, validator.validate(route.type(), survey), now);
  }

  public void release(UUID edge, UUID worker, String reason, Instant now) {
    gateway.releaseDirty(edge, worker, reason, now);
  }

  public void refreshCriticalPaths(Instant now) {
    gateway.updateCriticality(criticalPaths.score(gateway.network()), now);
  }

  public List<InfrastructureGateway.MaintenanceOrder> maintenance(UUID city) {
    return gateway.maintenance(city);
  }

  public List<InfrastructureGateway.Warning> warnings(UUID city) {
    return gateway.warnings(city);
  }
}
