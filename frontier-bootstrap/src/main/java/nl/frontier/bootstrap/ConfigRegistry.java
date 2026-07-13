package nl.frontier.bootstrap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import nl.frontier.economy.HarborPolicy;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigRegistry {
  public static final int CONFIG_VERSION = 1;
  public static final List<String> MODULES =
      List.of(
          "settlements",
          "districts",
          "buildings",
          "influence",
          "economy",
          "infrastructure",
          "caravans",
          "warfare",
          "repairs",
          "population",
          "kingdoms",
          "waypoints",
          "cartography",
          "map-walls",
          "web",
          "history",
          "world-simulation");

  private static final Map<String, Set<String>> KEYS = allowedKeys();
  private final JavaPlugin plugin;
  private FrontierConfiguration active;
  private Map<String, YamlConfiguration> moduleDocuments;

  private ConfigRegistry(JavaPlugin plugin) {
    this.plugin = Objects.requireNonNull(plugin);
  }

  public static ConfigRegistry load(JavaPlugin plugin) {
    ConfigRegistry registry = new ConfigRegistry(plugin);
    registry.active = registry.readFromDisk(true);
    return registry;
  }

  public FrontierConfiguration configuration() {
    return active;
  }

  public boolean enabled(String module) {
    return active.enabled(module);
  }

  public List<String> validate() {
    readFromDisk(false);
    return List.of("configuration valid", "config-version=" + CONFIG_VERSION, "secrets=redacted");
  }

  public ReloadReport reload() {
    FrontierConfiguration candidate = readFromDisk(false);
    List<String> changed = differences(active, candidate);
    ReloadClass classification =
        changed.isEmpty()
            ? ReloadClass.LIVE
            : changed.stream().allMatch(key -> key.endsWith(".enabled"))
                ? ReloadClass.MODULE_RESTART
                : ReloadClass.SERVER_RESTART;
    return new ReloadReport(classification, changed);
  }

  public List<String> show(String module) {
    String normalized = module.toLowerCase(Locale.ROOT);
    FileConfiguration document;
    if (normalized.equals("global")) document = plugin.getConfig();
    else {
      document = moduleDocuments.get(normalized);
      if (document == null) throw new IllegalArgumentException("unknown config module: " + module);
    }
    List<String> rows = new ArrayList<>();
    rows.add("config " + normalized);
    new TreeMap<>(document.getValues(true))
        .forEach(
            (key, value) -> {
              if (value instanceof org.bukkit.configuration.ConfigurationSection) return;
              String rendered = isSecret(key) ? "<redacted>" : String.valueOf(value);
              rows.add(key + "=" + rendered);
            });
    return List.copyOf(rows);
  }

  static FrontierConfiguration parse(
      FileConfiguration global, Map<String, YamlConfiguration> modules, Consumer<String> warning) {
    requireVersion(global, "config.yml");
    warnUnknown(global, KEYS.get("global"), "config.yml", warning);
    MODULES.forEach(
        module -> {
          YamlConfiguration document = requiredDocument(modules, module);
          requireVersion(document, "modules/" + module + ".yml");
          warnUnknown(document, KEYS.get(module), "modules/" + module + ".yml", warning);
        });

    var database =
        new FrontierConfiguration.Database(
            requiredText(global, "database.jdbc-url"),
            requiredText(global, "database.username"),
            global.getString("database.password", ""),
            positive(global, "database.maximum-pool-size", 64));
    var runtime = new FrontierConfiguration.Runtime(positive(global, "runtime.async-threads", 64));
    var security =
        new FrontierConfiguration.Security(
            positive(global, "security.command-rate-limit", Integer.MAX_VALUE),
            positiveLong(global, "security.command-rate-window-seconds"));
    var globalConfig =
        new FrontierConfiguration.Global(CONFIG_VERSION, database, runtime, security);

    YamlConfiguration settlements = modules.get("settlements");
    int minimumCoreDistance = positive(settlements, "founding.minimum-core-distance", 100_000);
    int harborExclusionRadius = positive(settlements, "founding.harbor-exclusion-radius", 100_000);
    if (harborExclusionRadius < minimumCoreDistance)
      throw invalid(
          "founding.harbor-exclusion-radius cannot be smaller than founding.minimum-core-distance");
    Set<String> allowedEnvironments = normalizedSet(settlements, "founding.allowed-environments");
    if (allowedEnvironments.isEmpty()
        || !Set.of("NORMAL", "NETHER", "THE_END", "CUSTOM").containsAll(allowedEnvironments))
      throw invalid(
          "founding.allowed-environments must contain NORMAL, NETHER, THE_END, or CUSTOM");
    long mayorInactivityDays = positiveLong(settlements, "membership.mayor-inactivity-days");
    long settlementInactivityDays =
        positiveLong(settlements, "membership.settlement-inactivity-days");
    if (settlementInactivityDays < mayorInactivityDays)
      throw invalid(
          "membership.settlement-inactivity-days cannot be smaller than membership.mayor-inactivity-days");
    long disbandConfirmationSeconds =
        positiveLong(settlements, "membership.disband-confirmation-seconds");
    long disbandRequestMinutes = positiveLong(settlements, "membership.disband-request-minutes");
    if (disbandRequestMinutes * 60 <= disbandConfirmationSeconds)
      throw invalid("disband request lifetime must exceed its confirmation cooldown");
    var settlementConfig =
        new FrontierConfiguration.Settlements(
            control(settlements),
            positiveLong(settlements, "simulation.check-seconds"),
            positive(settlements, "simulation.maximum-per-cycle", Integer.MAX_VALUE),
            positiveLong(settlements, "protection.cache-refresh-seconds"),
            positiveLong(settlements, "founding.fee-minor"),
            positive(settlements, "founding.minimum-founders", 100),
            positiveLong(settlements, "founding.expedition-lifetime-hours"),
            positiveLong(settlements, "founding.reservation-lifetime-minutes"),
            minimumCoreDistance,
            harborExclusionRadius,
            positive(settlements, "founding.materials.stone-bricks", 100_000),
            positive(settlements, "founding.materials.oak-logs", 100_000),
            positive(settlements, "founding.materials.bells", 100_000),
            allowedEnvironments,
            mayorInactivityDays,
            settlementInactivityDays,
            disbandConfirmationSeconds,
            disbandRequestMinutes);
    YamlConfiguration influence = modules.get("influence");
    var influenceConfig =
        new FrontierConfiguration.Influence(
            control(influence),
            positiveLong(influence, "simulation.cycle-seconds"),
            positive(influence, "simulation.maximum-settlements-per-cycle", Integer.MAX_VALUE),
            positive(influence, "simulation.contested-threshold", Integer.MAX_VALUE),
            positive(influence, "simulation.required-lead-cycles", Integer.MAX_VALUE));
    YamlConfiguration economy = modules.get("economy");
    var economyConfig =
        new FrontierConfiguration.Economy(
            control(economy),
            positiveLong(economy, "market.cycle-seconds"),
            positive(economy, "market.maximum-trades-per-cycle", Integer.MAX_VALUE),
            positiveLong(economy, "production.cycle-seconds"),
            positive(economy, "production.maximum-orders-per-cycle", Integer.MAX_VALUE),
            positiveLong(economy, "logistics.cycle-seconds"),
            positive(economy, "logistics.maximum-shipments-per-cycle", Integer.MAX_VALUE),
            positiveLong(economy, "harbor.refresh-seconds"),
            harborPolicy(economy));
    YamlConfiguration warfare = modules.get("warfare");
    int breachBase = positive(warfare, "breach.base-points", Integer.MAX_VALUE);
    int breachMaximum = positive(warfare, "breach.maximum-points", Integer.MAX_VALUE);
    if (breachBase > breachMaximum)
      throw invalid("warfare breach.base-points cannot exceed breach.maximum-points");
    var warfareConfig =
        new FrontierConfiguration.Warfare(
            control(warfare),
            positiveLong(warfare, "campaign.preparation-hours"),
            positive(warfare, "campaign.maximum-duration-days", Integer.MAX_VALUE),
            positiveLong(warfare, "breach.window-hours"),
            breachBase,
            breachMaximum,
            positiveLong(warfare, "campaign.declaration-cost-minor"),
            positiveLong(warfare, "lifecycle.cycle-seconds"),
            positive(warfare, "lifecycle.maximum-transitions-per-cycle", Integer.MAX_VALUE),
            positiveLong(warfare, "objectives.cycle-seconds"));
    YamlConfiguration repairs = modules.get("repairs");
    double unsafeRadius = repairs.getDouble("placement.unsafe-radius");
    if (!Double.isFinite(unsafeRadius) || unsafeRadius <= 0 || unsafeRadius > 1024)
      throw invalid("repairs placement.unsafe-radius must be between 0 and 1024");
    var repairConfig =
        new FrontierConfiguration.Repairs(
            control(repairs),
            positiveLong(repairs, "tasks.cycle-seconds"),
            positiveLong(repairs, "tasks.lease-seconds"),
            positive(repairs, "tasks.maximum-per-cycle", Integer.MAX_VALUE),
            positiveLong(repairs, "tasks.archive-after-hours"),
            unsafeRadius,
            positiveLong(repairs, "damage-recovery.cycle-seconds"),
            positive(repairs, "damage-recovery.maximum-per-cycle", Integer.MAX_VALUE));
    YamlConfiguration population = modules.get("population");
    var populationConfig =
        new FrontierConfiguration.Population(
            control(population),
            positiveLong(population, "presentation.materialization-cycle-seconds"),
            positive(population, "presentation.maximum-visible-per-settlement", 500));
    YamlConfiguration districts = modules.get("districts");
    var districtConfig =
        new FrontierConfiguration.Districts(
            control(districts),
            new nl.frontier.city.DistrictBalancePolicy(
                positive(districts, "balance.minimum-building-integrity", 100),
                positive(districts, "balance.minimum-infrastructure-integrity", 100),
                positive(districts, "balance.diminishing-return-percent", 100),
                positive(districts, "balance.maximum-building-contributions", 20),
                positive(districts, "balance.adjacency-bonus-percent", 100),
                positive(districts, "balance.maximum-adjacency-bonuses", 20),
                positive(districts, "balance.adjacency-distance-blocks", 256),
                positive(districts, "balance.over-specialization-threshold", 20),
                positive(districts, "balance.over-specialization-penalty-percent", 100),
                positive(districts, "balance.maximum-effective-bonus-percent", 100),
                positive(districts, "balance.industrial-maintenance-penalty-percent", 100),
                positive(districts, "balance.military-wage-penalty-percent", 100),
                positive(districts, "balance.commercial-market-orders-per-building", 100),
                positive(districts, "balance.logistics-warehouse-capacity-percent", 100)));
    YamlConfiguration kingdoms = modules.get("kingdoms");
    var kingdomConfig =
        new FrontierConfiguration.Kingdoms(
            control(kingdoms),
            positiveLong(kingdoms, "simulation.cycle-seconds"),
            positive(kingdoms, "simulation.maximum-per-cycle", Integer.MAX_VALUE));
    YamlConfiguration web = modules.get("web");
    int webPort = positive(web, "server.port", 65535);
    var webConfig =
        new FrontierConfiguration.Web(
            control(web),
            requiredText(web, "server.bind-address"),
            webPort,
            requiredText(web, "server.public-url"));
    YamlConfiguration history = modules.get("history");
    var historyConfig =
        new FrontierConfiguration.History(
            control(history),
            positiveLong(history, "outbox.cycle-seconds"),
            positive(history, "outbox.maximum-events-per-cycle", Integer.MAX_VALUE));
    YamlConfiguration world = modules.get("world-simulation");
    var worldConfig =
        new FrontierConfiguration.WorldSimulation(
            control(world),
            positiveLong(world, "simulation.cycle-seconds"),
            positive(world, "simulation.maximum-cities-per-cycle", Integer.MAX_VALUE));

    FrontierConfiguration parsed =
        new FrontierConfiguration(
            globalConfig,
            settlementConfig,
            districtConfig,
            control(modules.get("buildings")),
            influenceConfig,
            economyConfig,
            control(modules.get("infrastructure")),
            control(modules.get("caravans")),
            warfareConfig,
            repairConfig,
            populationConfig,
            kingdomConfig,
            control(modules.get("waypoints")),
            control(modules.get("cartography")),
            control(modules.get("map-walls")),
            webConfig,
            historyConfig,
            worldConfig);
    validateDependencies(parsed);
    return parsed;
  }

  private FrontierConfiguration readFromDisk(boolean createMissing) {
    if (createMissing) plugin.saveDefaultConfig();
    else plugin.reloadConfig();
    boolean legacy = !plugin.getConfig().isSet("config-version");
    Map<String, YamlConfiguration> documents = new LinkedHashMap<>();
    for (String module : MODULES) {
      File file = new File(plugin.getDataFolder(), "modules/" + module + ".yml");
      if (createMissing && !file.isFile()) plugin.saveResource("modules/" + module + ".yml", false);
      if (!file.isFile()) throw invalid("missing module configuration: " + file);
      documents.put(module, loadWithDefaults(file, "modules/" + module + ".yml"));
    }
    if (legacy) migrateLegacy(documents);
    FrontierConfiguration parsed =
        parse(
            plugin.getConfig(),
            documents,
            warning -> plugin.getLogger().warning("Configuration: " + warning));
    moduleDocuments = Map.copyOf(documents);
    return parsed;
  }

  private void migrateLegacy(Map<String, YamlConfiguration> documents) {
    FileConfiguration global = plugin.getConfig();
    copy(global, "performance.async-threads", global, "runtime.async-threads");
    copy(
        global,
        "settlements.simulation-check-seconds",
        documents.get("settlements"),
        "simulation.check-seconds");
    copy(
        global,
        "settlements.maximum-per-cycle",
        documents.get("settlements"),
        "simulation.maximum-per-cycle");
    copy(
        global,
        "protection.cache-refresh-seconds",
        documents.get("settlements"),
        "protection.cache-refresh-seconds");
    copyGroup(global, documents.get("influence"), "influence", "simulation");
    copy(global, "economy.market-cycle-seconds", documents.get("economy"), "market.cycle-seconds");
    copy(
        global,
        "economy.maximum-trades-per-cycle",
        documents.get("economy"),
        "market.maximum-trades-per-cycle");
    copyGroup(global, documents.get("economy"), "economy.production", "production");
    copyGroup(global, documents.get("economy"), "economy.logistics", "logistics");
    copy(global, "harbor.refresh-seconds", documents.get("economy"), "harbor.refresh-seconds");
    copy(
        global,
        "campaigns.preparation-hours",
        documents.get("warfare"),
        "campaign.preparation-hours");
    copy(
        global,
        "campaigns.maximum-duration-days",
        documents.get("warfare"),
        "campaign.maximum-duration-days");
    copy(
        global,
        "campaigns.declaration-cost-minor",
        documents.get("warfare"),
        "campaign.declaration-cost-minor");
    copy(global, "campaigns.breach-window-hours", documents.get("warfare"), "breach.window-hours");
    copy(global, "campaigns.breach-base-points", documents.get("warfare"), "breach.base-points");
    copy(
        global,
        "campaigns.breach-maximum-points",
        documents.get("warfare"),
        "breach.maximum-points");
    copy(
        global,
        "campaigns.lifecycle-cycle-seconds",
        documents.get("warfare"),
        "lifecycle.cycle-seconds");
    copy(
        global,
        "campaigns.maximum-transitions-per-cycle",
        documents.get("warfare"),
        "lifecycle.maximum-transitions-per-cycle");
    copy(
        global,
        "campaigns.objective-cycle-seconds",
        documents.get("warfare"),
        "objectives.cycle-seconds");
    copy(global, "repairs.cycle-seconds", documents.get("repairs"), "tasks.cycle-seconds");
    copy(global, "repairs.task-lease-seconds", documents.get("repairs"), "tasks.lease-seconds");
    copy(
        global,
        "repairs.maximum-tasks-per-cycle",
        documents.get("repairs"),
        "tasks.maximum-per-cycle");
    copy(
        global,
        "repairs.archive-after-hours",
        documents.get("repairs"),
        "tasks.archive-after-hours");
    copy(global, "repairs.unsafe-radius", documents.get("repairs"), "placement.unsafe-radius");
    copyGroup(global, documents.get("repairs"), "damage-recovery", "damage-recovery");
    copy(
        global,
        "npcs.materialization-cycle-seconds",
        documents.get("population"),
        "presentation.materialization-cycle-seconds");
    copy(
        global,
        "performance.maximum-visible-npcs-per-settlement",
        documents.get("population"),
        "presentation.maximum-visible-per-settlement");
    copy(
        global,
        "civilization.cycle-seconds",
        documents.get("kingdoms"),
        "simulation.cycle-seconds");
    copy(
        global,
        "civilization.maximum-kingdoms-per-cycle",
        documents.get("kingdoms"),
        "simulation.maximum-per-cycle");
    copyGroup(global, documents.get("history"), "outbox", "outbox");
    copyGroup(global, documents.get("world-simulation"), "world-simulation", "simulation");

    global.set("config-version", CONFIG_VERSION);
    for (String obsolete :
        List.of(
            "campaigns",
            "repairs",
            "economy",
            "compatibility",
            "performance",
            "npcs",
            "world-simulation",
            "civilization",
            "influence",
            "settlements",
            "outbox",
            "harbor",
            "protection",
            "damage-recovery")) global.set(obsolete, null);
    plugin.saveConfig();
    for (Map.Entry<String, YamlConfiguration> entry : documents.entrySet()) {
      File file = new File(plugin.getDataFolder(), "modules/" + entry.getKey() + ".yml");
      try {
        entry.getValue().save(file);
      } catch (IOException failure) {
        throw new IllegalStateException("could not migrate " + file, failure);
      }
    }
    plugin.getLogger().info("Migrated legacy config.yml to config-version 1 module files.");
  }

  private static void copyGroup(
      FileConfiguration source,
      FileConfiguration target,
      String sourcePrefix,
      String targetPrefix) {
    org.bukkit.configuration.ConfigurationSection section =
        source.getConfigurationSection(sourcePrefix);
    if (section == null) return;
    for (String key : section.getKeys(true)) {
      if (section.isConfigurationSection(key)) continue;
      target.set(targetPrefix + "." + key, section.get(key));
    }
  }

  private static void copy(
      FileConfiguration source, String sourceKey, FileConfiguration target, String targetKey) {
    if (source.isSet(sourceKey)) target.set(targetKey, source.get(sourceKey));
  }

  private static void validateDependencies(FrontierConfiguration config) {
    requireDependency(config, "districts", "settlements");
    requireDependency(config, "buildings", "districts");
    requireDependency(config, "influence", "settlements");
    requireDependency(config, "economy", "settlements");
    requireDependency(config, "infrastructure", "settlements");
    requireDependency(config, "caravans", "economy");
    requireDependency(config, "warfare", "influence");
    requireDependency(config, "repairs", "warfare");
    requireDependency(config, "population", "economy");
    requireDependency(config, "kingdoms", "settlements");
    requireDependency(config, "cartography", "waypoints");
    requireDependency(config, "map-walls", "cartography");
    requireDependency(config, "world-simulation", "settlements");
  }

  private static void requireDependency(
      FrontierConfiguration config, String module, String dependency) {
    if (config.enabled(module) && !config.enabled(dependency))
      throw invalid(module + " requires enabled module " + dependency);
  }

  private static FrontierConfiguration.Control control(FileConfiguration document) {
    return new FrontierConfiguration.Control(CONFIG_VERSION, document.getBoolean("enabled"));
  }

  private YamlConfiguration loadWithDefaults(File file, String resource) {
    YamlConfiguration document = YamlConfiguration.loadConfiguration(file);
    try (InputStream input = plugin.getResource(resource)) {
      if (input == null) throw invalid("missing packaged module defaults: " + resource);
      YamlConfiguration defaults =
          YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
      Set<String> before = document.getKeys(true);
      document.addDefaults(defaults);
      document.options().copyDefaults(true);
      if (!before.containsAll(defaults.getKeys(true))) document.save(file);
      return document;
    } catch (IOException failure) {
      throw new IllegalStateException("could not merge module defaults: " + file, failure);
    }
  }

  private static HarborPolicy harborPolicy(FileConfiguration document) {
    Set<String> allowed = Set.copyOf(document.getStringList("harbor.allowed-commodities"));
    Map<String, Long> stock = new LinkedHashMap<>();
    org.bukkit.configuration.ConfigurationSection stockSection =
        document.getConfigurationSection("harbor.initial-stock");
    if (stockSection == null) throw invalid("economy harbor.initial-stock is required");
    for (String commodity : stockSection.getKeys(false)) {
      long quantity = stockSection.getLong(commodity);
      if (quantity <= 0) throw invalid("Harbor initial stock must be positive: " + commodity);
      stock.put(commodity, quantity);
    }
    List<HarborPolicy.StarterJobDefinition> jobs =
        document.getMapList("harbor.starter-jobs").stream()
            .map(
                row ->
                    new HarborPolicy.StarterJobDefinition(
                        text(row, "type"), text(row, "description"), number(row, "reward-minor")))
            .toList();
    List<HarborPolicy.MarketOffer> buys = offers(document, "harbor.buy-orders");
    List<HarborPolicy.MarketOffer> sells = offers(document, "harbor.sell-orders");
    return new HarborPolicy(
        positiveLong(document, "harbor.daily-budget-minor"),
        positiveLong(document, "harbor.maximum-daily-source-minor"),
        positiveLong(document, "harbor.maximum-player-reward-per-day-minor"),
        positiveLong(document, "harbor.initial-capital-minor"),
        allowed,
        stock,
        jobs,
        buys,
        sells);
  }

  private static List<HarborPolicy.MarketOffer> offers(FileConfiguration document, String key) {
    return document.getMapList(key).stream()
        .map(
            row ->
                new HarborPolicy.MarketOffer(
                    text(row, "commodity"),
                    number(row, "quantity"),
                    number(row, "unit-price-minor")))
        .toList();
  }

  private static String text(Map<?, ?> row, String key) {
    Object value = row.get(key);
    if (!(value instanceof String text) || text.isBlank())
      throw invalid("Harbor list value is required: " + key);
    return text;
  }

  private static long number(Map<?, ?> row, String key) {
    Object value = row.get(key);
    if (!(value instanceof Number number) || number.longValue() <= 0)
      throw invalid("Harbor list number must be positive: " + key);
    return number.longValue();
  }

  private static int positive(FileConfiguration document, String key, int maximum) {
    int value = document.getInt(key);
    if (value <= 0 || value > maximum) throw invalid(key + " must be between 1 and " + maximum);
    return value;
  }

  private static long positiveLong(FileConfiguration document, String key) {
    long value = document.getLong(key);
    if (value <= 0) throw invalid(key + " must be positive");
    return value;
  }

  private static String requiredText(FileConfiguration document, String key) {
    String value = document.getString(key);
    if (value == null || value.isBlank()) throw invalid(key + " is required");
    return value;
  }

  private static Set<String> normalizedSet(FileConfiguration document, String key) {
    Set<String> values = new LinkedHashSet<>();
    for (String value : document.getStringList(key)) {
      if (!value.isBlank()) values.add(value.strip().toUpperCase(Locale.ROOT));
    }
    return Set.copyOf(values);
  }

  private static void requireVersion(FileConfiguration document, String source) {
    int version = document.getInt("config-version", -1);
    if (version != CONFIG_VERSION)
      throw invalid(
          source + " config-version must be " + CONFIG_VERSION + " (was " + version + ")");
  }

  private static YamlConfiguration requiredDocument(
      Map<String, YamlConfiguration> modules, String module) {
    YamlConfiguration document = modules.get(module);
    if (document == null) throw invalid("missing module configuration: " + module);
    return document;
  }

  private static void warnUnknown(
      FileConfiguration document,
      Set<String> allowedLeaves,
      String source,
      Consumer<String> warning) {
    for (String key : document.getKeys(true)) {
      boolean known =
          allowedLeaves.contains(key)
              || allowedLeaves.stream().anyMatch(leaf -> leaf.startsWith(key + "."));
      if (!known) warning.accept(source + " contains unknown key " + key);
    }
  }

  private static boolean isSecret(String key) {
    String normalized = key.toLowerCase(Locale.ROOT);
    return normalized.contains("password")
        || normalized.contains("secret")
        || normalized.contains("token");
  }

  private static List<String> differences(
      FrontierConfiguration current, FrontierConfiguration candidate) {
    if (current.equals(candidate)) return List.of();
    List<String> changed = new ArrayList<>();
    if (!current.global().equals(candidate.global())) changed.add("global.runtime-or-database");
    for (String module : MODULES) {
      if (current.enabled(module) != candidate.enabled(module)) changed.add(module + ".enabled");
    }
    if (changed.isEmpty()) changed.add("module.settings");
    return List.copyOf(changed);
  }

  private static IllegalStateException invalid(String message) {
    return new IllegalStateException("Invalid Frontier configuration: " + message);
  }

  private static Map<String, Set<String>> allowedKeys() {
    Map<String, Set<String>> keys = new LinkedHashMap<>();
    keys.put(
        "global",
        leaves(
            "config-version",
            "database.jdbc-url",
            "database.username",
            "database.password",
            "database.maximum-pool-size",
            "runtime.async-threads",
            "security.command-rate-limit",
            "security.command-rate-window-seconds"));
    keys.put(
        "settlements",
        leaves(
            "config-version",
            "enabled",
            "simulation.check-seconds",
            "simulation.maximum-per-cycle",
            "protection.cache-refresh-seconds",
            "founding.fee-minor",
            "founding.minimum-founders",
            "founding.expedition-lifetime-hours",
            "founding.reservation-lifetime-minutes",
            "founding.minimum-core-distance",
            "founding.harbor-exclusion-radius",
            "founding.materials.stone-bricks",
            "founding.materials.oak-logs",
            "founding.materials.bells",
            "founding.allowed-environments",
            "membership.mayor-inactivity-days",
            "membership.settlement-inactivity-days",
            "membership.disband-confirmation-seconds",
            "membership.disband-request-minutes"));
    keys.put(
        "influence",
        leaves(
            "config-version",
            "enabled",
            "simulation.cycle-seconds",
            "simulation.maximum-settlements-per-cycle",
            "simulation.contested-threshold",
            "simulation.required-lead-cycles"));
    keys.put(
        "districts",
        leaves(
            "config-version",
            "enabled",
            "balance.minimum-building-integrity",
            "balance.minimum-infrastructure-integrity",
            "balance.diminishing-return-percent",
            "balance.maximum-building-contributions",
            "balance.adjacency-bonus-percent",
            "balance.maximum-adjacency-bonuses",
            "balance.adjacency-distance-blocks",
            "balance.over-specialization-threshold",
            "balance.over-specialization-penalty-percent",
            "balance.maximum-effective-bonus-percent",
            "balance.industrial-maintenance-penalty-percent",
            "balance.military-wage-penalty-percent",
            "balance.commercial-market-orders-per-building",
            "balance.logistics-warehouse-capacity-percent"));
    keys.put(
        "economy",
        leaves(
            "config-version",
            "enabled",
            "market.cycle-seconds",
            "market.maximum-trades-per-cycle",
            "production.cycle-seconds",
            "production.maximum-orders-per-cycle",
            "logistics.cycle-seconds",
            "logistics.maximum-shipments-per-cycle",
            "harbor.refresh-seconds",
            "harbor.daily-budget-minor",
            "harbor.maximum-daily-source-minor",
            "harbor.maximum-player-reward-per-day-minor",
            "harbor.initial-capital-minor",
            "harbor.allowed-commodities",
            "harbor.initial-stock.minecraft:bread",
            "harbor.initial-stock.minecraft:wheat",
            "harbor.initial-stock.minecraft:oak_log",
            "harbor.initial-stock.minecraft:cobblestone",
            "harbor.initial-stock.minecraft:iron_ingot",
            "harbor.starter-jobs",
            "harbor.buy-orders",
            "harbor.sell-orders"));
    keys.put(
        "warfare",
        leaves(
            "config-version",
            "enabled",
            "campaign.preparation-hours",
            "campaign.maximum-duration-days",
            "campaign.declaration-cost-minor",
            "breach.window-hours",
            "breach.base-points",
            "breach.maximum-points",
            "lifecycle.cycle-seconds",
            "lifecycle.maximum-transitions-per-cycle",
            "objectives.cycle-seconds"));
    keys.put(
        "repairs",
        leaves(
            "config-version",
            "enabled",
            "tasks.cycle-seconds",
            "tasks.lease-seconds",
            "tasks.maximum-per-cycle",
            "tasks.archive-after-hours",
            "placement.unsafe-radius",
            "damage-recovery.cycle-seconds",
            "damage-recovery.maximum-per-cycle"));
    keys.put(
        "population",
        leaves(
            "config-version",
            "enabled",
            "presentation.materialization-cycle-seconds",
            "presentation.maximum-visible-per-settlement"));
    keys.put(
        "kingdoms",
        leaves(
            "config-version",
            "enabled",
            "simulation.cycle-seconds",
            "simulation.maximum-per-cycle"));
    keys.put(
        "web",
        leaves(
            "config-version",
            "enabled",
            "server.bind-address",
            "server.port",
            "server.public-url"));
    keys.put(
        "history",
        leaves(
            "config-version",
            "enabled",
            "outbox.cycle-seconds",
            "outbox.maximum-events-per-cycle"));
    keys.put(
        "world-simulation",
        leaves(
            "config-version",
            "enabled",
            "simulation.cycle-seconds",
            "simulation.maximum-cities-per-cycle"));
    for (String module : MODULES) keys.putIfAbsent(module, leaves("config-version", "enabled"));
    return Map.copyOf(keys);
  }

  private static Set<String> leaves(String... values) {
    return Set.copyOf(new LinkedHashSet<>(List.of(values)));
  }

  public enum ReloadClass {
    LIVE,
    MODULE_RESTART,
    SERVER_RESTART
  }

  public record ReloadReport(ReloadClass classification, List<String> changedKeys) {
    public ReloadReport {
      changedKeys = List.copyOf(changedKeys);
    }
  }
}
