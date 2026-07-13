package nl.frontier.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DistrictSpecializationPolicyTest {
  private static final DistrictBalancePolicy BALANCE =
      new DistrictBalancePolicy(40, 40, 50, 3, 10, 2, 16, 2, 20, 30, 10, 10, 4, 25);
  private final DistrictSpecializationPolicy policy = new DistrictSpecializationPolicy();

  @Test
  void emptyOrDisconnectedDistrictNeverReceivesBonuses() {
    assertFalse(policy.evaluate(DistrictType.AGRICULTURAL, BALANCE, 0, 2, 0, 1).active());
    assertFalse(policy.evaluate(DistrictType.AGRICULTURAL, BALANCE, 2, 0, 0, 1).active());
    assertEquals(
        0, policy.evaluate(DistrictType.AGRICULTURAL, BALANCE, 2, 0, 0, 1).bonuses().production());
  }

  @Test
  void buildingsDiminishAdjacencyHelpsAndOverspecializationPenalizes() {
    var one = policy.evaluate(DistrictType.AGRICULTURAL, BALANCE, 1, 1, 0, 1);
    var two = policy.evaluate(DistrictType.AGRICULTURAL, BALANCE, 2, 1, 0, 1);
    var capped = policy.evaluate(DistrictType.AGRICULTURAL, BALANCE, 8, 1, 5, 1);
    var overspecialized = policy.evaluate(DistrictType.AGRICULTURAL, BALANCE, 1, 1, 0, 4);
    assertEquals(20, one.bonuses().production());
    assertEquals(30, two.bonuses().production());
    assertEquals(30, capped.bonuses().production());
    assertEquals(12, overspecialized.bonuses().production());
  }

  @Test
  void tradeoffsAndAdjacencyMatrixAreExplicit() {
    assertEquals(
        10,
        policy.evaluate(DistrictType.INDUSTRIAL, BALANCE, 1, 1, 0, 1).maintenancePenaltyPercent());
    assertEquals(
        10, policy.evaluate(DistrictType.MILITARY, BALANCE, 1, 1, 0, 1).wagePenaltyPercent());
    assertEquals(
        8,
        policy.evaluate(DistrictType.COMMERCIAL, BALANCE, 2, 1, 0, 1).marketOrderCapacityBonus());
    assertEquals(
        25,
        policy
            .evaluate(DistrictType.LOGISTICS, BALANCE, 1, 1, 0, 1)
            .warehouseCapacityBonusPercent());
    assertTrue(policy.adjacencyCompatible(DistrictType.AGRICULTURAL, DistrictType.LOGISTICS));
    assertFalse(policy.adjacencyCompatible(DistrictType.AGRICULTURAL, DistrictType.MILITARY));
  }
}
