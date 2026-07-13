package nl.frontier.economy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.Money;

/**
 * Price-time matching planner. The returned trade is committed with escrow/stock in one SQL
 * transaction.
 */
public final class MarketEngine {
  public enum Side {
    BUY,
    SELL
  }

  public enum Status {
    OPEN,
    PARTIAL,
    FILLED,
    CANCELLED
  }

  public Match match(List<Order> orders) {
    List<Order> buys =
        orders.stream()
            .filter(order -> order.side() == Side.BUY && order.open())
            .sorted(
                Comparator.comparing(Order::unitPrice)
                    .reversed()
                    .thenComparing(Order::createdAt)
                    .thenComparing(Order::id))
            .toList();
    List<Order> sells =
        orders.stream()
            .filter(order -> order.side() == Side.SELL && order.open())
            .sorted(
                Comparator.comparing(Order::unitPrice)
                    .thenComparing(Order::createdAt)
                    .thenComparing(Order::id))
            .toList();
    if (buys.isEmpty() || sells.isEmpty()) return Match.none();
    Order buy = buys.getFirst();
    Order sell = sells.getFirst();
    if (!buy.commodity().equals(sell.commodity())
        || buy.unitPrice().compareTo(sell.unitPrice()) < 0) return Match.none();
    long quantity = Math.min(buy.remaining(), sell.remaining());
    Money price = sell.createdAt().isBefore(buy.createdAt()) ? sell.unitPrice() : buy.unitPrice();
    return new Match(buy, sell, quantity, price, price.multiply(quantity));
  }

  public List<Trade> matchAll(List<Order> initial) {
    List<Order> mutable = new ArrayList<>(initial);
    List<Trade> trades = new ArrayList<>();
    while (true) {
      Match match = match(mutable);
      if (!match.present()) return List.copyOf(trades);
      trades.add(
          new Trade(
              UUID.randomUUID(),
              match.buy().id(),
              match.sell().id(),
              match.quantity(),
              match.unitPrice(),
              match.total()));
      mutable.replaceAll(
          order -> {
            if (order.id().equals(match.buy().id()) || order.id().equals(match.sell().id()))
              return order.fill(match.quantity());
            return order;
          });
    }
  }

  public record Order(
      UUID id,
      UUID owner,
      UUID market,
      Side side,
      String commodity,
      long quantity,
      long remaining,
      Money unitPrice,
      Status status,
      Instant createdAt) {
    public Order {
      Objects.requireNonNull(id);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(market);
      Objects.requireNonNull(side);
      Objects.requireNonNull(commodity);
      Objects.requireNonNull(unitPrice);
      Objects.requireNonNull(status);
      Objects.requireNonNull(createdAt);
      if (commodity.isBlank()
          || quantity <= 0
          || remaining < 0
          || remaining > quantity
          || unitPrice.cents() <= 0) {
        throw new IllegalArgumentException("invalid market order");
      }
    }

    public boolean open() {
      return status == Status.OPEN || status == Status.PARTIAL;
    }

    public Order fill(long amount) {
      if (amount <= 0 || amount > remaining) throw new IllegalArgumentException("invalid fill");
      long left = remaining - amount;
      return new Order(
          id,
          owner,
          market,
          side,
          commodity,
          quantity,
          left,
          unitPrice,
          left == 0 ? Status.FILLED : Status.PARTIAL,
          createdAt);
    }
  }

  public record Match(Order buy, Order sell, long quantity, Money unitPrice, Money total) {
    static Match none() {
      return new Match(null, null, 0, Money.ZERO, Money.ZERO);
    }

    public boolean present() {
      return buy != null;
    }
  }

  public record Trade(
      UUID id, UUID buyOrder, UUID sellOrder, long quantity, Money unitPrice, Money total) {}
}
