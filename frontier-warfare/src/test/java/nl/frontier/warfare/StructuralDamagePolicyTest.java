package nl.frontier.warfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import nl.frontier.domain.DomainException;
import org.junit.jupiter.api.Test;

class StructuralDamagePolicyTest {
  @Test
  void offlineDamageIsLimitedButNeverAWindow() {
    StructuralDamagePolicy policy = new StructuralDamagePolicy();
    assertEquals(10, policy.effectiveDamage(100, 0, 1, 1));
    assertEquals(100, policy.effectiveDamage(100, 5, 1, 1));
  }

  @Test
  void breachPointsReturnAfterRollingWindow() {
    StructuralDamagePolicy.RollingBreachBudget budget =
        new StructuralDamagePolicy.RollingBreachBudget(100, 100, Duration.ofHours(6));
    Instant now = Instant.EPOCH;
    budget.spend(100, 0, now);
    assertThrows(DomainException.class, () -> budget.spend(1, 0, now));
    assertEquals(100, budget.remaining(0, now.plus(Duration.ofHours(7))));
  }
}
