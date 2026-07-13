package nl.frontier.city;

/** Server-wide, bounded tuning for database-authoritative district specialization. */
public record DistrictBalancePolicy(
    int minimumBuildingIntegrity,
    int minimumInfrastructureIntegrity,
    int diminishingReturnPercent,
    int maximumBuildingContributions,
    int adjacencyBonusPercent,
    int maximumAdjacencyBonuses,
    int adjacencyDistanceBlocks,
    int overSpecializationThreshold,
    int overSpecializationPenaltyPercent,
    int maximumEffectiveBonusPercent,
    int industrialMaintenancePenaltyPercent,
    int militaryWagePenaltyPercent,
    int commercialMarketOrdersPerBuilding,
    int logisticsWarehouseCapacityPercent) {}
