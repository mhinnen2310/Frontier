package nl.frontier.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class InfrastructureDirtyTrackerTest {
  @Test
  void queueIsBoundedDeduplicatedAndDrainedInBatches() {
    UUID world = UUID.randomUUID();
    InfrastructureDirtyTracker tracker = new InfrastructureDirtyTracker(2);

    assertTrue(tracker.offer(world, 1, 64, 1, "PLACE"));
    assertTrue(tracker.offer(world, 1, 64, 1, "BREAK"));
    assertTrue(tracker.offer(world, 2, 64, 1, "PLACE"));
    assertFalse(tracker.offer(world, 3, 64, 1, "PLACE"));
    assertEquals(2, tracker.size());
    assertEquals("BREAK", tracker.drain(1).getFirst().reason());
    assertEquals(1, tracker.size());
    assertEquals(1, tracker.drain(10).size());
  }
}
