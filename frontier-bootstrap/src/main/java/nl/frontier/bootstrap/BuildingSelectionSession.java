package nl.frontier.bootstrap;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import nl.frontier.city.BuildingType;
import nl.frontier.city.SettlementGateway;

record BuildingSelectionSession(
    UUID city,
    BuildingType type,
    String district,
    SelectionPoint first,
    SelectionPoint second,
    Instant expiresAt) {
  BuildingSelectionSession select(SelectionPoint point, boolean firstPoint) {
    SelectionPoint other = firstPoint ? second : first;
    if (other != null && !other.world().equals(point.world()))
      throw new IllegalArgumentException("both selection points must be in the same world");
    return firstPoint
        ? new BuildingSelectionSession(city, type, district, point, second, expiresAt)
        : new BuildingSelectionSession(city, type, district, first, point, expiresAt);
  }

  boolean expired(Instant now) {
    return !expiresAt.isAfter(now);
  }

  Optional<SettlementGateway.Bounds> bounds() {
    if (first == null || second == null) return Optional.empty();
    return Optional.of(
        new SettlementGateway.Bounds(
            first.world(),
            Math.min(first.x(), second.x()),
            Math.min(first.y(), second.y()),
            Math.min(first.z(), second.z()),
            Math.max(first.x(), second.x()),
            Math.max(first.y(), second.y()),
            Math.max(first.z(), second.z())));
  }

  record SelectionPoint(UUID world, int x, int y, int z) {}
}
