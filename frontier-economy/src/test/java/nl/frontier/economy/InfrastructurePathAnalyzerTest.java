package nl.frontier.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InfrastructurePathAnalyzerTest {
  private static final UUID WORLD = UUID.randomUUID();
  private static final InfrastructureValidationPolicy POLICY =
      new InfrastructureValidationPolicy(
          256,
          6,
          3,
          1_000,
          85,
          1,
          40,
          1.5,
          10,
          2,
          2,
          1,
          Map.of("STONE_BRICKS", 100),
          Set.of("IRON_BARS"));

  @Test
  void findsCurvedPhysicalPathAndDerivesEvidenceAndBounds() {
    List<InfrastructureSnapshot.Cell> cells = new ArrayList<>();
    int[][] route = {
      {0, 0}, {1, 0}, {1, 1}, {1, 2}, {2, 2}, {3, 2}, {4, 2}, {5, 2}, {5, 1}, {5, 0}, {6, 0}
    };
    for (int index = 0; index < route.length; index++) {
      int[] point = route[index];
      cells.add(
          new InfrastructureSnapshot.Cell(
              point[0], 64, point[1], 80, index == 5 || index == 6, false, index == 8));
    }
    var snapshot =
        new InfrastructureSnapshot(
            WORLD,
            new InfrastructureGateway.Point(WORLD, 0, 64, 0),
            new InfrastructureGateway.Point(WORLD, 6, 64, 0),
            cells,
            Set.of(),
            100);

    InfrastructureSurvey result = new InfrastructurePathAnalyzer(POLICY).analyze(snapshot);

    assertTrue(result.endpointsConnected());
    assertEquals(11, result.samples());
    assertEquals(11, result.route().size());
    assertEquals(2, result.bridgeSamples());
    assertEquals(1, result.gateSamples());
    assertEquals(0, result.minX());
    assertEquals(2, result.maxZ());
  }

  @Test
  void rejectsARealGapAndEnforcesSnapshotBound() {
    var disconnected =
        new InfrastructureSnapshot(
            WORLD,
            new InfrastructureGateway.Point(WORLD, 0, 64, 0),
            new InfrastructureGateway.Point(WORLD, 4, 64, 0),
            List.of(cell(0, 0), cell(1, 0), cell(3, 0), cell(4, 0)),
            Set.of(new InfrastructureSnapshot.Column(2, 0)),
            20);
    InfrastructureSurvey result = new InfrastructurePathAnalyzer(POLICY).analyze(disconnected);
    assertFalse(result.endpointsConnected());
    assertEquals(1, result.destroyedBridges());
    assertFalse(
        new InfrastructureValidator(POLICY).validate(InfrastructureType.ROAD, result).valid());

    var oversized =
        new InfrastructureSnapshot(
            WORLD, disconnected.from(), disconnected.to(), List.of(cell(0, 0)), Set.of(), 1_001);
    assertThrows(
        IllegalArgumentException.class,
        () -> new InfrastructurePathAnalyzer(POLICY).analyze(oversized));
  }

  private static InfrastructureSnapshot.Cell cell(int x, int z) {
    return new InfrastructureSnapshot.Cell(x, 64, z, 80, false, false, false);
  }
}
