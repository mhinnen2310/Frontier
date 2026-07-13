package nl.frontier.city;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ClaimProtectionGateway {
  Snapshot load(Instant now);

  record Snapshot(
      Map<ClaimKey, UUID> claims,
      Map<UUID, UUID> owners,
      List<Member> members,
      List<Override> overrides,
      Instant loadedAt) {}

  record ClaimKey(UUID world, int chunkX, int chunkZ) {}

  record Member(UUID city, UUID player, GovernmentRole role) {}

  record Override(UUID city, UUID player, ClaimProtectionService.Action action, boolean allowed) {}
}
