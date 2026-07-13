package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.economy.InfrastructureGateway;
import nl.frontier.economy.InfrastructureValidator;

public final class PostgresInfrastructureGateway implements InfrastructureGateway {
  private static final Set<String> ROLES = Set.of("MAYOR", "ARCHITECT");
  private final TransactionalStore store;
  private final int maximumLength;

  public PostgresInfrastructureGateway(TransactionalStore store) {
    this(store, 256);
  }

  public PostgresInfrastructureGateway(TransactionalStore store, int maximumLength) {
    this.store = store;
    this.maximumLength = maximumLength;
  }

  @Override
  public Context context(UUID city, UUID actor, UUID from, UUID to) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          return context(connection, city, from, to, false);
        });
  }

  @Override
  public Edge register(
      UUID city,
      UUID actor,
      UUID from,
      UUID to,
      int importance,
      InfrastructureValidator.Validation validation,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          Context context = context(connection, city, from, to, true);
          if (!validation.valid()) throw new DomainException("infrastructure survey is invalid");
          UUID edge = UUID.randomUUID();
          double distance =
              Math.sqrt(
                  Math.pow(context.from().x() - context.to().x(), 2)
                      + Math.pow(context.from().y() - context.to().y(), 2)
                      + Math.pow(context.from().z() - context.to().z(), 2));
          var survey = validation.survey();
          long capacity =
              Math.multiplyExact(validation.capacity(), 100L + roadCapacityBonus(connection, city))
                  / 100L;
          String report =
              "{\"samples\":"
                  + survey.samples()
                  + ",\"connected\":"
                  + survey.connectedSamples()
                  + ",\"endpointsConnected\":"
                  + survey.endpointsConnected()
                  + ",\"surfaceQuality\":"
                  + survey.surfaceQuality()
                  + ",\"bridgeSamples\":"
                  + survey.bridgeSamples()
                  + ",\"tunnelSamples\":"
                  + survey.tunnelSamples()
                  + ",\"gateSamples\":"
                  + survey.gateSamples()
                  + ",\"destroyedBridges\":"
                  + survey.destroyedBridges()
                  + ",\"routePoints\":"
                  + survey.route().size()
                  + ",\"bounds\":["
                  + survey.minX()
                  + ","
                  + survey.minY()
                  + ","
                  + survey.minZ()
                  + ","
                  + survey.maxX()
                  + ","
                  + survey.maxY()
                  + ","
                  + survey.maxZ()
                  + "]"
                  + "}";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO road_edges(id,from_node,to_node,distance,capacity,integrity,version,infrastructure_type,traffic,importance,owner_city,minimum_width,surface_quality,maximum_slope,broken_segments,bridge_segments,tunnel_segments,validation_report,validated_at,route_world,route_min_x,route_min_y,route_min_z,route_max_x,route_max_y,route_max_z,route_state) VALUES(?,?,?,?,?,?,0,?,0,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,'VALID')")) {
            statement.setObject(1, edge);
            statement.setObject(2, from);
            statement.setObject(3, to);
            statement.setDouble(4, distance);
            statement.setLong(5, capacity);
            statement.setInt(6, validation.health());
            statement.setString(7, validation.type().name());
            statement.setInt(8, importance);
            statement.setObject(9, city);
            statement.setInt(10, survey.minimumWidth());
            statement.setInt(11, survey.surfaceQuality());
            statement.setDouble(12, survey.maximumSlope());
            statement.setInt(13, survey.brokenSegments());
            statement.setInt(14, survey.bridgeSamples());
            statement.setInt(15, survey.tunnelSamples());
            statement.setString(16, report);
            statement.setTimestamp(17, Timestamp.from(now));
            statement.setObject(18, context.from().world());
            statement.setInt(19, survey.minX());
            statement.setInt(20, survey.minY());
            statement.setInt(21, survey.minZ());
            statement.setInt(22, survey.maxX());
            statement.setInt(23, survey.maxY());
            statement.setInt(24, survey.maxZ());
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE road_edges SET designed_capacity=capacity,physical_health=integrity,bridge_integrity=CASE WHEN infrastructure_type='BRIDGE' THEN integrity ELSE 100 END WHERE id=?")) {
            statement.setObject(1, edge);
            statement.executeUpdate();
          }
          if (!survey.route().isEmpty()) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO road_edge_segments(edge_id,sequence,world_id,x,y,z,target_data) VALUES(?,?,?,?,?,?,?)")) {
              for (var point : survey.route()) {
                statement.setObject(1, edge);
                statement.setInt(2, point.sequence());
                statement.setObject(3, context.from().world());
                statement.setInt(4, point.x());
                statement.setInt(5, point.y());
                statement.setInt(6, point.z());
                statement.setString(7, point.targetData());
                statement.addBatch();
              }
              statement.executeBatch();
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,new_value,reason,occurred_at) VALUES(?,?,'PHYSICAL_INFRASTRUCTURE_VALIDATED','ROAD_EDGE',?,?::jsonb,'physical route survey',?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, actor);
            statement.setObject(3, edge);
            statement.setString(4, report);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
          }
          return new Edge(
              edge,
              from,
              to,
              validation.type(),
              validation.health(),
              capacity,
              0,
              importance,
              city);
        });
  }

  @Override
  public int markDirty(List<ChangedBlock> changes, Instant now) {
    return store.inTransaction(
        connection -> {
          Map<UUID, List<ChangedBlock>> affected = new LinkedHashMap<>();
          for (ChangedBlock change : changes) {
            for (UUID edge : affectedEdges(connection, change))
              affected.computeIfAbsent(edge, ignored -> new ArrayList<>()).add(change);
          }
          for (Map.Entry<UUID, List<ChangedBlock>> entry : affected.entrySet()) {
            UUID edge = entry.getKey();
            ChangedBlock latest = entry.getValue().getLast();
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE road_edges SET route_state='DIRTY',version=version+1 WHERE id=? AND route_state<>'LEGACY'")) {
              statement.setObject(1, edge);
              statement.executeUpdate();
            }
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO dirty_road_edges(edge_id,reason,first_marked_at,last_marked_at,change_count) VALUES(?,?,?,?,?) ON CONFLICT(edge_id) DO UPDATE SET reason=excluded.reason,last_marked_at=excluded.last_marked_at,change_count=dirty_road_edges.change_count+excluded.change_count,lease_owner=NULL,lease_expires_at=NULL")) {
              statement.setObject(1, edge);
              statement.setString(2, latest.reason());
              statement.setTimestamp(3, Timestamp.from(now));
              statement.setTimestamp(4, Timestamp.from(now));
              statement.setInt(5, entry.getValue().size());
              statement.executeUpdate();
            }
            for (ChangedBlock change : entry.getValue()) {
              try (PreparedStatement statement =
                  connection.prepareStatement(
                      "INSERT INTO infrastructure_dirty_changes(edge_id,world_id,x,y,z,reason,observed_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(edge_id,world_id,x,y,z) DO UPDATE SET reason=excluded.reason,observed_at=excluded.observed_at")) {
                statement.setObject(1, edge);
                statement.setObject(2, change.world());
                statement.setInt(3, change.x());
                statement.setInt(4, change.y());
                statement.setInt(5, change.z());
                statement.setString(6, change.reason());
                statement.setTimestamp(7, Timestamp.from(now));
                statement.executeUpdate();
              }
            }
            rerouteShipments(connection, edge);
          }
          return affected.size();
        });
  }

  @Override
  public List<DirtyRoute> leaseDirty(UUID worker, int maximum, Instant now, Instant leaseUntil) {
    return store.inTransaction(
        connection -> {
          List<DirtyRoute> routes = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT d.edge_id,e.owner_city,e.from_node,e.to_node,e.infrastructure_type,e.importance,f.world_id,f.x,f.y,f.z,t.world_id,t.x,t.y,t.z FROM dirty_road_edges d JOIN road_edges e ON e.id=d.edge_id JOIN road_nodes f ON f.id=e.from_node JOIN road_nodes t ON t.id=e.to_node WHERE d.lease_expires_at IS NULL OR d.lease_expires_at<? ORDER BY e.criticality DESC,d.last_marked_at,d.edge_id LIMIT ? FOR UPDATE OF d SKIP LOCKED")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, maximum);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                UUID city = result.getObject(2, UUID.class);
                Point from =
                    new Point(
                        result.getObject(7, UUID.class),
                        result.getInt(8),
                        result.getInt(9),
                        result.getInt(10));
                Point to =
                    new Point(
                        result.getObject(11, UUID.class),
                        result.getInt(12),
                        result.getInt(13),
                        result.getInt(14));
                routes.add(
                    new DirtyRoute(
                        result.getObject(1, UUID.class),
                        city,
                        result.getObject(3, UUID.class),
                        result.getObject(4, UUID.class),
                        nl.frontier.economy.InfrastructureType.valueOf(result.getString(5)),
                        result.getInt(6),
                        new Context(city, from, to)));
              }
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE dirty_road_edges SET lease_owner=?,lease_expires_at=?,attempts=attempts+1 WHERE edge_id=?")) {
            for (DirtyRoute route : routes) {
              statement.setObject(1, worker);
              statement.setTimestamp(2, Timestamp.from(leaseUntil));
              statement.setObject(3, route.edge());
              statement.addBatch();
            }
            statement.executeBatch();
          }
          return List.copyOf(routes);
        });
  }

  @Override
  public HealthResolution applyInspection(
      UUID edge, UUID worker, InfrastructureValidator.Validation validation, Instant now) {
    return store.inTransaction(
        connection -> {
          requireDirtyLease(connection, edge, worker, now);
          EdgeHealth row = edgeHealth(connection, edge);
          var survey = validation.survey();
          String physicalState =
              validation.valid()
                  ? "VALID"
                  : survey.destroyedBridges() > 0 || survey.connectedSamples() == 0
                      ? "DESTROYED"
                      : "BLOCKED";
          int physical = validation.health();
          int effective = physicalState.equals("DESTROYED") ? 0 : Math.min(row.integrity, physical);
          String state =
              !physicalState.equals("VALID")
                  ? physicalState
                  : effective == 0 ? "DESTROYED" : effective < 10 ? "BLOCKED" : "VALID";
          int bridge =
              row.type.equals("BRIDGE")
                  ? state.equals("DESTROYED") ? 0 : Math.min(physical, row.integrity)
                  : 100;
          long capacity = state.equals("VALID") ? row.designedCapacity : 0;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE road_edges SET route_state=?,physical_health=?,bridge_integrity=?,integrity=?,capacity=?,blocked_at=CASE WHEN ?='VALID' THEN NULL ELSE coalesce(blocked_at,?) END,last_health_check_at=?,validated_at=?,validation_report=?::jsonb,version=version+1 WHERE id=?")) {
            statement.setString(1, state);
            statement.setInt(2, physical);
            statement.setInt(3, bridge);
            statement.setInt(4, effective);
            statement.setLong(5, capacity);
            statement.setString(6, state);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setTimestamp(9, Timestamp.from(now));
            statement.setString(10, validationReport(validation));
            statement.setObject(11, edge);
            statement.executeUpdate();
          }
          if (validation.valid() && !survey.route().isEmpty())
            replaceSegments(connection, edge, row.world, survey.route());
          boolean healthy = state.equals("VALID") && effective >= 70;
          UUID maintenance =
              healthy
                  ? completeMaintenance(connection, edge, now)
                  : upsertMaintenance(
                      connection,
                      edge,
                      row,
                      state.equals("VALID") ? "DEGRADED" : state,
                      effective,
                      now);
          updateWarning(connection, edge, row.city, state, effective, now);
          int rerouted = state.equals("VALID") ? 0 : rerouteShipments(connection, edge);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO infrastructure_health_history(id,edge_id,route_state,health_before,health_after,bridge_integrity,violations,checked_at) VALUES(?,?,?,?,?,?,?::jsonb,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, edge);
            statement.setString(3, state);
            statement.setInt(4, row.integrity);
            statement.setInt(5, effective);
            statement.setInt(6, bridge);
            statement.setString(7, violationsJson(validation.violations()));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "DELETE FROM infrastructure_dirty_changes WHERE edge_id=?")) {
            statement.setObject(1, edge);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement("DELETE FROM dirty_road_edges WHERE edge_id=?")) {
            statement.setObject(1, edge);
            statement.executeUpdate();
          }
          return new HealthResolution(edge, state, physical, bridge, maintenance, rerouted);
        });
  }

  @Override
  public void releaseDirty(UUID edge, UUID worker, String reason, Instant now) {
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE dirty_road_edges SET lease_owner=NULL,lease_expires_at=NULL,reason=?,last_marked_at=? WHERE edge_id=? AND lease_owner=?")) {
            statement.setString(1, reason.substring(0, Math.min(64, reason.length())));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, edge);
            statement.setObject(4, worker);
            statement.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public List<NetworkEdge> network() {
    return store.inTransaction(
        connection -> {
          List<NetworkEdge> edges = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT e.id,e.from_node,e.to_node,e.importance,(SELECT count(*) FROM shipment_route_edges r JOIN shipments s ON s.id=r.shipment_id WHERE r.edge_id=e.id AND s.status IN ('TRAVELING','REROUTING','WAITING_ROUTE')),e.route_state IN ('VALID','LEGACY') AND e.integrity>=10 AND e.capacity>0 FROM road_edges e ORDER BY e.id");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              edges.add(
                  new NetworkEdge(
                      result.getObject(1, UUID.class),
                      result.getObject(2, UUID.class),
                      result.getObject(3, UUID.class),
                      result.getInt(4),
                      result.getInt(5),
                      result.getBoolean(6)));
          }
          return List.copyOf(edges);
        });
  }

  @Override
  public void updateCriticality(Map<UUID, Integer> scores, Instant now) {
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE road_edges SET criticality=?,version=version+1 WHERE id=? AND criticality<>?")) {
            for (Map.Entry<UUID, Integer> entry : scores.entrySet()) {
              statement.setInt(1, entry.getValue());
              statement.setObject(2, entry.getKey());
              statement.setInt(3, entry.getValue());
              statement.addBatch();
            }
            statement.executeBatch();
          }
          return null;
        });
  }

  @Override
  public List<MaintenanceOrder> maintenance(UUID city) {
    return store.inTransaction(
        connection -> {
          List<MaintenanceOrder> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,edge_id,status,priority,reason,estimate_minor,repair_order_id,updated_at FROM infrastructure_maintenance_orders WHERE city_id=? ORDER BY CASE priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END,updated_at DESC LIMIT 100")) {
            statement.setObject(1, city);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new MaintenanceOrder(
                        result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class),
                        result.getString(3),
                        result.getString(4),
                        result.getString(5),
                        result.getLong(6),
                        result.getObject(7, UUID.class),
                        result.getTimestamp(8).toInstant()));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<Warning> warnings(UUID city) {
    return store.inTransaction(
        connection -> {
          List<Warning> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,edge_id,warning_key,severity,message,created_at FROM infrastructure_warnings WHERE city_id=? AND status='ACTIVE' ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,created_at DESC LIMIT 100")) {
            statement.setObject(1, city);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new Warning(
                        result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class),
                        result.getString(3),
                        result.getString(4),
                        result.getString(5),
                        result.getTimestamp(6).toInstant()));
            }
          }
          return List.copyOf(values);
        });
  }

  private static List<UUID> affectedEdges(Connection connection, ChangedBlock change)
      throws SQLException {
    List<UUID> edges = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT DISTINCT edge_id FROM road_edge_segments WHERE world_id=? AND x BETWEEN ?-1 AND ?+1 AND z BETWEEN ?-1 AND ?+1 AND y BETWEEN ?-3 AND ?+3")) {
      statement.setObject(1, change.world());
      statement.setInt(2, change.x());
      statement.setInt(3, change.x());
      statement.setInt(4, change.z());
      statement.setInt(5, change.z());
      statement.setInt(6, change.y());
      statement.setInt(7, change.y());
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) edges.add(result.getObject(1, UUID.class));
      }
    }
    return List.copyOf(edges);
  }

  private static int rerouteShipments(Connection connection, UUID edge) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE shipments SET status='REROUTING',expected_arrival_at=NULL,version=version+1 WHERE id IN (SELECT shipment_id FROM shipment_route_edges WHERE edge_id=?) AND status='TRAVELING'")) {
      statement.setObject(1, edge);
      return statement.executeUpdate();
    }
  }

  private static void requireDirtyLease(Connection connection, UUID edge, UUID worker, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM dirty_road_edges WHERE edge_id=? AND lease_owner=? AND lease_expires_at>=? FOR UPDATE")) {
      statement.setObject(1, edge);
      statement.setObject(2, worker);
      statement.setTimestamp(3, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("infrastructure dirty lease is not owned");
      }
    }
  }

  private static EdgeHealth edgeHealth(Connection connection, UUID edge) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT owner_city,infrastructure_type,integrity,designed_capacity,criticality,route_world FROM road_edges WHERE id=? FOR UPDATE")) {
      statement.setObject(1, edge);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("infrastructure edge not found");
        return new EdgeHealth(
            result.getObject(1, UUID.class),
            result.getString(2),
            result.getInt(3),
            result.getLong(4),
            result.getInt(5),
            result.getObject(6, UUID.class));
      }
    }
  }

  private static void replaceSegments(
      Connection connection,
      UUID edge,
      UUID world,
      List<nl.frontier.economy.InfrastructureSurvey.RoutePoint> route)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM road_edge_segments WHERE edge_id=?")) {
      statement.setObject(1, edge);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO road_edge_segments(edge_id,sequence,world_id,x,y,z,target_data) VALUES(?,?,?,?,?,?,?)")) {
      for (var point : route) {
        statement.setObject(1, edge);
        statement.setInt(2, point.sequence());
        statement.setObject(3, world);
        statement.setInt(4, point.x());
        statement.setInt(5, point.y());
        statement.setInt(6, point.z());
        statement.setString(7, point.targetData());
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private static UUID upsertMaintenance(
      Connection connection, UUID edge, EdgeHealth row, String state, int physical, Instant now)
      throws SQLException {
    String priority =
        state.equals("DESTROYED") || row.criticality >= 70
            ? "CRITICAL"
            : row.type.equals("BRIDGE") ? "HIGH" : "NORMAL";
    long estimate = Math.max(75, (100L - physical) * 10L);
    UUID maintenance;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO infrastructure_maintenance_orders(id,edge_id,city_id,status,priority,reason,estimate_minor,created_at,updated_at) VALUES(?,?,?,'OPEN',?,?,?,?,?) ON CONFLICT(edge_id) WHERE status IN ('OPEN','FUNDED','REPAIRING') DO UPDATE SET priority=excluded.priority,reason=excluded.reason,estimate_minor=greatest(infrastructure_maintenance_orders.estimate_minor,excluded.estimate_minor),updated_at=excluded.updated_at RETURNING id")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, edge);
      statement.setObject(3, row.city);
      statement.setString(4, priority);
      statement.setString(5, state);
      statement.setLong(6, estimate);
      statement.setTimestamp(7, Timestamp.from(now));
      statement.setTimestamp(8, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        maintenance = result.getObject(1, UUID.class);
      }
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO infrastructure_maintenance_targets(maintenance_order_id,world_id,x,y,z,target_data) SELECT DISTINCT ?,d.world_id,d.x,d.y,d.z,coalesce((SELECT s.target_data FROM road_edge_segments s WHERE s.edge_id=d.edge_id ORDER BY abs(s.x-d.x)+abs(s.y-d.y)+abs(s.z-d.z),s.sequence LIMIT 1),'minecraft:stone_bricks') FROM infrastructure_dirty_changes d WHERE d.edge_id=? ON CONFLICT DO NOTHING")) {
      statement.setObject(1, maintenance);
      statement.setObject(2, edge);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO infrastructure_maintenance_targets(maintenance_order_id,world_id,x,y,z,expected_data,target_data) SELECT ?,s.world_id,s.x,s.y,s.z,s.target_data,s.target_data FROM road_edge_segments s WHERE s.edge_id=? AND NOT EXISTS(SELECT 1 FROM infrastructure_maintenance_targets t WHERE t.maintenance_order_id=?) ORDER BY s.sequence LIMIT 1 ON CONFLICT DO NOTHING")) {
      statement.setObject(1, maintenance);
      statement.setObject(2, edge);
      statement.setObject(3, maintenance);
      statement.executeUpdate();
    }
    return maintenance;
  }

  private static UUID completeMaintenance(Connection connection, UUID edge, Instant now)
      throws SQLException {
    UUID id = null;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE infrastructure_maintenance_orders SET status='COMPLETED',completed_at=?,updated_at=? WHERE edge_id=? AND status IN ('OPEN','FUNDED','REPAIRING') RETURNING id")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setTimestamp(2, Timestamp.from(now));
      statement.setObject(3, edge);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) id = result.getObject(1, UUID.class);
      }
    }
    return id;
  }

  private static void updateWarning(
      Connection connection, UUID edge, UUID city, String state, int health, Instant now)
      throws SQLException {
    if (state.equals("VALID") && health >= 70) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE infrastructure_warnings SET status='RESOLVED',resolved_at=? WHERE edge_id=? AND status='ACTIVE'")) {
        statement.setTimestamp(1, Timestamp.from(now));
        statement.setObject(2, edge);
        statement.executeUpdate();
      }
      return;
    }
    String key =
        state.equals("DESTROYED")
            ? "ROUTE_DESTROYED"
            : state.equals("BLOCKED") ? "ROUTE_BLOCKED" : "ROUTE_DEGRADED";
    String severity = state.equals("DESTROYED") ? "CRITICAL" : "WARNING";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE infrastructure_warnings SET status='RESOLVED',resolved_at=? WHERE edge_id=? AND status='ACTIVE' AND warning_key<>?")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setObject(2, edge);
      statement.setString(3, key);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO infrastructure_warnings(id,city_id,edge_id,warning_key,severity,message,status,created_at) VALUES(?,?,?,?,?,?,'ACTIVE',?) ON CONFLICT(edge_id,warning_key) WHERE status='ACTIVE' DO UPDATE SET severity=excluded.severity,message=excluded.message")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, city);
      statement.setObject(3, edge);
      statement.setString(4, key);
      statement.setString(5, severity);
      statement.setString(
          6,
          "Infrastructure route "
              + edge
              + " is "
              + (state.equals("VALID") ? "degraded (" + health + "%)" : state.toLowerCase()));
      statement.setTimestamp(7, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static String validationReport(InfrastructureValidator.Validation validation) {
    var survey = validation.survey();
    return "{\"valid\":"
        + validation.valid()
        + ",\"health\":"
        + validation.health()
        + ",\"connected\":"
        + survey.connectedSamples()
        + ",\"samples\":"
        + survey.samples()
        + ",\"violations\":"
        + violationsJson(validation.violations())
        + "}";
  }

  private static String violationsJson(List<String> violations) {
    return violations.stream()
        .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "'") + "\"")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private record EdgeHealth(
      UUID city, String type, int integrity, long designedCapacity, int criticality, UUID world) {}

  private static int roadCapacityBonus(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(max((u.effect->>'roadCapacityPercent')::int),0) FROM kingdom_members m JOIN kingdom_unlocks u ON u.kingdom_id=m.kingdom_id WHERE m.city_id=? AND u.content_type='RESEARCH'")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private Context context(Connection connection, UUID city, UUID from, UUID to, boolean lock)
      throws SQLException {
    if (from.equals(to)) throw new DomainException("infrastructure edge needs two nodes");
    Point first = point(connection, from, lock);
    Point second = point(connection, to, lock);
    UUID owner = owner(connection, from);
    if (!owner.equals(city)) throw new DomainException("origin node belongs to another settlement");
    if (!first.world().equals(second.world()))
      throw new DomainException("infrastructure nodes are in different worlds");
    double horizontal = Math.hypot(first.x() - second.x(), first.z() - second.z());
    if (horizontal > maximumLength)
      throw new DomainException(
          "physical infrastructure is limited to " + maximumLength + " blocks per edge");
    return new Context(city, first, second);
  }

  private static Point point(Connection connection, UUID node, boolean lock) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT world_id,x,y,z FROM road_nodes WHERE id=?" + (lock ? " FOR SHARE" : ""))) {
      statement.setObject(1, node);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("road node not found");
        return new Point(
            result.getObject(1, UUID.class), result.getInt(2), result.getInt(3), result.getInt(4));
      }
    }
  }

  private static UUID owner(Connection connection, UUID node) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT city_id FROM road_nodes WHERE id=?")) {
      statement.setObject(1, node);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("road node not found");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static void requireRole(Connection connection, UUID city, UUID actor)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=?")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !ROLES.contains(result.getString(1)))
          throw new DomainException("settlement role cannot register infrastructure");
      }
    }
  }
}
