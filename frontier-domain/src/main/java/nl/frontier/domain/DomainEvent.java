package nl.frontier.domain;

import java.time.Instant;
import java.util.UUID;

public record DomainEvent(
    UUID id,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    String payload,
    Instant occurredAt) {
  public DomainEvent {
    if (aggregateType.isBlank() || eventType.isBlank())
      throw new IllegalArgumentException("event type is required");
  }
}
