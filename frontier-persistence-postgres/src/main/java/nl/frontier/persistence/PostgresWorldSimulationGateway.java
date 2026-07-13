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
import nl.frontier.world.WorldEvent;
import nl.frontier.world.WorldSimulation;
import nl.frontier.world.WorldSimulationGateway;

public final class PostgresWorldSimulationGateway implements WorldSimulationGateway {
  private static final Duration CITY_INTERVAL = Duration.ofMinutes(15);
  private static final Duration INFRASTRUCTURE_INTERVAL = Duration.ofMinutes(30);
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
                      "SELECT id,region_key,population,prosperity,stability,trade_activity,road_integrity,season,version FROM world_regions ORDER BY region_key");
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
                      result.getLong(9)));
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
                      "SELECT e.id,e.region_id,e.category,e.event_key,e.state,coalesce(o.progress,0),coalesce(o.target,1),e.state_at FROM world_events e LEFT JOIN event_objectives o ON o.event_id=e.id"
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
                      result.getTimestamp(8).toInstant()));
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
    int before = city.population;
    int delta = 0;
    if (city.foodUnits < Math.max(1, city.population / 4L) || city.activeWars > 0) delta = -1;
    else if (city.prosperity >= 70 && city.happiness >= 60 && city.treasury > 0) delta = 1;
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
    if (age && city.overdueMaintenance) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE road_edges SET integrity=greatest(0,integrity-1),version=version+1 WHERE from_node IN (SELECT id FROM road_nodes WHERE city_id=?)")) {
        statement.setObject(1, state.city);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE city_buildings SET integrity=greatest(0,integrity-1),status=CASE WHEN integrity-1<15 THEN 'DISABLED' WHEN integrity-1<40 THEN 'EMERGENCY' WHEN integrity-1<90 THEN 'DEGRADED' ELSE 'OPERATIONAL' END,version=version+1 WHERE city_id=?")) {
        statement.setObject(1, state.city);
        statement.executeUpdate();
      }
    }
    RegionRow region = refreshRegion(connection, state.region, city.world, season, now);
    boolean nature =
        state.lastNature == null || state.lastNature.plus(NATURE_INTERVAL).compareTo(now) <= 0;
    if (nature) updateNature(connection, region, now);
    boolean event = maybeCreateEvent(connection, region, season, now);
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
    return new CityCycle(delta != 0, age && city.overdueMaintenance, event);
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

  private static boolean maybeCreateEvent(
      Connection connection, RegionRow region, WorldSimulation.Season season, Instant now)
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
    String key = null;
    WorldEvent.Category category = null;
    if (region.prosperity >= 70 && region.trade > 0) {
      key = "TRADE_FAIR";
      category = WorldEvent.Category.ECONOMIC;
    } else if (region.stability < 40) {
      key = "BANDIT_RAID";
      category = WorldEvent.Category.MILITARY;
    } else if (season == WorldSimulation.Season.AUTUMN && region.population > 0) {
      key = "HARVEST_FESTIVAL";
      category = WorldEvent.Category.SOCIAL;
    }
    if (key == null) return false;
    UUID event = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO world_events(id,region_id,category,event_key,state,state_at,payload,version) VALUES(?,?,?,?,'SCHEDULED',?,'{}'::jsonb,0)")) {
      statement.setObject(1, event);
      statement.setObject(2, region.id);
      statement.setString(3, category.name());
      statement.setString(4, key);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO event_objectives(id,event_id,objective_key,progress,target,state,version) VALUES(?,?,?,0,100,'ACTIVE',0)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, event);
      statement.setString(3, key + "_REGIONAL_RESPONSE");
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO event_rewards(id,event_id,reward_key,amount,status) VALUES(?,?,?,100,'PENDING')")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, event);
      statement.setString(3, key.equals("BANDIT_RAID") ? "STABILITY" : "PROSPERITY");
      statement.executeUpdate();
    }
    eventHistory(connection, event, null, "SCHEDULED", now);
    return true;
  }

  private static int advanceEvents(Connection connection, Instant now) throws SQLException {
    List<EventRow> events = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id,region_id,state,state_at FROM world_events WHERE state<>'ARCHIVED' ORDER BY state_at LIMIT 100 FOR UPDATE SKIP LOCKED");
        ResultSet result = statement.executeQuery()) {
      while (result.next())
        events.add(
            new EventRow(
                result.getObject(1, UUID.class),
                result.getObject(2, UUID.class),
                result.getString(3),
                result.getTimestamp(4).toInstant()));
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
        advanced++;
      }
    }
    return advanced;
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

  private record EventRow(UUID id, UUID region, String state, Instant stateAt) {}

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
