package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.world.WorldEvent;
import nl.frontier.world.WorldEventPolicy;
import nl.frontier.world.WorldSimulation;
import nl.frontier.world.WorldSimulationGateway;

public final class PostgresWorldSimulationGateway implements WorldSimulationGateway {
  private static final Duration CITY_INTERVAL = Duration.ofMinutes(15);
  private static final Duration INFRASTRUCTURE_INTERVAL = Duration.ofDays(1);
  private static final Duration NATURE_INTERVAL = Duration.ofHours(1);
  private static final Duration SEASON_LENGTH = Duration.ofDays(21);
  private final TransactionalStore store;
  private final UUID worker = UUID.randomUUID();

  public PostgresWorldSimulationGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public CycleReport cycle(int maximumCities, Instant now) {
    WorldSimulation.Season currentSeason = season(now);
    int cities = 0;
    int migrations = 0;
    int infrastructure = 0;
    int created = 0;
    for (int index = 0; index < maximumCities; index++) {
      CityCycle result =
          store.inTransaction(connection -> processCity(connection, currentSeason, now));
      if (result == CityCycle.NONE) break;
      cities++;
      if (result.migrated) migrations++;
      if (result.aged) infrastructure++;
      if (result.eventCreated) created++;
    }
    int advanced = store.inTransaction(connection -> advanceEvents(connection, now));
    return new CycleReport(cities, migrations, infrastructure, created, advanced);
  }

  @Override
  public WorldSimulation.Season season(Instant now) {
    return store.inTransaction(connection -> ensureSeason(connection, now));
  }

  @Override
  public List<RegionSnapshot> regions() {
    return store.inTransaction(
        connection -> {
          List<RegionSnapshot> values = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT r.id,r.region_key,r.population,r.prosperity,r.stability,r.trade_activity,r.road_integrity,r.season,coalesce(w.weather_key,'CLEAR'),coalesce(w.severity,0),r.version FROM world_regions r LEFT JOIN world_weather w ON w.region_id=r.id ORDER BY r.region_key");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              values.add(
                  new RegionSnapshot(
                      result.getObject(1, UUID.class),
                      result.getString(2),
                      result.getInt(3),
                      result.getDouble(4),
                      result.getDouble(5),
                      result.getDouble(6),
                      result.getDouble(7),
                      result.getString(8),
                      result.getString(9),
                      result.getInt(10),
                      result.getLong(11)));
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<EventSnapshot> events(boolean activeOnly) {
    return store.inTransaction(
        connection -> {
          List<EventSnapshot> values = new ArrayList<>();
          String filter =
              activeOnly
                  ? " WHERE e.state IN ('SCHEDULED','ANNOUNCED','ACTIVE','RESOLVED','COOLDOWN')"
                  : "";
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT e.id,e.region_id,e.category,e.event_key,e.state,coalesce(o.progress,0),coalesce(o.target,1),e.severity,e.state_at FROM world_events e LEFT JOIN event_objectives o ON o.event_id=e.id"
                          + filter
                          + " ORDER BY e.state_at DESC LIMIT 200");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              values.add(
                  new EventSnapshot(
                      result.getObject(1, UUID.class),
                      result.getObject(2, UUID.class),
                      WorldEvent.Category.valueOf(result.getString(3)),
                      result.getString(4),
                      WorldEvent.State.valueOf(result.getString(5)),
                      result.getLong(6),
                      result.getLong(7),
                      result.getInt(8),
                      result.getTimestamp(9).toInstant()));
          }
          return List.copyOf(values);
        });
  }

