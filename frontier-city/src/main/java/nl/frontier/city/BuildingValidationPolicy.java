package nl.frontier.city;

/** Hard scan bounds and shared physical thresholds for building inspection. */
public record BuildingValidationPolicy(
    int maximumWidth,
    int maximumHeight,
    int maximumDepth,
    int maximumVolume,
    int minimumStructuralBlocks,
    int minimumFloorCoveragePercent,
    int minimumWallCoveragePercent,
    int minimumRoofCoveragePercent) {}
