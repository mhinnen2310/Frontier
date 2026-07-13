package nl.frontier.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import nl.frontier.api.TransactionalStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class MultiplayerScaleTest {
  private static final UUID WORLD =
      UUID.nameUUIDFromBytes("frontier-scale-world".getBytes(StandardCharsets.UTF_8));

  @Test
  void syntheticPlayerMatrixStaysBoundedAndConsistent() throws Exception {
    String url = System.getProperty("frontier.scale.database.url");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "scale database not configured");
    List<String> report = new ArrayList<>();
    report.add("players,seed_ms,world_cycle_ms,workload_ms,p95_ms,max_ms,operations");
    try (DatabaseManager database =
        new DatabaseManager(
            new DatabaseManager.Configuration(url, "frontier", "", 32, Duration.ofSeconds(10)))) {
      database.migrate();
      for (int players : List.of(50, 100, 250, 500)) {
        reset(database);
        long seedStarted = System.nanoTime();
        Fixture fixture = seed(database, players);
        long seedMillis = elapsedMillis(seedStarted);
        JdbcTransactionalStore store = new JdbcTransactionalStore(database.dataSource());
        long worldStarted = System.nanoTime();
        var worldReport = new PostgresWorldSimulationGateway(store).cycle(players, Instant.now());
        long worldMillis = elapsedMillis(worldStarted);
        assertEquals(players, worldReport.cities());

        int operations = players * 8;
        List<Long> timings = Collections.synchronizedList(new ArrayList<>(operations));
        long workloadStarted = System.nanoTime();
        try (var executor = Executors.newFixedThreadPool(16)) {
          List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
          for (int operation = 0; operation < operations; operation++) {
            int index = operation % players;
            int kind = operation % 9;
            futures.add(
                executor.submit(
                    () -> {
                      long started = System.nanoTime();
                      runReadWorkload(store, fixture, index, kind);
                      timings.add(System.nanoTime() - started);
                    }));
          }
          for (var future : futures) future.get(60, TimeUnit.SECONDS);
        }
        long workloadMillis = elapsedMillis(workloadStarted);
        List<Long> ordered = timings.stream().sorted().toList();
        double p95 =
            nanosToMillis(ordered.get(Math.max(0, (int) Math.ceil(ordered.size() * 0.95) - 1)));
        double maximum = nanosToMillis(ordered.getLast());
        report.add(
            players
                + ","
                + seedMillis
                + ","
                + worldMillis
                + ","
                + workloadMillis
                + ","
                + p95
                + ","
                + maximum
                + ","
                + operations);
        assertTrue(p95 < 1_000, "p95 database-backed operation exceeded 1 second at " + players);
        assertTrue(workloadMillis < 60_000, "workload exceeded 60 seconds at " + players);
        assertInvariants(store, players);
      }
    }
    Path output = Path.of("build", "reports", "scale-results.csv");
    Files.createDirectories(output.getParent());
    Files.write(output, report, StandardCharsets.UTF_8);
  }

  private static void runReadWorkload(
      TransactionalStore store, Fixture fixture, int index, int kind) {
    UUID city = fixture.cities.get(index);
    UUID player = fixture.players.get(index);
    switch (kind) {
      case 0 -> new PostgresCampaignGateway(store).policySnapshot(Instant.now());
      case 1 -> new PostgresPopulationGateway(store).workers(city, player);
      case 2 -> new PostgresEconomyGateway(store).openOrders(city);
      case 3 -> new PostgresRepairGateway(store).orders(city);
      case 4 -> new PostgresCaravanGateway(store).presentations(1_000);
      case 5 -> new PostgresDynamicEventGateway(store).available(player, Instant.now());
      case 6 -> new PostgresAdminDiagnostics(store).liveMetrics();
      case 7 -> new PostgresEndgameGateway(store).rankings(100);
      default -> new PostgresWorldSimulationGateway(store).regions();
    }
  }

  private static void assertInvariants(TransactionalStore store, int players) {
    store.inTransaction(
        connection -> {
          assertEquals(players, count(connection, "SELECT count(*) FROM cities"));
          assertEquals(players * 2L, count(connection, "SELECT count(*) FROM workers"));
          assertEquals(players, count(connection, "SELECT count(*) FROM caravans"));
          assertEquals(0, count(connection, "SELECT count(*) FROM accounts WHERE balance_minor<0"));
          assertEquals(
              0,
              count(
                  connection,
                  "SELECT count(*) FROM warehouse_stock WHERE available_quantity<0 OR reserved_quantity<0"));
          assertEquals(
              0,
              count(
                  connection,
                  "SELECT count(*) FROM (SELECT idempotency_key,count(*) FROM shipments WHERE idempotency_key IS NOT NULL GROUP BY idempotency_key HAVING count(*)>1)s"));
          return null;
        });
  }

  private static Fixture seed(DatabaseManager database, int players) throws Exception {
    List<UUID> cities = new ArrayList<>(players);
    List<UUID> actors = new ArrayList<>(players);
    List<UUID> warehouses = new ArrayList<>(players);
    for (int index = 0; index < players; index++) {
      cities.add(id("city", index));
      actors.add(id("player", index));
      warehouses.add(id("warehouse", index));
    }
    Instant now = Instant.now();
    try (Connection connection = database.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement city =
              connection.prepareStatement(
                  "INSERT INTO cities(id,name,owner_id,level,population,prosperity,civilization,created_at,version) VALUES(?,?,?,3,25,65,55,?,0)");
          PreparedStatement member =
              connection.prepareStatement(
                  "INSERT INTO city_members(city_id,player_id,role,joined_at) VALUES(?,?,'MAYOR',?)");
          PreparedStatement claim =
              connection.prepareStatement(
                  "INSERT INTO city_claims(city_id,world_id,chunk_x,chunk_z,state,influence,lead_cycles,version) VALUES(?,?,?,0,'CAPITAL',100,3,0)");
          PreparedStatement account =
              connection.prepareStatement(
                  "INSERT INTO accounts(id,owner_type,owner_id,balance_minor,version) VALUES(?,'CITY',?,100000,0)");
          PreparedStatement warehouse =
              connection.prepareStatement(
                  "INSERT INTO warehouses(id,city_id,capacity,status,version) VALUES(?,?,100000,'ACTIVE',0)");
          PreparedStatement stock =
              connection.prepareStatement(
                  "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity,version) VALUES(?,'minecraft:wheat',10000,0,0)");
          PreparedStatement worker =
              connection.prepareStatement(
                  "INSERT INTO workers(id,city_id,profession,skill,state,salary_minor,version) VALUES(?,?,'BUILDER',60,'IDLE',100,0)");
          PreparedStatement simulation =
              connection.prepareStatement(
                  "INSERT INTO city_world_simulation_state(city_id,region_key,observed_city_version,next_cycle_at,version) VALUES(?,?, -1,?,0)");
          PreparedStatement population =
              connection.prepareStatement(
                  "INSERT INTO city_population_state(city_id,housing_capacity,food_security,safety,next_cycle_at,version) VALUES(?,50,80,80,?,0)");
          PreparedStatement market =
              connection.prepareStatement(
                  "INSERT INTO market_orders(id,owner_id,settlement_id,side,commodity_key,quantity,remaining_quantity,unit_price_minor,status,created_at,version,idempotency_key) VALUES(?,?,?,'SELL','minecraft:wheat',100,100,10,'OPEN',?,0,?)")) {
        for (int index = 0; index < players; index++) {
          UUID cityId = cities.get(index);
          UUID actor = actors.get(index);
          UUID warehouseId = warehouses.get(index);
          city.setObject(1, cityId);
          city.setString(2, "Scale-" + index);
          city.setObject(3, actor);
          city.setTimestamp(4, Timestamp.from(now));
          city.addBatch();
          member.setObject(1, cityId);
          member.setObject(2, actor);
          member.setTimestamp(3, Timestamp.from(now));
          member.addBatch();
          claim.setObject(1, cityId);
          claim.setObject(2, WORLD);
          claim.setInt(3, index);
          claim.addBatch();
          account.setObject(1, id("account", index));
          account.setObject(2, cityId);
          account.addBatch();
          warehouse.setObject(1, warehouseId);
          warehouse.setObject(2, cityId);
          warehouse.addBatch();
          stock.setObject(1, warehouseId);
          stock.addBatch();
          for (int number = 0; number < 2; number++) {
            worker.setObject(1, id("worker-" + number, index));
            worker.setObject(2, cityId);
            worker.addBatch();
          }
          simulation.setObject(1, cityId);
          simulation.setString(2, WORLD + ":" + index / 32 + ":0");
          simulation.setTimestamp(3, Timestamp.from(now));
          simulation.addBatch();
          population.setObject(1, cityId);
          population.setTimestamp(2, Timestamp.from(now.plusSeconds(86_400)));
          population.addBatch();
          market.setObject(1, id("market", index));
          market.setObject(2, actor);
          market.setObject(3, cityId);
          market.setTimestamp(4, Timestamp.from(now));
          market.setObject(5, id("market-key", index));
          market.addBatch();
        }
        execute(
            city, member, claim, account, warehouse, stock, worker, simulation, population, market);
      }
      seedActivity(connection, cities, actors, warehouses, now);
      connection.commit();
    }
    return new Fixture(List.copyOf(cities), List.copyOf(actors));
  }

  private static void seedActivity(
      Connection connection,
      List<UUID> cities,
      List<UUID> actors,
      List<UUID> warehouses,
      Instant now)
      throws Exception {
    try (PreparedStatement campaign =
            connection.prepareStatement(
                "INSERT INTO campaigns(id,attacker_city_id,defender_city_id,campaign_type,phase,declared_at,active_at,scheduled_active_at,maximum_ends_at,baseline_finalized,version,idempotency_key) VALUES(?,?,?,'BORDER','ACTIVE',?,?,?,?,true,0,?)");
        PreparedStatement objective =
            connection.prepareStatement(
                "INSERT INTO campaign_objectives(id,campaign_id,objective_type,state,world_id,bounds,progress,target,version,minimum_participants) VALUES(?,?,'CONTROL','ACTIVE',?,'{\"minX\":0,\"minY\":0,\"minZ\":0,\"maxX\":16,\"maxY\":320,\"maxZ\":16}'::jsonb,0,100,0,1)");
        PreparedStatement repair =
            connection.prepareStatement(
                "INSERT INTO repair_orders(id,city_id,campaign_id,priority,status,estimate_minor,total_tasks,completed_tasks,version,created_by,created_at,idempotency_key) VALUES(?, ?,?,'NORMAL','REPAIRING',1000,4,0,0,?,?,?)");
        PreparedStatement task =
            connection.prepareStatement(
                "INSERT INTO repair_tasks(id,repair_order_id,world_id,x,y,z,expected_current,target_data,commodity_key,layer,status,version,priority_score) VALUES(?,?,?,?,64,0,'minecraft:air','minecraft:stone','minecraft:stone','STRUCTURE','READY',0,50)");
        PreparedStatement shipment =
            connection.prepareStatement(
                "INSERT INTO shipments(id,origin_storage_id,destination_storage_id,manifest,carrier_type,status,declared_value_minor,insured_value_minor,departed_at,expected_arrival_at,version,owner_city_id,idempotency_key) VALUES(?,?,?,'{\"minecraft:wheat\":10}'::jsonb,'CARAVAN','TRAVELING',100,0,?,?,0,?,?)");
        PreparedStatement caravan =
            connection.prepareStatement(
                "INSERT INTO caravans(shipment_id,state,health,progress,route_index,simulation_mode,state_at,updated_at,version) VALUES(?,'WALKING',100,0,0,'SIMULATED',?,?,0)");
        PreparedStatement event =
            connection.prepareStatement(
                "INSERT INTO world_events(id,category,event_key,state,state_at,payload,version,severity,city_id,expires_at) VALUES(?,'SOCIAL','SCALE_EVENT','ACTIVE',?,'{}'::jsonb,0,50,?,?)");
        PreparedStatement eventObjective =
            connection.prepareStatement(
                "INSERT INTO event_objectives(id,event_id,objective_key,progress,target,state,version) VALUES(?,?,'SCALE_RESPONSE',0,100,'ACTIVE',0)")) {
      for (int index = 0; index < cities.size(); index++) {
        UUID shipmentId = id("shipment", index);
        shipment.setObject(1, shipmentId);
        shipment.setObject(2, warehouses.get(index));
        shipment.setObject(3, warehouses.get((index + 1) % cities.size()));
        shipment.setTimestamp(4, Timestamp.from(now));
        shipment.setTimestamp(5, Timestamp.from(now.plusSeconds(3600)));
        shipment.setObject(6, cities.get(index));
        shipment.setObject(7, id("shipment-key", index));
        shipment.addBatch();
        caravan.setObject(1, shipmentId);
        caravan.setTimestamp(2, Timestamp.from(now));
        caravan.setTimestamp(3, Timestamp.from(now));
        caravan.addBatch();
        if (index % 2 == 0 && index + 1 < cities.size()) {
          UUID campaignId = id("campaign", index);
          UUID repairId = id("repair", index);
          campaign.setObject(1, campaignId);
          campaign.setObject(2, cities.get(index));
          campaign.setObject(3, cities.get(index + 1));
          campaign.setTimestamp(4, Timestamp.from(now));
          campaign.setTimestamp(5, Timestamp.from(now));
          campaign.setTimestamp(6, Timestamp.from(now));
          campaign.setTimestamp(7, Timestamp.from(now.plusSeconds(86_400)));
          campaign.setObject(8, id("campaign-key", index));
          campaign.addBatch();
          objective.setObject(1, id("objective", index));
          objective.setObject(2, campaignId);
          objective.setObject(3, WORLD);
          objective.addBatch();
          repair.setObject(1, repairId);
          repair.setObject(2, cities.get(index + 1));
          repair.setObject(3, campaignId);
          repair.setObject(4, actors.get(index + 1));
          repair.setTimestamp(5, Timestamp.from(now));
          repair.setObject(6, id("repair-key", index));
          repair.addBatch();
          for (int number = 0; number < 4; number++) {
            task.setObject(1, id("task-" + number, index));
            task.setObject(2, repairId);
            task.setObject(3, WORLD);
            task.setInt(4, index * 8 + number);
            task.addBatch();
          }
        }
        if (index % 10 == 0) {
          UUID eventId = id("event", index);
          event.setObject(1, eventId);
          event.setTimestamp(2, Timestamp.from(now));
          event.setObject(3, cities.get(index));
          event.setTimestamp(4, Timestamp.from(now.plusSeconds(3600)));
          event.addBatch();
          eventObjective.setObject(1, id("event-objective", index));
          eventObjective.setObject(2, eventId);
          eventObjective.addBatch();
        }
      }
      execute(campaign, objective, repair, task, shipment, caravan, event, eventObjective);
    }
  }

  private static void execute(PreparedStatement... statements) throws Exception {
    for (PreparedStatement statement : statements) statement.executeBatch();
  }

  private static long count(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static UUID id(String type, int index) {
    return UUID.nameUUIDFromBytes(
        ("frontier-scale:" + type + ":" + index).getBytes(StandardCharsets.UTF_8));
  }

  private static long elapsedMillis(long started) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }

  private static double nanosToMillis(long nanos) {
    return Math.round(nanos / 10_000.0) / 100.0;
  }

  private static void reset(DatabaseManager database) throws Exception {
    try (var connection = database.dataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "DO $$ DECLARE names text; BEGIN SELECT string_agg(format('%I.%I',schemaname,tablename),',') INTO names FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','commodity_definitions','recipes','recipe_inputs','recipe_outputs','endgame_research_definitions','endgame_wonder_definitions','endgame_mega_definitions'); IF names IS NOT NULL THEN EXECUTE 'TRUNCATE TABLE '||names||' CASCADE'; END IF; END $$");
    }
  }

  private record Fixture(List<UUID> cities, List<UUID> players) {}
}
