package nl.frontier.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import nl.frontier.api.AdminDiagnostics;
import nl.frontier.api.TransactionalStore;

public final class PostgresAdminDiagnostics implements AdminDiagnostics {
  private static final Map<String, String> TABLES =
      Map.of(
          "city", "cities",
          "war", "campaigns",
          "order", "repair_orders",
          "task", "repair_tasks",
          "worker", "workers",
          "shipment", "shipments",
          "kingdom", "kingdoms",
          "wonder", "world_wonders");
  private final TransactionalStore store;

  public PostgresAdminDiagnostics(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public Snapshot snapshot() {
    return store.inTransaction(
        connection -> {
          Map<String, Long> counts = new LinkedHashMap<>();
          for (Map.Entry<String, String> entry : TABLES.entrySet()) {
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT count(*) FROM " + entry.getValue());
                ResultSet result = statement.executeQuery()) {
              result.next();
              counts.put(entry.getKey(), result.getLong(1));
            }
          }
          long lag;
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT coalesce(extract(epoch from (now()-min(occurred_at)))::bigint,0) FROM outbox_events WHERE published_at IS NULL");
              ResultSet result = statement.executeQuery()) {
            result.next();
            lag = Math.max(0, result.getLong(1));
          }
          return new Snapshot(Map.copyOf(counts), lag);
        });
  }

  @Override
  public List<String> inspect(String aggregateType, UUID aggregateId) {
    String table = TABLES.get(aggregateType.toLowerCase(Locale.ROOT));
    if (table == null) throw new IllegalArgumentException("unknown inspect type");
    return store.inTransaction(
        connection -> {
          List<String> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT row_to_json(value)::text FROM " + table + " value WHERE id=?")) {
            statement.setObject(1, aggregateId);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(result.getString(1));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<String> audit(int limit) {
    return store.inTransaction(
        connection -> {
          List<String> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT occurred_at||' '||action||' '||aggregate_type||' '||coalesce(aggregate_id::text,'-')||' actor='||coalesce(actor_id::text,'system')||' reason='||coalesce(reason,'-') FROM audit_log ORDER BY occurred_at DESC LIMIT ?")) {
            statement.setInt(1, Math.max(1, Math.min(limit, 100)));
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(result.getString(1));
            }
          }
          return List.copyOf(values);
        });
  }
}
