package nl.frontier.world;

import static nl.frontier.domain.Ids.KingdomId;
import static nl.frontier.domain.Ids.SettlementId;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class CivilizationProgression {
  public enum Relation {
    ALLIED,
    FRIENDLY,
    NEUTRAL,
    SUSPICIOUS,
    RIVAL,
    AT_WAR
  }

  public enum Era {
    FRONTIER,
    EXPANSION,
    INDUSTRIAL,
    KINGDOM,
    GOLDEN_AGE
  }

  public static final class Kingdom {
    private final KingdomId id;
    private final Set<SettlementId> settlements = new HashSet<>();
    private long prestige;
    private Era era = Era.FRONTIER;

    public Kingdom(KingdomId id, SettlementId founder) {
      this.id = Objects.requireNonNull(id);
      settlements.add(founder);
    }

    public void addSettlement(SettlementId settlement) {
      settlements.add(settlement);
    }

    public void addPrestige(long amount) {
      if (amount <= 0) throw new IllegalArgumentException("prestige must be positive");
      prestige = Math.addExact(prestige, amount);
    }

    public void advance(Era target) {
      if (target.ordinal() != era.ordinal() + 1)
        throw new DomainException("era must advance one step");
      era = target;
    }

    public KingdomId id() {
      return id;
    }

    public Set<SettlementId> settlements() {
      return Set.copyOf(settlements);
    }

    public long prestige() {
      return prestige;
    }

    public Era era() {
      return era;
    }
  }

  public record Wonder(
      UUID id, String key, KingdomId owner, long requiredUnits, long contributedUnits) {
    public Wonder {
      Objects.requireNonNull(id);
      Objects.requireNonNull(key);
      Objects.requireNonNull(owner);
      if (key.isBlank()
          || requiredUnits <= 0
          || contributedUnits < 0
          || contributedUnits > requiredUnits) throw new IllegalArgumentException("invalid wonder");
    }

    public Wonder contribute(long units) {
      if (units <= 0 || units > requiredUnits - contributedUnits)
        throw new DomainException("invalid wonder contribution");
      return new Wonder(id, key, owner, requiredUnits, Math.addExact(contributedUnits, units));
    }

    public boolean complete() {
      return contributedUnits == requiredUnits;
    }
  }
}
