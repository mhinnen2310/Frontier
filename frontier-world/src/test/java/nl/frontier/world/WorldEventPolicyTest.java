package nl.frontier.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldEventPolicyTest {
  @Test
  void selectsEveryRequiredWorldEventFromDeterministicConditions() {
    WorldEventPolicy policy = new WorldEventPolicy();
    List<Case> cases =
        List.of(
            new Case("DISASTER", conditions(10, 50, 70, 0, 20, "CLEAR", 0)),
            new Case("FLOOD", conditions(10, 50, 70, 0, 80, "STORM", 80)),
            new Case("PLAGUE", conditions(200, 50, 44, 0, 80, "CLEAR", 0)),
            new Case("BANDIT_RAID", conditions(20, 50, 30, 0, 80, "CLEAR", 0)),
            new Case("HARVEST_FAILURE", conditions(20, 50, 70, 0, 80, "HEATWAVE", 70)),
            new Case("TRADE_FAIR", conditions(20, 80, 70, 100, 80, "CLEAR", 0)),
            new Case("MIGRATION_WAVE", conditions(20, 70, 70, 0, 80, "CLEAR", 0)));
    for (Case value : cases)
      assertEquals(value.key, policy.select(value.conditions).orElseThrow().key());
  }

  private static WorldEventPolicy.Conditions conditions(
      int population,
      double prosperity,
      double stability,
      double trade,
      double roads,
      String weather,
      int severity) {
    return new WorldEventPolicy.Conditions(
        population,
        prosperity,
        stability,
        trade,
        roads,
        WorldSimulation.Season.SUMMER,
        weather,
        severity);
  }

  private record Case(String key, WorldEventPolicy.Conditions conditions) {}
}
