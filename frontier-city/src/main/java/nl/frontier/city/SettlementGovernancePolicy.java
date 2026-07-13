package nl.frontier.city;

import java.time.Duration;

public record SettlementGovernancePolicy(
    Duration mayorInactivity,
    Duration settlementInactivity,
    Duration disbandConfirmationDelay,
    Duration disbandRequestLifetime) {
  public SettlementGovernancePolicy {
    if (mayorInactivity.isNegative()
        || mayorInactivity.isZero()
        || settlementInactivity.compareTo(mayorInactivity) < 0
        || disbandConfirmationDelay.isNegative()
        || disbandRequestLifetime.compareTo(disbandConfirmationDelay) <= 0) {
      throw new IllegalArgumentException("invalid settlement governance policy");
    }
  }

  public static SettlementGovernancePolicy defaults() {
    return new SettlementGovernancePolicy(
        Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(30), Duration.ofMinutes(10));
  }
}
