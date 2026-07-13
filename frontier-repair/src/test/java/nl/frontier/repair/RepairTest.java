package nl.frontier.repair;

import static nl.frontier.domain.Ids.RepairOrderId;
import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Ids.WarId;
import static nl.frontier.domain.Ids.WorldId;
import static nl.frontier.domain.Position.BlockPos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import nl.frontier.domain.DomainException;
import nl.frontier.domain.Money;
import org.junit.jupiter.api.Test;

class RepairTest {
  @Test
  void cycleInPlanIsRejected() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    WorldId world = new WorldId(UUID.randomUUID());
    ReconstructionPlanner.Task a =
        new ReconstructionPlanner.Task(
            first,
            new BlockPos(world, 0, 0, 0),
            "air",
            "stone",
            "stone",
            ReconstructionPlanner.Layer.FOUNDATION,
            Set.of(second));
    ReconstructionPlanner.Task b =
        new ReconstructionPlanner.Task(
            second,
            new BlockPos(world, 0, 1, 0),
            "air",
            "stone",
            "stone",
            ReconstructionPlanner.Layer.STRUCTURE,
            Set.of(first));
    assertThrows(DomainException.class, () -> new ReconstructionPlanner().plan(List.of(a, b)));
  }

  @Test
  void orderCompletesOnlyAfterEveryTask() {
    RepairOrder order =
        new RepairOrder(
            new RepairOrderId(UUID.randomUUID()),
            new SettlementId(UUID.randomUUID()),
            new WarId(UUID.randomUUID()),
            new Money(100),
            1,
            RepairOrder.Priority.NORMAL);
    order.requestPayment();
    order.paymentAndMaterialsCommitted();
    order.activate();
    order.completeTask();
    assertEquals(RepairOrder.Status.COMPLETED, order.status());
  }
}
