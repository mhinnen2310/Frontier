package nl.frontier.influence;

import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Ids.WorldId;
import static nl.frontier.domain.Position.ChunkPos;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InfluenceEngineTest {
  private final InfluenceEngine engine = new InfluenceEngine();

  @Test
  void floodFillNeverCreatesDisconnectedClaims() {
    ChunkPos core = new ChunkPos(new WorldId(UUID.randomUUID()), 0, 0);
    Set<ChunkPos> result =
        engine.reachable(core, 8, Set.of(new ChunkPos(core.world(), 1, 0)), Set.of());
    assertEquals(8, result.size());
    assertEquals(true, result.contains(core));
  }

  @Test
  void contestedOwnershipNeedsHysteresis() {
    SettlementId first = new SettlementId(UUID.randomUUID());
    SettlementId second = new SettlementId(UUID.randomUUID());
    InfluenceEngine.Resolution one =
        engine.resolve(Map.of(first, 100, second, 10), second, null, 0, 75, 3);
    InfluenceEngine.Resolution two =
        engine.resolve(
            Map.of(first, 100, second, 10),
            second,
            one.leader(),
            one.consecutiveLeadCycles(),
            75,
            3);
    InfluenceEngine.Resolution three =
        engine.resolve(
            Map.of(first, 100, second, 10),
            second,
            two.leader(),
            two.consecutiveLeadCycles(),
            75,
            3);
    assertEquals(TerritoryState.CONTESTED, one.state());
    assertEquals(TerritoryState.CONTROLLED, three.state());
    assertEquals(first, three.owner());
  }
}
