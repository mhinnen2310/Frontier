package nl.frontier.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigRegistryTest {
  @Test
  void everyPackagedKeyParsesIntoImmutableTypedConfiguration() {
    var warnings = new ArrayList<String>();
    FrontierConfiguration config =
        ConfigRegistry.parse(resource("config.yml"), moduleResources(), warnings::add);

    assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
    assertEquals(10, config.global().database().maximumPoolSize());
    assertEquals(75, config.influence().contestedThreshold());
    assertEquals(300, config.economy().harborRefreshSeconds());
    assertEquals(96.0, config.repairs().unsafeRadius());
    assertEquals(2_500, config.settlements().foundingFeeMinor());
    assertEquals(256, config.settlements().harborExclusionRadius());
    assertEquals(java.util.Set.of("NORMAL"), config.settlements().allowedEnvironments());
    assertEquals(7, config.settlements().mayorInactivityDays());
    assertEquals(30, config.settlements().settlementInactivityDays());
    assertEquals(40, config.districts().balance().minimumBuildingIntegrity());
    assertEquals(3, config.districts().balance().maximumBuildingContributions());
    assertEquals(25, config.districts().balance().logisticsWarehouseCapacityPercent());
    assertEquals(32_768, config.buildings().validation().maximumVolume());
    assertEquals(60, config.buildings().validation().minimumRoofCoveragePercent());
    assertTrue(config.enabled("settlements"));
    assertFalse(config.enabled("waypoints"));
    assertFalse(config.enabled("cartography"));
    assertFalse(config.enabled("map-walls"));
    assertFalse(config.enabled("web"));
  }

  @Test
  void invalidBoundsAndUnknownKeysAreExplicitWithoutLoggingValues() {
    var invalidModules = moduleResources();
    invalidModules.get("warfare").set("breach.base-points", 4_000);
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> ConfigRegistry.parse(resource("config.yml"), invalidModules, ignored -> {}));
    assertTrue(failure.getMessage().contains("cannot exceed"));

    var modules = moduleResources();
    modules.get("economy").set("secret-example", "must-not-appear");
    var warnings = new ArrayList<String>();
    ConfigRegistry.parse(resource("config.yml"), modules, warnings::add);
    assertEquals(1, warnings.size());
    assertTrue(warnings.getFirst().contains("secret-example"));
    assertFalse(warnings.getFirst().contains("must-not-appear"));

    var invalidFounding = moduleResources();
    invalidFounding.get("settlements").set("founding.harbor-exclusion-radius", 64);
    var foundingFailure =
        assertThrows(
            IllegalStateException.class,
            () -> ConfigRegistry.parse(resource("config.yml"), invalidFounding, ignored -> {}));
    assertTrue(foundingFailure.getMessage().contains("cannot be smaller"));

    var invalidMembership = moduleResources();
    invalidMembership.get("settlements").set("membership.settlement-inactivity-days", 3);
    var membershipFailure =
        assertThrows(
            IllegalStateException.class,
            () -> ConfigRegistry.parse(resource("config.yml"), invalidMembership, ignored -> {}));
    assertTrue(membershipFailure.getMessage().contains("mayor-inactivity-days"));

    var invalidDistricts = moduleResources();
    invalidDistricts.get("districts").set("balance.maximum-effective-bonus-percent", 101);
    var districtFailure =
        assertThrows(
            IllegalStateException.class,
            () -> ConfigRegistry.parse(resource("config.yml"), invalidDistricts, ignored -> {}));
    assertTrue(districtFailure.getMessage().contains("maximum-effective-bonus-percent"));

    var invalidBuildings = moduleResources();
    invalidBuildings.get("buildings").set("validation.minimum-wall-coverage-percent", 101);
    var buildingFailure =
        assertThrows(
            IllegalStateException.class,
            () -> ConfigRegistry.parse(resource("config.yml"), invalidBuildings, ignored -> {}));
    assertTrue(buildingFailure.getMessage().contains("minimum-wall-coverage-percent"));
  }

  @Test
  void enabledModulesCannotLoseRequiredDependencies() {
    var modules = moduleResources();
    modules.get("settlements").set("enabled", false);
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> ConfigRegistry.parse(resource("config.yml"), modules, ignored -> {}));
    assertTrue(failure.getMessage().contains("requires enabled module settlements"));
  }

  private static Map<String, YamlConfiguration> moduleResources() {
    Map<String, YamlConfiguration> modules = new LinkedHashMap<>();
    for (String module : ConfigRegistry.MODULES)
      modules.put(module, resource("modules/" + module + ".yml"));
    return modules;
  }

  private static YamlConfiguration resource(String name) {
    var input = ConfigRegistryTest.class.getClassLoader().getResourceAsStream(name);
    if (input == null) throw new IllegalStateException("missing test resource " + name);
    return YamlConfiguration.loadConfiguration(
        new InputStreamReader(input, StandardCharsets.UTF_8));
  }
}
