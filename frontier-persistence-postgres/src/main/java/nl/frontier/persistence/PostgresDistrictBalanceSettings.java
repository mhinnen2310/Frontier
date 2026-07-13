package nl.frontier.persistence;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import nl.frontier.api.TransactionalStore;
import nl.frontier.city.DistrictBalancePolicy;

/** Synchronizes validated server configuration into the singleton consumed by district_effects. */
public final class PostgresDistrictBalanceSettings {
  private final TransactionalStore store;

  public PostgresDistrictBalanceSettings(TransactionalStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public void apply(DistrictBalancePolicy policy, Instant now) {
    Objects.requireNonNull(policy);
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE district_balance_settings SET minimum_building_integrity=?,minimum_infrastructure_integrity=?,diminishing_return_percent=?,maximum_building_contributions=?,adjacency_bonus_percent=?,maximum_adjacency_bonuses=?,adjacency_distance_blocks=?,over_specialization_threshold=?,over_specialization_penalty_percent=?,maximum_effective_bonus_percent=?,industrial_maintenance_penalty_percent=?,military_wage_penalty_percent=?,commercial_market_orders_per_building=?,logistics_warehouse_capacity_percent=?,updated_at=? WHERE singleton=TRUE")) {
            statement.setInt(1, policy.minimumBuildingIntegrity());
            statement.setInt(2, policy.minimumInfrastructureIntegrity());
            statement.setInt(3, policy.diminishingReturnPercent());
            statement.setInt(4, policy.maximumBuildingContributions());
            statement.setInt(5, policy.adjacencyBonusPercent());
            statement.setInt(6, policy.maximumAdjacencyBonuses());
            statement.setInt(7, policy.adjacencyDistanceBlocks());
            statement.setInt(8, policy.overSpecializationThreshold());
            statement.setInt(9, policy.overSpecializationPenaltyPercent());
            statement.setInt(10, policy.maximumEffectiveBonusPercent());
            statement.setInt(11, policy.industrialMaintenancePenaltyPercent());
            statement.setInt(12, policy.militaryWagePenaltyPercent());
            statement.setInt(13, policy.commercialMarketOrdersPerBuilding());
            statement.setInt(14, policy.logisticsWarehouseCapacityPercent());
            statement.setTimestamp(15, Timestamp.from(now));
            if (statement.executeUpdate() != 1)
              throw new IllegalStateException("district balance settings row is missing");
          }
          return null;
        });
  }
}
