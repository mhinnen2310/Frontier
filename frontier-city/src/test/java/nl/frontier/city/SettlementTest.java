package nl.frontier.city;

import static nl.frontier.domain.Ids.PlayerId;
import static nl.frontier.domain.Ids.SettlementId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import nl.frontier.domain.DomainException;
import org.junit.jupiter.api.Test;

class SettlementTest {
  private final PlayerId owner = new PlayerId(UUID.randomUUID());
  private final Settlement city =
      new Settlement(new SettlementId(UUID.randomUUID()), "Ravenford", owner, Instant.EPOCH);

  @Test
  void ownerCannotBeRemoved() {
    assertThrows(DomainException.class, () -> city.removeMember(owner, owner));
  }

  @Test
  void upgradeRequiresAllServerSideEvidence() {
    assertThrows(
        DomainException.class,
        () -> city.upgrade(owner, new Settlement.UpgradeEvidence(9, 80, 50, true)));
    city.upgrade(owner, new Settlement.UpgradeEvidence(10, 80, 50, true));
    assertEquals(SettlementLevel.OUTPOST, city.level());
  }
}
