package nl.frontier.economy;

import java.util.ArrayList;
import java.util.List;

public final class InfrastructureValidator {
  public Validation validate(InfrastructureType type, InfrastructureSurvey survey) {
    List<String> violations = new ArrayList<>();
    if (survey.samples() < 2) violations.add("route requires at least two samples");
    if (survey.connectivity() < 0.85) violations.add("route connectivity must be at least 85%");
    if (survey.minimumWidth() < 2) violations.add("road width must be at least two blocks");
    if (survey.surfaceQuality() < 40) violations.add("surface quality must be at least 40");
    if (survey.maximumSlope() > 1.5) violations.add("route slope is too steep");
    if (survey.brokenSegments() > Math.max(1, survey.samples() / 10))
      violations.add("route has too many broken segments");
    if (type == InfrastructureType.BRIDGE && survey.bridgeSamples() < 2)
      violations.add("bridge route requires a physical bridge span");
    if (type == InfrastructureType.TUNNEL && survey.tunnelSamples() < 2)
      violations.add("tunnel route requires enclosed tunnel segments");
    if (survey.destroyedBridges() > 0) violations.add("route contains destroyed bridge spans");
    int health =
        Math.max(
            0,
            Math.min(
                100,
                (int) Math.round(survey.connectivity() * 50)
                    + survey.surfaceQuality() / 2
                    - survey.brokenSegments() * 5));
    long capacity = Math.max(1, (long) survey.minimumWidth() * Math.max(10, health) * 10L);
    return new Validation(
        type, violations.isEmpty(), health, capacity, List.copyOf(violations), survey);
  }

  public record Validation(
      InfrastructureType type,
      boolean valid,
      int health,
      long capacity,
      List<String> violations,
      InfrastructureSurvey survey) {}
}
