package nl.frontier.city;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SettlementSimulationGateway {
  List<Snapshot> leaseDue(UUID worker, int limit, Instant now, Instant leaseUntil);

  void apply(
      UUID worker,
      Snapshot snapshot,
      SettlementDailySimulation.Result result,
      UUID cycleKey,
      Instant nextCycle,
      Instant now);

  void release(UUID worker, UUID city);

  record Snapshot(
      UUID city,
      SettlementLevel level,
      int population,
      int prosperity,
      int civilization,
      long treasuryMinor,
      int buildings,
      int workers,
      int roadSegments,
      long workerWagesMinor,
      long wheatAvailable,
      long breadAvailable,
      String taxProfile,
      int housingBonus,
      int maintenanceBonus,
      int maintenancePenalty,
      int wagePenalty,
      long version) {}
}
