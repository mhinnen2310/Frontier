package nl.frontier.world;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class DynamicEventService {
  private final DynamicEventGateway gateway;

  public DynamicEventService(DynamicEventGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public DynamicEventGateway.CycleReport cycle(int maximum, Instant now) {
    if (maximum < 1 || maximum > 100) throw new DomainException("event cycle limit must be 1-100");
    return gateway.detect(maximum, now);
  }

  public List<DynamicEventGateway.EventSummary> available(UUID player, Instant now) {
    return gateway.available(player, now);
  }

  public DynamicEventGateway.EventSummary join(UUID event, UUID player, String role, Instant now) {
    if (role == null || !role.matches("[A-Za-z][A-Za-z0-9_-]{1,31}"))
      throw new DomainException("event role must be 2-32 simple characters");
    return gateway.join(event, player, role.toUpperCase(Locale.ROOT), now);
  }

  public DynamicEventGateway.EventSummary respond(
      UUID event, UUID player, long contribution, Instant now) {
    if (contribution <= 0 || contribution > 10_000)
      throw new DomainException("event contribution must be 1-10000");
    return gateway.respond(event, player, contribution, now);
  }
}
