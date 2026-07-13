package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Restart-safe production boundary. Inputs are reserved before progress and outputs commit once.
 */
public interface ProductionGateway {
  ProductionOrder queue(
      UUID city,
      UUID actor,
      UUID building,
      String recipe,
      int quantity,
      int priority,
      UUID idempotencyKey,
      Instant now);

  WorkerSnapshot hire(
      UUID city, UUID actor, String profession, int skill, long dailySalaryMinor, Instant now);

  List<ProductionOrder> orders(UUID city);

  CycleReport cycle(int maximumOrders, Instant now);

  record ProductionOrder(
      UUID id,
      UUID building,
      String recipe,
      int requested,
      int completed,
      String status,
      int priority,
      long progress,
      long target) {}

  record WorkerSnapshot(
      UUID id, UUID city, String profession, int skill, String state, long salaryMinor) {}

  record CycleReport(int visited, int completed, int paused) {}
}
