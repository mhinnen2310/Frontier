package nl.frontier.bootstrap;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import nl.frontier.api.HealthStatus;
import nl.frontier.city.BuildingValidationService;
import nl.frontier.city.BuildingValidator;
import nl.frontier.city.ClaimProtectionCache;
import nl.frontier.city.ClaimProtectionGateway;
import nl.frontier.city.ClaimProtectionService;
import nl.frontier.city.DistrictApplicationService;
import nl.frontier.city.PopulationService;
import nl.frontier.city.SettlementApplicationService;
import nl.frontier.city.SettlementDailySimulation;
import nl.frontier.city.SettlementLifecycleService;
import nl.frontier.economy.CaravanService;
import nl.frontier.economy.CommercialService;
import nl.frontier.economy.ContractGateway;
import nl.frontier.economy.EconomyApplicationService;
import nl.frontier.economy.FinanceApplicationService;
import nl.frontier.economy.HarborApplicationService;
import nl.frontier.economy.HarborGateway;
import nl.frontier.economy.InfrastructureService;
import nl.frontier.economy.InfrastructureValidator;
import nl.frontier.economy.LogisticsGateway;
import nl.frontier.economy.ProductionApplicationService;
import nl.frontier.influence.ChunkOwnershipCache;
import nl.frontier.influence.InfluenceSimulationService;
import nl.frontier.observability.BuildInformationService;
import nl.frontier.observability.FrontierMetrics;
import nl.frontier.persistence.DatabaseManager;
import nl.frontier.persistence.JdbcTransactionalStore;
import nl.frontier.persistence.PostgresAdminDiagnostics;
import nl.frontier.persistence.PostgresBuildingValidationGateway;
import nl.frontier.persistence.PostgresCampaignGateway;
import nl.frontier.persistence.PostgresCampaignOutcomeGateway;
import nl.frontier.persistence.PostgresCaravanGateway;
import nl.frontier.persistence.PostgresCivilizationGateway;
import nl.frontier.persistence.PostgresClaimProtectionGateway;
import nl.frontier.persistence.PostgresCommercialGateway;
import nl.frontier.persistence.PostgresContractGateway;
import nl.frontier.persistence.PostgresDistrictGateway;
import nl.frontier.persistence.PostgresDynamicEventGateway;
import nl.frontier.persistence.PostgresEconomyGateway;
import nl.frontier.persistence.PostgresEndgameGateway;
import nl.frontier.persistence.PostgresFinanceGateway;
import nl.frontier.persistence.PostgresHarborGateway;
import nl.frontier.persistence.PostgresInfluencePersistence;
import nl.frontier.persistence.PostgresInfrastructureGateway;
import nl.frontier.persistence.PostgresKingdomIntegrationGateway;
import nl.frontier.persistence.PostgresLogisticsGateway;
import nl.frontier.persistence.PostgresNpcMaterializationGateway;
import nl.frontier.persistence.PostgresOutboxDispatcher;
import nl.frontier.persistence.PostgresPopulationGateway;
import nl.frontier.persistence.PostgresProductionGateway;
import nl.frontier.persistence.PostgresRecoveryCoordinator;
import nl.frontier.persistence.PostgresRepairGateway;
import nl.frontier.persistence.PostgresSettlementGateway;
import nl.frontier.persistence.PostgresSettlementLifecycleGateway;
import nl.frontier.persistence.PostgresSettlementSimulationGateway;
import nl.frontier.persistence.PostgresWarDamageGateway;
import nl.frontier.persistence.PostgresWorldSimulationGateway;
import nl.frontier.repair.RepairGateway;
import nl.frontier.ui.PaperFrontierUi;
import nl.frontier.warfare.CampaignGateway;
import nl.frontier.warfare.CampaignOutcomeService;
import nl.frontier.warfare.WarPolicyCache;
import nl.frontier.world.CivilizationGateway;
import nl.frontier.world.DynamicEventService;
import nl.frontier.world.EndgameService;
import nl.frontier.world.KingdomIntegrationService;
import nl.frontier.world.WorldSimulationGateway;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class FrontierPlugin extends JavaPlugin {
  private DatabaseManager database;
  private PaperSchedulerFacade schedulers;
  private PostgresRecoveryCoordinator recovery;
  private FrontierMetrics metrics;
  private InfluenceSupervisor influenceSupervisor;
  private SettlementSimulationSupervisor settlementSimulationSupervisor;
  private EconomySupervisor economySupervisor;
  private ProductionSupervisor productionSupervisor;
  private LogisticsSupervisor logisticsSupervisor;
  private NpcMaterializationSupervisor npcMaterializationSupervisor;
  private CampaignSupervisor campaignSupervisor;
  private ObjectiveSupervisor objectiveSupervisor;
  private RepairSupervisor repairSupervisor;
  private WorldSimulationSupervisor worldSimulationSupervisor;
  private CivilizationSupervisor civilizationSupervisor;
  private OutboxSupervisor outboxSupervisor;
  private HarborSupervisor harborSupervisor;
  private ClaimProtectionSupervisor claimProtectionSupervisor;
  private DamageRecoverySupervisor damageRecoverySupervisor;
  private SettlementLifecycleSupervisor settlementLifecycleSupervisor;
  private CaravanPresentationSupervisor caravanSupervisor;
  private PopulationSupervisor populationSupervisor;
  private CommercialSupervisor commercialSupervisor;
  private CampaignOutcomeSupervisor campaignOutcomeSupervisor;
  private KingdomIntegrationSupervisor kingdomIntegrationSupervisor;
  private DynamicEventSupervisor dynamicEventSupervisor;
  private volatile boolean acceptingWrites;
  private ConfigRegistry configRegistry;

  @Override
  public void onEnable() {
    try {
      validateRuntime();
      configRegistry = ConfigRegistry.load(this);
      FrontierConfiguration config = configRegistry.configuration();
      database =
          new DatabaseManager(
              new DatabaseManager.Configuration(
                  config.global().database().jdbcUrl(),
                  config.global().database().username(),
                  config.global().database().password(),
                  config.global().database().maximumPoolSize(),
                  Duration.ofSeconds(10)));
      database.migrate(materializeMigrations());
      JdbcTransactionalStore store = new JdbcTransactionalStore(database.dataSource());
      recovery = new PostgresRecoveryCoordinator(store);
      recovery.recover();
      schedulers = new PaperSchedulerFacade(this, config.global().runtime().asyncThreads());
      metrics = new FrontierMetrics(new SimpleMeterRegistry());
      PaperPresentationService presentation = new PaperPresentationService(getServer());
      PostgresAdminDiagnostics diagnostics = new PostgresAdminDiagnostics(store);
      ChunkOwnershipCache ownershipCache = new ChunkOwnershipCache();
      InfluenceSimulationService influence =
          new InfluenceSimulationService(
              new PostgresInfluencePersistence(store),
              ownershipCache,
              config.influence().contestedThreshold(),
              config.influence().requiredLeadCycles());
      PostgresEconomyGateway economyGateway = new PostgresEconomyGateway(store);
      FinanceApplicationService finance =
          new FinanceApplicationService(new PostgresFinanceGateway(store));
      HarborGateway harborGateway =
          new PostgresHarborGateway(store, config.economy().harborPolicy());
      HarborApplicationService harbor = new HarborApplicationService(harborGateway);
      World primaryWorld = getServer().getWorlds().getFirst();
      Location harborLocation = primaryWorld.getSpawnLocation();
      if (config.enabled("economy"))
        harborGateway.bootstrap(
            primaryWorld.getUID(),
            harborLocation.getBlockX() >> 4,
            harborLocation.getBlockZ() >> 4,
            Instant.now());
      PostgresProductionGateway productionGateway = new PostgresProductionGateway(store);
      LogisticsGateway logisticsGateway = new PostgresLogisticsGateway(store);
      ContractGateway contractGateway = new PostgresContractGateway(store);
      CaravanService caravans = new CaravanService(new PostgresCaravanGateway(store));
      PopulationService population = new PopulationService(new PostgresPopulationGateway(store));
      CommercialService commerce = new CommercialService(new PostgresCommercialGateway(store));
      CampaignGateway campaignGateway = new PostgresCampaignGateway(store);
      CampaignOutcomeService campaignOutcomes =
          new CampaignOutcomeService(new PostgresCampaignOutcomeGateway(store));
      WarPolicyCache warPolicyCache = new WarPolicyCache();
      warPolicyCache.replace(campaignGateway.policySnapshot(Instant.now()));
      ClaimProtectionGateway claimProtectionGateway = new PostgresClaimProtectionGateway(store);
      ClaimProtectionCache claimProtectionCache = new ClaimProtectionCache();
      claimProtectionCache.replace(claimProtectionGateway.load(Instant.now()));
      ClaimProtectionService claimProtection =
          new ClaimProtectionService(
              claimProtectionCache,
              (player, city) ->
                  config.enabled("warfare")
                      && warPolicyCache.hostileCampaign(player, city).isPresent());
      RepairGateway repairGateway = new PostgresRepairGateway(store);
      nl.frontier.warfare.WarDamageGateway warDamageGateway = new PostgresWarDamageGateway(store);
      WorldSimulationGateway worldSimulationGateway = new PostgresWorldSimulationGateway(store);
      CivilizationGateway civilizationGateway = new PostgresCivilizationGateway(store);
      KingdomIntegrationService kingdomIntegration =
          new KingdomIntegrationService(new PostgresKingdomIntegrationGateway(store));
      DynamicEventService dynamicEvents =
          new DynamicEventService(new PostgresDynamicEventGateway(store));
      EndgameService endgame = new EndgameService(new PostgresEndgameGateway(store));
      SettlementApplicationService settlements =
          new SettlementApplicationService(new PostgresSettlementGateway(store));
      SettlementLifecycleService settlementLifecycle =
          new SettlementLifecycleService(new PostgresSettlementLifecycleGateway(store));
      BuildingRegistrationCoordinator buildingRegistrations =
          new BuildingRegistrationCoordinator(
              schedulers,
              new PaperBuildingSurveyor(),
              new BuildingValidationService(
                  new PostgresBuildingValidationGateway(store), new BuildingValidator()));
      FrontierCommand handler =
          new FrontierCommand(
              this::health,
              recovery,
              diagnostics,
              new BuildInformationService(
                  BuildInformationService.load(getClassLoader()),
                  diagnostics::schemaVersion,
                  Map.ofEntries(
                      Map.entry("domain", "ACTIVE"),
                      Map.entry("api", "ACTIVE"),
                      Map.entry("city", moduleStatus(config, "settlements")),
                      Map.entry("districts", moduleStatus(config, "districts")),
                      Map.entry("buildings", moduleStatus(config, "buildings")),
                      Map.entry("influence", moduleStatus(config, "influence")),
                      Map.entry("economy", moduleStatus(config, "economy")),
                      Map.entry("infrastructure", moduleStatus(config, "infrastructure")),
                      Map.entry("caravans", moduleStatus(config, "caravans")),
                      Map.entry("warfare", moduleStatus(config, "warfare")),
                      Map.entry("repair", moduleStatus(config, "repairs")),
                      Map.entry("npc", moduleStatus(config, "population")),
                      Map.entry("world", moduleStatus(config, "world-simulation")),
                      Map.entry("kingdoms", moduleStatus(config, "kingdoms")),
                      Map.entry("ui-paper", "ACTIVE"),
                      Map.entry("persistence-postgres", "ACTIVE"),
                      Map.entry("observability", "ACTIVE"),
                      Map.entry("bootstrap", "ACTIVE"),
                      Map.entry("testkit", "BUILD_ONLY"),
                      Map.entry("waypoints", moduleStatus(config, "waypoints")),
                      Map.entry("cartography", moduleStatus(config, "cartography")),
                      Map.entry("map-walls", moduleStatus(config, "map-walls")),
                      Map.entry("web", moduleStatus(config, "web")),
                      Map.entry("history", moduleStatus(config, "history")))),
              configRegistry,
              metrics,
              new CommandRateLimiter(
                  config.global().security().commandRateLimit(),
                  Duration.ofSeconds(config.global().security().commandRateWindowSeconds())),
              settlements,
              buildingRegistrations,
              new DistrictApplicationService(new PostgresDistrictGateway(store)),
              settlementLifecycle,
              new SettlementFoundingCoordinator(schedulers, settlements, settlementLifecycle),
              new InfrastructureRegistrationCoordinator(
                  schedulers,
                  new PaperInfrastructureSurveyor(),
                  new InfrastructureService(
                      new PostgresInfrastructureGateway(store), new InfrastructureValidator())),
              caravans,
              population,
              commerce,
              finance,
              harbor,
              new EconomyApplicationService(economyGateway),
              new ProductionApplicationService(productionGateway),
              logisticsGateway,
              contractGateway,
              campaignGateway,
              campaignOutcomes,
              repairGateway,
              worldSimulationGateway,
              civilizationGateway,
              kingdomIntegration,
              dynamicEvents,
              endgame,
              Duration.ofHours(config.warfare().preparationHours()),
              Duration.ofDays(config.warfare().maximumDurationDays()),
              config.warfare().declarationCostMinor(),
              schedulers,
              new PaperFrontierUi(getServer()),
              presentation);
      PluginCommand command = getCommand("frontier");
      if (command == null)
        throw new IllegalStateException("frontier command is missing from plugin.yml");
      command.setExecutor(handler);
      command.setTabCompleter(handler);
      if (config.enabled("economy"))
        getServer()
            .getPluginManager()
            .registerEvents(new HarborOnboardingListener(schedulers, harbor, presentation), this);
      if (config.enabled("settlements"))
        getServer()
            .getPluginManager()
            .registerEvents(new SettlementActivityListener(schedulers, settlementLifecycle), this);
      if (config.enabled("caravans"))
        getServer()
            .getPluginManager()
            .registerEvents(new CaravanCombatListener(this, schedulers, caravans), this);
      caravanSupervisor =
          new CaravanPresentationSupervisor(this, schedulers, caravans, getLogger());
      if (config.enabled("caravans")) caravanSupervisor.start();
      populationSupervisor = new PopulationSupervisor(schedulers, population, getLogger());
      if (config.enabled("population")) populationSupervisor.start();
      commercialSupervisor = new CommercialSupervisor(schedulers, commerce, getLogger());
      if (config.enabled("economy")) commercialSupervisor.start();
      campaignOutcomeSupervisor =
          new CampaignOutcomeSupervisor(schedulers, campaignOutcomes, getLogger());
      if (config.enabled("warfare")) campaignOutcomeSupervisor.start();
      settlementLifecycleSupervisor =
          new SettlementLifecycleSupervisor(schedulers, settlementLifecycle, getLogger());
      if (config.enabled("settlements")) settlementLifecycleSupervisor.start();
      influenceSupervisor =
          new InfluenceSupervisor(
              schedulers,
              influence,
              Duration.ofSeconds(config.influence().cycleSeconds()),
              config.influence().maximumSettlementsPerCycle(),
              getLogger());
      if (config.enabled("influence")) influenceSupervisor.start();
      settlementSimulationSupervisor =
          new SettlementSimulationSupervisor(
              schedulers,
              new SettlementDailySimulation(new PostgresSettlementSimulationGateway(store)),
              Duration.ofSeconds(config.settlements().simulationCheckSeconds()),
              config.settlements().maximumPerCycle(),
              getLogger());
      if (config.enabled("settlements")) settlementSimulationSupervisor.start();
      economySupervisor =
          new EconomySupervisor(
              schedulers,
              economyGateway,
              Duration.ofSeconds(config.economy().marketCycleSeconds()),
              config.economy().maximumTradesPerCycle(),
              getLogger());
      if (config.enabled("economy")) economySupervisor.start();
      productionSupervisor =
          new ProductionSupervisor(
              schedulers,
              productionGateway,
              Duration.ofSeconds(config.economy().productionCycleSeconds()),
              config.economy().maximumProductionOrdersPerCycle(),
              getLogger());
      if (config.enabled("economy")) productionSupervisor.start();
      logisticsSupervisor =
          new LogisticsSupervisor(
              schedulers,
              logisticsGateway,
              Duration.ofSeconds(config.economy().logisticsCycleSeconds()),
              config.economy().maximumShipmentsPerCycle(),
              getLogger());
      if (config.enabled("infrastructure")) logisticsSupervisor.start();
      npcMaterializationSupervisor =
          new NpcMaterializationSupervisor(
              this,
              schedulers,
              new PostgresNpcMaterializationGateway(store),
              Duration.ofSeconds(config.population().materializationCycleSeconds()),
              config.population().maximumVisibleNpcsPerSettlement(),
              getLogger());
      if (config.enabled("population")) npcMaterializationSupervisor.start();
      campaignSupervisor =
          new CampaignSupervisor(
              schedulers,
              campaignGateway,
              warPolicyCache,
              Duration.ofSeconds(config.warfare().lifecycleCycleSeconds()),
              config.warfare().maximumTransitionsPerCycle(),
              getLogger(),
              presentation);
      if (config.enabled("warfare")) campaignSupervisor.start();
      objectiveSupervisor =
          new ObjectiveSupervisor(
              getServer(),
              schedulers,
              campaignGateway,
              Duration.ofSeconds(config.warfare().objectiveCycleSeconds()),
              getLogger());
      if (config.enabled("warfare")) {
        objectiveSupervisor.start();
        getServer()
            .getPluginManager()
            .registerEvents(
                new CampaignCombatListener(warPolicyCache, campaignGateway, schedulers), this);
      }
      if (config.enabled("settlements"))
        getServer()
            .getPluginManager()
            .registerEvents(
                new CampaignStructuralListener(
                    schedulers,
                    ownershipCache,
                    claimProtection,
                    warPolicyCache,
                    warDamageGateway,
                    Duration.ofHours(config.warfare().breachWindowHours()),
                    config.warfare().breachBasePoints(),
                    config.warfare().breachMaximumPoints()),
                this);
      if (config.enabled("settlements"))
        getServer()
            .getPluginManager()
            .registerEvents(new ClaimProtectionListener(claimProtection), this);
      claimProtectionSupervisor =
          new ClaimProtectionSupervisor(
              schedulers,
              claimProtectionGateway,
              claimProtectionCache,
              Duration.ofSeconds(config.settlements().protectionCacheRefreshSeconds()),
              getLogger());
      if (config.enabled("settlements")) claimProtectionSupervisor.start();
      repairSupervisor =
          new RepairSupervisor(
              schedulers,
              repairGateway,
              warPolicyCache,
              Duration.ofSeconds(config.repairs().cycleSeconds()),
              Duration.ofSeconds(config.repairs().taskLeaseSeconds()),
              Duration.ofHours(config.repairs().archiveAfterHours()),
              config.repairs().maximumTasksPerCycle(),
              config.repairs().unsafeRadius(),
              getLogger(),
              presentation);
      if (config.enabled("repairs")) repairSupervisor.start();
      damageRecoverySupervisor =
          new DamageRecoverySupervisor(
              schedulers,
              warDamageGateway,
              Duration.ofSeconds(config.repairs().damageRecoveryCycleSeconds()),
              config.repairs().maximumDamageRecoveryPerCycle(),
              getLogger());
      if (config.enabled("repairs")) damageRecoverySupervisor.start();
      worldSimulationSupervisor =
          new WorldSimulationSupervisor(
              schedulers,
              worldSimulationGateway,
              Duration.ofSeconds(config.worldSimulation().cycleSeconds()),
              config.worldSimulation().maximumCitiesPerCycle(),
              getLogger());
      if (config.enabled("world-simulation")) worldSimulationSupervisor.start();
      dynamicEventSupervisor =
          new DynamicEventSupervisor(schedulers, dynamicEvents, Duration.ofMinutes(1), getLogger());
      if (config.enabled("world-simulation")) dynamicEventSupervisor.start();
      civilizationSupervisor =
          new CivilizationSupervisor(
              schedulers,
              civilizationGateway,
              Duration.ofSeconds(config.kingdoms().civilizationCycleSeconds()),
              config.kingdoms().maximumKingdomsPerCycle(),
              getLogger());
      if (config.enabled("kingdoms")) civilizationSupervisor.start();
      kingdomIntegrationSupervisor =
          new KingdomIntegrationSupervisor(
              schedulers, civilizationGateway, kingdomIntegration, getLogger());
      if (config.enabled("kingdoms")) kingdomIntegrationSupervisor.start();
      outboxSupervisor =
          new OutboxSupervisor(
              schedulers,
              new PostgresOutboxDispatcher(
                  store,
                  event ->
                      getLogger()
                          .fine(
                              "Published "
                                  + event.eventType()
                                  + " for "
                                  + event.aggregateType()
                                  + "/"
                                  + event.aggregateId())),
              metrics,
              Duration.ofSeconds(config.history().outboxCycleSeconds()),
              config.history().maximumEventsPerCycle(),
              getLogger());
      if (config.enabled("history")) outboxSupervisor.start();
      harborSupervisor =
          new HarborSupervisor(
              schedulers,
              harborGateway,
              Duration.ofSeconds(config.economy().harborRefreshSeconds()),
              getLogger());
      if (config.enabled("economy")) harborSupervisor.start();
      acceptingWrites = true;
      getLogger().info("The Frontier enabled; database migrations and recovery passed.");
    } catch (RuntimeException failure) {
      getLogger()
          .log(java.util.logging.Level.SEVERE, "Frontier failed closed during startup", failure);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    acceptingWrites = false;
    if (influenceSupervisor != null) influenceSupervisor.stop();
    if (settlementSimulationSupervisor != null) settlementSimulationSupervisor.stop();
    if (economySupervisor != null) economySupervisor.stop();
    if (productionSupervisor != null) productionSupervisor.stop();
    if (logisticsSupervisor != null) logisticsSupervisor.stop();
    if (npcMaterializationSupervisor != null) npcMaterializationSupervisor.stop();
    if (campaignSupervisor != null) campaignSupervisor.stop();
    if (objectiveSupervisor != null) objectiveSupervisor.stop();
    if (repairSupervisor != null) repairSupervisor.stop();
    if (worldSimulationSupervisor != null) worldSimulationSupervisor.stop();
    if (dynamicEventSupervisor != null) dynamicEventSupervisor.stop();
    if (civilizationSupervisor != null) civilizationSupervisor.stop();
    if (kingdomIntegrationSupervisor != null) kingdomIntegrationSupervisor.stop();
    if (outboxSupervisor != null) outboxSupervisor.stop();
    if (harborSupervisor != null) harborSupervisor.stop();
    if (claimProtectionSupervisor != null) claimProtectionSupervisor.stop();
    if (damageRecoverySupervisor != null) damageRecoverySupervisor.stop();
    if (settlementLifecycleSupervisor != null) settlementLifecycleSupervisor.stop();
    if (caravanSupervisor != null) caravanSupervisor.stop();
    if (populationSupervisor != null) populationSupervisor.stop();
    if (commercialSupervisor != null) commercialSupervisor.stop();
    if (campaignOutcomeSupervisor != null) campaignOutcomeSupervisor.stop();
    if (schedulers != null) schedulers.close();
    if (database != null) database.close();
  }

  private void validateRuntime() {
    if (Runtime.version().feature() < 25)
      throw new IllegalStateException("The Frontier requires Java 25+");
  }

  private static String moduleStatus(FrontierConfiguration config, String module) {
    return config.enabled(module) ? "ACTIVE" : "DISABLED";
  }

  private String materializeMigrations() {
    Path directory = getDataFolder().toPath().resolve("migrations");
    try {
      Files.createDirectories(directory);
      try (InputStream index = getResource("db/migration/index.txt")) {
        if (index == null) throw new IllegalStateException("migration index is missing");
        for (String filename :
            new String(index.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
          if (filename.isBlank()) continue;
          try (InputStream input = getResource("db/migration/" + filename)) {
            if (input == null)
              throw new IllegalStateException("packaged migration is missing: " + filename);
            Files.copy(input, directory.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    } catch (IOException failure) {
      throw new IllegalStateException("could not materialize database migrations", failure);
    }
    return "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
  }

  private HealthStatus health() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("writes", acceptingWrites ? "accepting" : "stopped");
    boolean databaseHealthy = false;
    try (Connection connection = database.dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT 1")) {
      databaseHealthy = result.next() && result.getInt(1) == 1;
    } catch (java.sql.SQLException failure) {
      values.put(
          "database-error", failure.getSQLState() == null ? "unavailable" : failure.getSQLState());
      if (metrics != null) metrics.failure();
    }

    values.put("database", databaseHealthy ? "up" : "down");
    if (databaseHealthy) {
      DatabaseManager.PoolStats pool = database.poolStats();
      values.put(
          "database-pool",
          pool.active() + " active, " + pool.idle() + " idle, " + pool.awaiting() + " waiting");
      if (metrics != null) metrics.databasePool(pool.active(), pool.awaiting());
    }
    if (metrics != null) {
      Map<String, Number> snapshot = metrics.snapshot();
      values.put("outbox-pending", snapshot.get("outboxPending").toString());
      values.put("outbox-lag-seconds", snapshot.get("outboxLagSeconds").toString());
    }
    values.put("supervisors", acceptingWrites ? "running" : "stopped");
    return new HealthStatus(acceptingWrites && databaseHealthy, values);
  }
}
