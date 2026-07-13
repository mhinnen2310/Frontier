package nl.frontier.city;

import java.util.Set;

/** Pure specialization rules mirrored by the database projection used by gameplay consumers. */
public final class DistrictSpecializationPolicy {
  public Result evaluate(
      DistrictType type,
      DistrictBalancePolicy policy,
      int validBuildings,
      int infrastructureNodes,
      int compatibleAdjacencies,
      int sameTypeDistricts) {
    int factor =
        validBuildings > 0 && infrastructureNodes > 0
            ? Math.max(
                0,
                100
                    + Math.min(
                            policy.maximumBuildingContributions() - 1,
                            Math.max(validBuildings - 1, 0))
                        * policy.diminishingReturnPercent()
                    + Math.min(policy.maximumAdjacencyBonuses(), compatibleAdjacencies)
                        * policy.adjacencyBonusPercent()
                    - Math.max(sameTypeDistricts - policy.overSpecializationThreshold(), 0)
                        * policy.overSpecializationPenaltyPercent())
            : 0;
    DistrictType.Bonuses base = type.bonuses();
    DistrictType.Bonuses effective =
        new DistrictType.Bonuses(
            scaled(base.production(), factor, policy),
            scaled(base.housing(), factor, policy),
            scaled(base.maintenance(), factor, policy),
            scaled(base.defense(), factor, policy),
            scaled(base.trade(), factor, policy),
            scaled(base.workerEfficiency(), factor, policy),
            scaled(base.repairPriority(), factor, policy));
    boolean active = factor > 0;
    return new Result(
        active,
        factor,
        effective,
        active && type == DistrictType.INDUSTRIAL
            ? policy.industrialMaintenancePenaltyPercent()
            : 0,
        active && type == DistrictType.MILITARY ? policy.militaryWagePenaltyPercent() : 0,
        active && type == DistrictType.COMMERCIAL
            ? validBuildings * policy.commercialMarketOrdersPerBuilding()
            : 0,
        active && type == DistrictType.LOGISTICS ? policy.logisticsWarehouseCapacityPercent() : 0);
  }

  public boolean adjacencyCompatible(DistrictType district, DistrictType neighbor) {
    return switch (district) {
      case RESIDENTIAL -> Set.of(DistrictType.COMMERCIAL, DistrictType.CULTURE).contains(neighbor);
      case AGRICULTURAL ->
          Set.of(DistrictType.COMMERCIAL, DistrictType.LOGISTICS).contains(neighbor);
      case INDUSTRIAL ->
          Set.of(
                  DistrictType.COMMERCIAL,
                  DistrictType.LOGISTICS,
                  DistrictType.MINING,
                  DistrictType.FORESTRY,
                  DistrictType.RESEARCH)
              .contains(neighbor);
      case COMMERCIAL ->
          Set.of(
                  DistrictType.RESIDENTIAL,
                  DistrictType.AGRICULTURAL,
                  DistrictType.INDUSTRIAL,
                  DistrictType.LOGISTICS,
                  DistrictType.HARBOR,
                  DistrictType.CULTURE)
              .contains(neighbor);
      case MILITARY -> Set.of(DistrictType.RESIDENTIAL, DistrictType.GOVERNMENT).contains(neighbor);
      case GOVERNMENT ->
          Set.of(DistrictType.MILITARY, DistrictType.RESEARCH, DistrictType.CULTURE)
              .contains(neighbor);
      case LOGISTICS ->
          Set.of(
                  DistrictType.AGRICULTURAL,
                  DistrictType.INDUSTRIAL,
                  DistrictType.COMMERCIAL,
                  DistrictType.HARBOR,
                  DistrictType.MINING,
                  DistrictType.FORESTRY)
              .contains(neighbor);
      case HARBOR -> Set.of(DistrictType.COMMERCIAL, DistrictType.LOGISTICS).contains(neighbor);
      case MINING, FORESTRY ->
          Set.of(DistrictType.INDUSTRIAL, DistrictType.LOGISTICS).contains(neighbor);
      case RESEARCH -> Set.of(DistrictType.INDUSTRIAL, DistrictType.GOVERNMENT).contains(neighbor);
      case CULTURE ->
          Set.of(DistrictType.RESIDENTIAL, DistrictType.COMMERCIAL, DistrictType.GOVERNMENT)
              .contains(neighbor);
    };
  }

  private static int scaled(int base, int factor, DistrictBalancePolicy policy) {
    return Math.min(policy.maximumEffectiveBonusPercent(), base * factor / 100);
  }

  public record Result(
      boolean active,
      int effectiveFactorPercent,
      DistrictType.Bonuses bonuses,
      int maintenancePenaltyPercent,
      int wagePenaltyPercent,
      int marketOrderCapacityBonus,
      int warehouseCapacityBonusPercent) {}
}
