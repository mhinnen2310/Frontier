package nl.frontier.economy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InfrastructureValidatorTest {
  private final InfrastructureValidator validator = new InfrastructureValidator();

  @Test
  void validatesRoadBridgeAndTunnelEvidence() {
    assertTrue(
        validator
            .validate(
                InfrastructureType.ROAD, new InfrastructureSurvey(20, 20, 3, 0, 0, 80, 1, 0, 0))
            .valid());
    assertTrue(
        validator
            .validate(
                InfrastructureType.GATE,
                new InfrastructureSurvey(20, 20, 3, 0, 0, 80, 1, 0, 0, true, 1, 0, 0, 0, 19, 0, 0))
            .valid());
    assertTrue(
        validator
            .validate(
                InfrastructureType.BRIDGE, new InfrastructureSurvey(20, 20, 3, 12, 0, 80, 1, 0, 0))
            .valid());
    assertTrue(
        validator
            .validate(
                InfrastructureType.TUNNEL, new InfrastructureSurvey(20, 20, 3, 0, 12, 80, 1, 0, 0))
            .valid());
  }

  @Test
  void rejectsNarrowDisconnectedSteepAndDestroyedRoutes() {
    var result =
        validator.validate(
            InfrastructureType.BRIDGE, new InfrastructureSurvey(20, 10, 1, 0, 0, 20, 3, 10, 2));
    assertFalse(result.valid());
    assertTrue(result.violations().size() >= 6);
    assertFalse(
        validator
            .validate(
                InfrastructureType.ROAD,
                new InfrastructureSurvey(
                    300, 300, 3, 0, 0, 80, 1, 0, 0, true, 0, 0, 64, 0, 299, 64, 0))
            .valid());
  }
}
