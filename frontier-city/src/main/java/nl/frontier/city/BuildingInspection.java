package nl.frontier.city;

public record BuildingInspection(
    BuildingSurvey survey,
    long volume,
    int floorCoveragePercent,
    int wallCoveragePercent,
    int roofCoveragePercent,
    boolean withinScanBounds) {
  public static BuildingInspection inspect(BuildingSurvey survey, BuildingValidationPolicy policy) {
    long volume = (long) survey.width() * survey.height() * survey.depth();
    int footprint = Math.max(1, survey.width() * survey.depth());
    int wallSurface =
        Math.max(
            1, (2 * survey.width() + 2 * survey.depth() - 4) * Math.max(1, survey.height() - 2));
    return new BuildingInspection(
        survey,
        volume,
        percent(survey.floorBlocks(), footprint),
        percent(survey.wallBlocks(), wallSurface),
        percent(survey.roofBlocks(), footprint),
        survey.width() > 0
            && survey.height() > 0
            && survey.depth() > 0
            && survey.width() <= policy.maximumWidth()
            && survey.height() <= policy.maximumHeight()
            && survey.depth() <= policy.maximumDepth()
            && volume <= policy.maximumVolume());
  }

  private static int percent(int value, int total) {
    return Math.min(100, Math.max(0, value * 100 / total));
  }
}
