package nl.frontier.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildingValidatorTest {
  private final BuildingValidator validator =
      new BuildingValidator(new BuildingValidationPolicy(64, 64, 64, 32_768, 8, 60, 50, 60));

  @Test
  void everySupportedBuildingTypeAcceptsACompletePhysicalSurvey() {
    for (BuildingType type : BuildingType.values()) {
      assertTrue(
          validator
              .validate(
                  type, completeSurvey(), new BuildingValidationContext(true, false, true, true))
              .valid(),
          type.name());
    }
  }

  @Test
  void validatorsRejectMissingPhysicalRequirements() {
    BuildingSurvey empty =
        new BuildingSurvey(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    for (BuildingType type : BuildingType.values()) {
      var result =
          validator.validate(type, empty, new BuildingValidationContext(true, false, true, false));
      assertFalse(result.valid(), type.name());
      assertFalse(result.violations().isEmpty(), type.name());
    }
  }

  @Test
  void playerFacingViolationsShowRequiredAndObservedFunctionalCounts() {
    var result =
        validator.validate(
            BuildingType.TOWN_HALL,
            new BuildingSurvey(
                9,
                8,
                9,
                300,
                40,
                81,
                192,
                81,
                2,
                4,
                4,
                6,
                32,
                4,
                16,
                4,
                4,
                2,
                Map.of("minecraft:bell", 1)),
            new BuildingValidationContext(true, false, true, true));
    assertFalse(result.valid());
    assertTrue(
        result.violations().stream()
            .anyMatch(message -> message.equals("requires 1 lecterns (found 0)")));
    assertTrue(
        result.violations().stream()
            .anyMatch(message -> message.equals("requires 4 seats (found 0)")));
  }

  @Test
  void flexibleMaterialGroupsDoNotRequireAnExactSchematic() {
    BuildingSurvey survey =
        new BuildingSurvey(
            9,
            8,
            9,
            300,
            40,
            81,
            192,
            81,
            2,
            4,
            4,
            6,
            32,
            4,
            16,
            4,
            4,
            2,
            Map.of(
                "minecraft:birch_stairs", 5,
                "minecraft:smoker", 2,
                "minecraft:powered_rail", 6,
                "minecraft:scaffolding", 8));
    assertEquals(5, BuildingFeature.SEATING.count(survey));
    assertEquals(2, BuildingFeature.FURNACE.count(survey));
    assertEquals(6, BuildingFeature.RAIL.count(survey));
    assertEquals(8, BuildingFeature.LADDER.count(survey));
  }

  @Test
  void overlapAndWrongDistrictAlwaysBlockRegistration() {
    var result =
        validator.validate(
            BuildingType.HOUSING,
            completeSurvey(),
            new BuildingValidationContext(true, true, false, true));
    assertFalse(result.valid());
    assertTrue(result.violations().stream().anyMatch(message -> message.contains("overlaps")));
    assertTrue(result.violations().stream().anyMatch(message -> message.contains("district")));
  }

  @Test
  void sharedRulesRejectUncontrolledAndOversizedSelectionsWithInspectionDetails() {
    BuildingSurvey oversized =
        new BuildingSurvey(
            65, 4, 5, 1_000, 10, 325, 540, 325, 1, 1, 2, 4, 16, 1, 8, 2, 3, 1, Map.of());
    var result =
        validator.validate(
            BuildingType.WAREHOUSE,
            oversized,
            new BuildingValidationContext(false, false, true, true));
    assertFalse(result.valid());
    assertFalse(result.inspection().withinScanBounds());
    assertTrue(result.violations().stream().anyMatch(value -> value.contains("controlled")));
    assertTrue(result.violations().stream().anyMatch(value -> value.contains("scan bounds")));
    assertFalse(validator.definition(BuildingType.WAREHOUSE).rules().isEmpty());
  }

  private static BuildingSurvey completeSurvey() {
    return new BuildingSurvey(
        9,
        8,
        9,
        300,
        40,
        81,
        192,
        81,
        2,
        4,
        4,
        6,
        32,
        4,
        16,
        4,
        4,
        2,
        Map.of(
            "minecraft:stone", 280,
            "minecraft:bell", 1,
            "minecraft:lectern", 1,
            "minecraft:oak_stairs", 4,
            "minecraft:furnace", 1,
            "minecraft:rail", 4,
            "minecraft:ladder", 4));
  }
}
