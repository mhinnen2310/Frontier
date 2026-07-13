package nl.frontier.domain;

import java.util.Objects;
import java.util.UUID;

/** Strong identifiers prevent accidental cross-aggregate UUID use. */
public final class Ids {
  private Ids() {}

  public record SettlementId(UUID value) {
    public SettlementId {
      Objects.requireNonNull(value);
    }
  }

  public record PlayerId(UUID value) {
    public PlayerId {
      Objects.requireNonNull(value);
    }
  }

  public record WorldId(UUID value) {
    public WorldId {
      Objects.requireNonNull(value);
    }
  }

  public record WarId(UUID value) {
    public WarId {
      Objects.requireNonNull(value);
    }
  }

  public record RepairOrderId(UUID value) {
    public RepairOrderId {
      Objects.requireNonNull(value);
    }
  }

  public record WorkerId(UUID value) {
    public WorkerId {
      Objects.requireNonNull(value);
    }
  }

  public record BuildingId(UUID value) {
    public BuildingId {
      Objects.requireNonNull(value);
    }
  }

  public record WarehouseId(UUID value) {
    public WarehouseId {
      Objects.requireNonNull(value);
    }
  }

  public record KingdomId(UUID value) {
    public KingdomId {
      Objects.requireNonNull(value);
    }
  }
}
