package nl.frontier.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AdminDiagnostics {
  Snapshot snapshot();

  List<String> inspect(String aggregateType, UUID aggregateId);

  List<String> audit(int limit);

  record Snapshot(Map<String, Long> counts, long oldestOutboxLagSeconds) {}
}
