package nl.frontier.influence;

import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Position.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/** Pure, deterministic influence calculation; callers persist/apply only returned deltas. */
public final class InfluenceEngine {
  public int budget(Factors factors) {
    return Math.max(
        0,
        Math.addExact(
            Math.addExact(
                Math.addExact(factors.baseLevel(), factors.populationBonus()),
                Math.addExact(factors.prosperityBonus(), factors.infrastructureBonus())),
            -Math.addExact(
                Math.addExact(factors.maintenancePenalty(), factors.warDamagePenalty()),
                factors.corruptionPenalty())));
  }

  public Set<ChunkPos> reachable(
      ChunkPos core, int maximumChunks, Set<ChunkPos> blocked, Set<ChunkPos> roadConnected) {
    if (maximumChunks < 1) return Set.of();
    Set<ChunkPos> visited = new HashSet<>();
    Queue<ChunkPos> queue = new ArrayDeque<>();
    queue.add(core);
    while (!queue.isEmpty() && visited.size() < maximumChunks) {
      ChunkPos current = queue.remove();
      if (blocked.contains(current) || !visited.add(current)) continue;
      List<ChunkPos> neighbors = neighbors(current);
      neighbors.sort(
          Comparator.comparing((ChunkPos position) -> !roadConnected.contains(position))
              .thenComparingInt(position -> manhattan(core, position))
              .thenComparingInt(ChunkPos::x)
              .thenComparingInt(ChunkPos::z));
      for (ChunkPos neighbor : neighbors) if (!visited.contains(neighbor)) queue.add(neighbor);
    }
    return Set.copyOf(visited);
  }

  public Resolution resolve(
      Map<SettlementId, Integer> scores,
      SettlementId currentOwner,
      SettlementId leadingPreviousCycle,
      int consecutiveLeadCycles,
      int threshold,
      int requiredCycles) {
    if (scores.isEmpty()) return new Resolution(TerritoryState.WILDERNESS, null, null, 0);
    List<Map.Entry<SettlementId, Integer>> ordered = new ArrayList<>(scores.entrySet());
    ordered.sort(
        Map.Entry.<SettlementId, Integer>comparingByValue()
            .reversed()
            .thenComparing(entry -> entry.getKey().value()));
    Map.Entry<SettlementId, Integer> leader = ordered.getFirst();
    boolean overlap = ordered.size() > 1 && ordered.get(1).getValue() > 0;
    if (!overlap)
      return new Resolution(
          TerritoryState.CONTROLLED, leader.getKey(), leader.getKey(), requiredCycles);

    int cycles =
        Objects.equals(leader.getKey(), leadingPreviousCycle) ? consecutiveLeadCycles + 1 : 1;
    int runnerUp = ordered.get(1).getValue();
    if (leader.getValue() - runnerUp >= threshold && cycles >= requiredCycles) {
      return new Resolution(TerritoryState.CONTROLLED, leader.getKey(), leader.getKey(), cycles);
    }
    return new Resolution(TerritoryState.CONTESTED, currentOwner, leader.getKey(), cycles);
  }

  public Map<ChunkPos, TerritoryDelta> deltas(
      Map<ChunkPos, Snapshot> before, Map<ChunkPos, Snapshot> after) {
    Map<ChunkPos, TerritoryDelta> result = new HashMap<>();
    Set<ChunkPos> keys = new HashSet<>(before.keySet());
    keys.addAll(after.keySet());
    for (ChunkPos key : keys) {
      Snapshot oldValue = before.getOrDefault(key, Snapshot.wilderness());
      Snapshot newValue = after.getOrDefault(key, Snapshot.wilderness());
      if (!oldValue.equals(newValue)) result.put(key, new TerritoryDelta(oldValue, newValue));
    }
    return Map.copyOf(result);
  }

  private static List<ChunkPos> neighbors(ChunkPos position) {
    return new ArrayList<>(
        List.of(
            new ChunkPos(position.world(), position.x() + 1, position.z()),
            new ChunkPos(position.world(), position.x() - 1, position.z()),
            new ChunkPos(position.world(), position.x(), position.z() + 1),
            new ChunkPos(position.world(), position.x(), position.z() - 1)));
  }

  private static int manhattan(ChunkPos left, ChunkPos right) {
    return Math.abs(left.x() - right.x()) + Math.abs(left.z() - right.z());
  }

  public record Factors(
      int baseLevel,
      int populationBonus,
      int prosperityBonus,
      int infrastructureBonus,
      int maintenancePenalty,
      int warDamagePenalty,
      int corruptionPenalty) {}

  public record Resolution(
      TerritoryState state, SettlementId owner, SettlementId leader, int consecutiveLeadCycles) {}

  public record Snapshot(TerritoryState state, SettlementId owner) {
    public static Snapshot wilderness() {
      return new Snapshot(TerritoryState.WILDERNESS, null);
    }
  }

  public record TerritoryDelta(Snapshot before, Snapshot after) {}
}
