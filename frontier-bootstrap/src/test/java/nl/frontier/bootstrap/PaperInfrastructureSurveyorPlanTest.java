package nl.frontier.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import nl.frontier.economy.InfrastructureGateway;
import nl.frontier.economy.InfrastructureValidationPolicy;
import org.junit.jupiter.api.Test;

class PaperInfrastructureSurveyorPlanTest {
  @Test
  void corridorPlanIsDeduplicatedBoundedAndRejectsExcessLength() {
    UUID world = UUID.randomUUID();
    var surveyor = new PaperInfrastructureSurveyor(InfrastructureValidationPolicy.defaults());
    var maximum =
        new InfrastructureGateway.Context(
            UUID.randomUUID(),
            new InfrastructureGateway.Point(world, 0, 64, 0),
            new InfrastructureGateway.Point(world, 256, 64, 0));

    var columns = surveyor.plan(maximum);
    assertTrue(columns.size() <= 65_536);
    assertEquals(columns.size(), columns.stream().distinct().count());

    var excessive =
        new InfrastructureGateway.Context(
            UUID.randomUUID(), maximum.from(), new InfrastructureGateway.Point(world, 257, 64, 0));
    assertThrows(IllegalArgumentException.class, () -> surveyor.plan(excessive));
  }
}
