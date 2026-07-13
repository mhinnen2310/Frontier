package nl.frontier.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DistrictTypeTest {
  @Test
  void allElevenDistrictTypesExposeBoundedGameplayBonuses() {
    assertEquals(11, DistrictType.values().length);
    for (DistrictType type : DistrictType.values()) {
      var bonuses = type.bonuses();
      assertTrue(bonuses.production() >= 0 && bonuses.production() <= 20, type.name());
      assertTrue(bonuses.housing() >= 0 && bonuses.housing() <= 20, type.name());
      assertTrue(bonuses.maintenance() >= 0 && bonuses.maintenance() <= 20, type.name());
      assertTrue(bonuses.defense() >= 0 && bonuses.defense() <= 20, type.name());
      assertTrue(bonuses.trade() >= 0 && bonuses.trade() <= 20, type.name());
      assertTrue(bonuses.workerEfficiency() >= 0 && bonuses.workerEfficiency() <= 20, type.name());
      assertTrue(bonuses.repairPriority() >= 0 && bonuses.repairPriority() <= 20, type.name());
    }
  }
}
