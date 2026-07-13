package nl.frontier.city;

import java.util.Set;

public final class DistrictBuildingPolicy {
  private DistrictBuildingPolicy() {}

  public static boolean compatible(DistrictType district, BuildingType building) {
    return switch (building) {
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
    };
  }
}
