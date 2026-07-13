package nl.frontier.npc;

import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Ids.WorkerId;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import nl.frontier.domain.Money;
import org.junit.jupiter.api.Test;

class WorkerTest {
  @Test
  void entityLossDoesNotLoseTaskAndExpiredLeaseIsRecoverable() {
    Worker worker =
        new Worker(
            new WorkerId(UUID.randomUUID()),
            new SettlementId(UUID.randomUUID()),
            Worker.Profession.BUILDER,
            50,
            new Money(100));
    worker.claim(UUID.randomUUID(), Instant.ofEpochSecond(10));
    worker.materialized(UUID.randomUUID());
    worker.presentationRemoved();
    assertEquals(Worker.State.TRAVELLING, worker.state());
    worker.recoverExpired(Instant.ofEpochSecond(11));
    assertEquals(Worker.State.IDLE, worker.state());
  }
}
