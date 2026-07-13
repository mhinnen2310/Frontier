package nl.frontier.economy;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class WarehouseStock {
  private final Map<String, StockLine> lines = new HashMap<>();
  private final Map<UUID, Reservation> reservations = new HashMap<>();

  public synchronized void add(String commodity, long quantity) {
    validate(commodity, quantity);
    StockLine line = lines.getOrDefault(commodity, new StockLine(0, 0));
    lines.put(commodity, new StockLine(Math.addExact(line.available(), quantity), line.reserved()));
  }

  public synchronized Reservation reserve(UUID id, UUID owner, String commodity, long quantity) {
    validate(commodity, quantity);
    Reservation existing = reservations.get(id);
    if (existing != null) return existing;
    StockLine line = lines.getOrDefault(commodity, new StockLine(0, 0));
    if (line.available() < quantity) throw new DomainException("insufficient warehouse stock");
    lines.put(
        commodity,
        new StockLine(line.available() - quantity, Math.addExact(line.reserved(), quantity)));
    Reservation reservation =
        new Reservation(id, owner, commodity, quantity, 0, ReservationStatus.RESERVED);
    reservations.put(id, reservation);
    return reservation;
  }

  public synchronized Reservation consume(UUID reservationId, long quantity) {
    if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
    Reservation old = requireReservation(reservationId);
    if (old.status() != ReservationStatus.RESERVED || old.remaining() < quantity) {
      throw new DomainException("reservation cannot consume requested quantity");
    }
    StockLine line = lines.get(old.commodity());
    lines.put(old.commodity(), new StockLine(line.available(), line.reserved() - quantity));
    long consumed = Math.addExact(old.consumed(), quantity);
    ReservationStatus status =
        consumed == old.quantity() ? ReservationStatus.CONSUMED : ReservationStatus.RESERVED;
    Reservation updated =
        new Reservation(old.id(), old.owner(), old.commodity(), old.quantity(), consumed, status);
    reservations.put(old.id(), updated);
    return updated;
  }

  public synchronized Reservation release(UUID reservationId) {
    Reservation old = requireReservation(reservationId);
    if (old.status() == ReservationStatus.RELEASED || old.status() == ReservationStatus.CONSUMED)
      return old;
    long remainder = old.remaining();
    StockLine line = lines.get(old.commodity());
    lines.put(
        old.commodity(),
        new StockLine(Math.addExact(line.available(), remainder), line.reserved() - remainder));
    Reservation updated =
        new Reservation(
            old.id(),
            old.owner(),
            old.commodity(),
            old.quantity(),
            old.consumed(),
            ReservationStatus.RELEASED);
    reservations.put(old.id(), updated);
    return updated;
  }

  private Reservation requireReservation(UUID id) {
    Reservation reservation = reservations.get(id);
    if (reservation == null) throw new DomainException("unknown reservation");
    return reservation;
  }

  private static void validate(String commodity, long quantity) {
    if (Objects.requireNonNull(commodity).isBlank() || quantity <= 0)
      throw new IllegalArgumentException("commodity and positive quantity required");
  }

  public synchronized StockLine stock(String commodity) {
    return lines.getOrDefault(commodity, new StockLine(0, 0));
  }

  public enum ReservationStatus {
    RESERVED,
    CONSUMED,
    RELEASED
  }

  public record StockLine(long available, long reserved) {}

  public record Reservation(
      UUID id,
      UUID owner,
      String commodity,
      long quantity,
      long consumed,
      ReservationStatus status) {
    public long remaining() {
      return quantity - consumed;
    }
  }
}
