package nl.frontier.repair;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transactional boundary for Builder Guild management and player repair contribution. */
public interface BuilderGuildGateway {
  Overview overview(UUID city, UUID actor, Instant now);

  Overview appointForeman(UUID city, UUID actor, UUID worker, Instant now);

  Team createTeam(
      UUID city, UUID actor, String name, UUID foreman, List<UUID> builders, Instant now);

  Project prioritize(UUID city, UUID actor, UUID order, RepairOrder.Priority priority, Instant now);

  Project emergency(UUID city, UUID actor, UUID order, Instant now);

  Contribution deliver(
      UUID city,
      UUID actor,
      UUID order,
      String commodity,
      long units,
      UUID idempotency,
      Instant now);

  Contribution boost(UUID city, UUID actor, UUID order, int points, UUID idempotency, Instant now);

  RepairZone inspect(UUID city, UUID actor, UUID world, int x, int y, int z, Instant now);

  RepairZone resolveConflict(UUID city, UUID actor, UUID conflict, Instant now);

  AssistPlan beginAssist(UUID city, UUID actor, UUID order, Instant now, Instant expiresAt);

  ManualResult completeManual(
      UUID session,
      UUID actor,
      UUID task,
      UUID world,
      int x,
      int y,
      int z,
      String placedData,
      UUID idempotency,
      Instant now);

  record Overview(
      UUID depot,
      UUID building,
      int tier,
      long capacity,
      long stored,
      int teamCapacity,
      UUID foreman,
      int availableBuilders,
      int workerShortage,
      List<Team> teams,
      List<Project> projects) {
    public Overview {
      teams = List.copyOf(teams);
      projects = List.copyOf(projects);
    }
  }

  record Team(UUID id, String name, UUID foreman, int builders, int capacity, int priority) {}

  record Project(
      UUID id,
      RepairOrder.Priority priority,
      String status,
      int completed,
      int total,
      boolean emergency,
      int boost,
      List<String> blockedReasons) {
    public Project {
      blockedReasons = List.copyOf(blockedReasons);
    }
  }

  record Contribution(
      UUID id, UUID order, String kind, String commodity, long units, String status) {}

  record RepairZone(
      UUID task,
      UUID order,
      String taskStatus,
      String expectedData,
      String targetData,
      UUID conflict,
      String blockedReason) {}

  record AssistTask(
      UUID id, UUID world, int x, int y, int z, String expectedData, String targetData) {}

  record AssistPlan(UUID session, UUID order, Instant expiresAt, List<AssistTask> tasks) {
    public AssistPlan {
      tasks = List.copyOf(tasks);
    }
  }

  record ManualResult(UUID task, UUID order, int completed, int total, String status) {}
}
