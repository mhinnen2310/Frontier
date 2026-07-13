package nl.frontier.bootstrap;

public record FrontierConfiguration(
    Global global,
    Settlements settlements,
    Control districts,
    Control buildings,
    Influence influence,
    Economy economy,
    Control infrastructure,
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
      long protectionCacheRefreshSeconds) {}

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
      long harborRefreshSeconds) {}

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
      Control control, long materializationCycleSeconds, int maximumVisibleNpcsPerSettlement) {}

  public record Kingdoms(
      Control control, long civilizationCycleSeconds, int maximumKingdomsPerCycle) {}

  public record Web(Control control, String bindAddress, int port, String publicUrl) {}

  public record History(Control control, long outboxCycleSeconds, int maximumEventsPerCycle) {}

  public record WorldSimulation(Control control, long cycleSeconds, int maximumCitiesPerCycle) {}

  public boolean enabled(String module) {
    return switch (module) {
      case "settlements" -> settlements.control().enabled();
      case "districts" -> districts.enabled();
      case "buildings" -> buildings.enabled();
      case "influence" -> influence.control().enabled();
      case "economy" -> economy.control().enabled();
      case "infrastructure" -> infrastructure.enabled();
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
