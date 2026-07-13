package nl.frontier.city;

import java.util.List;

public record BuildingDefinition(BuildingType type, List<ValidationRule> rules) {
  public BuildingDefinition {
    rules = List.copyOf(rules);
  }
}
