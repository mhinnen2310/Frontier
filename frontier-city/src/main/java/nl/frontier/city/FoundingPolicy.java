package nl.frontier.city;

import java.time.Duration;

public record FoundingPolicy(
    long feeMinor,
    int minimumFounders,
    Duration expeditionLifetime,
    Duration reservationLifetime,
    int minimumCoreDistance,
    int harborExclusionRadius) {
  public FoundingPolicy {
    if (feeMinor < 0
        || minimumFounders < 1
        || expeditionLifetime.isNegative()
        || expeditionLifetime.isZero()
        || reservationLifetime.isNegative()
        || reservationLifetime.isZero()
        || minimumCoreDistance < 1
        || harborExclusionRadius < minimumCoreDistance) {
      throw new IllegalArgumentException("invalid settlement founding policy");
    }
  }

  public static FoundingPolicy defaults() {
    return new FoundingPolicy(2_500, 1, Duration.ofHours(24), Duration.ofMinutes(5), 128, 256);
  }
}
