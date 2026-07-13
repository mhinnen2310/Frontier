package nl.frontier.city;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Hard scan bounds, shared thresholds and type-specific physical requirements. */
public record BuildingValidationPolicy(
    int maximumWidth,
    int maximumHeight,
    int maximumDepth,
    int maximumVolume,
    int minimumStructuralBlocks,
    int minimumFloorCoveragePercent,
    int minimumWallCoveragePercent,
    int minimumRoofCoveragePercent,
    Map<BuildingType, BuildingRequirements> requirements) {
  public BuildingValidationPolicy {
    requirements = Map.copyOf(requirements);
    if (!requirements.keySet().containsAll(Set.of(BuildingType.values())))
      throw new IllegalArgumentException("every building type requires a validation profile");
  }

  public BuildingValidationPolicy(
      int maximumWidth,
      int maximumHeight,
      int maximumDepth,
      int maximumVolume,
      int minimumStructuralBlocks,
      int minimumFloorCoveragePercent,
      int minimumWallCoveragePercent,
      int minimumRoofCoveragePercent) {
    this(
        maximumWidth,
        maximumHeight,
        maximumDepth,
        maximumVolume,
        minimumStructuralBlocks,
        minimumFloorCoveragePercent,
        minimumWallCoveragePercent,
        minimumRoofCoveragePercent,
        defaultRequirements());
  }

  public BuildingRequirements requirements(BuildingType type) {
    return requirements.get(type);
  }

  public static Map<BuildingType, BuildingRequirements> defaultRequirements() {
    EnumMap<BuildingType, BuildingRequirements> values = new EnumMap<>(BuildingType.class);
    values.put(
        BuildingType.TOWN_HALL,
        profile(
            7,
            5,
            7,
            true,
            true,
            true,
            BuildingFeature.BELL,
            1,
            BuildingFeature.LECTERN,
            1,
            BuildingFeature.SEATING,
            4));
    values.put(
        BuildingType.WAREHOUSE, profile(5, 4, 5, true, true, true, BuildingFeature.STORAGE, 2));
    values.put(
        BuildingType.HOUSING,
        profile(
            3,
            3,
            3,
            true,
            true,
            false,
            BuildingFeature.BED,
            1,
            BuildingFeature.INTERIOR_AIR,
            6,
            BuildingFeature.LIGHT,
            1));
    values.put(
        BuildingType.FARM,
        profile(
            1,
            1,
            1,
            false,
            false,
            false,
            BuildingFeature.FARMLAND,
            16,
            BuildingFeature.WATER,
            1,
            BuildingFeature.CROP,
            8));
    values.put(
        BuildingType.BUILDER_GUILD,
        profile(
            7, 4, 7, true, true, false, BuildingFeature.CRAFTING, 2, BuildingFeature.STORAGE, 2));
    values.put(BuildingType.MARKET, profile(1, 1, 1, false, true, true, BuildingFeature.STALL, 3));
    values.put(
        BuildingType.BARRACKS,
        profile(5, 4, 5, true, true, false, BuildingFeature.BED, 4, BuildingFeature.STORAGE, 2));
    values.put(
        BuildingType.WORKSHOP,
        profile(
            5,
            4,
            5,
            true,
            true,
            true,
            BuildingFeature.CRAFTING,
            2,
            BuildingFeature.FURNACE,
            1,
            BuildingFeature.STORAGE,
            1));
    values.put(
        BuildingType.MINE_ENTRANCE,
        profile(3, 3, 3, false, true, true, BuildingFeature.RAIL, 4, BuildingFeature.LIGHT, 2));
    values.put(
        BuildingType.WATCHTOWER,
        profile(3, 8, 3, true, true, false, BuildingFeature.LADDER, 4, BuildingFeature.LIGHT, 1));
    return Map.copyOf(values);
  }

  private static BuildingRequirements profile(
      int width,
      int height,
      int depth,
      boolean enclosure,
      boolean entrance,
      boolean road,
      Object... featurePairs) {
    EnumMap<BuildingFeature, Integer> features = new EnumMap<>(BuildingFeature.class);
    for (int index = 0; index < featurePairs.length; index += 2)
      features.put((BuildingFeature) featurePairs[index], (Integer) featurePairs[index + 1]);
    return new BuildingRequirements(width, height, depth, enclosure, entrance, road, features);
  }
}
