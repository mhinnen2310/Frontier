package nl.frontier.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import nl.frontier.city.BuildingType;
import org.junit.jupiter.api.Test;

class BuildingSelectionSessionTest {
  @Test
  void reverseCornerSelectionCreatesNormalizedBoundsAndExpires() {
    UUID world = UUID.randomUUID();
    Instant expiry = Instant.parse("2026-07-14T12:00:00Z");
    var session =
        new BuildingSelectionSession(
                UUID.randomUUID(), BuildingType.WAREHOUSE, null, null, null, expiry)
            .select(new BuildingSelectionSession.SelectionPoint(world, 20, 80, 30), true)
            .select(new BuildingSelectionSession.SelectionPoint(world, 10, 60, 15), false);

    var bounds = session.bounds().orElseThrow();
    assertEquals(10, bounds.minX());
    assertEquals(60, bounds.minY());
    assertEquals(15, bounds.minZ());
    assertEquals(20, bounds.maxX());
    assertEquals(80, bounds.maxY());
    assertEquals(30, bounds.maxZ());
    assertFalse(session.expired(expiry.minusMillis(1)));
    assertTrue(session.expired(expiry));
  }

  @Test
  void selectionCannotSpanWorlds() {
    var session =
        new BuildingSelectionSession(
                UUID.randomUUID(),
                BuildingType.FARM,
                null,
                null,
                null,
                Instant.now().plusSeconds(60))
            .select(new BuildingSelectionSession.SelectionPoint(UUID.randomUUID(), 0, 64, 0), true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            session.select(
                new BuildingSelectionSession.SelectionPoint(UUID.randomUUID(), 1, 64, 1), false));
  }
}
