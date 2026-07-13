package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CommandRateLimiter {
  private final int capacity;
  private final Duration window;
  private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

  CommandRateLimiter(int capacity, Duration window) {
    if (capacity < 1 || window.isNegative() || window.isZero())
      throw new IllegalArgumentException("invalid rate limit");
    this.capacity = capacity;
    this.window = window;
  }

  boolean allow(UUID player, Instant now) {
    return buckets.compute(
            player,
            (ignored, current) -> {
              if (current == null || !now.isBefore(current.started.plus(window)))
                return new Bucket(now, 1, true);
              if (current.count >= capacity)
                return new Bucket(current.started, current.count, false);
              return new Bucket(current.started, current.count + 1, true);
            })
        .allowed;
  }

  private record Bucket(Instant started, int count, boolean allowed) {}
}
