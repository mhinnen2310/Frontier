package nl.frontier.warfare;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import nl.frontier.domain.DomainException;

public final class StructuralDamagePolicy {
  public double defenderMultiplier(int eligibleActiveDefenders) {
    return switch (Math.max(0, eligibleActiveDefenders)) {
      case 0 -> 0.10;
      case 1 -> 0.25;
      case 2 -> 0.45;
      case 3 -> 0.65;
      case 4 -> 0.80;
      default -> 1.00;
    };
  }

  public int effectiveDamage(
      int baseDamage, int activeDefenders, double objective, double material) {
    if (baseDamage < 0 || objective < 0 || material < 0)
      throw new IllegalArgumentException("damage inputs cannot be negative");
    return (int)
        Math.floor(baseDamage * defenderMultiplier(activeDefenders) * objective * material);
  }

  public static final class RollingBreachBudget {
    private final int basePoints;
    private final int maximumPoints;
    private final Duration window;
    private final Deque<Spend> spends = new ArrayDeque<>();

    public RollingBreachBudget(int basePoints, int maximumPoints, Duration window) {
      if (basePoints < 0 || maximumPoints < basePoints || window.isZero() || window.isNegative()) {
        throw new IllegalArgumentException("invalid breach budget");
      }
      this.basePoints = basePoints;
      this.maximumPoints = maximumPoints;
      this.window = window;
    }

    public synchronized void spend(int cost, int bonus, Instant now) {
      if (cost <= 0) throw new IllegalArgumentException("cost must be positive");
      prune(now);
      int capacity = Math.min(maximumPoints, Math.addExact(basePoints, Math.max(0, bonus)));
      int used = spends.stream().mapToInt(Spend::points).sum();
      if (cost > capacity - used) throw new DomainException("breach capacity exhausted");
      spends.addLast(new Spend(cost, now));
    }

    public synchronized int remaining(int bonus, Instant now) {
      prune(now);
      int capacity = Math.min(maximumPoints, Math.addExact(basePoints, Math.max(0, bonus)));
      return capacity - spends.stream().mapToInt(Spend::points).sum();
    }

    private void prune(Instant now) {
      Instant cutoff = now.minus(window);
      while (!spends.isEmpty() && spends.getFirst().timestamp().isBefore(cutoff))
        spends.removeFirst();
    }

    private record Spend(int points, Instant timestamp) {}
  }
}
