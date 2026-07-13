package nl.frontier.city;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every mutation is an authorization-rechecking database transaction. */
public interface DistrictGateway {
  DistrictSnapshot create(
      UUID city,
      UUID actor,
      String name,
      DistrictType type,
      SettlementGateway.Bounds bounds,
      long maintenanceMinor,
      Instant now);

  List<DistrictSnapshot> list(UUID city, UUID actor);

  DistrictReport report(UUID district, UUID actor);

  DistrictSnapshot rename(UUID district, UUID actor, String name, Instant now);

  DistrictSnapshot resize(
      UUID district,
      UUID actor,
      SettlementGateway.Bounds bounds,
      long maintenanceMinor,
      Instant now);

  DistrictSnapshot assignManager(
      UUID district, UUID actor, UUID manager, boolean transfer, Instant now);

  DistrictSnapshot setBudget(UUID district, UUID actor, long budgetMinor, Instant now);

  DistrictSnapshot setPriority(UUID district, UUID actor, int priority, Instant now);

  DistrictSnapshot setPolicy(UUID district, UUID actor, String key, String value, Instant now);

  void delete(UUID district, UUID actor, Instant now);

  WorkerAssignment assignWorker(UUID district, UUID actor, UUID worker, int priority, Instant now);

  void removeWorker(UUID district, UUID actor, UUID worker, Instant now);

  List<DistrictMembership> memberships(UUID district, UUID actor);

  record DistrictSnapshot(
      UUID id,
      UUID city,
      String name,
      DistrictType type,
      SettlementGateway.Bounds bounds,
      DistrictCenter center,
      UUID manager,
      DistrictStatus status,
      int tier,
      long budgetMinor,
      long maintenanceMinor,
      int priority,
      Map<String, String> policies,
      DistrictType.Bonuses bonuses,
      long version) {}

  record WorkerAssignment(UUID district, UUID worker, int priority, Instant assignedAt) {}

  record DistrictCenter(UUID world, int x, int y, int z) {}

  record DistrictMembership(
      UUID district, UUID player, DistrictRole role, UUID assignedBy, Instant joinedAt) {}

  record HistoryEntry(String action, String details, UUID actor, Instant occurredAt) {}

  record DistrictReport(
      DistrictSnapshot district,
      int members,
      int workers,
      int buildings,
      long storedUnits,
      long budgetSpentMinor,
      List<HistoryEntry> history) {}
}
