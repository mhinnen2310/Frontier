package nl.frontier.economy;

public final class ProductionAndLogistics {
  private ProductionAndLogistics() {}

  public enum ProductionStatus {
    DRAFT,
    QUEUED,
    RESERVING,
    ACTIVE,
    PAUSED_NO_INPUT,
    PAUSED_NO_WORKERS,
    PAUSED_UNSAFE,
    COMPLETED,
    CANCELLED,
    FAILED
  }

  public enum CaravanStatus {
    ASSEMBLING,
    DEPARTING,
    TRAVELING,
    WAITING_ROUTE,
    UNDER_ATTACK,
    REROUTING,
    ARRIVED,
    LOST,
    CANCELLED
  }

  public enum ContractStatus {
    DRAFT,
    POSTED,
    ACCEPTED,
    IN_PROGRESS,
    DELIVERED,
    VERIFIED,
    PAID,
    FAILED,
    EXPIRED,
    DISPUTED
  }
}
