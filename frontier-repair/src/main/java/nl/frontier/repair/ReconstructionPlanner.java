package nl.frontier.repair;

import static nl.frontier.domain.Position.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class ReconstructionPlanner {
  public enum Layer {
    FOUNDATION,
    STRUCTURE,
    FLOOR,
    ROOF,
    MULTIPART,
    REDSTONE,
    DECORATION
  }

  public Plan plan(List<Task> tasks) {
    Map<UUID, Task> byId = new HashMap<>();
    Map<UUID, Integer> incoming = new HashMap<>();
    Map<UUID, List<UUID>> outgoing = new HashMap<>();
    for (Task task : tasks) {
      if (byId.put(task.id(), task) != null) throw new DomainException("duplicate repair task");
      incoming.put(task.id(), task.dependencies().size());
      for (UUID dependency : task.dependencies())
        outgoing.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(task.id());
    }
    for (Task task : tasks) {
      if (!byId.keySet().containsAll(task.dependencies()))
        throw new DomainException("unknown task dependency");
    }
    Queue<UUID> ready = new ArrayDeque<>();
    tasks.stream()
        .filter(task -> incoming.get(task.id()) == 0)
        .sorted(java.util.Comparator.comparing(Task::layer).thenComparing(Task::id))
        .forEach(task -> ready.add(task.id()));
    List<Task> ordered = new ArrayList<>();
    while (!ready.isEmpty()) {
      UUID id = ready.remove();
      ordered.add(byId.get(id));
      for (UUID next : outgoing.getOrDefault(id, List.of())) {
        int remaining = incoming.compute(next, (ignored, value) -> value == null ? 0 : value - 1);
        if (remaining == 0) ready.add(next);
      }
    }
    if (ordered.size() != tasks.size())
      throw new DomainException("repair dependency graph contains a cycle");
    return new Plan(List.copyOf(ordered), zones(ordered));
  }

  private static Map<Integer, List<Task>> zones(List<Task> tasks) {
    Map<Integer, List<Task>> mutable = new HashMap<>();
    for (Task task : tasks) {
      int zone =
          31 * Math.floorDiv(task.position().x(), 16) + Math.floorDiv(task.position().z(), 16);
      mutable.computeIfAbsent(zone, ignored -> new ArrayList<>()).add(task);
    }
    Map<Integer, List<Task>> result = new HashMap<>();
    mutable.forEach((key, value) -> result.put(key, List.copyOf(value)));
    return Map.copyOf(result);
  }

  public record Task(
      UUID id,
      BlockPos position,
      String expectedCurrent,
      String target,
      String commodity,
      Layer layer,
      Set<UUID> dependencies) {
    public Task {
      dependencies = Set.copyOf(new HashSet<>(dependencies));
    }
  }

  public record Plan(List<Task> executionOrder, Map<Integer, List<Task>> zones) {}
}
