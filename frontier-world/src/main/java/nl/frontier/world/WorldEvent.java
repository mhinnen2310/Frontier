package nl.frontier.world;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class WorldEvent {
  public enum Category {
    ECONOMIC,
    MILITARY,
    NATURAL,
    SOCIAL,
    EXPLORATION,
    LEGENDARY
  }

  public enum State {
    ELIGIBLE,
    SCHEDULED,
    ANNOUNCED,
    ACTIVE,
    RESOLVED,
    COOLDOWN,
    ARCHIVED
  }

  private final UUID id;
  private final Category category;
  private final Map<String, Double> triggerThresholds;
  private State state = State.ELIGIBLE;
  private Instant stateAt;

  public WorldEvent(
      UUID id, Category category, Map<String, Double> triggerThresholds, Instant now) {
    this.id = Objects.requireNonNull(id);
    this.category = Objects.requireNonNull(category);
    this.triggerThresholds = Map.copyOf(triggerThresholds);
    stateAt = Objects.requireNonNull(now);
  }

  public boolean eligible(Map<String, Double> regionalMetrics) {
    return triggerThresholds.entrySet().stream()
        .allMatch(entry -> regionalMetrics.getOrDefault(entry.getKey(), 0.0) >= entry.getValue());
  }

  public void advance(State target, Instant now) {
    if (target.ordinal() != state.ordinal() + 1)
      throw new DomainException("invalid event transition");
    state = target;
    stateAt = now;
  }

  public UUID id() {
    return id;
  }

  public Category category() {
    return category;
  }

  public State state() {
    return state;
  }

  public Instant stateAt() {
    return stateAt;
  }
}
