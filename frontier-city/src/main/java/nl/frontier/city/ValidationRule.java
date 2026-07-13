package nl.frontier.city;

import java.util.Optional;

@FunctionalInterface
public interface ValidationRule {
  Optional<String> validate(BuildingInspection inspection, BuildingValidationContext context);
}
