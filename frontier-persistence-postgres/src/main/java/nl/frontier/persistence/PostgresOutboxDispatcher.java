package nl.frontier.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import nl.frontier.api.OutboxDispatcher;
import nl.frontier.api.TransactionalStore;

public final class PostgresOutboxDispatcher implements OutboxDispatcher {
  private final TransactionalStore store;
  private final Consumer<Event> consumer;

  public PostgresOutboxDispatcher(TransactionalStore store, Consumer<Event> consumer) {
    this.store = store;
    this.consumer = consumer;
  }

  @Override
  public DispatchReport dispatch(int maximum, Instant now) {
    if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
    return store.inTransaction(
        connection -> {
          List<Event> events = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,aggregate_type,aggregate_id,event_type,payload::text,occurred_at FROM outbox_events WHERE published_at IS NULL ORDER BY occurred_at,id LIMIT ? FOR UPDATE SKIP LOCKED")) {
            statement.setInt(1, maximum);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                events.add(
                    new Event(
                        result.getObject(1, UUID.class),
                        result.getString(2),
                        result.getObject(3, UUID.class),
                        result.getString(4),
                        result.getString(5),
                        result.getTimestamp(6).toInstant()));
              }
            }
          }
          for (Event event : events) consumer.accept(event);
          if (!events.isEmpty()) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE outbox_events SET published_at=? WHERE id=? AND published_at IS NULL")) {
              for (Event event : events) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setObject(2, event.id());
                statement.addBatch();
              }
              statement.executeBatch();
            }
          }
          int remaining;
          long lag;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT count(*),coalesce(extract(epoch from (?::timestamptz-min(occurred_at)))::bigint,0) FROM outbox_events WHERE published_at IS NULL")) {
            statement.setTimestamp(1, Timestamp.from(now));
            try (ResultSet result = statement.executeQuery()) {
              result.next();
              remaining = result.getInt(1);
              lag = Math.max(0, result.getLong(2));
            }
          }
          return new DispatchReport(events.size(), remaining, lag);
        });
  }

  public record Event(
      UUID id,
      String aggregateType,
      UUID aggregateId,
      String eventType,
      String payload,
      Instant occurredAt) {}
}
