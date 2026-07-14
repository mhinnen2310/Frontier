package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.npc.AmbientLifeGateway;
import nl.frontier.npc.AmbientLifePolicy;
import nl.frontier.npc.PlayerObservation;

public final class PostgresAmbientLifeGateway implements AmbientLifeGateway {
  private final TransactionalStore store;

  public PostgresAmbientLifeGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public CycleReport cycle(
      Set<PlayerObservation> observers,
      Set<UUID> daylightWorlds,
      AmbientLifePolicy policy,
      int maximumWorkerPresentations,
      Instant announcementCutoff,
      Instant now) {
    if (maximumWorkerPresentations < 0 || maximumWorkerPresentations > 500)
      throw new DomainException("ambient worker presentation budget must be 0-500");
    return store.inTransaction(
        connection -> {
          List<Context> contexts = contexts(connection, observers, maximumWorkerPresentations);
          Set<UUID> visible = contexts.stream().map(Context::city).collect(Collectors.toSet());
          List<Scene> scenes = new ArrayList<>();
          List<Binding> retirements = new ArrayList<>();
          List<Announcement> announcements = new ArrayList<>();
          for (Context context : contexts) {
            Map<String, Position> positions =
                positions(connection, context.city(), context.capital());
            AmbientLifePolicy.Inputs input =
                new AmbientLifePolicy.Inputs(
                    context.population(),
                    context.housing(),
                    context.food(),
                    context.employment(),
                    context.trend(),
                    daylightWorlds.contains(context.capital().world()),
                    context.marketOrders(),
                    context.repairs(),
                    context.event(),
                    positions.containsKey("MARKET"),
                    positions.containsKey("BARRACKS"),
                    positions.containsKey("BUILDER_GUILD"),
                    context.workerPresentations());
            List<AmbientLifePolicy.SceneSpec> desired = policy.scenes(input);
            reconcileCity(connection, context, positions, desired, scenes, retirements, now);
            String message = policy.announcement(context.name(), input);
            Announcement announcement =
                updateState(connection, context, desired.size(), message, announcementCutoff, now);
            if (announcement != null) announcements.add(announcement);
          }
          retireHidden(connection, visible, retirements, now);
          return new CycleReport(
              contexts.size(), scenes.size(), scenes, retirements, announcements);
        });
  }

