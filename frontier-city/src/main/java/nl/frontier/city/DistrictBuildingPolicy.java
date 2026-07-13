package nl.frontier.city;

import java.util.Set;

public final class DistrictBuildingPolicy {
  private DistrictBuildingPolicy() {}

  public static boolean compatible(DistrictType district, BuildingType building) {
    return switch (building) {
      case TOWN_HALL -> Set.of(DistrictType.GOVERNMENT, DistrictType.CULTURE).contains(district);
      case WAREHOUSE ->
          Set.of(
                  DistrictType.INDUSTRIAL,
                  DistrictType.COMMERCIAL,
                  DistrictType.LOGISTICS,
                  DistrictType.HARBOR,
                  DistrictType.MINING,
                  DistrictType.FORESTRY)
              .contains(district);
      case HOUSING -> district == DistrictType.RESIDENTIAL;
      case FARM -> district == DistrictType.AGRICULTURAL;
      case BUILDER_GUILD ->
          Set.of(DistrictType.INDUSTRIAL, DistrictType.GOVERNMENT, DistrictType.RESEARCH)
              .contains(district);
      case MARKET ->
          Set.of(DistrictType.COMMERCIAL, DistrictType.HARBOR, DistrictType.CULTURE)
              .contains(district);
      case BARRACKS -> district == DistrictType.MILITARY;
      case WORKSHOP ->
          Set.of(DistrictType.INDUSTRIAL, DistrictType.MINING, DistrictType.FORESTRY)
              .contains(district);
      case MINE_ENTRANCE -> district == DistrictType.MINING;
      case WATCHTOWER ->
          Set.of(DistrictType.MILITARY, DistrictType.GOVERNMENT, DistrictType.HARBOR)
              .contains(district);
    };
  }
}
