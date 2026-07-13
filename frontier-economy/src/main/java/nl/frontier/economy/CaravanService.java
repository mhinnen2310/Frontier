package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class CaravanService {
  private final CaravanGateway gateway;

  public CaravanService(CaravanGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public CaravanGateway.CycleReport cycle(int limit, Instant now) {
    if (limit < 1 || limit > 500) throw new DomainException("caravan cycle limit must be 1-500");
    return gateway.cycle(limit, now);
  }

  public List<CaravanGateway.Presentation> presentations(int limit) {
    return gateway.presentations(Math.max(1, Math.min(500, limit)));
  }

  public void bind(UUID shipment, UUID entity, Instant now) {
    gateway.bind(shipment, entity, now);
  }

  public void unbind(UUID shipment, UUID entity, Instant now) {
    gateway.unbind(shipment, entity, now);
  }

  public CaravanGateway.CaravanSnapshot escort(UUID shipment, UUID player, Instant now) {
    return gateway.escort(shipment, player, now);
  }

  public CaravanGateway.CaravanSnapshot damage(
      UUID shipment, UUID attacker, int damage, Instant now) {
    if (damage < 1 || damage > 100) throw new DomainException("invalid caravan damage");
    return gateway.damage(shipment, attacker, damage, now);
  }
}
