package nl.frontier.city;

import static nl.frontier.domain.Ids.BuildingId;
import static nl.frontier.domain.Ids.SettlementId;

import java.util.Objects;

public final class Building {
  public enum Category {
    GOVERNMENT,
    RESIDENTIAL,
    ECONOMIC,
    INDUSTRY,
    AGRICULTURE,
    MILITARY,
    INFRASTRUCTURE,
    CULTURE
  }

  public enum Status {
    OPERATIONAL,
    DEGRADED,
    EMERGENCY,
    DISABLED
  }

  private final BuildingId id;
  private final SettlementId settlementId;
  private final Category category;
  private int integrity;
  private long version;

  public Building(BuildingId id, SettlementId settlementId, Category category) {
    this.id = Objects.requireNonNull(id);
    this.settlementId = Objects.requireNonNull(settlementId);
    this.category = Objects.requireNonNull(category);
    integrity = 100;
  }

  public void damage(int percentagePoints) {
    if (percentagePoints <= 0) throw new IllegalArgumentException("damage must be positive");
    integrity = Math.max(0, integrity - percentagePoints);
    version++;
  }

  public void repair(int percentagePoints) {
    if (percentagePoints <= 0) throw new IllegalArgumentException("repair must be positive");
    integrity = Math.min(100, Math.addExact(integrity, percentagePoints));
    version++;
  }

  public Status status() {
    if (integrity < 15) return Status.DISABLED;
    if (integrity < 40) return Status.EMERGENCY;
    if (integrity < 90) return Status.DEGRADED;
    return Status.OPERATIONAL;
  }

  public double efficiency() {
    return switch (status()) {
      case OPERATIONAL -> 1.0;
      case DEGRADED -> integrity >= 70 ? 0.85 : 0.55;
      case EMERGENCY -> 0.20;
      case DISABLED -> 0.0;
    };
  }

  public BuildingId id() {
    return id;
  }

  public SettlementId settlementId() {
    return settlementId;
  }

  public Category category() {
    return category;
  }

  public int integrity() {
    return integrity;
  }

  public long version() {
    return version;
  }
}
