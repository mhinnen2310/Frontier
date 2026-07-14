package nl.frontier.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AmbientLifePolicyTest {
  private final AmbientLifePolicy policy = new AmbientLifePolicy(12, 6, 2, 2, 2);

  @Test
  void daytimeSettlementShowsEveryAvailableActivityWithinBudget() {
    var scenes = policy.scenes(inputs(true, 2));

    assertTrue(
        scenes.stream().anyMatch(scene -> scene.type() == AmbientLifePolicy.SceneType.CITIZEN));
    assertTrue(
        scenes.stream().anyMatch(scene -> scene.type() == AmbientLifePolicy.SceneType.MARKET));
    assertTrue(
        scenes.stream().anyMatch(scene -> scene.type() == AmbientLifePolicy.SceneType.GUARD));
    assertTrue(
        scenes.stream().anyMatch(scene -> scene.type() == AmbientLifePolicy.SceneType.REPAIR));
    assertTrue(
        scenes.stream()
            .anyMatch(scene -> scene.type() == AmbientLifePolicy.SceneType.TOWN_HALL_EVENT));
    assertTrue(
        scenes.stream().anyMatch(scene -> scene.type() == AmbientLifePolicy.SceneType.SHORTAGE));
    assertTrue(scenes.size() + 2 <= 12);
  }

  @Test
  void nightClosesMarketReducesCitizensAndAddsWatch() {
    var scenes = policy.scenes(inputs(false, 0));

    assertFalse(
        scenes.stream().anyMatch(scene -> scene.type() == AmbientLifePolicy.SceneType.MARKET));
    assertEquals(
        1,
        scenes.stream()
            .filter(scene -> scene.type() == AmbientLifePolicy.SceneType.CITIZEN)
            .count());
    assertEquals(
        2,
        scenes.stream().filter(scene -> scene.type() == AmbientLifePolicy.SceneType.GUARD).count());
  }

  @Test
  void workersConsumeTheSharedSettlementPresentationBudget() {
    var scenes = policy.scenes(inputs(true, 12));

    assertTrue(scenes.isEmpty());
  }

  @Test
  void announcementExplainsTownHallEventShortageAndTrend() {
    String announcement = policy.announcement("Haven", inputs(true, 0));

    assertTrue(announcement.contains("Town Hall event"));
    assertTrue(announcement.contains("shortage: food, housing, jobs"));
    assertTrue(announcement.contains("population -2"));
  }

  private static AmbientLifePolicy.Inputs inputs(boolean daytime, int workers) {
    return new AmbientLifePolicy.Inputs(
        40, 20, 20, 20, -2, daytime, 12, 6, "TRADE_FAIR", true, true, true, workers);
  }
}
