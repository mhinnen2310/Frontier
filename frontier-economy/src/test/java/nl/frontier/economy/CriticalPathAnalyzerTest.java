package nl.frontier.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CriticalPathAnalyzerTest {
  @Test
  void scoresOnlyTheGraphBridgeAsStructurallyCritical() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
    var ab = edge(a, b, 50, 0);
    var bc = edge(b, c, 50, 0);
    var ca = edge(c, a, 50, 0);
    var cd = edge(c, d, 80, 2);

    var scores = new CriticalPathAnalyzer().score(List.of(ab, bc, ca, cd));

    assertEquals(10, scores.get(ab.id()));
    assertEquals(10, scores.get(bc.id()));
    assertEquals(10, scores.get(ca.id()));
    assertTrue(scores.get(cd.id()) >= 80);
  }

  private static InfrastructureGateway.NetworkEdge edge(
      UUID from, UUID to, int importance, int shipments) {
    return new InfrastructureGateway.NetworkEdge(
        UUID.randomUUID(), from, to, importance, shipments, true);
  }
}