  @Override
  public void bind(UUID scene, UUID entity, Instant now) {
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE ambient_scenes SET presentation_entity_id=?,presentation_bound_at=?,updated_at=?,version=version+1 WHERE id=? AND status='ACTIVE' AND (presentation_entity_id IS NULL OR presentation_entity_id=?)")) {
            statement.setObject(1, entity);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setObject(4, scene);
            statement.setObject(5, entity);
            if (statement.executeUpdate() != 1)
              throw new DomainException("ambient scene already has another presentation");
          }
          return null;
        });
  }

  @Override
  public void unbind(UUID scene, UUID entity, Instant now) {
    store.inTransaction(
        connection -> {
          update(
              connection,
              "UPDATE ambient_scenes SET presentation_entity_id=NULL,presentation_bound_at=NULL,updated_at=?,version=version+1 WHERE id=? AND presentation_entity_id=?",
              now,
              scene,
              entity);
          return null;
        });
  }

  private static List<Context> contexts(
      Connection connection, Set<PlayerObservation> observers, int maximumWorkers)
      throws SQLException {
    if (observers.isEmpty()) return List.of();
    String rows = observers.stream().map(ignored -> "(?,?,?,?)").collect(Collectors.joining(","));
    String sql =
        "WITH observers(player_id,world_id,x,z) AS (VALUES "
            + rows
            + "), visible AS (SELECT DISTINCT c.id FROM cities c JOIN city_claims cl ON cl.city_id=c.id AND cl.state='CAPITAL' JOIN observers o ON o.world_id=cl.world_id AND abs(cl.chunk_x*16+8-o.x)<=128 AND abs(cl.chunk_z*16+8-o.z)<=128 WHERE c.lifecycle_status='ACTIVE') SELECT c.id,c.name,cl.world_id,cl.chunk_x*16+8,cl.chunk_z*16+8,c.population,coalesce(p.housing_capacity,5),coalesce(p.food_security,50),coalesce(p.employment_score,50),coalesce(p.population_trend,0),(SELECT count(*) FROM market_orders m WHERE m.settlement_id=c.id AND m.status IN ('OPEN','PARTIAL')),(SELECT count(*) FROM repair_orders r WHERE r.city_id=c.id AND r.status IN ('REGISTERED','RESERVED','REPAIRING')),(SELECT e.event_key FROM world_events e WHERE e.city_id=c.id AND e.state IN ('ANNOUNCED','ACTIVE') ORDER BY e.state_at DESC,e.id LIMIT 1),least(?,(SELECT count(*) FROM workers w WHERE w.city_id=c.id AND w.state<>'UNAVAILABLE')) FROM visible v JOIN cities c ON c.id=v.id JOIN city_claims cl ON cl.city_id=c.id AND cl.state='CAPITAL' LEFT JOIN city_population_state p ON p.city_id=c.id ORDER BY c.id";
    List<Context> values = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      for (PlayerObservation observer : observers) {
        statement.setObject(index++, observer.player());
        statement.setObject(index++, observer.world());
        statement.setInt(index++, observer.x());
        statement.setInt(index++, observer.z());
      }
      statement.setInt(index, maximumWorkers);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          values.add(
              new Context(
                  result.getObject(1, UUID.class),
                  result.getString(2),
                  new Position(
                      result.getObject(3, UUID.class), result.getInt(4), 64, result.getInt(5)),
                  result.getInt(6),
                  result.getInt(7),
                  result.getInt(8),
                  result.getInt(9),
                  result.getInt(10),
                  result.getInt(11),
                  result.getInt(12),
                  result.getString(13),
                  result.getInt(14)));
      }
    }
    return List.copyOf(values);
  }

  private static Map<String, Position> positions(
      Connection connection, UUID city, Position fallback) throws SQLException {
    Map<String, Position> values = new HashMap<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT building_type,(bounds->>'world')::uuid,((bounds->>'minX')::int+(bounds->>'maxX')::int)/2,(bounds->>'minY')::int+1,((bounds->>'minZ')::int+(bounds->>'maxZ')::int)/2 FROM city_buildings WHERE city_id=? AND status='ACTIVE' AND building_type IN ('TOWN_HALL','MARKET','BARRACKS','BUILDER_GUILD') ORDER BY id")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          values.putIfAbsent(
              result.getString(1),
              new Position(
                  result.getObject(2, UUID.class),
                  result.getInt(3),
                  result.getInt(4),
                  result.getInt(5)));
      }
    }
    values.putIfAbsent("TOWN_HALL", fallback);
    return values;
  }

  private static void reconcileCity(
      Connection connection,
      Context context,
      Map<String, Position> positions,
      List<AmbientLifePolicy.SceneSpec> desired,
      List<Scene> scenes,
      List<Binding> retirements,
      Instant now)
      throws SQLException {
    Map<Key, StoredScene> stored = new HashMap<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,scene_type,scene_slot,presentation_entity_id FROM ambient_scenes WHERE city_id=? FOR UPDATE")) {
      statement.setObject(1, context.city());
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          StoredScene scene =
              new StoredScene(
                  result.getObject(1, UUID.class),
                  result.getString(2),
                  result.getInt(3),
                  result.getObject(4, UUID.class));
          stored.put(new Key(scene.type(), scene.slot()), scene);
        }
      }
    }
    Set<Key> desiredKeys = new HashSet<>();
    for (AmbientLifePolicy.SceneSpec spec : desired) {
      Key key = new Key(spec.type().name(), spec.slot());
      desiredKeys.add(key);
      StoredScene existing = stored.get(key);
      UUID id = existing == null ? UUID.randomUUID() : existing.id();
      UUID entity = existing == null ? null : existing.entity();
      Position position = position(spec.type(), positions, context.capital());
      if (existing == null)
        update(
            connection,
            "INSERT INTO ambient_scenes(id,city_id,scene_type,scene_slot,label,world_id,x,y,z,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
            id,
            context.city(),
            spec.type().name(),
            spec.slot(),
            spec.label(),
            position.world(),
            position.x() + offset(spec.slot()),
            position.y(),
            position.z() + offset(spec.slot()),
            now,
            now);
      else
        update(
            connection,
            "UPDATE ambient_scenes SET label=?,world_id=?,x=?,y=?,z=?,status='ACTIVE',updated_at=?,version=version+1 WHERE id=?",
            spec.label(),
            position.world(),
            position.x() + offset(spec.slot()),
            position.y(),
            position.z() + offset(spec.slot()),
            now,
            id);
      scenes.add(
          new Scene(
              id,
              context.city(),
              spec.type().name(),
              spec.label(),
              position.world(),
              position.x() + offset(spec.slot()),
              position.y(),
              position.z() + offset(spec.slot()),
              entity));
    }
    for (Map.Entry<Key, StoredScene> entry : stored.entrySet()) {
      if (desiredKeys.contains(entry.getKey())) continue;
      StoredScene scene = entry.getValue();
      update(
          connection,
          "UPDATE ambient_scenes SET status='INACTIVE',updated_at=?,version=version+1 WHERE id=? AND status<>'INACTIVE'",
          now,
          scene.id());
      if (scene.entity() != null) retirements.add(new Binding(scene.id(), scene.entity()));
    }
  }

  private static void retireHidden(
      Connection connection, Set<UUID> visible, List<Binding> retirements, Instant now)
      throws SQLException {
    List<HiddenScene> hidden = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,city_id,presentation_entity_id FROM ambient_scenes WHERE status='ACTIVE' FOR UPDATE")) {
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          UUID city = result.getObject(2, UUID.class);
          if (visible.contains(city)) continue;
          hidden.add(
              new HiddenScene(
                  result.getObject(1, UUID.class), city, result.getObject(3, UUID.class)));
        }
      }
    }
    Set<UUID> hiddenCities = new HashSet<>();
    for (HiddenScene scene : hidden) {
      update(
          connection,
          "UPDATE ambient_scenes SET status='INACTIVE',updated_at=?,version=version+1 WHERE id=?",
          now,
          scene.id());
      hiddenCities.add(scene.city());
      if (scene.entity() != null) retirements.add(new Binding(scene.id(), scene.entity()));
    }
    for (UUID city : hiddenCities)
      update(
          connection,
          "UPDATE settlement_ambient_state SET current_scene_count=0,updated_at=?,version=version+1 WHERE city_id=?",
          now,
          city);
  }

  private static Announcement updateState(
      Connection connection,
      Context context,
      int scenes,
      String message,
      Instant cutoff,
      Instant now)
      throws SQLException {
    update(
        connection,
        "INSERT INTO settlement_ambient_state(city_id,updated_at) VALUES(?,?) ON CONFLICT(city_id) DO NOTHING",
        context.city(),
        now);
    String prior = null;
    Instant announced = null;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT last_announcement_signature,last_announced_at FROM settlement_ambient_state WHERE city_id=? FOR UPDATE")) {
      statement.setObject(1, context.city());
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        prior = result.getString(1);
        Timestamp value = result.getTimestamp(2);
        announced = value == null ? null : value.toInstant();
      }
    }
    boolean due =
        message != null
            && (!message.equals(prior) || announced == null || announced.isBefore(cutoff));
    update(
        connection,
        "UPDATE settlement_ambient_state SET last_announcement_signature=?,last_announced_at=CASE WHEN ? THEN ? ELSE last_announced_at END,current_scene_count=?,peak_scene_count=greatest(peak_scene_count,?),cycles=cycles+1,updated_at=?,version=version+1 WHERE city_id=?",
        message,
        due,
        now,
        scenes,
        scenes,
        now,
        context.city());
    return due
        ? new Announcement(
            context.city(),
            context.capital().world(),
            context.capital().x(),
            context.capital().z(),
            message)
        : null;
  }

  private static Position position(
      AmbientLifePolicy.SceneType type, Map<String, Position> positions, Position fallback) {
    return switch (type) {
      case MARKET -> positions.getOrDefault("MARKET", positions.get("TOWN_HALL"));
      case GUARD -> positions.getOrDefault("BARRACKS", positions.get("TOWN_HALL"));
      case REPAIR -> positions.getOrDefault("BUILDER_GUILD", positions.get("TOWN_HALL"));
      case CITIZEN, TOWN_HALL_EVENT, SHORTAGE -> positions.getOrDefault("TOWN_HALL", fallback);
    };
  }

  private static int offset(int slot) {
    return slot == 0 ? 0 : slot % 2 == 0 ? slot / 2 : -(slot + 1) / 2;
  }

  private static void update(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        Object value = values[index];
        if (value instanceof Instant instant)
          statement.setTimestamp(index + 1, Timestamp.from(instant));
        else statement.setObject(index + 1, value);
      }
      statement.executeUpdate();
    }
  }

  private record Context(
      UUID city,
      String name,
      Position capital,
      int population,
      int housing,
      int food,
      int employment,
      int trend,
      int marketOrders,
      int repairs,
      String event,
      int workerPresentations) {}

  private record Position(UUID world, int x, int y, int z) {}

  private record Key(String type, int slot) {}

  private record StoredScene(UUID id, String type, int slot, UUID entity) {}

  private record HiddenScene(UUID id, UUID city, UUID entity) {}
}
