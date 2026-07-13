package nl.frontier.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ActionTokenRegistryTest {
  @Test
  void tokenIsBoundExpiresAndCanOnlyBeConsumedOnce() {
    ActionTokenRegistry registry = new ActionTokenRegistry();
    UUID player = UUID.randomUUID();
    UUID aggregate = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    UUID token = registry.issue(player, "claim", aggregate, Duration.ofSeconds(5), now);
    assertFalse(registry.consume(token, UUID.randomUUID(), "claim", aggregate, now));

    token = registry.issue(player, "claim", aggregate, Duration.ofSeconds(5), now);
    assertTrue(registry.consume(token, player, "claim", aggregate, now));
    assertFalse(registry.consume(token, player, "claim", aggregate, now));

    token = registry.issue(player, "claim", aggregate, Duration.ofSeconds(5), now);
    assertFalse(registry.consume(token, player, "claim", aggregate, now.plusSeconds(6)));
  }
}