  private static CityCycle processCity(
      Connection connection, WorldSimulation.Season season, Instant now) throws SQLException {
    StateRow state;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT city_id,region_key,last_infrastructure_at,last_nature_at FROM city_world_simulation_state WHERE next_cycle_at<=? AND (lease_expires_at IS NULL OR lease_expires_at<?) ORDER BY next_cycle_at,city_id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setTimestamp(2, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return CityCycle.NONE;
        state =
            new StateRow(
                result.getObject(1, UUID.class),
                result.getString(2),
                instant(result, 3),
                instant(result, 4));
      }
    }
    CityStats city = cityStats(connection, state.city);
    RegionRow region = refreshRegion(connection, state.region, city.world, season, now);
    Weather weather = ensureWeather(connection, region, season, now);
    int before = city.population;
    int delta = 0;
    if (city.foodUnits < Math.max(1, city.population / 4L) || city.activeWars > 0) delta = -1;
    else if (city.prosperity >= 70
        && city.happiness >= 60
        && city.treasury > 0
        && season != WorldSimulation.Season.WINTER
        && !weather.key.equals("HEATWAVE")
        && !weather.key.equals("FROST")) delta = 1;
    if (activeEvent(connection, region.id, "MIGRATION_WAVE")) delta = Math.max(delta, 2);
    if (activeEvent(connection, region.id, "PLAGUE")) delta = Math.min(delta, -2);
    int after = Math.max(0, before + delta);
    if (delta != 0) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE cities SET population=?,version=version+1 WHERE id=?")) {
        statement.setInt(1, after);
        statement.setObject(2, state.city);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO migration_history(id,city_id,population_before,population_after,reason,occurred_at) VALUES(?,?,?,?,?,?)")) {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, state.city);
        statement.setInt(3, before);
        statement.setInt(4, after);
        statement.setString(
            5,
            delta > 0
                ? "PROSPERITY_ATTRACTION"
                : city.activeWars > 0 ? "WAR_DISPLACEMENT" : "SHORTAGE");
        statement.setTimestamp(6, Timestamp.from(now));
        statement.executeUpdate();
      }
    }
    boolean age =
        state.lastInfrastructure == null
            || state.lastInfrastructure.plus(INFRASTRUCTURE_INTERVAL).compareTo(now) <= 0;
    int decay =
        (city.overdueMaintenance ? 1 : 0)
            + (season == WorldSimulation.Season.WINTER ? 1 : 0)
            + (weather.key.equals("STORM") ? Math.max(1, weather.severity / 40) : 0);
    int decayed =
        age && decay > 0
            ? decayInfrastructure(connection, state.city, decay, season, weather, now)
            : 0;
    if (age && city.overdueMaintenance) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE city_buildings SET integrity=greatest(0,integrity-1),status=CASE WHEN integrity-1<=0 THEN 'DESTROYED' WHEN integrity-1<15 THEN 'DISABLED' WHEN integrity-1<90 THEN 'DAMAGED' ELSE 'ACTIVE' END,version=version+1 WHERE city_id=?")) {
        statement.setObject(1, state.city);
        statement.executeUpdate();
      }
    }
    region = refreshRegion(connection, state.region, city.world, season, now);
    boolean nature =
        state.lastNature == null || state.lastNature.plus(NATURE_INTERVAL).compareTo(now) <= 0;
    if (nature) updateNature(connection, region, now);
    boolean event = maybeCreateEvent(connection, region, season, weather, now);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE city_world_simulation_state SET observed_city_version=(SELECT version FROM cities WHERE id=?),next_cycle_at=?,lease_owner=NULL,lease_expires_at=NULL,last_infrastructure_at=CASE WHEN ? THEN ? ELSE last_infrastructure_at END,last_nature_at=CASE WHEN ? THEN ? ELSE last_nature_at END,version=version+1 WHERE city_id=?")) {
      statement.setObject(1, state.city);
      statement.setTimestamp(2, Timestamp.from(now.plus(CITY_INTERVAL)));
      statement.setBoolean(3, age);
      statement.setTimestamp(4, Timestamp.from(now));
      statement.setBoolean(5, nature);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.setObject(7, state.city);
      statement.executeUpdate();
    }
    return new CityCycle(delta != 0, decayed > 0 || age && city.overdueMaintenance, event);
  }

  private static CityStats cityStats(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT c.population,c.prosperity,a.balance_minor,coalesce((SELECT avg(w.happiness) FROM workers w WHERE w.city_id=c.id),70),coalesce((SELECT sum(s.available_quantity*CASE WHEN s.commodity_key='minecraft:bread' THEN 4 ELSE 1 END) FROM warehouse_stock s JOIN warehouses wh ON wh.id=s.warehouse_id WHERE wh.city_id=c.id AND s.commodity_key IN ('minecraft:wheat','minecraft:bread')),0),(SELECT count(*) FROM campaigns ca WHERE (ca.attacker_city_id=c.id OR ca.defender_city_id=c.id) AND ca.phase='ACTIVE'),exists(SELECT 1 FROM maintenance_invoices m WHERE m.city_id=c.id AND m.status='OVERDUE'),cl.world_id FROM cities c JOIN accounts a ON a.owner_type='CITY' AND a.owner_id=c.id JOIN city_claims cl ON cl.city_id=c.id AND cl.state='CAPITAL' WHERE c.id=?")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("world simulation city snapshot missing");
        return new CityStats(
            result.getInt(1),
            result.getInt(2),
            result.getLong(3),
            result.getDouble(4),
            result.getLong(5),
            result.getInt(6),
            result.getBoolean(7),
            result.getObject(8, UUID.class));
      }
    }
  }

  private static RegionRow refreshRegion(
      Connection connection, String key, UUID world, WorldSimulation.Season season, Instant now)
      throws SQLException {
    UUID id = regionId(connection, key, world, season, now);
    int population;
    double prosperity;
    double stability;
    double trade;
    double roads;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(sum(c.population),0),coalesce(avg(c.prosperity),50),greatest(0,100-coalesce(sum((SELECT count(*) FROM campaigns ca WHERE (ca.attacker_city_id=c.id OR ca.defender_city_id=c.id) AND ca.phase='ACTIVE'))*15,0)),coalesce(sum((SELECT coalesce(sum(t.quantity*t.unit_price_minor),0) FROM trades t JOIN market_orders mo ON mo.id=t.buy_order_id WHERE mo.settlement_id=c.id AND t.occurred_at>=?)),0),coalesce(avg((SELECT avg(e.integrity) FROM road_edges e JOIN road_nodes n ON n.id=e.from_node WHERE n.city_id=c.id)),100) FROM cities c JOIN city_world_simulation_state s ON s.city_id=c.id WHERE s.region_key=?")) {
      statement.setTimestamp(1, Timestamp.from(now.minus(Duration.ofDays(1))));
      statement.setString(2, key);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        population = result.getInt(1);
        prosperity = result.getDouble(2);
        stability = result.getDouble(3);
        trade = result.getDouble(4);
        roads = result.getDouble(5);
      }
    }
    String statistics =
        "{\"population\":"
            + population
            + ",\"prosperity\":"
            + prosperity
            + ",\"stability\":"
            + stability
            + ",\"tradeActivity\":"
            + trade
            + "}";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE world_regions SET statistics=?::jsonb,population=?,prosperity=?,stability=?,trade_activity=?,road_integrity=?,season=?,simulated_at=?,version=version+1 WHERE id=?")) {
      statement.setString(1, statistics);
      statement.setInt(2, population);
      statement.setDouble(3, clamp(prosperity));
      statement.setDouble(4, clamp(stability));
      statement.setDouble(5, Math.max(0, trade));
      statement.setDouble(6, clamp(roads));
      statement.setString(7, season.name());
      statement.setTimestamp(8, Timestamp.from(now));
      statement.setObject(9, id);
      statement.executeUpdate();
    }
    return new RegionRow(id, key, population, prosperity, stability, trade, roads);
  }

  private static UUID regionId(
      Connection connection, String key, UUID world, WorldSimulation.Season season, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM world_regions WHERE region_key=? FOR UPDATE")) {
      statement.setString(1, key);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) return result.getObject(1, UUID.class);
      }
    }
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO world_regions(id,region_key,statistics,world_id,season,simulated_at,version) VALUES(?,?,'{}'::jsonb,?,?,?,0)")) {
      statement.setObject(1, id);
      statement.setString(2, key);
      statement.setObject(3, world);
      statement.setString(4, season.name());
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
    return id;
  }

  private static void updateNature(Connection connection, RegionRow region, Instant now)
      throws SQLException {
    double recovery = region.population == 0 ? 5 : Math.max(0, 2 - region.population / 1000.0);
    double ruin = region.stability < 40 ? 5 : -2;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO nature_state(region_id,recovery,ruin_pressure,simulated_at,version) VALUES(?,?,?,?,0) ON CONFLICT(region_id) DO UPDATE SET recovery=greatest(0,least(100,nature_state.recovery+?)),ruin_pressure=greatest(0,least(100,nature_state.ruin_pressure+?)),simulated_at=excluded.simulated_at,version=nature_state.version+1")) {
      statement.setObject(1, region.id);
      statement.setDouble(2, clamp(recovery));
      statement.setDouble(3, clamp(Math.max(0, ruin)));
      statement.setTimestamp(4, Timestamp.from(now));
      statement.setDouble(5, recovery);
      statement.setDouble(6, ruin);
      statement.executeUpdate();
    }
  }

  private static Weather ensureWeather(
      Connection connection, RegionRow region, WorldSimulation.Season season, Instant now)
      throws SQLException {
    LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT weather_key,severity,observed_on FROM world_weather WHERE region_id=? FOR UPDATE")) {
      statement.setObject(1, region.id);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next() && result.getDate(3).toLocalDate().equals(date))
          return new Weather(result.getString(1), result.getInt(2));
      }
    }
    int roll = Math.floorMod((region.key + ":" + date).hashCode(), 100);
    String key;
    if (season == WorldSimulation.Season.WINTER && roll >= 60) key = "FROST";
    else if (season == WorldSimulation.Season.SUMMER && roll >= 85) key = "HEATWAVE";
    else if (roll >= 75) key = "STORM";
    else if (roll >= 50) key = "RAIN";
    else key = "CLEAR";
    int severity =
        key.equals("CLEAR") ? roll / 2 : Math.min(100, 30 + Math.floorMod(roll * 17, 71));
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO world_weather(region_id,weather_key,severity,observed_on,changed_at,next_change_at,version) VALUES(?,?,?,?,?,?,0) ON CONFLICT(region_id) DO UPDATE SET weather_key=excluded.weather_key,severity=excluded.severity,observed_on=excluded.observed_on,changed_at=excluded.changed_at,next_change_at=excluded.next_change_at,version=world_weather.version+1")) {
      statement.setObject(1, region.id);
      statement.setString(2, key);
      statement.setInt(3, severity);
      statement.setDate(4, java.sql.Date.valueOf(date));
      statement.setTimestamp(5, Timestamp.from(now));
      statement.setTimestamp(6, Timestamp.from(now.plus(Duration.ofDays(1))));
      statement.executeUpdate();
    }
    return new Weather(key, severity);
  }

  private static int decayInfrastructure(
      Connection connection,
      UUID city,
      int baseDecay,
      WorldSimulation.Season season,
      Weather weather,
      Instant now)
      throws SQLException {
    List<Decay> edges = new ArrayList<>();
    int effectiveBase =
        hasUnlock(connection, city, "MEGA_PROJECT", "CONTINENTAL_HIGHWAY")
            ? Math.max(0, baseDecay - 1)
            : baseDecay;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,infrastructure_type,integrity,traffic FROM road_edges WHERE owner_city=? AND integrity>0 FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          int before = result.getInt(3);
          int amount =
              effectiveBase
                  + (result.getString(2).equals("BRIDGE") ? 1 : 0)
                  + (int) Math.min(2, result.getLong(4) / 1_000);
          edges.add(
              new Decay(
                  result.getObject(1, UUID.class),
                  result.getString(2),
                  before,
                  Math.max(0, before - amount)));
        }
      }
    }
    for (Decay edge : edges) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE road_edges SET integrity=?,version=version+1 WHERE id=?")) {
        statement.setInt(1, edge.after);
        statement.setObject(2, edge.id);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO infrastructure_decay_history(id,edge_id,city_id,infrastructure_type,integrity_before,integrity_after,cause,occurred_at) VALUES(?,?,?,?,?,?,?,?)")) {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, edge.id);
        statement.setObject(3, city);
        statement.setString(4, edge.type);
        statement.setInt(5, edge.before);
        statement.setInt(6, edge.after);
        statement.setString(7, season.name() + "_" + weather.key);
        statement.setTimestamp(8, Timestamp.from(now));
        statement.executeUpdate();
      }
    }
    return edges.size();
  }

  private static boolean hasUnlock(Connection connection, UUID city, String type, String key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM kingdom_members m JOIN kingdom_unlocks u ON u.kingdom_id=m.kingdom_id WHERE m.city_id=? AND u.content_type=? AND u.content_key=?")) {
      statement.setObject(1, city);
      statement.setString(2, type);
      statement.setString(3, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static boolean activeEvent(Connection connection, UUID region, String key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM world_events WHERE region_id=? AND event_key=? AND state='ACTIVE'")) {
      statement.setObject(1, region);
      statement.setString(2, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static boolean maybeCreateEvent(
      Connection connection,
      RegionRow region,
      WorldSimulation.Season season,
      Weather weather,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM world_events WHERE region_id=? AND state<>'ARCHIVED' AND state_at>=?")) {
      statement.setObject(1, region.id);
      statement.setTimestamp(2, Timestamp.from(now.minus(Duration.ofDays(1))));
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) return false;
      }
    }
    var choice =
        new WorldEventPolicy()
            .select(
                new WorldEventPolicy.Conditions(
                    region.population,
                    region.prosperity,
                    region.stability,
                    region.trade,
                    region.roads,
                    season,
                    weather.key,
                    weather.severity));
    if (choice.isEmpty()) return false;
    String key = choice.orElseThrow().key();
    WorldEvent.Category category = choice.orElseThrow().category();
    int severity = Math.max(25, weather.severity);
    UUID event = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO world_events(id,region_id,category,event_key,state,state_at,payload,version,severity) VALUES(?,?,?,?,'SCHEDULED',?,jsonb_build_object('weather',?,'season',?),0,?)")) {
      statement.setObject(1, event);
      statement.setObject(2, region.id);
      statement.setString(3, category.name());
      statement.setString(4, key);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.setString(6, weather.key);
      statement.setString(7, season.name());
      statement.setInt(8, severity);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO event_objectives(id,event_id,objective_key,progress,target,state,version) VALUES(?,?,?,0,100,'ACTIVE',0)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, event);
      statement.setString(3, key + "_" + choice.orElseThrow().response());
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO event_rewards(id,event_id,reward_key,amount,status) VALUES(?,?,?,100,'PENDING')")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, event);
      statement.setString(
          3,
          switch (key) {
            case "BANDIT_RAID", "PLAGUE" -> "STABILITY";
            case "FLOOD", "DISASTER" -> "INFRASTRUCTURE";
            case "HARVEST_FAILURE" -> "FOOD";
            default -> "PROSPERITY";
          });
      statement.executeUpdate();
    }
    eventHistory(connection, event, null, "SCHEDULED", now);
    return true;
  }

  private static int advanceEvents(Connection connection, Instant now) throws SQLException {
    List<EventRow> events = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id,region_id,state,state_at,event_key,severity FROM world_events WHERE state<>'ARCHIVED' ORDER BY state_at LIMIT 100 FOR UPDATE SKIP LOCKED");
        ResultSet result = statement.executeQuery()) {
      while (result.next())
        events.add(
            new EventRow(
                result.getObject(1, UUID.class),
                result.getObject(2, UUID.class),
                result.getString(3),
                result.getTimestamp(4).toInstant(),
                result.getString(5),
                result.getInt(6)));
    }
    int advanced = 0;
    for (EventRow event : events) {
      String next = null;
      if (event.state.equals("SCHEDULED")
          && event.stateAt.plus(Duration.ofMinutes(1)).isBefore(now)) next = "ANNOUNCED";
      else if (event.state.equals("ANNOUNCED")
          && event.stateAt.plus(Duration.ofMinutes(1)).isBefore(now)) next = "ACTIVE";
      else if (event.state.equals("ACTIVE")) {
        progressEvent(connection, event.id);
        long progress = objectiveProgress(connection, event.id);
        if (progress >= 100 || event.stateAt.plus(Duration.ofHours(1)).isBefore(now))
          next = "RESOLVED";
      } else if (event.state.equals("RESOLVED")) {
        applyRewards(connection, event, now);
        next = "COOLDOWN";
      } else if (event.state.equals("COOLDOWN")
          && event.stateAt.plus(Duration.ofDays(1)).isBefore(now)) next = "ARCHIVED";
      if (next != null) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE world_events SET state=?,state_at=?,version=version+1 WHERE id=?")) {
          statement.setString(1, next);
          statement.setTimestamp(2, Timestamp.from(now));
          statement.setObject(3, event.id);
          statement.executeUpdate();
        }
        eventHistory(connection, event.id, event.state, next, now);
        if (next.equals("ACTIVE")) applyEventImpacts(connection, event, now);
        advanced++;
      }
    }
    return advanced;
  }

  private static void applyEventImpacts(Connection connection, EventRow event, Instant now)
      throws SQLException {
    List<UUID> cities = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT s.city_id FROM city_world_simulation_state s JOIN world_regions r ON r.region_key=s.region_key WHERE r.id=? ORDER BY s.city_id")) {
      statement.setObject(1, event.region);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) cities.add(result.getObject(1, UUID.class));
      }
    }
    for (UUID city : cities) {
      int amount = Math.max(1, event.severity / 10);
      String impact = event.key;
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO world_event_impacts(id,event_id,city_id,impact_key,amount,applied_at) VALUES(?,?,?,?,?,?) ON CONFLICT(event_id,city_id,impact_key) DO NOTHING")) {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, event.id);
        statement.setObject(3, city);
        statement.setString(4, impact);
        statement.setLong(5, amount);
        statement.setTimestamp(6, Timestamp.from(now));
        if (statement.executeUpdate() == 0) continue;
      }
      switch (event.key) {
        case "BANDIT_RAID" -> {
          updateCity(connection, city, -2, 0);
          damageRoads(connection, city, "ROAD", Math.max(1, amount / 2));
        }
        case "DISASTER" -> {
          damageRoads(connection, city, null, amount);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_buildings SET integrity=greatest(0,integrity-?),status=CASE WHEN integrity-?<=0 THEN 'DESTROYED' WHEN integrity-?<15 THEN 'DISABLED' ELSE 'DAMAGED' END,version=version+1 WHERE city_id=? AND status<>'DESTROYED'")) {
            statement.setInt(1, amount);
            statement.setInt(2, amount);
            statement.setInt(3, amount);
            statement.setObject(4, city);
            statement.executeUpdate();
          }
        }
        case "FLOOD" -> {
          damageRoads(connection, city, "BRIDGE", amount);
          reduceFood(connection, city, 90);
        }
        case "PLAGUE" -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE cities SET population=greatest(0,population-greatest(1,population*?/100)),prosperity=greatest(0,prosperity-3),version=version+1 WHERE id=?")) {
            statement.setInt(1, Math.max(1, event.severity / 20));
            statement.setObject(2, city);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_population_state SET safety=greatest(0,safety-10),version=version+1 WHERE city_id=?")) {
            statement.setObject(1, city);
            statement.executeUpdate();
          }
        }
        case "HARVEST_FAILURE" -> reduceFood(connection, city, 75);
        case "MIGRATION_WAVE" -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE cities SET population=population+?,version=version+1 WHERE id=?")) {
            statement.setInt(1, Math.max(1, event.severity / 25));
            statement.setObject(2, city);
            statement.executeUpdate();
          }
        }
        case "TRADE_FAIR" -> updateCity(connection, city, 5, 0);
        case "HARVEST_FESTIVAL" -> updateCity(connection, city, 2, 0);
        default -> {
          // Unknown extension events retain lifecycle/reward behavior without an implicit effect.
        }
      }
    }
  }

  private static void updateCity(Connection connection, UUID city, int prosperity, int population)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE cities SET prosperity=greatest(0,least(100,prosperity+?)),population=greatest(0,population+?),version=version+1 WHERE id=?")) {
      statement.setInt(1, prosperity);
      statement.setInt(2, population);
      statement.setObject(3, city);
      statement.executeUpdate();
    }
  }

  private static void damageRoads(Connection connection, UUID city, String type, int amount)
      throws SQLException {
    String filter = type == null ? "" : " AND infrastructure_type=?";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE road_edges SET integrity=greatest(0,integrity-?),version=version+1 WHERE owner_city=?"
                + filter)) {
      statement.setInt(1, amount);
      statement.setObject(2, city);
      if (type != null) statement.setString(3, type);
      statement.executeUpdate();
    }
  }

  private static void reduceFood(Connection connection, UUID city, int percent)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE warehouse_stock SET available_quantity=available_quantity*?/100,version=version+1 WHERE warehouse_id IN (SELECT id FROM warehouses WHERE city_id=?) AND (commodity_key LIKE '%wheat%' OR commodity_key LIKE '%bread%' OR commodity_key LIKE '%carrot%' OR commodity_key LIKE '%potato%')")) {
      statement.setInt(1, percent);
      statement.setObject(2, city);
      statement.executeUpdate();
    }
  }

  private static void progressEvent(Connection connection, UUID event) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE event_objectives SET progress=least(target,progress+10),state=CASE WHEN progress+10>=target THEN 'COMPLETED' ELSE 'ACTIVE' END,version=version+1 WHERE event_id=? AND state='ACTIVE'")) {
      statement.setObject(1, event);
      statement.executeUpdate();
    }
  }

  private static long objectiveProgress(Connection connection, UUID event) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(max(progress*100/target),0) FROM event_objectives WHERE event_id=?")) {
      statement.setObject(1, event);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static void applyRewards(Connection connection, EventRow event, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE world_regions SET prosperity=least(100,prosperity+2),stability=least(100,stability+2),version=version+1 WHERE id=?")) {
      statement.setObject(1, event.region);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE event_rewards SET status='APPLIED',applied_at=? WHERE event_id=? AND status='PENDING'")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setObject(2, event.id);
      statement.executeUpdate();
    }
  }

  private static void eventHistory(
      Connection connection, UUID event, String previous, String next, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO event_history(id,event_id,previous_state,new_state,occurred_at) VALUES(?,?,?,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, event);
      statement.setString(3, previous);
      statement.setString(4, next);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static WorldSimulation.Season ensureSeason(Connection connection, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT season,ends_at FROM season_state WHERE id=1 FOR UPDATE")) {
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) {
          WorldSimulation.Season season = WorldSimulation.Season.valueOf(result.getString(1));
          Instant ends = result.getTimestamp(2).toInstant();
          while (!ends.isAfter(now)) {
            season = WorldSimulation.Season.values()[(season.ordinal() + 1) % 4];
            Instant starts = ends;
            ends = starts.plus(SEASON_LENGTH);
            try (PreparedStatement update =
                connection.prepareStatement(
                    "UPDATE season_state SET season=?,started_at=?,ends_at=?,version=version+1 WHERE id=1")) {
              update.setString(1, season.name());
              update.setTimestamp(2, Timestamp.from(starts));
              update.setTimestamp(3, Timestamp.from(ends));
              update.executeUpdate();
            }
          }
          return season;
        }
      }
    }
    Instant start = now;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO season_state(id,season,started_at,ends_at,version) VALUES(1,'SPRING',?,?,0)")) {
      statement.setTimestamp(1, Timestamp.from(start));
      statement.setTimestamp(2, Timestamp.from(start.plus(SEASON_LENGTH)));
      statement.executeUpdate();
    }
    return WorldSimulation.Season.SPRING;
  }

  private static Instant instant(ResultSet result, int index) throws SQLException {
    Timestamp value = result.getTimestamp(index);
    return value == null ? null : value.toInstant();
  }

  private static double clamp(double value) {
    return Math.max(0, Math.min(100, value));
  }

  private record StateRow(
      UUID city, String region, Instant lastInfrastructure, Instant lastNature) {}

  private record CityStats(
      int population,
      int prosperity,
      long treasury,
      double happiness,
      long foodUnits,
      int activeWars,
      boolean overdueMaintenance,
      UUID world) {}

  private record RegionRow(
      UUID id,
      String key,
      int population,
      double prosperity,
      double stability,
      double trade,
      double roads) {}

  private record Weather(String key, int severity) {}

  private record Decay(UUID id, String type, int before, int after) {}

  private record EventRow(
      UUID id, UUID region, String state, Instant stateAt, String key, int severity) {}

  private static final class CityCycle {
    static final CityCycle NONE = new CityCycle(false, false, false);
    final boolean migrated;
    final boolean aged;
    final boolean eventCreated;

    CityCycle(boolean migrated, boolean aged, boolean eventCreated) {
      this.migrated = migrated;
      this.aged = aged;
      this.eventCreated = eventCreated;
    }
  }
}
