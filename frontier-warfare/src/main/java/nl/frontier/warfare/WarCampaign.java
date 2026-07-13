package nl.frontier.warfare;

import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Ids.WarId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import nl.frontier.domain.DomainException;

public final class WarCampaign {
  public enum Phase {
    DECLARED,
    PREPARATION,
    ACTIVE,
    CEASEFIRE,
    RESOLUTION,
    ENDED
  }

  public enum Type {
    RESOURCE,
    BORDER,
    REPARATIONS,
    LIBERATION,
    CONQUEST
  }

  private final WarId id;
  private final SettlementId attacker;
  private final SettlementId defender;
  private final Type type;
  private final Instant declaredAt;
  private final Duration preparation;
  private final Duration maximumDuration;
  private Phase phase = Phase.DECLARED;
  private Instant activeAt;
  private Instant endedAt;
  private long version;

  public WarCampaign(
      WarId id,
      SettlementId attacker,
      SettlementId defender,
      Type type,
      Instant declaredAt,
      Duration preparation,
      Duration maximumDuration) {
    this.id = Objects.requireNonNull(id);
    this.attacker = Objects.requireNonNull(attacker);
    this.defender = Objects.requireNonNull(defender);
    this.type = Objects.requireNonNull(type);
    this.declaredAt = Objects.requireNonNull(declaredAt);
    this.preparation = Objects.requireNonNull(preparation);
    this.maximumDuration = Objects.requireNonNull(maximumDuration);
    if (attacker.equals(defender))
      throw new IllegalArgumentException("a settlement cannot declare war on itself");
    if (preparation.isNegative()
        || preparation.isZero()
        || maximumDuration.isNegative()
        || maximumDuration.isZero()) {
      throw new IllegalArgumentException("campaign durations must be positive");
    }
  }

  public void beginPreparation() {
    transition(Phase.DECLARED, Phase.PREPARATION);
  }

  public void activate(Instant now, boolean baselineFinalized) {
    if (!baselineFinalized) throw new DomainException("damage baseline must be finalized");
    if (phase != Phase.PREPARATION || now.isBefore(declaredAt.plus(preparation)))
      throw new DomainException("preparation is not complete");
    phase = Phase.ACTIVE;
    activeAt = now;
    version++;
  }

  public void ceasefire() {
    transition(Phase.ACTIVE, Phase.CEASEFIRE);
  }

  public void resume() {
    transition(Phase.CEASEFIRE, Phase.ACTIVE);
  }

  public void resolve(Instant now) {
    if (phase != Phase.ACTIVE && phase != Phase.CEASEFIRE)
      throw new DomainException("campaign cannot resolve from " + phase);
    if (activeAt != null && now.isAfter(activeAt.plus(maximumDuration))) {
      phase = Phase.RESOLUTION;
    } else {
      phase = Phase.RESOLUTION;
    }
    version++;
  }

  public void end(Instant now) {
    transition(Phase.RESOLUTION, Phase.ENDED);
    endedAt = now;
  }

  private void transition(Phase expected, Phase next) {
    if (phase != expected) throw new DomainException("expected " + expected + " but was " + phase);
    phase = next;
    version++;
  }

  public WarId id() {
    return id;
  }

  public SettlementId attacker() {
    return attacker;
  }

  public SettlementId defender() {
    return defender;
  }

  public Type type() {
    return type;
  }

  public Phase phase() {
    return phase;
  }

  public Instant activeAt() {
    return activeAt;
  }

  public Instant endedAt() {
    return endedAt;
  }

  public long version() {
    return version;
  }
}
