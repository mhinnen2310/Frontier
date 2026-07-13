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
import nl.frontier.city.SettlementApplicationService;
import nl.frontier.city.SettlementDailySimulation;
import nl.frontier.city.SettlementLifecycleService;
import nl.frontier.economy.CaravanService;
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
import nl.frontier.observability.FrontierMetrics;
import nl.frontier.persistence.DatabaseManager;
import nl.frontier.persistence.JdbcTransactionalStore;
import nl.frontier.persistence.PostgresAdminDiagnostics;
import nl.frontier.persistence.PostgresBuildingValidationGateway;
import nl.frontier.persistence.PostgresCampaignGateway;
import nl.frontier.persistence.PostgresCaravanGateway;
import nl.frontier.persistence.PostgresCivilizationGateway;
import nl.frontier.persistence.PostgresClaimProtectionGateway;
import nl.frontier.persistence.PostgresContractGateway;
import nl.frontier.persistence.PostgresDistrictGateway;
import nl.frontier.persistence.PostgresEconomyGateway;
import nl.frontier.persistence.PostgresFinanceGateway;
import nl.frontier.persistence.PostgresHarborGateway;
import nl.frontier.persistence.PostgresInfluencePersistence;
import nl.frontier.persistence.PostgresInfrastructureGateway;
import nl.frontier.persistence.PostgresLogisticsGateway;
import nl.frontier.persistence.PostgresNpcMaterializationGateway;
import nl.frontier.persistence.PostgresOutboxDispatcher;
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
import nl.frontier.warfare.WarPolicyCache;
import nl.frontier.world.CivilizationGateway;
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
  private volatile boolean acceptingWrites;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    try {
      validateRuntime();
      database =
          new DatabaseManager(
              new DatabaseManager.Configuration(
                  getConfig()
                      .getString("database.jdbc-url", "jdbc:postgresql://localhost:5432/frontier"),
                  getConfig().getString("database.username", "frontier"),
                  getConfig().getString("database.password", "frontier"),
                  getConfig().getInt("database.maximum-pool-size", 10),
                  Duration.ofSeconds(10)));
      database.migrate(materializeMigrations());
      JdbcTransactionalStore store = new JdbcTransactionalStore(database.dataSource());
      recovery = new PostgresRecoveryCoordinator(store);
      recovery.recover();
      schedulers =
          new PaperSchedulerFacade(this, getConfig().getInt("performance.async-threads", 4));
      metrics = new FrontierMetrics(new SimpleMeterRegistry());
      PostgresAdminDiagnostics diagnostics = new PostgresAdminDiagnostics(store);
      ChunkOwnershipCache ownershipCache = new ChunkOwnershipCache();
      InfluenceSimulationService influence =
          new InfluenceSimulationService(
              new PostgresInfluencePersistence(store),
              ownershipCache,
              getConfig().getInt("influence.contested-threshold", 75),
              getConfig().getInt("influence.required-lead-cycles", 3));
      PostgresEconomyGateway economyGateway = new PostgresEconomyGateway(store);
      FinanceApplicationService finance =
          new FinanceApplicationService(new PostgresFinanceGateway(store));
      HarborGateway harborGateway = new PostgresHarborGateway(store);
      HarborApplicationService harbor = new HarborApplicationService(harborGateway);
      World primaryWorld = getServer().getWorlds().getFirst();
      Location harborLocation = primaryWorld.getSpawnLocation();
      harborGateway.bootstrap(
          primaryWorld.getUID(),
          harborLocation.getBlockX() >> 4,
          harborLocation.getBlockZ() >> 4,
          Instant.now());
      PostgresProductionGateway productionGateway = new PostgresProductionGateway(store);
      LogisticsGateway logisticsGateway = new PostgresLogisticsGateway(store);
      ContractGateway contractGateway = new PostgresContractGateway(store);
      CaravanService caravans = new CaravanService(new PostgresCaravanGateway(store));
      CampaignGateway campaignGateway = new PostgresCampaignGateway(store);
      WarPolicyCache warPolicyCache = new WarPolicyCache();
      warPolicyCache.replace(campaignGateway.policySnapshot(Instant.now()));
      ClaimProtectionGateway claimProtectionGateway = new PostgresClaimProtectionGateway(store);
      ClaimProtectionCache claimProtectionCache = new ClaimProtectionCache();
      claimProtectionCache.replace(claimProtectionGateway.load(Instant.now()));
      ClaimProtectionService claimProtection =
          new ClaimProtectionService(
              claimProtectionCache,
              (player, city) -> warPolicyCache.hostileCampaign(player, city).isPresent());
      RepairGateway repairGateway = new PostgresRepairGateway(store);
      nl.frontier.warfare.WarDamageGateway warDamageGateway = new PostgresWarDamageGateway(store);
      WorldSimulationGateway worldSimulationGateway = new PostgresWorldSimulationGateway(store);
      CivilizationGateway civilizationGateway = new PostgresCivilizationGateway(store);
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
              metrics,
              new CommandRateLimiter(
                  getConfig().getInt("security.command-rate-limit", 12),
                  Duration.ofSeconds(
                      getConfig().getLong("security.command-rate-window-seconds", 2))),
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
              finance,
              harbor,
              new EconomyApplicationService(economyGateway),
              new ProductionApplicationService(productionGateway),
              logisticsGateway,
              contractGateway,
              campaignGateway,
              repairGateway,
              worldSimulationGateway,
              civilizationGateway,
              Duration.ofHours(getConfig().getLong("campaigns.preparation-hours", 24)),
              Duration.ofDays(getConfig().getLong("campaigns.maximum-duration-days", 14)),
              getConfig().getLong("campaigns.declaration-cost-minor", 5_000),
              schedulers,
              new PaperFrontierUi(getServer()));
      PluginCommand command = getCommand("frontier");
      if (command == null)
        throw new IllegalStateException("frontier command is missing from plugin.yml");
      command.setExecutor(handler);
      command.setTabCompleter(handler);
      getServer()
          .getPluginManager()
          .registerEvents(new HarborOnboardingListener(schedulers, harbor), this);
      getServer()
          .getPluginManager()
          .registerEvents(new SettlementActivityListener(schedulers, settlementLifecycle), this);
      getServer()
          .getPluginManager()
          .registerEvents(new CaravanCombatListener(this, schedulers, caravans), this);
      caravanSupervisor =
          new CaravanPresentationSupervisor(this, schedulers, caravans, getLogger());
      caravanSupervisor.start();
      settlementLifecycleSupervisor =
          new SettlementLifecycleSupervisor(schedulers, settlementLifecycle, getLogger());
      settlementLifecycleSupervisor.start();
      influenceSupervisor =
          new InfluenceSupervisor(
              schedulers,
              influence,
              Duration.ofSeconds(getConfig().getLong("influence.cycle-seconds", 60)),
              getConfig().getInt("influence.maximum-settlements-per-cycle", 16),
              getLogger());
      influenceSupervisor.start();
      settlementSimulationSupervisor =
          new SettlementSimulationSupervisor(
              schedulers,
              new SettlementDailySimulation(new PostgresSettlementSimulationGateway(store)),
              Duration.ofSeconds(getConfig().getLong("settlements.simulation-check-seconds", 60)),
              getConfig().getInt("settlements.maximum-per-cycle", 32),
              getLogger());
      settlementSimulationSupervisor.start();
      economySupervisor =
          new EconomySupervisor(
              schedulers,
              economyGateway,
              Duration.ofSeconds(getConfig().getLong("economy.market-cycle-seconds", 5)),
              getConfig().getInt("economy.maximum-trades-per-cycle", 64),
              getLogger());
      economySupervisor.start();
      productionSupervisor =
          new ProductionSupervisor(
              schedulers,
              productionGateway,
              Duration.ofSeconds(getConfig().getLong("economy.production.cycle-seconds", 10)),
              getConfig().getInt("economy.production.maximum-orders-per-cycle", 64),
              getLogger());
      productionSupervisor.start();
      logisticsSupervisor =
          new LogisticsSupervisor(
              schedulers,
              logisticsGateway,
              Duration.ofSeconds(getConfig().getLong("economy.logistics.cycle-seconds", 10)),
              getConfig().getInt("economy.logistics.maximum-shipments-per-cycle", 64),
              getLogger());
      logisticsSupervisor.start();
      npcMaterializationSupervisor =
          new NpcMaterializationSupervisor(
              this,
              schedulers,
              new PostgresNpcMaterializationGateway(store),
              Duration.ofSeconds(getConfig().getLong("npcs.materialization-cycle-seconds", 5)),
              getConfig().getInt("performance.maximum-visible-npcs-per-settlement", 20),
              getLogger());
      npcMaterializationSupervisor.start();
      campaignSupervisor =
          new CampaignSupervisor(
              schedulers,
              campaignGateway,
              warPolicyCache,
              Duration.ofSeconds(getConfig().getLong("campaigns.lifecycle-cycle-seconds", 5)),
              getConfig().getInt("campaigns.maximum-transitions-per-cycle", 32),
              getLogger());
      campaignSupervisor.start();
      objectiveSupervisor =
          new ObjectiveSupervisor(
              getServer(),
              schedulers,
              campaignGateway,
              Duration.ofSeconds(getConfig().getLong("campaigns.objective-cycle-seconds", 5)),
              getLogger());
      objectiveSupervisor.start();
      getServer()
          .getPluginManager()
          .registerEvents(
              new CampaignCombatListener(warPolicyCache, campaignGateway, schedulers), this);
      getServer()
          .getPluginManager()
          .registerEvents(
              new CampaignStructuralListener(
                  schedulers,
                  ownershipCache,
                  warPolicyCache,
                  warDamageGateway,
                  Duration.ofHours(getConfig().getLong("campaigns.breach-window-hours", 6)),
                  getConfig().getInt("campaigns.breach-base-points", 1_200),
                  getConfig().getInt("campaigns.breach-maximum-points", 3_000)),
              this);
      getServer()
          .getPluginManager()
          .registerEvents(new ClaimProtectionListener(claimProtection), this);
      claimProtectionSupervisor =
          new ClaimProtectionSupervisor(
              schedulers,
              claimProtectionGateway,
              claimProtectionCache,
              Duration.ofSeconds(getConfig().getLong("protection.cache-refresh-seconds", 2)),
              getLogger());
      claimProtectionSupervisor.start();
      repairSupervisor =
          new RepairSupervisor(
              schedulers,
              repairGateway,
              warPolicyCache,
              Duration.ofSeconds(getConfig().getLong("repairs.cycle-seconds", 2)),
              Duration.ofSeconds(getConfig().getLong("repairs.task-lease-seconds", 30)),
              Duration.ofHours(getConfig().getLong("repairs.archive-after-hours", 24)),
              getConfig().getInt("repairs.maximum-tasks-per-cycle", 8),
              getConfig().getDouble("repairs.unsafe-radius", 96),
              getLogger());
      repairSupervisor.start();
      damageRecoverySupervisor =
          new DamageRecoverySupervisor(
              schedulers,
              warDamageGateway,
              Duration.ofSeconds(getConfig().getLong("damage-recovery.cycle-seconds", 10)),
              getConfig().getInt("damage-recovery.maximum-per-cycle", 128),
              getLogger());
      damageRecoverySupervisor.start();
      worldSimulationSupervisor =
          new WorldSimulationSupervisor(
              schedulers,
              worldSimulationGateway,
              Duration.ofSeconds(getConfig().getLong("world-simulation.cycle-seconds", 60)),
              getConfig().getInt("world-simulation.maximum-cities-per-cycle", 32),
              getLogger());
      worldSimulationSupervisor.start();
      civilizationSupervisor =
          new CivilizationSupervisor(
              schedulers,
              civilizationGateway,
              Duration.ofSeconds(getConfig().getLong("civilization.cycle-seconds", 300)),
              getConfig().getInt("civilization.maximum-kingdoms-per-cycle", 32),
              getLogger());
      civilizationSupervisor.start();
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
              Duration.ofSeconds(getConfig().getLong("outbox.cycle-seconds", 1)),
              getConfig().getInt("outbox.maximum-events-per-cycle", 128),
              getLogger());
      outboxSupervisor.start();
      harborSupervisor =
          new HarborSupervisor(
              schedulers,
              harborGateway,
              Duration.ofSeconds(getConfig().getLong("harbor.refresh-seconds", 300)),
              getLogger());
      harborSupervisor.start();
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
    if (civilizationSupervisor != null) civilizationSupervisor.stop();
    if (outboxSupervisor != null) outboxSupervisor.stop();
    if (harborSupervisor != null) harborSupervisor.stop();
    if (claimProtectionSupervisor != null) claimProtectionSupervisor.stop();
    if (damageRecoverySupervisor != null) damageRecoverySupervisor.stop();
    if (settlementLifecycleSupervisor != null) settlementLifecycleSupervisor.stop();
    if (caravanSupervisor != null) caravanSupervisor.stop();
    if (schedulers != null) schedulers.close();
    if (database != null) database.close();
  }

  private void validateRuntime() {
    if (Runtime.version().feature() < 25)
      throw new IllegalStateException("The Frontier requires Java 25+");
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
