package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class ProductionApplicationService {
  private final ProductionGateway gateway;

  public ProductionApplicationService(ProductionGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public ProductionGateway.ProductionOrder queue(
      UUID city,
      UUID actor,
      UUID building,
      String recipe,
      int quantity,
      int priority,
      UUID idempotency,
      Instant now) {
    if (quantity < 1 || quantity > 10_000)
      throw new IllegalArgumentException("quantity must be 1-10000");
    if (priority < 0 || priority > 100)
      throw new IllegalArgumentException("priority must be 0-100");
    return gateway.queue(
        city,
        actor,
        building,
        key(recipe),
        quantity,
        priority,
        Objects.requireNonNull(idempotency),
        now);
  }

  public ProductionGateway.WorkerSnapshot hire(
      UUID city, UUID actor, String profession, int skill, long salary, Instant now) {
    if (skill < 1 || skill > 100) throw new IllegalArgumentException("skill must be 1-100");
    if (salary < 0) throw new IllegalArgumentException("salary cannot be negative");
    return gateway.hire(city, actor, key(profession).toUpperCase(Locale.ROOT), skill, salary, now);
  }

  public List<ProductionGateway.ProductionOrder> orders(UUID city) {
    return gateway.orders(city);
  }

  private static String key(String value) {
    String result = Objects.requireNonNull(value).trim().toLowerCase(Locale.ROOT);
    if (!result.matches("[a-z0-9_.:-]{2,96}")) throw new IllegalArgumentException("invalid key");
    return result;
  }
}
