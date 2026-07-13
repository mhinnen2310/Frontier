package nl.frontier.influence;

import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Position.ChunkPos;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class InfluenceSimulationService {
  private final InfluencePersistence persistence;
  private final InfluenceEngine engine;
  private final ChunkOwnershipCache cache;
  private final UUID worker = UUID.randomUUID();
  private final int contestedThreshold;
  private final int requiredLeadCycles;

  public InfluenceSimulationService(
      InfluencePersistence persistence,
      ChunkOwnershipCache cache,
      int contestedThreshold,
      int requiredLeadCycles) {
    this.persistence = persistence;
    this.cache = cache;
    this.engine = new InfluenceEngine();
    this.contestedThreshold = contestedThreshold;
    this.requiredLeadCycles = requiredLeadCycles;
  }

  public void rebuildCache() {
    cache.replace(persistence.allOwnership());
  }

  public CycleReport cycle(int maximumSettlements, Instant now) {
    List<InfluencePersistence.Dirty> dirty =
        persistence.leaseDirty(worker, maximumSettlements, now, now.plus(Duration.ofMinutes(2)));
    int chunks = 0;
    for (InfluencePersistence.Dirty item : dirty) {
      try {
        chunks += recalculate(item.city(), now);
      } finally {
        persistence.release(item.city(), worker);
      }
    }
    return new CycleReport(dirty.size(), chunks);
  }

  private int recalculate(SettlementId city, Instant now) {
    InfluencePersistence.Context context = persistence.load(city);
    int budget = Math.min(context.maximumClaims(), engine.budget(context.factors()));
    Set<ChunkPos> reachable =
        engine.reachable(
            context.core(), Math.max(1, budget), context.blocked(), context.roadConnected());
    Map<ChunkPos, InfluencePersistence.Score> cityScores = new HashMap<>();
    for (ChunkPos position : reachable) {
      int distance =
          Math.abs(position.x() - context.core().x()) + Math.abs(position.z() - context.core().z());
      int score = Math.max(1, engine.budget(context.factors()) - distance * 10);
      cityScores.put(position, new InfluencePersistence.Score(score, 0));
    }

    Set<ChunkPos> affected = new HashSet<>(context.previousScored());
    affected.addAll(reachable);
    Map<ChunkPos, Map<SettlementId, InfluencePersistence.Score>> allScores =
        new HashMap<>(persistence.scores(affected));
    for (ChunkPos position : affected) {
      Map<SettlementId, InfluencePersistence.Score> values =
          new HashMap<>(allScores.getOrDefault(position, Map.of()));
      InfluencePersistence.Score newValue = cityScores.get(position);
      if (newValue == null) values.remove(city);
      else values.put(city, newValue);
      allScores.put(position, values);
    }
    Map<ChunkPos, ChunkOwnershipCache.Entry> ownership = persistence.ownership(affected);
    Map<ChunkPos, InfluenceEngine.Resolution> resolutions = new HashMap<>();
    for (ChunkPos position : affected) {
      Map<SettlementId, InfluencePersistence.Score> scores =
          allScores.getOrDefault(position, Map.of());
      Map<SettlementId, Integer> values = new HashMap<>();
      scores.forEach((key, value) -> values.put(key, value.value()));
      SettlementId previousLeader =
          scores.entrySet().stream()
              .filter(entry -> entry.getValue().leadCycles() > 0)
              .max(java.util.Comparator.comparingInt(entry -> entry.getValue().leadCycles()))
              .map(Map.Entry::getKey)
              .orElse(null);
      int previousCycles = previousLeader == null ? 0 : scores.get(previousLeader).leadCycles();
      SettlementId currentOwner =
          ownership.containsKey(position) ? ownership.get(position).owner() : null;
      InfluenceEngine.Resolution resolution =
          engine.resolve(
              values,
              currentOwner,
              previousLeader,
              previousCycles,
              contestedThreshold,
              requiredLeadCycles);
      resolutions.put(position, resolution);
      if (resolution.leader() != null && cityScores.containsKey(position)) {
        InfluencePersistence.Score original = cityScores.get(position);
        cityScores.put(
            position,
            new InfluencePersistence.Score(
                original.value(),
                resolution.leader().equals(city) ? resolution.consecutiveLeadCycles() : 0));
      }
    }
    Map<ChunkPos, ChunkOwnershipCache.Entry> committed =
        persistence.commit(city, Map.copyOf(cityScores), Map.copyOf(resolutions), now);
    cache.apply(committed);
    return committed.size();
  }

  public record CycleReport(int settlements, int changedChunks) {}
}
