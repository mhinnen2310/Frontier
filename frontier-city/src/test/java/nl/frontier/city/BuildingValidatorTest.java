package nl.frontier.city;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildingValidatorTest {
  private final BuildingValidator validator = new BuildingValidator();

  @Test
  void everySupportedBuildingTypeAcceptsACompletePhysicalSurvey() {
    for (BuildingType type : BuildingType.values()) {
      assertTrue(
          validator
              .validate(
                  type,
                  completeSurvey(),
                  new BuildingValidator.ValidationContext(false, true, true))
              .valid(),
          type.name());
    }
  }

  @Test
  void validatorsRejectMissingPhysicalRequirements() {
    BuildingSurvey empty =
        new BuildingSurvey(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    for (BuildingType type : BuildingType.values()) {
      var result =
          validator.validate(
              type, empty, new BuildingValidator.ValidationContext(false, true, false));
      assertFalse(result.valid(), type.name());
      assertFalse(result.violations().isEmpty(), type.name());
    }
  }

  @Test
  void overlapAndWrongDistrictAlwaysBlockRegistration() {
    var result =
        validator.validate(
            BuildingType.HOUSING,
            completeSurvey(),
            new BuildingValidator.ValidationContext(true, false, true));
    assertFalse(result.valid());
    assertTrue(result.violations().stream().anyMatch(message -> message.contains("overlaps")));
    assertTrue(result.violations().stream().anyMatch(message -> message.contains("district")));
  }

  private static BuildingSurvey completeSurvey() {
    return new BuildingSurvey(
        9, 8, 9, 300, 40, 81, 2, 4, 4, 6, 32, 4, 16, 4, 4, 2, Map.of("minecraft:stone", 300));
  }
}
