package nl.frontier.city;

import java.util.ArrayList;
import java.util.List;

public final class BuildingValidator {
  public ValidationResult validate(
      BuildingType type, BuildingSurvey survey, ValidationContext context) {
    List<String> violations = new ArrayList<>();
    if (context.overlap()) violations.add("building overlaps an existing registered building");
    if (!context.districtCompatible())
      violations.add("building type is incompatible with district");
    switch (type) {
      case WAREHOUSE -> warehouse(survey, context, violations);
      case HOUSING -> housing(survey, violations);
      case FARM -> farm(survey, violations);
      case BUILDER_GUILD -> builderGuild(survey, violations);
      case MARKET -> market(survey, violations);
      case BARRACKS -> barracks(survey, violations);
    }
    return new ValidationResult(type, violations.isEmpty(), List.copyOf(violations), survey);
  }

  private static void warehouse(
      BuildingSurvey survey, ValidationContext context, List<String> violations) {
    minimum(survey, 5, 4, 5, violations);
    require(
        survey.storageBlocks() >= 2, "warehouse requires at least two storage blocks", violations);
    require(survey.entranceBlocks() >= 1, "warehouse requires an entrance", violations);
    require(context.roadConnected(), "warehouse requires a physical road connection", violations);
  }

  private static void housing(BuildingSurvey survey, List<String> violations) {
    minimum(survey, 3, 3, 3, violations);
    require(survey.bedBlocks() >= 1, "housing requires a bed", violations);
    require(
        survey.interiorAirBlocks() >= 6, "housing requires enclosed interior space", violations);
    require(
        survey.roofCoverage() >= 0.60, "housing requires at least 60% roof coverage", violations);
    require(survey.entranceBlocks() >= 1, "housing requires an entrance", violations);
    require(survey.lightBlocks() >= 1, "housing requires a light source", violations);
  }

  private static void farm(BuildingSurvey survey, List<String> violations) {
    require(survey.farmlandBlocks() >= 16, "farm requires at least 16 farmland blocks", violations);
    require(survey.waterBlocks() >= 1, "farm requires water", violations);
    require(survey.cropBlocks() >= 8, "farm requires at least 8 planted crops", violations);
  }

  private static void builderGuild(BuildingSurvey survey, List<String> violations) {
    minimum(survey, 7, 4, 7, violations);
    require(
        survey.craftingBlocks() >= 2, "builder guild requires two crafting stations", violations);
    require(survey.storageBlocks() >= 2, "builder guild requires two storage blocks", violations);
    require(survey.entranceBlocks() >= 1, "builder guild requires an entrance", violations);
  }

  private static void market(BuildingSurvey survey, List<String> violations) {
    require(survey.stallBlocks() >= 3, "market requires at least three stalls", violations);
    require(survey.entranceBlocks() >= 1, "market requires public access", violations);
  }

  private static void barracks(BuildingSurvey survey, List<String> violations) {
    minimum(survey, 5, 4, 5, violations);
    require(survey.bedBlocks() >= 4, "barracks requires four beds", violations);
    require(survey.storageBlocks() >= 2, "barracks requires equipment storage", violations);
    require(survey.entranceBlocks() >= 1, "barracks requires an entrance", violations);
  }

  private static void minimum(
      BuildingSurvey survey, int width, int height, int depth, List<String> violations) {
    require(
        survey.width() >= width && survey.height() >= height && survey.depth() >= depth,
        "minimum dimensions are " + width + "x" + height + "x" + depth,
        violations);
  }

  private static void require(boolean condition, String violation, List<String> violations) {
    if (!condition) violations.add(violation);
  }

  public record ValidationContext(
      boolean overlap, boolean districtCompatible, boolean roadConnected) {}

  public record ValidationResult(
      BuildingType type, boolean valid, List<String> violations, BuildingSurvey survey) {}
}
