package nl.frontier.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CommandRateLimiterTest {
  @Test
  void boundsCommandsAndRefillsAfterWindow() {
    CommandRateLimiter limiter = new CommandRateLimiter(2, Duration.ofSeconds(1));
    UUID player = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    assertTrue(limiter.allow(player, now));
    assertTrue(limiter.allow(player, now));
    assertFalse(limiter.allow(player, now));
    assertTrue(limiter.allow(player, now.plusSeconds(1)));
  }
}
