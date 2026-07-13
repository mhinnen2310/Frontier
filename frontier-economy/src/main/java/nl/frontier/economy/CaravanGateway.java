package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CaravanGateway {
  CycleReport cycle(int limit, Instant now);

  List<Presentation> presentations(int limit);

  void bind(UUID shipment, UUID entity, Instant now);

  void unbind(UUID shipment, UUID entity, Instant now);

  CaravanSnapshot escort(UUID shipment, UUID player, Instant now);

  CaravanSnapshot damage(UUID shipment, UUID attacker, int damage, Instant now);

  record Presentation(
      UUID shipment,
      UUID world,
      double x,
      double y,
      double z,
      String state,
      int health,
      UUID escort,
      String cargo,
      UUID entity) {}

  record CaravanSnapshot(
      UUID shipment,
      String state,
      int health,
      double progress,
      UUID escort,
      UUID entity,
      String mode) {}

  record CycleReport(int synchronizedShipments, int advanced, int unloaded, int despawned) {}
}
