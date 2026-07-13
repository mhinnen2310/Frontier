package nl.frontier.economy;

import java.util.ArrayList;
import java.util.List;

public final class InfrastructureValidator {
  private final InfrastructureValidationPolicy policy;

  public InfrastructureValidator() {
    this(InfrastructureValidationPolicy.defaults());
  }

  public InfrastructureValidator(InfrastructureValidationPolicy policy) {
    this.policy = policy;
  }

  public Validation validate(InfrastructureType type, InfrastructureSurvey survey) {
    List<String> violations = new ArrayList<>();
    if (survey.samples() < 2) violations.add("route requires at least two samples");
    if (survey.samples() - 1 > policy.maximumLength())
      violations.add("route exceeds the maximum physical length");
    if (!survey.endpointsConnected())
      violations.add("route does not connect both registered endpoints");
    if (survey.connectivity() * 100 < policy.minimumConnectivityPercent())
      violations.add(
          "route connectivity must be at least " + policy.minimumConnectivityPercent() + "%");
    if (survey.minimumWidth() < policy.minimumWidth())
      violations.add("road width must be at least " + policy.minimumWidth() + " blocks");
    if (survey.surfaceQuality() < policy.minimumSurfaceQuality())
      violations.add("surface quality must be at least " + policy.minimumSurfaceQuality());
    if (survey.maximumSlope() > policy.maximumSlope()) violations.add("route slope is too steep");
    if (survey.brokenSegments() * 100 > survey.samples() * policy.maximumBrokenPercent())
      violations.add("route has too many broken segments");
    if (type == InfrastructureType.BRIDGE && survey.bridgeSamples() < policy.minimumBridgeSamples())
      violations.add("bridge route requires a physical bridge span");
    if (type == InfrastructureType.TUNNEL && survey.tunnelSamples() < policy.minimumTunnelSamples())
      violations.add("tunnel route requires enclosed tunnel segments");
    if (type == InfrastructureType.GATE && survey.gateSamples() < policy.minimumGateSamples())
      violations.add("gate route requires a physical gate segment");
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
