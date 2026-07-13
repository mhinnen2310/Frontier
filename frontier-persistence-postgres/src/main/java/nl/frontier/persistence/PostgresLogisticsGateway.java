package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.economy.LogisticsGateway;
import nl.frontier.economy.RoutePlanner;

public final class PostgresLogisticsGateway implements LogisticsGateway {
  private static final Set<String> ROLES = Set.of("MAYOR", "TREASURER", "ARCHITECT");
  private final TransactionalStore store;
  private final RoutePlanner planner = new RoutePlanner();

  public PostgresLogisticsGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public RoadNode registerNode(
      UUID city, UUID actor, UUID world, int x, int y, int z, String type, Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          if (!Set.of(
                  "WAREHOUSE",
                  "MARKET",
                  "GATE",
                  "HARBOR",
                  "DEPOT",
                  "ROAD",
                  "BRIDGE",
                  "TUNNEL",
                  "WATCHTOWER")
              .contains(type)) throw new DomainException("unsupported road node type");
          requireControlledChunk(
              connection, city, world, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
          UUID id = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO road_nodes(id,city_id,world_id,x,y,z,node_type,integrity,version) VALUES(?,?,?,?,?,?,?,100,0)")) {
            statement.setObject(1, id);
            statement.setObject(2, city);
            statement.setObject(3, world);
            statement.setInt(4, x);
            statement.setInt(5, y);
            statement.setInt(6, z);
            statement.setString(7, type);
            statement.executeUpdate();
          }
          audit(connection, actor, "ROAD_NODE_REGISTERED", "ROAD_NODE", id, now);
          return new RoadNode(id, city, world, x, y, z, type, 100);
        });
  }

  @Override
  public RoadEdge connect(UUID city, UUID actor, UUID from, UUID to, long capacity, Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          NodeRow first = node(connection, from);
          NodeRow second = node(connection, to);
          if (!first.city.equals(city))
            throw new DomainException("origin road node is not owned by city");
          if (!first.world.equals(second.world))
            throw new DomainException("road nodes are in different worlds");
          if (from.equals(to)) throw new DomainException("road edge needs two nodes");
          double distance =
              Math.sqrt(
                  Math.pow(first.x - second.x, 2)
                      + Math.pow(first.y - second.y, 2)
                      + Math.pow(first.z - second.z, 2));
          UUID id = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO road_edges(id,from_node,to_node,distance,capacity,integrity,version) VALUES(?,?,?,?,?,100,0)")) {
            statement.setObject(1, id);
            statement.setObject(2, from);
            statement.setObject(3, to);
            statement.setDouble(4, distance);
            statement.setLong(5, capacity);
            statement.executeUpdate();
          }
          audit(connection, actor, "ROAD_EDGE_REGISTERED", "ROAD_EDGE", id, now);
          return new RoadEdge(id, from, to, distance, capacity, 100);
        });
  }

  @Override
  public Shipment createShipment(
      UUID city,
      UUID actor,
      UUID originWarehouse,
      UUID destinationWarehouse,
      UUID originNode,
      UUID destinationNode,
      String commodity,
      long quantity,
      String carrier,
      long declaredValue,
      UUID idempotency,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          Shipment existing = byIdempotency(connection, idempotency);
          if (existing != null) return existing;
          requireWarehouseOwner(connection, originWarehouse, city);
          requireWarehouse(connection, destinationWarehouse);
          lockStock(connection, originWarehouse, commodity);
          if (available(connection, originWarehouse, commodity) < quantity)
            throw new DomainException("insufficient shipment stock");
          RoutePlanner.Route route = plan(connection, originNode, destinationNode);
          UUID shipment = UUID.randomUUID();
          UUID reservation = UUID.randomUUID();
          changeStock(connection, originWarehouse, commodity, -quantity, quantity);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO stock_reservations(id,warehouse_id,owner_type,owner_id,commodity_key,quantity,consumed,status,version) VALUES(?,?,'SHIPMENT',?,?,?,0,'ACTIVE',0)")) {
            statement.setObject(1, reservation);
            statement.setObject(2, originWarehouse);
            statement.setObject(3, shipment);
            statement.setString(4, commodity);
            statement.setLong(5, quantity);
            statement.executeUpdate();
          }
          UUID routeId = route.reachable() ? UUID.randomUUID() : null;
          Instant expected =
              route.reachable()
                  ? now.plus(
                      Math.max(1, (long) Math.ceil(route.weightedDistance() / 4.0)),
                      ChronoUnit.SECONDS)
                  : null;
          String status = route.reachable() ? "TRAVELING" : "WAITING_ROUTE";
          String manifest = "{\"" + commodity + "\":" + quantity + "}";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO shipments(id,origin_storage_id,destination_storage_id,manifest,route_id,carrier_type,status,declared_value_minor,insured_value_minor,departed_at,expected_arrival_at,version,owner_city_id,origin_node_id,destination_node_id,idempotency_key) VALUES(?,?,?,?::jsonb,?,?,?,?,0,?,?,0,?,?,?,?)")) {
            statement.setObject(1, shipment);
            statement.setObject(2, originWarehouse);
            statement.setObject(3, destinationWarehouse);
            statement.setString(4, manifest);
            statement.setObject(5, routeId);
            statement.setString(6, carrier);
            statement.setString(7, status);
            statement.setLong(8, declaredValue);
            statement.setTimestamp(9, Timestamp.from(now));
            statement.setTimestamp(10, expected == null ? null : Timestamp.from(expected));
            statement.setObject(11, city);
            statement.setObject(12, originNode);
            statement.setObject(13, destinationNode);
            statement.setObject(14, idempotency);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO shipment_items(shipment_id,commodity_key,quantity,reservation_id) VALUES(?,?,?,?)")) {
            statement.setObject(1, shipment);
            statement.setString(2, commodity);
            statement.setLong(3, quantity);
            statement.setObject(4, reservation);
            statement.executeUpdate();
          }
          if (route.reachable()) insertRoute(connection, routeId, shipment, route, now);
          audit(connection, actor, "SHIPMENT_CREATED", "SHIPMENT", shipment, now);
          return load(connection, shipment);
        });
  }

  @Override
  public List<Shipment> shipments(UUID city) {
    return store.inTransaction(
        connection -> {
          List<Shipment> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT s.id,s.origin_storage_id,s.destination_storage_id,i.commodity_key,i.quantity,s.carrier_type,s.status,s.departed_at,s.expected_arrival_at,s.arrived_at FROM shipments s JOIN shipment_items i ON i.shipment_id=s.id WHERE s.owner_city_id=? ORDER BY s.departed_at DESC LIMIT 100")) {
            statement.setObject(1, city);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(map(result));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public CycleReport cycle(int maximumShipments, Instant now) {
    int visited = 0;
    int delivered = 0;
    int waiting = 0;
    for (int index = 0; index < maximumShipments; index++) {
      CycleResult result = store.inTransaction(connection -> processOne(connection, now));
      if (result == CycleResult.NONE) break;
      visited++;
      if (result == CycleResult.DELIVERED) delivered++;
      if (result == CycleResult.WAITING) waiting++;
    }
    return new CycleReport(visited, delivered, waiting);
  }

  private static CycleResult processOne(Connection connection, Instant now) throws SQLException {
    DueRow shipment;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,origin_storage_id,destination_storage_id,origin_node_id,destination_node_id,status,expected_arrival_at FROM shipments WHERE status IN ('TRAVELING','WAITING_ROUTE','REROUTING') AND (status<>'TRAVELING' OR expected_arrival_at<=?) ORDER BY COALESCE(expected_arrival_at,departed_at),id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
      statement.setTimestamp(1, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return CycleResult.NONE;
        shipment =
            new DueRow(
                result.getObject(1, UUID.class),
                result.getObject(2, UUID.class),
                result.getObject(3, UUID.class),
                result.getObject(4, UUID.class),
                result.getObject(5, UUID.class),
                result.getString(6));
      }
    }
    RoutePlanner.Route route =
        planStatic(connection, shipment.originNode, shipment.destinationNode);
    if (!route.reachable()) {
      setWaiting(connection, shipment.id);
      return CycleResult.WAITING;
    }
    if (!shipment.status.equals("TRAVELING")) {
      UUID routeId = UUID.randomUUID();
      Instant expected =
          now.plus(
              Math.max(1, (long) Math.ceil(route.weightedDistance() / 4.0)), ChronoUnit.SECONDS);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE shipments SET route_id=?,status='TRAVELING',expected_arrival_at=?,version=version+1 WHERE id=?")) {
        statement.setObject(1, routeId);
        statement.setTimestamp(2, Timestamp.from(expected));
        statement.setObject(3, shipment.id);
        statement.executeUpdate();
      }
      insertRoute(connection, routeId, shipment.id, route, now);
      return CycleResult.REROUTED;
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT commodity_key,quantity,reservation_id FROM shipment_items WHERE shipment_id=?")) {
      statement.setObject(1, shipment.id);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          String commodity = result.getString(1);
          long quantity = result.getLong(2);
          UUID reservation = result.getObject(3, UUID.class);
          lockStock(connection, shipment.origin, commodity);
          changeStock(connection, shipment.origin, commodity, 0, -quantity);
          lockStock(connection, shipment.destination, commodity);
          changeStock(connection, shipment.destination, commodity, quantity, 0);
          try (PreparedStatement consume =
              connection.prepareStatement(
                  "UPDATE stock_reservations SET consumed=quantity,status='CONSUMED',version=version+1 WHERE id=? AND status='ACTIVE'")) {
            consume.setObject(1, reservation);
            if (consume.executeUpdate() != 1)
              throw new DomainException("shipment reservation invariant violated");
          }
        }
      }
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE shipments SET status='ARRIVED',arrived_at=?,version=version+1 WHERE id=?")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setObject(2, shipment.id);
      statement.executeUpdate();
    }
    outbox(connection, shipment.id, "ShipmentArrived", now);
    return CycleResult.DELIVERED;
  }

  private RoutePlanner.Route plan(Connection connection, UUID from, UUID to) throws SQLException {
    return planner.plan(from, to, edges(connection));
  }

  private static RoutePlanner.Route planStatic(Connection connection, UUID from, UUID to)
      throws SQLException {
    return new RoutePlanner().plan(from, to, edges(connection));
  }

  private static List<RoutePlanner.Edge> edges(Connection connection) throws SQLException {
    List<RoutePlanner.Edge> values = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT from_node,to_node,distance,capacity,integrity FROM road_edges WHERE integrity>=10 AND capacity>0");
        ResultSet result = statement.executeQuery()) {
      while (result.next())
        values.add(
            new RoutePlanner.Edge(
                result.getObject(1, UUID.class),
                result.getObject(2, UUID.class),
                result.getDouble(3),
                result.getLong(4),
                result.getInt(5)));
    }
    return values;
  }

  private static void insertRoute(
      Connection connection, UUID routeId, UUID shipment, RoutePlanner.Route route, Instant now)
      throws SQLException {
    String nodes =
        route.nodes().stream()
            .map(id -> "\"" + id + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO shipment_routes(id,shipment_id,nodes,weighted_distance,calculated_at,version) VALUES(?,?,?::jsonb,?,?,0) ON CONFLICT(shipment_id) DO UPDATE SET id=EXCLUDED.id,nodes=EXCLUDED.nodes,weighted_distance=EXCLUDED.weighted_distance,calculated_at=EXCLUDED.calculated_at,version=shipment_routes.version+1")) {
      statement.setObject(1, routeId);
      statement.setObject(2, shipment);
      statement.setString(3, nodes);
      statement.setDouble(4, route.weightedDistance());
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
    for (int index = 1; index < route.nodes().size(); index++) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE road_edges SET traffic=traffic+1,version=version+1 WHERE (from_node=? AND to_node=?) OR (from_node=? AND to_node=?)")) {
        UUID from = route.nodes().get(index - 1);
        UUID to = route.nodes().get(index);
        statement.setObject(1, from);
        statement.setObject(2, to);
        statement.setObject(3, to);
        statement.setObject(4, from);
        statement.executeUpdate();
      }
    }
  }

  private static Shipment byIdempotency(Connection connection, UUID key) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT s.id,s.origin_storage_id,s.destination_storage_id,i.commodity_key,i.quantity,s.carrier_type,s.status,s.departed_at,s.expected_arrival_at,s.arrived_at FROM shipments s JOIN shipment_items i ON i.shipment_id=s.id WHERE s.idempotency_key=?")) {
      statement.setObject(1, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? map(result) : null;
      }
    }
  }

  private static Shipment load(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT s.id,s.origin_storage_id,s.destination_storage_id,i.commodity_key,i.quantity,s.carrier_type,s.status,s.departed_at,s.expected_arrival_at,s.arrived_at FROM shipments s JOIN shipment_items i ON i.shipment_id=s.id WHERE s.id=?")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("shipment not found");
        return map(result);
      }
    }
  }

  private static Shipment map(ResultSet result) throws SQLException {
    return new Shipment(
        result.getObject(1, UUID.class),
        result.getObject(2, UUID.class),
        result.getObject(3, UUID.class),
        result.getString(4),
        result.getLong(5),
        result.getString(6),
        result.getString(7),
        instant(result, 8),
        instant(result, 9),
        instant(result, 10));
  }

  private static Instant instant(ResultSet result, int index) throws SQLException {
    Timestamp value = result.getTimestamp(index);
    return value == null ? null : value.toInstant();
  }

  private static NodeRow node(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT city_id,world_id,x,y,z FROM road_nodes WHERE id=? FOR SHARE")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("road node not found");
        return new NodeRow(
            result.getObject(1, UUID.class),
            result.getObject(2, UUID.class),
            result.getInt(3),
            result.getInt(4),
            result.getInt(5));
      }
    }
  }

  private static void requireWarehouseOwner(Connection connection, UUID warehouse, UUID city)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM warehouses WHERE id=? AND city_id=? AND status='ACTIVE' FOR SHARE")) {
      statement.setObject(1, warehouse);
      statement.setObject(2, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("origin warehouse not owned by city");
      }
    }
  }

  private static void requireWarehouse(Connection connection, UUID warehouse) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM warehouses WHERE id=? AND status='ACTIVE' FOR SHARE")) {
      statement.setObject(1, warehouse);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("destination warehouse unavailable");
      }
    }
  }

  private static void lockStock(Connection connection, UUID warehouse, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity,version) VALUES(?,?,0,0,0) ON CONFLICT DO NOTHING")) {
      statement.setObject(1, warehouse);
      statement.setString(2, commodity);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM warehouse_stock WHERE warehouse_id=? AND commodity_key=? FOR UPDATE")) {
      statement.setObject(1, warehouse);
      statement.setString(2, commodity);
      statement.executeQuery().close();
    }
  }

  private static long available(Connection connection, UUID warehouse, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT available_quantity FROM warehouse_stock WHERE warehouse_id=? AND commodity_key=?")) {
      statement.setObject(1, warehouse);
      statement.setString(2, commodity);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getLong(1) : 0;
      }
    }
  }

  private static void changeStock(
      Connection connection, UUID warehouse, String commodity, long available, long reserved)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE warehouse_stock SET available_quantity=available_quantity+?,reserved_quantity=reserved_quantity+?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity+?>=0 AND reserved_quantity+?>=0")) {
      statement.setLong(1, available);
      statement.setLong(2, reserved);
      statement.setObject(3, warehouse);
      statement.setString(4, commodity);
      statement.setLong(5, available);
      statement.setLong(6, reserved);
      if (statement.executeUpdate() != 1) throw new DomainException("stock invariant violated");
    }
  }

  private static void setWaiting(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE shipments SET status='WAITING_ROUTE',expected_arrival_at=NULL,version=version+1 WHERE id=?")) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }

  private static void requireRole(Connection connection, UUID city, UUID actor)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=? FOR SHARE")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !ROLES.contains(result.getString(1)))
          throw new DomainException("settlement role does not allow logistics management");
      }
    }
  }

  private static void requireControlledChunk(
      Connection connection, UUID city, UUID world, int chunkX, int chunkZ) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM city_claims WHERE city_id=? AND world_id=? AND chunk_x=? AND chunk_z=? AND state IN ('CAPITAL','CONTROLLED') FOR SHARE")) {
      statement.setObject(1, city);
      statement.setObject(2, world);
      statement.setInt(3, chunkX);
      statement.setInt(4, chunkZ);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next())
          throw new DomainException("road node must be inside controlled territory");
      }
    }
  }

  private static void audit(
      Connection connection, UUID actor, String action, String type, UUID id, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,occurred_at) VALUES(?,?,?,?,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, actor);
      statement.setString(3, action);
      statement.setString(4, type);
      statement.setObject(5, id);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void outbox(Connection connection, UUID id, String event, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,'SHIPMENT',?,?, '{}'::jsonb,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, id);
      statement.setString(3, event);
      statement.setTimestamp(4, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private enum CycleResult {
    NONE,
    WAITING,
    REROUTED,
    DELIVERED
  }

  private record NodeRow(UUID city, UUID world, int x, int y, int z) {}

  private record DueRow(
      UUID id,
      UUID origin,
      UUID destination,
      UUID originNode,
      UUID destinationNode,
      String status) {}
}
