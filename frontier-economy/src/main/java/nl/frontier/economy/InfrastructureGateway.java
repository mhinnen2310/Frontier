package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface InfrastructureGateway {
  Context context(UUID city, UUID actor, UUID from, UUID to);

  Edge register(
      UUID city,
      UUID actor,
      UUID from,
      UUID to,
      int importance,
      InfrastructureValidator.Validation validation,
      Instant now);

  int markDirty(List<ChangedBlock> changes, Instant now);

  List<DirtyRoute> leaseDirty(UUID worker, int maximum, Instant now, Instant leaseUntil);

  HealthResolution applyInspection(
      UUID edge, UUID worker, InfrastructureValidator.Validation validation, Instant now);

  void releaseDirty(UUID edge, UUID worker, String reason, Instant now);

  List<NetworkEdge> network();

  void updateCriticality(Map<UUID, Integer> scores, Instant now);

  List<MaintenanceOrder> maintenance(UUID city);

  List<Warning> warnings(UUID city);

  record Point(UUID world, int x, int y, int z) {}

  record Context(UUID city, Point from, Point to) {}

  record ChangedBlock(UUID world, int x, int y, int z, String reason) {}

  record DirtyRoute(
      UUID edge,
      UUID city,
      UUID fromNode,
      UUID toNode,
      InfrastructureType type,
      int importance,
      Context context) {}

  record HealthResolution(
      UUID edge,
      String state,
      int physicalHealth,
      int bridgeIntegrity,
      UUID maintenanceOrder,
      int reroutedShipments) {}

  record NetworkEdge(
      UUID id, UUID from, UUID to, int importance, int activeShipments, boolean operational) {}

  record MaintenanceOrder(
      UUID id,
      UUID edge,
      String status,
      String priority,
      String reason,
      long estimateMinor,
      UUID repairOrder,
      Instant updatedAt) {}

  record Warning(
      UUID id, UUID edge, String key, String severity, String message, Instant createdAt) {}

  record Edge(
      UUID id,
      UUID from,
      UUID to,
      InfrastructureType type,
      int health,
      long capacity,
      long traffic,
      int importance,
      UUID owner) {}
}
