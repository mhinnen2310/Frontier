package nl.frontier.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ActionTokenRegistry {
  private final Map<UUID, Token> tokens = new ConcurrentHashMap<>();

  UUID issue(UUID player, String action, UUID aggregate, Duration lifetime, Instant now) {
    purge(now);
    UUID id = UUID.randomUUID();
    tokens.put(id, new Token(player, action, aggregate, now.plus(lifetime)));
    return id;
  }

  boolean consume(UUID id, UUID player, String action, UUID aggregate, Instant now) {
    Token token = tokens.remove(id);
    return token != null
        && token.player.equals(player)
        && token.action.equals(action)
        && token.aggregate.equals(aggregate)
        && !now.isAfter(token.expiresAt);
  }

  private void purge(Instant now) {
    tokens.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt));
  }

  private record Token(UUID player, String action, UUID aggregate, Instant expiresAt) {}
}
