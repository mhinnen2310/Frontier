package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.economy.CaravanGateway;

public final class PostgresCaravanGateway implements CaravanGateway {
  private final TransactionalStore store;

  public PostgresCaravanGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public CycleReport cycle(int limit, Instant now) {
    return store.inTransaction(
        connection -> {
          int synchronizedShipments;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO caravans(shipment_id,state,state_at,updated_at) SELECT id,'LOADING',?,? FROM shipments s WHERE s.status IN ('WAITING_ROUTE','REROUTING','TRAVELING') AND NOT EXISTS(SELECT 1 FROM caravans c WHERE c.shipment_id=s.id) ORDER BY s.departed_at NULLS FIRST,s.id LIMIT ? ON CONFLICT DO NOTHING")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setInt(3, limit);
            synchronizedShipments = statement.executeUpdate();
          }
          List<Row> rows = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT c.shipment_id,c.state,c.health,c.progress,c.combat_until,c.state_at,s.status,s.departed_at,s.expected_arrival_at FROM caravans c JOIN shipments s ON s.id=c.shipment_id WHERE c.state<>'DESPAWNED' ORDER BY c.updated_at,c.shipment_id LIMIT ? FOR UPDATE OF c SKIP LOCKED")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                rows.add(
                    new Row(
                        result.getObject(1, UUID.class),
                        result.getString(2),
                        result.getInt(3),
                        result.getDouble(4),
                        instant(result, 5),
                        result.getTimestamp(6).toInstant(),
                        result.getString(7),
                        instant(result, 8),
                        instant(result, 9)));
            }
          }
          int advanced = 0;
          int unloaded = 0;
          int despawned = 0;
          for (Row row : rows) {
            String next = row.state;
            double progress = row.progress;
            int health = row.health;
            if (row.shipmentState.equals("ARRIVED")) {
              if (row.state.equals("UNLOADING") && row.stateAt.plusSeconds(5).isBefore(now)) {
                next = "DESPAWNED";
                despawned++;
              } else {
                next = "UNLOADING";
                unloaded++;
              }
              progress = 1;
            } else if (row.shipmentState.equals("RETREATING")) {
              progress = Math.max(0, row.progress - 0.1);
              next = "RETREATING";
              if (progress == 0) {
                health = 50;
                next = "PAUSED";
                update(
                    connection,
                    "UPDATE shipments SET status='REROUTING',version=version+1 WHERE id=? AND status='RETREATING'",
                    row.shipment);
              }
            } else if (row.shipmentState.equals("PAUSED_COMBAT")) {
              if (row.combatUntil != null && row.combatUntil.isAfter(now)) {
                next = "COMBAT";
              } else {
                next = "PAUSED";
                update(
                    connection,
                    "UPDATE shipments SET status='REROUTING',version=version+1 WHERE id=? AND status='PAUSED_COMBAT'",
                    row.shipment);
              }
            } else if (row.shipmentState.equals("WAITING_ROUTE")
                || row.shipmentState.equals("REROUTING")) {
              next = row.health <= 25 ? "RETREATING" : "PAUSED";
              if (row.health <= 25)
                update(
                    connection,
                    "UPDATE shipments SET status='RETREATING',version=version+1 WHERE id=? AND status IN ('WAITING_ROUTE','REROUTING')",
                    row.shipment);
            } else if (row.state.equals("COMBAT")
                && row.combatUntil != null
                && row.combatUntil.isAfter(now)) {
              next = "COMBAT";
            } else if (row.health <= 25) {
              next = "RETREATING";
              update(
                  connection,
                  "UPDATE shipments SET status='RETREATING',expected_arrival_at=NULL,version=version+1 WHERE id=? AND status='TRAVELING'",
                  row.shipment);
            } else {
              next = "WALKING";
              progress = progress(row.departedAt, row.expectedArrivalAt, now, row.progress);
              advanced++;
            }
            boolean changed = !next.equals(row.state);
            update(
                connection,
                "UPDATE caravans SET state=?,health=?,progress=?,state_at=CASE WHEN state<>? THEN ? ELSE state_at END,updated_at=?,presentation_entity=CASE WHEN ?='DESPAWNED' THEN NULL ELSE presentation_entity END,simulation_mode=CASE WHEN ?='DESPAWNED' THEN 'SIMULATED' ELSE simulation_mode END,version=version+1 WHERE shipment_id=?",
                next,
                health,
                progress,
                next,
                now,
                now,
                next,
                next,
                row.shipment);
            if (changed) history(connection, row.shipment, "STATE_" + next, null, "{}", now);
          }
          return new CycleReport(synchronizedShipments, advanced, unloaded, despawned);
        });
  }

  @Override
  public List<Presentation> presentations(int limit) {
    return store.inTransaction(
        connection -> {
          List<Base> bases = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT c.shipment_id,c.state,c.health,c.progress,c.escort_player,c.presentation_entity,coalesce((SELECT string_agg(i.quantity||'x '||i.commodity_key,', ' ORDER BY i.commodity_key) FROM shipment_items i WHERE i.shipment_id=c.shipment_id),'empty') FROM caravans c WHERE c.state IN ('LOADING','WALKING','PAUSED','COMBAT','RETREATING','UNLOADING') ORDER BY c.updated_at LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                bases.add(
                    new Base(
                        result.getObject(1, UUID.class),
                        result.getString(2),
                        result.getInt(3),
                        result.getDouble(4),
                        result.getObject(5, UUID.class),
                        result.getObject(6, UUID.class),
                        result.getString(7)));
            }
          }
          List<Presentation> values = new ArrayList<>();
          for (Base base : bases) {
            List<Point> path = path(connection, base.shipment);
            if (path.isEmpty()) continue;
            Point point = point(path, base.progress);
            values.add(
                new Presentation(
                    base.shipment,
                    point.world,
                    point.x + 0.5,
                    point.y + 1,
                    point.z + 0.5,
                    base.state,
                    base.health,
                    base.escort,
                    base.cargo,
                    base.entity));
          }
          return List.copyOf(values);
        });
  }

  @Override
  public void bind(UUID shipment, UUID entity, Instant now) {
    store.inTransaction(
        connection -> {
          update(
              connection,
              "UPDATE caravans SET presentation_entity=?,simulation_mode='PHYSICAL',updated_at=?,version=version+1 WHERE shipment_id=? AND state<>'DESPAWNED'",
              entity,
              now,
              shipment);
          return null;
        });
  }

  @Override
  public void unbind(UUID shipment, UUID entity, Instant now) {
    store.inTransaction(
        connection -> {
          update(
              connection,
              "UPDATE caravans SET presentation_entity=NULL,simulation_mode='SIMULATED',updated_at=?,version=version+1 WHERE shipment_id=? AND presentation_entity=?",
              now,
              shipment,
              entity);
          return null;
        });
  }

  @Override
  public CaravanSnapshot escort(UUID shipment, UUID player, Instant now) {
    return store.inTransaction(
        connection -> {
          if (scalar(
                  connection,
                  "SELECT count(*) FROM shipments s JOIN city_members m ON m.city_id=s.owner_city_id WHERE s.id=? AND m.player_id=? AND s.status IN ('WAITING_ROUTE','REROUTING','TRAVELING')",
                  shipment,
                  player)
              != 1) throw new DomainException("you cannot escort this caravan");
          update(
              connection,
              "UPDATE caravans SET escort_player=?,updated_at=?,version=version+1 WHERE shipment_id=? AND state<>'DESPAWNED'",
              player,
              now,
              shipment);
          history(connection, shipment, "ESCORT_ASSIGNED", player, "{}", now);
          return snapshot(connection, shipment);
        });
  }

  @Override
  public CaravanSnapshot damage(UUID shipment, UUID attacker, int damage, Instant now) {
    return store.inTransaction(
        connection -> {
          CaravanSnapshot before = snapshot(connection, shipment);
          int effective = before.escort() == null ? damage : Math.max(1, damage * 3 / 4);
          int health = Math.max(0, before.health() - effective);
          String state = health <= 25 ? "RETREATING" : "COMBAT";
          update(
              connection,
              "UPDATE caravans SET health=?,state=?,combat_until=?,state_at=?,updated_at=?,version=version+1 WHERE shipment_id=? AND state<>'DESPAWNED'",
              health,
              state,
              now.plusSeconds(10),
              now,
              now,
              shipment);
          if (health <= 25)
            update(
                connection,
                "UPDATE shipments SET status='RETREATING',expected_arrival_at=NULL,version=version+1 WHERE id=? AND status='TRAVELING'",
                shipment);
          else
            update(
                connection,
                "UPDATE shipments SET status='PAUSED_COMBAT',expected_arrival_at=NULL,version=version+1 WHERE id=? AND status='TRAVELING'",
                shipment);
          history(
              connection,
              shipment,
              health <= 25 ? "RETREAT" : "COMBAT",
              attacker,
              "{\"damage\":" + effective + "}",
              now);
          return snapshot(connection, shipment);
        });
  }

  private static CaravanSnapshot snapshot(Connection connection, UUID shipment)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT shipment_id,state,health,progress,escort_player,presentation_entity,simulation_mode FROM caravans WHERE shipment_id=? FOR UPDATE")) {
      statement.setObject(1, shipment);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("caravan not found");
        return new CaravanSnapshot(
            shipment,
            result.getString(2),
            result.getInt(3),
            result.getDouble(4),
            result.getObject(5, UUID.class),
            result.getObject(6, UUID.class),
            result.getString(7));
      }
    }
  }

  private static List<Point> path(Connection connection, UUID shipment) throws SQLException {
    List<Point> points = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT n.world_id,n.x,n.y,n.z FROM shipment_routes r CROSS JOIN LATERAL jsonb_array_elements_text(r.nodes) WITH ORDINALITY p(node,ord) JOIN road_nodes n ON n.id=p.node::uuid WHERE r.shipment_id=? ORDER BY p.ord")) {
      statement.setObject(1, shipment);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          points.add(
              new Point(
                  result.getObject(1, UUID.class),
                  result.getInt(2),
                  result.getInt(3),
                  result.getInt(4)));
      }
    }
    return points;
  }

  private static Point point(List<Point> path, double progress) {
    double scaled = progress * (path.size() - 1);
    int lower = Math.min(path.size() - 1, (int) Math.floor(scaled));
    int upper = Math.min(path.size() - 1, lower + 1);
    double fraction = scaled - lower;
    Point first = path.get(lower);
    Point second = path.get(upper);
    return new Point(
        first.world,
        (int) Math.round(first.x + (second.x - first.x) * fraction),
        (int) Math.round(first.y + (second.y - first.y) * fraction),
        (int) Math.round(first.z + (second.z - first.z) * fraction));
  }

  private static double progress(Instant departed, Instant expected, Instant now, double current) {
    if (departed == null || expected == null || !expected.isAfter(departed)) return current;
    double elapsed = now.toEpochMilli() - departed.toEpochMilli();
    double duration = expected.toEpochMilli() - departed.toEpochMilli();
    return Math.max(current, Math.min(1, elapsed / duration));
  }

  private static Instant instant(ResultSet result, int column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static void history(
      Connection connection, UUID shipment, String event, UUID actor, String payload, Instant now)
      throws SQLException {
    update(
        connection,
        "INSERT INTO caravan_history(id,shipment_id,event_type,actor_id,payload,occurred_at) VALUES(?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        shipment,
        event,
        actor,
        payload,
        now);
  }

  private static long scalar(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static void update(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      statement.executeUpdate();
    }
  }

  private static void bind(PreparedStatement statement, Object... values) throws SQLException {
    for (int index = 0; index < values.length; index++) {
      Object value = values[index];
      if (value instanceof Instant instant)
        statement.setTimestamp(index + 1, Timestamp.from(instant));
      else statement.setObject(index + 1, value);
    }
  }

  private record Row(
      UUID shipment,
      String state,
      int health,
      double progress,
      Instant combatUntil,
      Instant stateAt,
      String shipmentState,
      Instant departedAt,
      Instant expectedArrivalAt) {}

  private record Base(
      UUID shipment,
      String state,
      int health,
      double progress,
      UUID escort,
      UUID entity,
      String cargo) {}

  private record Point(UUID world, int x, int y, int z) {}
}
