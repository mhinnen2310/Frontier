package nl.frontier.city;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class DistrictApplicationService {
  private final DistrictGateway gateway;

  public DistrictApplicationService(DistrictGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public DistrictGateway.DistrictSnapshot create(
      UUID city,
      UUID actor,
      String name,
      DistrictType type,
      SettlementGateway.Bounds bounds,
      Instant now) {
    return gateway.create(city, actor, cleanName(name), type, bounds, now);
  }

  public List<DistrictGateway.DistrictSnapshot> list(UUID city, UUID actor) {
    return gateway.list(city, actor);
  }

  public DistrictGateway.DistrictReport report(UUID district, UUID actor) {
    return gateway.report(district, actor);
  }

  public DistrictGateway.DistrictSnapshot rename(
      UUID district, UUID actor, String name, Instant now) {
    return gateway.rename(district, actor, cleanName(name), now);
  }

  public DistrictGateway.DistrictSnapshot resize(
      UUID district, UUID actor, SettlementGateway.Bounds bounds, Instant now) {
    return gateway.resize(district, actor, bounds, now);
  }

  public DistrictGateway.DistrictSnapshot manager(
      UUID district, UUID actor, UUID manager, boolean transfer, Instant now) {
    return gateway.assignManager(district, actor, manager, transfer, now);
  }

  public DistrictGateway.DistrictSnapshot budget(
      UUID district, UUID actor, long budgetMinor, Instant now) {
    if (budgetMinor < 0) throw new DomainException("district budget cannot be negative");
    return gateway.setBudget(district, actor, budgetMinor, now);
  }

  public DistrictGateway.DistrictSnapshot priority(
      UUID district, UUID actor, int priority, Instant now) {
    if (priority < 0 || priority > 100)
      throw new DomainException("district priority must be 0-100");
    return gateway.setPriority(district, actor, priority, now);
  }

  public DistrictGateway.DistrictSnapshot policy(
      UUID district, UUID actor, String key, String value, Instant now) {
    String normalized = Objects.requireNonNull(key).strip().toUpperCase(Locale.ROOT);
    if (!java.util.Set.of("ACCESS", "AUTOMATION", "TAX", "REPAIR", "WORK").contains(normalized))
      throw new DomainException("district policy must be ACCESS, AUTOMATION, TAX, REPAIR, or WORK");
    String clean = Objects.requireNonNull(value).strip();
    if (clean.isEmpty() || clean.length() > 64) throw new DomainException("invalid policy value");
    return gateway.setPolicy(district, actor, normalized, clean, now);
  }

  public void delete(UUID district, UUID actor, Instant now) {
    gateway.delete(district, actor, now);
  }

  public DistrictGateway.WorkerAssignment worker(
      UUID district, UUID actor, UUID worker, int priority, Instant now) {
    if (priority < 0 || priority > 100) throw new DomainException("worker priority must be 0-100");
    return gateway.assignWorker(district, actor, worker, priority, now);
  }

  public void removeWorker(UUID district, UUID actor, UUID worker, Instant now) {
    gateway.removeWorker(district, actor, worker, now);
  }

  private static String cleanName(String name) {
    String clean = Objects.requireNonNull(name).strip();
    if (clean.length() < 3 || clean.length() > 32)
      throw new DomainException("district name must be 3-32 characters");
    return clean;
  }
}
