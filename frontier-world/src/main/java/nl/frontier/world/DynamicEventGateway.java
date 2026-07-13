package nl.frontier.world;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DynamicEventGateway {
  CycleReport detect(int maximum, Instant now);

  List<EventSummary> available(UUID player, Instant now);

  EventSummary join(UUID event, UUID player, String role, Instant now);

  EventSummary respond(UUID event, UUID player, long contribution, Instant now);

  record EventSummary(
      UUID id,
      String key,
      String state,
      UUID city,
      UUID kingdom,
      UUID shipment,
      UUID roadEdge,
      long progress,
      long target,
      boolean joined,
      Instant expiresAt) {}

  record CycleReport(int detected, int skippedCooldown) {}
}
