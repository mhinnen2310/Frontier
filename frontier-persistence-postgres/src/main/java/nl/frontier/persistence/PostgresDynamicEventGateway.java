package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.world.DynamicEventGateway;

public final class PostgresDynamicEventGateway implements DynamicEventGateway {
  private static final Duration COOLDOWN = Duration.ofDays(1);
  private final TransactionalStore store;

  public PostgresDynamicEventGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public CycleReport detect(int maximum, Instant now) {
    return store.inTransaction(
        connection -> {
          List<Candidate> candidates = candidates(connection, now);
          int detected = 0;
          int skipped = 0;
          for (Candidate candidate : candidates) {
            if (detected >= maximum) break;
            if (cooldown(connection, candidate.source, now)) {
              skipped++;
              continue;
            }
            create(connection, candidate, now);
            detected++;
          }
          return new CycleReport(detected, skipped);
        });
  }

  @Override
  public List<EventSummary> available(UUID player, Instant now) {
    return store.inTransaction(
        connection -> {
          List<EventSummary> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT e.id,e.event_key,e.state,e.city_id,e.kingdom_id,e.shipment_id,e.road_edge_id,coalesce(o.progress,0),coalesce(o.target,100),exists(SELECT 1 FROM dynamic_event_participants p WHERE p.event_id=e.id AND p.player_id=?),e.expires_at FROM world_events e LEFT JOIN event_objectives o ON o.event_id=e.id WHERE e.state IN ('SCHEDULED','ANNOUNCED','ACTIVE') AND (e.expires_at IS NULL OR e.expires_at>?) ORDER BY e.state_at DESC LIMIT 100")) {
            statement.setObject(1, player);
            statement.setTimestamp(2, Timestamp.from(now));
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(summary(result));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public EventSummary join(UUID event, UUID player, String role, Instant now) {
    return store.inTransaction(
        connection -> {
          lockAvailable(connection, event, now);
          requireCitizen(connection, player);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO dynamic_event_participants(event_id,player_id,role,joined_at) VALUES(?,?,?,?) ON CONFLICT(event_id,player_id) DO UPDATE SET role=excluded.role")) {
            statement.setObject(1, event);
            statement.setObject(2, player);
            statement.setString(3, role);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE caravans SET escort_player=?,version=version+1 WHERE shipment_id=(SELECT shipment_id FROM world_events WHERE id=? AND event_key='ESCORT')")) {
            statement.setObject(1, player);
            statement.setObject(2, event);
            statement.executeUpdate();
          }
          return load(connection, event, player);
        });
  }

  @Override
  public EventSummary respond(UUID event, UUID player, long contribution, Instant now) {
    return store.inTransaction(
        connection -> {
          lockAvailable(connection, event, now);
          if (!participant(connection, event, player))
            throw new DomainException("join the event before responding");
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO dynamic_event_responses(event_id,player_id,contribution,responded_at,version) VALUES(?,?,?,?,0) ON CONFLICT(event_id,player_id) DO UPDATE SET contribution=dynamic_event_responses.contribution+excluded.contribution,responded_at=excluded.responded_at,version=dynamic_event_responses.version+1")) {
            statement.setObject(1, event);
            statement.setObject(2, player);
            statement.setLong(3, contribution);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE event_objectives SET progress=least(target,progress+?),state=CASE WHEN progress+?>=target THEN 'COMPLETED' ELSE 'ACTIVE' END,version=version+1 WHERE event_id=?")) {
            statement.setLong(1, contribution);
            statement.setLong(2, contribution);
            statement.setObject(3, event);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE world_events SET state='RESOLVED',state_at=?,version=version+1 WHERE id=? AND EXISTS(SELECT 1 FROM event_objectives WHERE event_id=? AND state='COMPLETED')")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, event);
            statement.setObject(3, event);
            statement.executeUpdate();
          }
          return load(connection, event, player);
        });
  }

  private static List<Candidate> candidates(Connection connection, Instant now)
      throws SQLException {
    List<Candidate> values = new ArrayList<>();
    add(
        connection,
        values,
        "ESCORT",
        "SELECT 'escort:'||c.shipment_id,c.shipment_id::text,w.city_id,NULL::uuid,c.shipment_id,NULL::uuid FROM caravans c JOIN shipments s ON s.id=c.shipment_id JOIN warehouses w ON w.id=s.origin_storage_id WHERE c.state NOT IN ('DESPAWNED','UNLOADED') AND c.escort_player IS NULL ORDER BY c.updated_at LIMIT 10");
    add(
        connection,
        values,
        "CONVOY",
        "SELECT 'convoy:'||w.city_id,w.city_id::text,w.city_id,NULL::uuid,(array_agg(c.shipment_id ORDER BY c.shipment_id))[1],NULL::uuid FROM caravans c JOIN shipments s ON s.id=c.shipment_id JOIN warehouses w ON w.id=s.origin_storage_id WHERE c.state NOT IN ('DESPAWNED','UNLOADED') GROUP BY w.city_id HAVING count(*)>=2 LIMIT 5");
    add(
        connection,
        values,
        "CIVIL_UNREST",
        "SELECT 'unrest:'||c.id,c.id::text,c.id,NULL::uuid,NULL::uuid,NULL::uuid FROM cities c LEFT JOIN city_population_state p ON p.city_id=c.id WHERE c.prosperity<30 OR coalesce(p.safety,50)<30 ORDER BY c.prosperity LIMIT 10");
    add(
        connection,
        values,
        "KINGDOM_REQUEST",
        "SELECT 'kingdom-request:'||m.id,m.id::text,km.city_id,m.kingdom_id,NULL::uuid,NULL::uuid FROM mega_projects m JOIN LATERAL(SELECT city_id FROM kingdom_members WHERE kingdom_id=m.kingdom_id ORDER BY city_id LIMIT 1) km ON true WHERE m.status='ACTIVE' ORDER BY m.project_key LIMIT 10");
    add(
        connection,
        values,
        "SETTLEMENT_REQUEST",
        "SELECT 'settlement-request:'||p.city_id,p.city_id::text,p.city_id,NULL::uuid,NULL::uuid,NULL::uuid FROM city_population_state p WHERE p.food_security<30 ORDER BY p.food_security LIMIT 10");
    add(
        connection,
        values,
        "BRIDGE_COLLAPSE",
        "SELECT 'bridge-collapse:'||e.id,e.id::text,e.owner_city,NULL::uuid,NULL::uuid,e.id FROM road_edges e WHERE e.infrastructure_type='BRIDGE' AND e.integrity<=10 ORDER BY e.integrity LIMIT 10");
    add(
        connection,
        values,
        "MINE_COLLAPSE",
        "SELECT 'mine-collapse:'||b.id,b.id::text,b.city_id,NULL::uuid,NULL::uuid,NULL::uuid FROM city_buildings b WHERE b.building_type IN ('MINE','MINING') AND b.integrity<=20 AND b.status<>'DESTROYED' ORDER BY b.integrity LIMIT 10");
    add(
        connection,
        values,
        "REFUGEES",
        "SELECT 'refugees:'||c.id,c.id::text,c.defender_city_id,NULL::uuid,NULL::uuid,NULL::uuid FROM campaigns c WHERE c.phase='ACTIVE' ORDER BY c.active_at LIMIT 10");
    add(
        connection,
        values,
        "TRADE_FESTIVAL",
        "SELECT 'trade-festival:'||r.id,r.id::text,s.city_id,NULL::uuid,NULL::uuid,NULL::uuid FROM world_regions r JOIN LATERAL(SELECT city_id FROM city_world_simulation_state WHERE region_key=r.region_key ORDER BY city_id LIMIT 1)s ON true WHERE r.prosperity>=75 AND r.trade_activity>0 ORDER BY r.trade_activity DESC LIMIT 10");
    return values;
  }

  private static void add(Connection connection, List<Candidate> target, String key, String sql)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      while (result.next())
        target.add(
            new Candidate(
                key,
                result.getString(1),
                result.getString(2),
                result.getObject(3, UUID.class),
                result.getObject(4, UUID.class),
                result.getObject(5, UUID.class),
                result.getObject(6, UUID.class)));
    }
  }

  private static boolean cooldown(Connection connection, String source, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM world_events WHERE payload->>'sourceKey'=? AND state_at>=? AND state<>'ARCHIVED'")) {
      statement.setString(1, source);
      statement.setTimestamp(2, Timestamp.from(now.minus(COOLDOWN)));
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void create(Connection connection, Candidate value, Instant now)
      throws SQLException {
    UUID event = UUID.randomUUID();
    UUID region = region(connection, value.city);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO world_events(id,region_id,category,event_key,state,state_at,payload,version,severity,city_id,kingdom_id,shipment_id,road_edge_id,expires_at) VALUES(?,?,?,?,'SCHEDULED',?,jsonb_build_object('sourceKey',?,'source',?),0,50,?,?,?,?,?)")) {
      statement.setObject(1, event);
      statement.setObject(2, region);
      statement.setString(3, category(value.key));
      statement.setString(4, value.key);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.setString(6, value.source);
      statement.setString(7, value.label);
      statement.setObject(8, value.city);
      statement.setObject(9, value.kingdom);
      statement.setObject(10, value.shipment);
      statement.setObject(11, value.edge);
      statement.setTimestamp(12, Timestamp.from(now.plus(Duration.ofHours(24))));
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO event_objectives(id,event_id,objective_key,progress,target,state,version) VALUES(?,?,?,0,100,'ACTIVE',0)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, event);
      statement.setString(3, value.key + "_RESPONSE");
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO event_rewards(id,event_id,reward_key,amount,status) VALUES(?,?,?,100,'PENDING')")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, event);
      statement.setString(3, value.key + "_REWARD");
      statement.executeUpdate();
    }
  }

  private static String category(String key) {
    return switch (key) {
      case "ESCORT", "CONVOY", "KINGDOM_REQUEST", "SETTLEMENT_REQUEST" -> "SOCIAL";
      case "CIVIL_UNREST", "REFUGEES" -> "MILITARY";
      case "BRIDGE_COLLAPSE", "MINE_COLLAPSE" -> "NATURAL";
      default -> "ECONOMIC";
    };
  }

  private static UUID region(Connection connection, UUID city) throws SQLException {
    if (city == null) return null;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT r.id FROM city_world_simulation_state s JOIN world_regions r ON r.region_key=s.region_key WHERE s.city_id=?")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getObject(1, UUID.class) : null;
      }
    }
  }

  private static void lockAvailable(Connection connection, UUID event, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT state,expires_at FROM world_events WHERE id=? FOR UPDATE")) {
      statement.setObject(1, event);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("dynamic event not found");
        if (!List.of("SCHEDULED", "ANNOUNCED", "ACTIVE").contains(result.getString(1)))
          throw new DomainException("dynamic event is no longer open");
        Timestamp expires = result.getTimestamp(2);
        if (expires != null && !expires.toInstant().isAfter(now))
          throw new DomainException("dynamic event expired");
      }
    }
  }

  private static void requireCitizen(Connection connection, UUID player) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM city_members WHERE player_id=?"); ) {
      statement.setObject(1, player);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("join a settlement before joining events");
      }
    }
  }

  private static boolean participant(Connection connection, UUID event, UUID player)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM dynamic_event_participants WHERE event_id=? AND player_id=?")) {
      statement.setObject(1, event);
      statement.setObject(2, player);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static EventSummary load(Connection connection, UUID event, UUID player)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT e.id,e.event_key,e.state,e.city_id,e.kingdom_id,e.shipment_id,e.road_edge_id,coalesce(o.progress,0),coalesce(o.target,100),exists(SELECT 1 FROM dynamic_event_participants p WHERE p.event_id=e.id AND p.player_id=?),e.expires_at FROM world_events e LEFT JOIN event_objectives o ON o.event_id=e.id WHERE e.id=?")) {
      statement.setObject(1, player);
      statement.setObject(2, event);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("dynamic event not found");
        return summary(result);
      }
    }
  }

  private static EventSummary summary(ResultSet result) throws SQLException {
    Timestamp expires = result.getTimestamp(11);
    return new EventSummary(
        result.getObject(1, UUID.class),
        result.getString(2),
        result.getString(3),
        result.getObject(4, UUID.class),
        result.getObject(5, UUID.class),
        result.getObject(6, UUID.class),
        result.getObject(7, UUID.class),
        result.getLong(8),
        result.getLong(9),
        result.getBoolean(10),
        expires == null ? null : expires.toInstant());
  }

  private record Candidate(
      String key, String source, String label, UUID city, UUID kingdom, UUID shipment, UUID edge) {}
}
