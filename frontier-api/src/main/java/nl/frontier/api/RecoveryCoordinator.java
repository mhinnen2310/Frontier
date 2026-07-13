package nl.frontier.api;

public interface RecoveryCoordinator {
  RecoveryReport recover();

  record RecoveryReport(int outboxEvents, int leases, int consumptions, int transfers) {}
}
