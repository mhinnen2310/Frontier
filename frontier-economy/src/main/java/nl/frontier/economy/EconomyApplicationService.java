package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class EconomyApplicationService {
  private final EconomyGateway gateway;

  public EconomyApplicationService(EconomyGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public EconomyGateway.WarehouseSnapshot warehouse(UUID city, UUID actor, Instant now) {
    return gateway.warehouse(city, actor, now);
  }

  public EconomyGateway.WarehouseSnapshot deposit(
      UUID city, UUID actor, String commodity, long quantity, Instant now) {
    if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
    return gateway.deposit(city, actor, commodity(commodity), quantity, now);
  }

  public EconomyGateway.OrderSnapshot order(
      UUID city,
      UUID actor,
      MarketEngine.Side side,
      String commodity,
      long quantity,
      long unitPriceMinor,
      UUID idempotencyKey,
      Instant now) {
    if (quantity <= 0 || quantity > 1_000_000)
      throw new IllegalArgumentException("quantity must be 1-1000000");
    if (unitPriceMinor <= 0) throw new IllegalArgumentException("price must be positive");
    return gateway.placeOrder(
        city,
        actor,
        Objects.requireNonNull(side),
        commodity(commodity),
        quantity,
        unitPriceMinor,
        Objects.requireNonNull(idempotencyKey),
        now);
  }

  public void cancel(UUID city, UUID actor, UUID order, Instant now) {
    gateway.cancel(city, actor, order, now);
  }

  public List<EconomyGateway.OrderSnapshot> openOrders(UUID city) {
    return gateway.openOrders(city);
  }

  private static String commodity(String value) {
    String normalized = Objects.requireNonNull(value).trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z0-9_.:-]{2,96}"))
      throw new IllegalArgumentException("invalid commodity key");
    return normalized;
  }
}
