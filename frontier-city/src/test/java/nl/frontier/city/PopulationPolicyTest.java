package nl.frontier.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PopulationPolicyTest {
  private final PopulationPolicy policy = PopulationPolicy.defaults();

  @Test
  void healthySettlementGrowsWithinHousingAndDailyCap() {
    var outcome =
        policy.evaluate(new PopulationPolicy.Factors(10, 30, 90, 80, 80, 75, false, false));

    assertEquals(4, outcome.trend());
    assertEquals(1, outcome.births());
    assertEquals(3, outcome.immigration());
    assertTrue(outcome.reasons().contains("+ Housing available"));
    assertTrue(outcome.reasons().contains("+ Employment"));
  }

  @Test
  void graceSuppressesShortageCollapseButStillExplainsPressure() {
    var outcome = policy.evaluate(new PopulationPolicy.Factors(20, 20, 0, 80, 80, 80, false, true));

    assertEquals(20, outcome.population());
    assertEquals(0, outcome.deaths());
    assertEquals(0, outcome.emigration());
    assertTrue(outcome.reasons().contains("- Food shortage (grace active)"));
  }

  @Test
  void newSettlementGraceSuppressesNaturalAndPressureDecline() {
    var outcome =
        policy.evaluate(new PopulationPolicy.Factors(2_000, 2_000, 80, 0, 0, 0, true, false));

    assertEquals(2_000, outcome.population());
    assertEquals(0, outcome.deaths());
    assertEquals(0, outcome.emigration());
    assertTrue(outcome.reasons().contains("+ New settlement protection"));
  }

  @Test
  void declineIsCappedAndCannotCrossCollapseFloor() {
    var pressured =
        policy.evaluate(new PopulationPolicy.Factors(100, 100, 0, 0, 0, 0, false, false));
    var lastResident =
        policy.evaluate(new PopulationPolicy.Factors(1, 1, 0, 0, 0, 0, false, false));

    assertEquals(-3, pressured.trend());
    assertEquals(97, pressured.population());
    assertEquals(1, lastResident.population());
    assertEquals(0, lastResident.trend());
  }

  @Test
  void fullHousingAndUnemploymentPreventGrowth() {
    var outcome =
        policy.evaluate(new PopulationPolicy.Factors(10, 10, 100, 100, 100, 20, true, false));

    assertEquals(0, outcome.trend());
    assertTrue(outcome.reasons().contains("- Housing full"));
    assertTrue(outcome.reasons().contains("- Unemployment"));
  }
}
