package nl.frontier.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AdminDiagnostics {
  Snapshot snapshot();

  List<String> inspect(String aggregateType, UUID aggregateId);

  List<String> audit(int limit);

  List<String> viewer(String viewType, UUID aggregateId);

  List<String> heatmap(UUID world, int centerChunkX, int centerChunkZ, int radius);

  List<String> chunkOwnership(UUID world, int chunkX, int chunkZ);

  Map<String, Long> liveMetrics();

  record Snapshot(Map<String, Long> counts, long oldestOutboxLagSeconds) {}
}
