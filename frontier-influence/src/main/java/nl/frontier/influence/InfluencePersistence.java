package nl.frontier.influence;

import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Position.ChunkPos;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface InfluencePersistence {
  List<Dirty> leaseDirty(UUID worker, int limit, Instant now, Instant leaseUntil);

  Context load(SettlementId city);

  Map<ChunkPos, Map<SettlementId, Score>> scores(Set<ChunkPos> positions);

  Map<ChunkPos, ChunkOwnershipCache.Entry> ownership(Set<ChunkPos> positions);

  Map<ChunkPos, ChunkOwnershipCache.Entry> allOwnership();

  Map<ChunkPos, ChunkOwnershipCache.Entry> commit(
      SettlementId city,
      Map<ChunkPos, Score> exactScores,
      Map<ChunkPos, InfluenceEngine.Resolution> resolutions,
      Instant now);

  void release(SettlementId city, UUID worker);

  record Dirty(SettlementId city, String reason) {}

  record Context(
      SettlementId city,
      ChunkPos core,
      int maximumClaims,
      InfluenceEngine.Factors factors,
      Set<ChunkPos> blocked,
      Set<ChunkPos> roadConnected,
      Set<ChunkPos> previousScored) {}

  record Score(int value, int leadCycles) {}
}
