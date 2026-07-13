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
import nl.frontier.city.PopulationGateway;
import nl.frontier.domain.DomainException;

public final class PostgresPopulationGateway implements PopulationGateway {
  private final TransactionalStore store;

  public PostgresPopulationGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public CycleReport cycle(int limit, Instant now) {
    return store.inTransaction(
        connection -> {
          update(
              connection,
              "INSERT INTO city_population_state(city_id,next_cycle_at) SELECT id,? FROM cities c WHERE c.lifecycle_status='ACTIVE' ON CONFLICT(city_id) DO NOTHING",
              now);
          List<UUID> cities = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT p.city_id FROM city_population_state p JOIN cities c ON c.id=p.city_id WHERE p.next_cycle_at<=? AND c.lifecycle_status='ACTIVE' ORDER BY p.next_cycle_at,p.city_id LIMIT ? FOR UPDATE OF p SKIP LOCKED")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) cities.add(result.getObject(1, UUID.class));
            }
          }
          int births = 0;
          int deaths = 0;
          int immigration = 0;
          int emigration = 0;
          int retired = 0;
          for (UUID city : cities) {
            Factors factors = factors(connection, city);
            int freeHousing = Math.max(0, factors.housing - factors.population);
            int cityBirths =
                factors.population > 1
                        && factors.food >= 70
                        && factors.safety >= 50
                        && factors.prosperity >= 50
                        && freeHousing > 0
                    ? Math.min(freeHousing, Math.max(1, factors.population / 200))
                    : 0;
            int cityDeaths =
                factors.food < 25 && factors.population > 0
                    ? Math.max(1, factors.population / 100)
                    : factors.population / 1000;
            int cityImmigration =
                freeHousing > cityBirths
                        && factors.prosperity >= 55
                        && factors.safety >= 50
                        && factors.food >= 50
                    ? Math.min(freeHousing - cityBirths, Math.max(1, factors.prosperity / 25))
                    : 0;
            int cityEmigration =
                (factors.food < 50 || factors.safety < 40 || factors.prosperity < 30)
                        && factors.population > 0
                    ? Math.max(1, factors.population / 50)
                    : 0;
            int population =
                Math.max(
                    0,
                    factors.population
                        + cityBirths
                        - cityDeaths
                        + cityImmigration
                        - cityEmigration);
            update(
                connection,
                "UPDATE cities SET population=?,version=version+1 WHERE id=?",
                population,
                city);
            update(
                connection,
                "UPDATE city_population_state SET housing_capacity=?,food_security=?,safety=?,births=?,deaths=?,immigration=?,emigration=?,simulated_at=?,next_cycle_at=?,version=version+1 WHERE city_id=?",
                factors.housing,
                factors.food,
                factors.safety,
                cityBirths,
                cityDeaths,
                cityImmigration,
                cityEmigration,
                now,
                now.plusSeconds(86_400),
                city);
            record(connection, city, "BIRTH", cityBirths, population, factors, now);
            record(connection, city, "DEATH", cityDeaths, population, factors, now);
            migration(
                connection, city, "IMMIGRATION", cityImmigration, "prosperity_and_capacity", now);
            migration(
                connection, city, "EMIGRATION", cityEmigration, "food_safety_or_prosperity", now);
            retired += updateWorkers(connection, city, factors, now);
            births += cityBirths;
            deaths += cityDeaths;
            immigration += cityImmigration;
            emigration += cityEmigration;
          }
          return new CycleReport(cities.size(), births, deaths, immigration, emigration, retired);
        });
  }

  @Override
  public PopulationReport report(UUID city, UUID actor) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT c.population,p.housing_capacity,p.food_security,p.safety,c.prosperity,p.births,p.deaths,p.immigration,p.emigration,p.simulated_at FROM cities c JOIN city_population_state p ON p.city_id=c.id WHERE c.id=?")) {
            statement.setObject(1, city);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("population report is not initialized");
              Timestamp simulated = result.getTimestamp(10);
              return new PopulationReport(
                  city,
                  result.getInt(1),
                  result.getInt(2),
                  result.getInt(3),
                  result.getInt(4),
                  result.getInt(5),
                  result.getInt(6),
                  result.getInt(7),
                  result.getInt(8),
                  result.getInt(9),
                  simulated == null ? null : simulated.toInstant());
            }
          }
        });
  }

  @Override
  public List<WorkerProfile> workers(UUID city, UUID actor) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor);
          List<WorkerProfile> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,profession,skill,morale,efficiency,salary_minor,experience,employment_status,housing_building,age_days,retirement_age_days FROM workers WHERE city_id=? ORDER BY employment_status,efficiency DESC,id")) {
            statement.setObject(1, city);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new WorkerProfile(
                        result.getObject(1, UUID.class),
                        result.getString(2),
                        result.getInt(3),
                        result.getInt(4),
                        result.getInt(5),
                        result.getLong(6),
                        result.getLong(7),
                        result.getString(8),
                        result.getObject(9, UUID.class),
                        result.getInt(10),
                        result.getInt(11)));
            }
          }
          return List.copyOf(values);
        });
  }

  private static Factors factors(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT c.population,c.prosperity,5+(SELECT count(*)*10 FROM city_buildings b WHERE b.city_id=c.id AND (b.building_type='HOUSING' OR b.category='RESIDENTIAL') AND b.status IN ('ACTIVE','DAMAGED'))+coalesce((SELECT max(housing_bonus) FROM district_effects d WHERE d.city_id=c.id),0),least(100,coalesce((SELECT sum(CASE WHEN s.commodity_key='minecraft:bread' THEN s.available_quantity*4 ELSE s.available_quantity END)*100/greatest(1,ceil(c.population/4.0)) FROM warehouse_stock s JOIN warehouses w ON w.id=s.warehouse_id WHERE w.city_id=c.id AND w.status='ACTIVE' AND s.commodity_key IN ('minecraft:wheat','minecraft:bread')),0)),greatest(0,least(100,50+(SELECT count(*)*10 FROM city_buildings b WHERE b.city_id=c.id AND (b.building_type='BARRACKS' OR b.category='MILITARY') AND b.status IN ('ACTIVE','DAMAGED'))-(SELECT count(*)*20 FROM campaigns ca WHERE ca.defender_city_id=c.id AND ca.phase IN ('PREPARATION','ACTIVE')))) FROM cities c WHERE c.id=?")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("population settlement missing");
        return new Factors(
            result.getInt(1),
            result.getInt(2),
            result.getInt(3),
            result.getInt(4),
            result.getInt(5));
      }
    }
  }

  private static int updateWorkers(Connection connection, UUID city, Factors factors, Instant now)
      throws SQLException {
    UUID housing = null;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM city_buildings WHERE city_id=? AND (building_type='HOUSING' OR category='RESIDENTIAL') AND status IN ('ACTIVE','DAMAGED') ORDER BY id LIMIT 1")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) housing = result.getObject(1, UUID.class);
      }
    }
    List<Worker> workers = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT w.id,w.skill,w.mood,w.happiness,w.experience,w.salary_minor,w.age_days,w.retirement_age_days,w.employment_status,coalesce((SELECT de.worker_efficiency_bonus FROM district_workers dw JOIN district_effects de ON de.district_id=dw.district_id WHERE dw.worker_id=w.id),0) FROM workers w WHERE w.city_id=? FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          workers.add(
              new Worker(
                  result.getObject(1, UUID.class),
                  result.getInt(2),
                  result.getInt(3),
                  result.getInt(4),
                  result.getLong(5),
                  result.getLong(6),
                  result.getInt(7),
                  result.getInt(8),
                  result.getString(9),
                  result.getInt(10)));
      }
    }
    int retired = 0;
    for (Worker worker : workers) {
      int age = worker.age + 1;
      int morale =
          clamp((worker.mood + worker.happiness + factors.prosperity + factors.food) / 4, 0, 100);
      int experienceScore = (int) Math.min(100, worker.experience / 1_000);
      int efficiency =
          clamp(
              worker.skill / 2 + morale * 3 / 10 + experienceScore / 5 + worker.districtBonus,
              0,
              150);
      boolean retiring = age >= worker.retirementAge;
      String employment = retiring ? "RETIRED" : worker.salary > 0 ? "EMPLOYED" : "SEEKING_WORK";
      if (retiring && !worker.employment.equals("RETIRED")) retired++;
      update(
          connection,
          "UPDATE workers SET morale=?,efficiency=?,employment_status=?,housing_building=?,age_days=?,experience=experience+?,retired_at=CASE WHEN ? THEN coalesce(retired_at,?) ELSE retired_at END,state=CASE WHEN ? THEN 'RETIRED' ELSE state END,version=version+1 WHERE id=?",
          morale,
          efficiency,
          employment,
          housing,
          age,
          employment.equals("EMPLOYED") ? 10 : 0,
          retiring,
          now,
          retiring,
          worker.id);
    }
    return retired;
  }

  private static void record(
      Connection connection,
      UUID city,
      String event,
      int quantity,
      int population,
      Factors factors,
      Instant now)
      throws SQLException {
    if (quantity <= 0) return;
    update(
        connection,
        "INSERT INTO demographic_history(id,city_id,event_type,quantity,population_after,factors,occurred_at) VALUES(?,?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        city,
        event,
        quantity,
        population,
        "{\"housing\":"
            + factors.housing
            + ",\"food\":"
            + factors.food
            + ",\"safety\":"
            + factors.safety
            + ",\"prosperity\":"
            + factors.prosperity
            + "}",
        now);
  }

  private static void migration(
      Connection connection, UUID city, String direction, int quantity, String reason, Instant now)
      throws SQLException {
    if (quantity <= 0) return;
    update(
        connection,
        "INSERT INTO migration_applications(id,city_id,direction,quantity,reason,status,created_at,resolved_at) VALUES(?,?,?,?,?,'APPLIED',?,?)",
        UUID.randomUUID(),
        city,
        direction,
        quantity,
        reason,
        now,
        now);
  }

  private static void requireMember(Connection connection, UUID city, UUID actor)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM city_members WHERE city_id=? AND player_id=?")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("not a settlement member");
      }
    }
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
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

  private record Factors(int population, int prosperity, int housing, int food, int safety) {}

  private record Worker(
      UUID id,
      int skill,
      int mood,
      int happiness,
      long experience,
      long salary,
      int age,
      int retirementAge,
      String employment,
      int districtBonus) {}
}
