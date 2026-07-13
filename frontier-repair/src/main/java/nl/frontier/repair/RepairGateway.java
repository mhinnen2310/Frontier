package nl.frontier.repair;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface RepairGateway {
  Quote quote(UUID city, UUID actor, UUID campaign, RepairOrder.Priority priority, Instant now);

  RepairSnapshot purchase(
      UUID city,
      UUID actor,
      UUID campaign,
      RepairOrder.Priority priority,
      UUID idempotency,
      Instant now);

  List<RepairSnapshot> orders(UUID city);

  List<PreparedTask> leaseReady(UUID worker, int maximum, Instant now, Instant leaseUntil);

  void commit(UUID worker, UUID task, Instant now);

  void release(UUID worker, UUID task, String reason, Instant now);

  void conflict(UUID worker, UUID task, String actualBlockData, Instant now);

  int archiveCompleted(Instant completedBefore, int maximum, Instant now);

  record Requirement(String commodity, long required, long available, long shortage) {}

  record Quote(
      UUID city,
      UUID campaign,
      int tasks,
      long laborCostMinor,
      long materialCostMinor,
      long totalCostMinor,
      List<Requirement> requirements) {}

  record RepairSnapshot(
      UUID id,
      UUID city,
      UUID campaign,
      RepairOrder.Priority priority,
      RepairOrder.Status status,
      long estimateMinor,
      int totalTasks,
      int completedTasks,
      Map<String, Long> shortages,
      long version) {}

  record PreparedTask(
      UUID id,
      UUID order,
      UUID city,
      UUID worker,
      UUID workerEntity,
      UUID world,
      int x,
      int y,
      int z,
      String expectedCurrent,
      String targetData,
      String commodity,
      ReconstructionPlanner.Layer layer,
      UUID consumption) {}
}
