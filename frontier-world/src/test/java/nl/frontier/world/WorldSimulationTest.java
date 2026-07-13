package nl.frontier.world;

import static nl.frontier.domain.Ids.SettlementId;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldSimulationTest {
  @Test
  void cycleIsBoundedToDirtySettlements() {
    WorldSimulation simulation = new WorldSimulation();
    SettlementId first = new SettlementId(UUID.randomUUID());
    SettlementId second = new SettlementId(UUID.randomUUID());
    simulation.markDirty(first);
    simulation.markDirty(second);
    var results =
        simulation.cycle(
            Map.of(
                first,
                new WorldSimulation.Snapshot(100, 25, 70, 10),
                second,
                new WorldSimulation.Snapshot(100, 25, 70, 10)),
            WorldSimulation.Season.SPRING,
            1);
    assertEquals(1, results.size());
  }
}
