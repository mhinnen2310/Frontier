package nl.frontier.city;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class PopulationService {
  private final PopulationGateway gateway;

  public PopulationService(PopulationGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public PopulationGateway.CycleReport cycle(int limit, Instant now) {
    if (limit < 1 || limit > 500) throw new DomainException("population cycle limit must be 1-500");
    return gateway.cycle(limit, now);
  }

  public PopulationGateway.PopulationReport report(UUID city, UUID actor) {
    return gateway.report(city, actor);
  }

  public List<PopulationGateway.WorkerProfile> workers(UUID city, UUID actor) {
    return gateway.workers(city, actor);
  }

  public PopulationGateway.WorkerProfile assignBuilding(
      UUID city, UUID actor, UUID worker, UUID building, Instant now) {
    return gateway.assignBuilding(city, actor, worker, building, now);
  }

  public PopulationGateway.WorkerProfile clearBuilding(
      UUID city, UUID actor, UUID worker, Instant now) {
    return gateway.clearBuilding(city, actor, worker, now);
  }

  public PopulationGateway.WorkerProfile setWage(
      UUID city, UUID actor, UUID worker, long wageMinor, Instant now) {
    if (wageMinor < 0 || wageMinor > 1_000_000)
      throw new DomainException("worker wage must be 0-1000000 cents");
    return gateway.setWage(city, actor, worker, wageMinor, now);
  }
}
