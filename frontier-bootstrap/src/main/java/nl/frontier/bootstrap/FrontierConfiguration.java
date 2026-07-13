package nl.frontier.bootstrap;

import java.util.Set;
import nl.frontier.city.BuildingValidationPolicy;
import nl.frontier.city.DistrictBalancePolicy;
import nl.frontier.economy.HarborPolicy;
import nl.frontier.economy.InfrastructureValidationPolicy;

public record FrontierConfiguration(
    Global global,
    Settlements settlements,
    Districts districts,
    Buildings buildings,
    Influence influence,
    Economy economy,
    Infrastructure infrastructure,
    Control caravans,
    Warfare warfare,
    Repairs repairs,
    Population population,
    Kingdoms kingdoms,
    Control waypoints,
    Control cartography,
    Control mapWalls,
    Web web,
    History history,
    WorldSimulation worldSimulation) {

  public record Control(int configVersion, boolean enabled) {}

  public record Global(int configVersion, Database database, Runtime runtime, Security security) {}

  public record Database(String jdbcUrl, String username, String password, int maximumPoolSize) {}

  public record Runtime(int asyncThreads) {}

  public record Security(int commandRateLimit, long commandRateWindowSeconds) {}

  public record Settlements(
      Control control,
      long simulationCheckSeconds,
      int maximumPerCycle,
      long protectionCacheRefreshSeconds,
      long foundingFeeMinor,
      int minimumFounders,
      long expeditionLifetimeHours,
      long reservationLifetimeMinutes,
      int minimumCoreDistance,
      int harborExclusionRadius,
      int stoneBricksRequired,
      int oakLogsRequired,
      int bellsRequired,
      Set<String> allowedEnvironments,
      long mayorInactivityDays,
      long settlementInactivityDays,
      long disbandConfirmationSeconds,
      long disbandRequestMinutes) {
    public Settlements {
      allowedEnvironments = Set.copyOf(allowedEnvironments);
    }
  }

  public record Districts(Control control, DistrictBalancePolicy balance) {}

  public record Buildings(
      Control control,
      BuildingValidationPolicy validation,
      long selectionTimeoutSeconds,
      long transferProposalHours) {}

  public record Influence(
      Control control,
      long cycleSeconds,
      int maximumSettlementsPerCycle,
      int contestedThreshold,
      int requiredLeadCycles) {}

  public record Economy(
      Control control,
      long marketCycleSeconds,
      int maximumTradesPerCycle,
      long productionCycleSeconds,
      int maximumProductionOrdersPerCycle,
      long logisticsCycleSeconds,
      int maximumShipmentsPerCycle,
      long harborRefreshSeconds,
      HarborPolicy harborPolicy) {}

  public record Infrastructure(
      Control control,
      InfrastructureValidationPolicy validation,
      long dirtyCycleSeconds,
      int maximumDirtyPerCycle,
      int maximumDirtyQueue,
      long healthCycleSeconds,
      long healthLeaseSeconds,
      int maximumHealthPerCycle) {}

  public record Warfare(
      Control control,
      long preparationHours,
      int maximumDurationDays,
      long breachWindowHours,
      int breachBasePoints,
      int breachMaximumPoints,
      long declarationCostMinor,
      long lifecycleCycleSeconds,
      int maximumTransitionsPerCycle,
      long objectiveCycleSeconds) {}

  public record Repairs(
      Control control,
      long cycleSeconds,
      long taskLeaseSeconds,
      int maximumTasksPerCycle,
      long archiveAfterHours,
      double unsafeRadius,
      long damageRecoveryCycleSeconds,
      int maximumDamageRecoveryPerCycle) {}

  public record Population(
      Control control,
      long materializationCycleSeconds,
      int maximumVisibleNpcsPerSettlement,
      int maximumActivitiesPerCycle,
      long activityLeaseSeconds,
      int maximumPathSteps,
      long pathStepMillis) {}

  public record Kingdoms(
      Control control, long civilizationCycleSeconds, int maximumKingdomsPerCycle) {}

  public record Web(Control control, String bindAddress, int port, String publicUrl) {}

  public record History(Control control, long outboxCycleSeconds, int maximumEventsPerCycle) {}

  public record WorldSimulation(Control control, long cycleSeconds, int maximumCitiesPerCycle) {}

  public boolean enabled(String module) {
    return switch (module) {
      case "settlements" -> settlements.control().enabled();
      case "districts" -> districts.control().enabled();
      case "buildings" -> buildings.control().enabled();
      case "influence" -> influence.control().enabled();
      case "economy" -> economy.control().enabled();
      case "infrastructure" -> infrastructure.control().enabled();
      case "caravans" -> caravans.enabled();
      case "warfare" -> warfare.control().enabled();
      case "repairs" -> repairs.control().enabled();
      case "population" -> population.control().enabled();
      case "kingdoms" -> kingdoms.control().enabled();
      case "waypoints" -> waypoints.enabled();
      case "cartography" -> cartography.enabled();
      case "map-walls" -> mapWalls.enabled();
      case "web" -> web.control().enabled();
      case "history" -> history.control().enabled();
      case "world-simulation" -> worldSimulation.control().enabled();
      default -> throw new IllegalArgumentException("unknown module: " + module);
    };
  }
}
