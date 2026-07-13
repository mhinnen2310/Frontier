package nl.frontier.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DistrictBuildingPolicyTest {
  @Test
  void compatibilityMatrixIsExplicitAndComplete() {
    assertCompatible(
        BuildingType.WAREHOUSE,
        DistrictType.INDUSTRIAL,
        DistrictType.COMMERCIAL,
        DistrictType.LOGISTICS,
        DistrictType.HARBOR,
        DistrictType.MINING,
        DistrictType.FORESTRY);
    assertCompatible(BuildingType.HOUSING, DistrictType.RESIDENTIAL);
    assertCompatible(BuildingType.FARM, DistrictType.AGRICULTURAL);
    assertCompatible(
        BuildingType.BUILDER_GUILD,
        DistrictType.INDUSTRIAL,
        DistrictType.GOVERNMENT,
        DistrictType.RESEARCH);
    assertCompatible(
        BuildingType.MARKET, DistrictType.COMMERCIAL, DistrictType.HARBOR, DistrictType.CULTURE);
    assertCompatible(BuildingType.BARRACKS, DistrictType.MILITARY);
  }

  private static void assertCompatible(BuildingType building, DistrictType... allowed) {
    Set<DistrictType> expected = Set.of(allowed);
    for (DistrictType district : DistrictType.values())
      assertEquals(
          expected.contains(district),
          DistrictBuildingPolicy.compatible(district, building),
          district + " / " + building);
    assertTrue(
        expected.stream().allMatch(type -> DistrictBuildingPolicy.compatible(type, building)));
    assertFalse(expected.isEmpty());
  }
}
