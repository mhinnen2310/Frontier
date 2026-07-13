package nl.frontier.city;

import static nl.frontier.domain.Ids.PlayerId;
import static nl.frontier.domain.Ids.SettlementId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
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

  @Test
  void districtHousingAndMaintenanceBonusesAffectDailySimulation() {
    SettlementSimulationGateway gateway =
        new SettlementSimulationGateway() {
          @Override
          public List<Snapshot> leaseDue(UUID worker, int limit, Instant now, Instant leaseUntil) {
            return List.of();
          }

          @Override
          public void apply(
              UUID worker,
              Snapshot snapshot,
              SettlementDailySimulation.Result result,
              UUID cycleKey,
              Instant nextCycle,
              Instant now) {}

          @Override
          public void release(UUID worker, UUID city) {}
        };
    var result =
        new SettlementDailySimulation(gateway)
            .calculate(
                new SettlementSimulationGateway.Snapshot(
                    UUID.randomUUID(),
                    SettlementLevel.CAMP,
                    100,
                    70,
                    10,
                    100_000,
                    10,
                    0,
                    0,
                    0,
                    100,
                    100,
                    "STANDARD",
                    20,
                    10,
                    0));
    assertEquals(3_150, result.maintenanceMinor());
    assertEquals(2, result.populationDelta());
  }
}
