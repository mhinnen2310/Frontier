package nl.frontier.economy;

import java.time.Instant;
import java.util.UUID;

public interface InfrastructureGateway {
  Context context(UUID city, UUID actor, UUID from, UUID to);

  Edge register(
      UUID city,
      UUID actor,
      UUID from,
      UUID to,
      int importance,
      InfrastructureValidator.Validation validation,
      Instant now);

  record Point(UUID world, int x, int y, int z) {}

  record Context(UUID city, Point from, Point to) {}

  record Edge(
      UUID id,
      UUID from,
      UUID to,
      InfrastructureType type,
      int health,
      long capacity,
      long traffic,
      int importance,
      UUID owner) {}
}
