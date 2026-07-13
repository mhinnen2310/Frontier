package nl.frontier.city;

import java.util.List;

public record BuildingValidationResult(
    BuildingType type, boolean valid, List<String> violations, BuildingInspection inspection) {
  public BuildingValidationResult {
    violations = List.copyOf(violations);
  }
}
