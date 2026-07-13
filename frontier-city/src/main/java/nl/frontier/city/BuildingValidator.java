package nl.frontier.city;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BuildingValidator {
  private final BuildingValidationPolicy policy;

  public BuildingValidator(BuildingValidationPolicy policy) {
    this.policy = policy;
  }

  public BuildingDefinition definition(BuildingType type) {
    List<ValidationRule> rules = new ArrayList<>(commonRules());
    BuildingRequirements requirements = policy.requirements(type);
    rules.add(
        minimum(
            requirements.minimumWidth(),
            requirements.minimumHeight(),
            requirements.minimumDepth()));
    if (requirements.enclosureRequired()) rules.addAll(enclosure());
    if (requirements.entranceRequired()) rules.add(entrance());
    if (requirements.roadRequired()) rules.add(road());
    requirements
        .functionalMinimums()
        .forEach((feature, minimum) -> rules.add(feature(feature, minimum)));
    return new BuildingDefinition(type, rules);
  }

  public BuildingValidationResult validate(
      BuildingType type, BuildingSurvey survey, BuildingValidationContext context) {
    BuildingInspection inspection = BuildingInspection.inspect(survey, policy);
    List<String> violations =
        definition(type).rules().stream()
            .map(rule -> rule.validate(inspection, context))
            .flatMap(Optional::stream)
            .toList();
    return new BuildingValidationResult(type, violations.isEmpty(), violations, inspection);
  }

  private List<ValidationRule> commonRules() {
    return List.of(
        (inspection, context) ->
            context.controlledBySettlement()
                ? Optional.empty()
                : Optional.of("must be wholly inside controlled settlement claims"),
        (inspection, context) ->
            context.overlap()
                ? Optional.of("overlaps an existing registered building")
                : Optional.empty(),
        (inspection, context) ->
            context.districtCompatible()
                ? Optional.empty()
                : Optional.of("is incompatible with the selected district"),
        (inspection, context) ->
            inspection.withinScanBounds()
                ? Optional.empty()
                : Optional.of("exceeds configured building scan bounds"),
        (inspection, context) ->
            inspection.survey().nonAirBlocks() >= policy.minimumStructuralBlocks()
                ? Optional.empty()
                : Optional.of(
                    "requires at least "
                        + policy.minimumStructuralBlocks()
                        + " structural blocks (found "
                        + inspection.survey().nonAirBlocks()
                        + ")"));
  }

  private List<ValidationRule> enclosure() {
    return List.of(
        (inspection, context) ->
            inspection.floorCoveragePercent() >= policy.minimumFloorCoveragePercent()
                ? Optional.empty()
                : Optional.of(
                    "floor coverage must be at least "
                        + policy.minimumFloorCoveragePercent()
                        + "% (found "
                        + inspection.floorCoveragePercent()
                        + "%)"),
        (inspection, context) ->
            inspection.wallCoveragePercent() >= policy.minimumWallCoveragePercent()
                ? Optional.empty()
                : Optional.of(
                    "wall coverage must be at least "
                        + policy.minimumWallCoveragePercent()
                        + "% (found "
                        + inspection.wallCoveragePercent()
                        + "%)"),
        (inspection, context) ->
            inspection.roofCoveragePercent() >= policy.minimumRoofCoveragePercent()
                ? Optional.empty()
                : Optional.of(
                    "roof coverage must be at least "
                        + policy.minimumRoofCoveragePercent()
                        + "% (found "
                        + inspection.roofCoveragePercent()
                        + "%)"));
  }

  private static ValidationRule minimum(int width, int height, int depth) {
    return (inspection, context) -> {
      BuildingSurvey survey = inspection.survey();
      return survey.width() >= width && survey.height() >= height && survey.depth() >= depth
          ? Optional.empty()
          : Optional.of(
              "minimum dimensions are "
                  + width
                  + "x"
                  + height
                  + "x"
                  + depth
                  + " (found "
                  + survey.width()
                  + "x"
                  + survey.height()
                  + "x"
                  + survey.depth()
                  + ")");
    };
  }

  private static ValidationRule entrance() {
    return (inspection, context) ->
        inspection.survey().entranceBlocks() >= 1
            ? Optional.empty()
            : Optional.of("requires an entrance (found 0)");
  }

  private static ValidationRule road() {
    return (inspection, context) ->
        context.roadConnected()
            ? Optional.empty()
            : Optional.of("requires a physical perimeter-road connection (found 0)");
  }

  private static ValidationRule feature(BuildingFeature feature, int minimum) {
    return (inspection, context) -> {
      int actual = feature.count(inspection.survey());
      return actual >= minimum
          ? Optional.empty()
          : Optional.of(
              "requires " + minimum + " " + feature.displayName() + " (found " + actual + ")");
    };
  }
}
