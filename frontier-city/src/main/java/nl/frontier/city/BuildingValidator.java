package nl.frontier.city;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class BuildingValidator {
  private final BuildingValidationPolicy policy;

  public BuildingValidator(BuildingValidationPolicy policy) {
    this.policy = policy;
  }

  public BuildingDefinition definition(BuildingType type) {
    List<ValidationRule> rules = new ArrayList<>(commonRules());
    switch (type) {
      case WAREHOUSE -> {
        rules.add(minimum(5, 4, 5));
        rules.addAll(enclosure());
        rules.add(require(survey -> survey.storageBlocks() >= 2, "requires two storage blocks"));
        rules.add(entrance());
        rules.add(road());
      }
      case HOUSING -> {
        rules.add(minimum(3, 3, 3));
        rules.addAll(enclosure());
        rules.add(require(survey -> survey.bedBlocks() >= 1, "requires a bed"));
        rules.add(
            require(survey -> survey.interiorAirBlocks() >= 6, "requires enclosed interior space"));
        rules.add(entrance());
        rules.add(require(survey -> survey.lightBlocks() >= 1, "requires a light source"));
      }
      case FARM -> {
        rules.add(require(survey -> survey.farmlandBlocks() >= 16, "requires 16 farmland blocks"));
        rules.add(require(survey -> survey.waterBlocks() >= 1, "requires water"));
        rules.add(require(survey -> survey.cropBlocks() >= 8, "requires 8 planted crops"));
      }
      case BUILDER_GUILD -> {
        rules.add(minimum(7, 4, 7));
        rules.addAll(enclosure());
        rules.add(
            require(survey -> survey.craftingBlocks() >= 2, "requires two crafting stations"));
        rules.add(require(survey -> survey.storageBlocks() >= 2, "requires two storage blocks"));
        rules.add(entrance());
      }
      case MARKET -> {
        rules.add(require(survey -> survey.stallBlocks() >= 3, "requires three stalls"));
        rules.add(entrance());
        rules.add(road());
      }
      case BARRACKS -> {
        rules.add(minimum(5, 4, 5));
        rules.addAll(enclosure());
        rules.add(require(survey -> survey.bedBlocks() >= 4, "requires four beds"));
        rules.add(require(survey -> survey.storageBlocks() >= 2, "requires equipment storage"));
        rules.add(entrance());
      }
    }
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
                        + " structural blocks"));
  }

  private List<ValidationRule> enclosure() {
    return List.of(
        (inspection, context) ->
            inspection.floorCoveragePercent() >= policy.minimumFloorCoveragePercent()
                ? Optional.empty()
                : Optional.of(
                    "floor coverage must be at least "
                        + policy.minimumFloorCoveragePercent()
                        + "%"),
        (inspection, context) ->
            inspection.wallCoveragePercent() >= policy.minimumWallCoveragePercent()
                ? Optional.empty()
                : Optional.of(
                    "wall coverage must be at least " + policy.minimumWallCoveragePercent() + "%"),
        (inspection, context) ->
            inspection.roofCoveragePercent() >= policy.minimumRoofCoveragePercent()
                ? Optional.empty()
                : Optional.of(
                    "roof coverage must be at least " + policy.minimumRoofCoveragePercent() + "%"));
  }

  private static ValidationRule minimum(int width, int height, int depth) {
    return (inspection, context) -> {
      BuildingSurvey survey = inspection.survey();
      return survey.width() >= width && survey.height() >= height && survey.depth() >= depth
          ? Optional.empty()
          : Optional.of("minimum dimensions are " + width + "x" + height + "x" + depth);
    };
  }

  private static ValidationRule require(Predicate<BuildingSurvey> predicate, String failure) {
    return (inspection, context) ->
        predicate.test(inspection.survey()) ? Optional.empty() : Optional.of(failure);
  }

  private static ValidationRule entrance() {
    return require(survey -> survey.entranceBlocks() >= 1, "requires an entrance");
  }

  private static ValidationRule road() {
    return (inspection, context) ->
        context.roadConnected()
            ? Optional.empty()
            : Optional.of("requires a physical road connection");
  }
}
