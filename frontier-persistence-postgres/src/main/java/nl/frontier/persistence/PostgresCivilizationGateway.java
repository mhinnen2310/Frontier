package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.world.CivilizationGateway;
import nl.frontier.world.CivilizationProgression;

public final class PostgresCivilizationGateway implements CivilizationGateway {
  private static final Set<String> CITY_FINANCE_ROLES = Set.of("MAYOR", "TREASURER");
  private final TransactionalStore store;

  public PostgresCivilizationGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public KingdomSnapshot createKingdom(UUID city, UUID actor, String name, Instant now) {
    return store.inTransaction(
        connection -> {
          requireCityRole(connection, city, actor, Set.of("MAYOR"));
          if (!name.matches("[A-Za-z0-9][A-Za-z0-9 '\\-]{2,47}"))
            throw new DomainException("kingdom name must be 3-48 simple characters");
          if (cityKingdom(connection, city) != null)
            throw new DomainException("city already belongs to a kingdom");
          UUID kingdom = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO kingdoms(id,name,era,prestige,version,leader_player_id,created_at) VALUES(?,?,'FRONTIER',0,0,?,?)")) {
            statement.setObject(1, kingdom);
            statement.setString(2, name);
            statement.setObject(3, actor);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
          }
          insertMember(connection, kingdom, city, now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO kingdom_roles(kingdom_id,player_id,role,granted_at) VALUES(?,?,'KING',?)")) {
            statement.setObject(1, kingdom);
            statement.setObject(2, actor);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO research_points(kingdom_id,available_points,lifetime_points,version) VALUES(?,0,0,0)")) {
            statement.setObject(1, kingdom);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO kingdom_tax_policy(kingdom_id,rate_basis_points,updated_by,updated_at,version) VALUES(?,0,?,?,0)")) {
            statement.setObject(1, kingdom);
            statement.setObject(2, actor);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO accounts(id,owner_type,owner_id,balance_minor,version) VALUES(?,'KINGDOM',?,0,0)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, kingdom);
            statement.executeUpdate();
          }
          history(connection, kingdom, "KINGDOM_FOUNDED", "{\"city\":\"" + city + "\"}", now);
          return loadKingdom(connection, kingdom);
        });
  }

  @Override
  public Invitation inviteCity(
      UUID kingdom, UUID actor, UUID city, Instant expiresAt, Instant now) {
    return store.inTransaction(
        connection -> {
          requireKingdomAuthority(connection, kingdom, actor);
          requireCity(connection, city);
          if (cityKingdom(connection, city) != null)
            throw new DomainException("city already belongs to a kingdom");
          UUID id = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO kingdom_invitations(id,kingdom_id,city_id,status,expires_at,created_by,created_at,version) VALUES(?,?,?,'PENDING',?,?,?,0)")) {
            statement.setObject(1, id);
            statement.setObject(2, kingdom);
            statement.setObject(3, city);
            statement.setTimestamp(4, Timestamp.from(expiresAt));
            statement.setObject(5, actor);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.executeUpdate();
          }
          return new Invitation(id, kingdom, city, "PENDING", expiresAt);
        });
  }

  @Override
  public KingdomSnapshot acceptInvitation(UUID invitation, UUID city, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          requireCityRole(connection, city, actor, Set.of("MAYOR"));
          UUID kingdom;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT kingdom_id,status,expires_at FROM kingdom_invitations WHERE id=? AND city_id=? FOR UPDATE")) {
            statement.setObject(1, invitation);
            statement.setObject(2, city);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("kingdom invitation not found");
              kingdom = result.getObject(1, UUID.class);
              if (!result.getString(2).equals("PENDING"))
                throw new DomainException("kingdom invitation is not pending");
              if (!result.getTimestamp(3).toInstant().isAfter(now))
                throw new DomainException("kingdom invitation expired");
            }
          }
          if (cityKingdom(connection, city) != null)
            throw new DomainException("city already belongs to a kingdom");
          insertMember(connection, kingdom, city, now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE kingdom_invitations SET status='ACCEPTED',version=version+1 WHERE id=?")) {
            statement.setObject(1, invitation);
            statement.executeUpdate();
          }
          history(connection, kingdom, "CITY_JOINED", "{\"city\":\"" + city + "\"}", now);
          return loadKingdom(connection, kingdom);
        });
  }

  @Override
  public TreatySnapshot proposeTreaty(
      UUID kingdom,
      UUID actor,
      UUID counterpart,
      String type,
      String termsJson,
      Instant expiresAt,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireKingdomAuthority(connection, kingdom, actor);
          requireKingdom(connection, counterpart);
          if (kingdom.equals(counterpart)) throw new DomainException("treaty needs two kingdoms");
          UUID id = UUID.randomUUID();
          UUID first = orderedFirst(kingdom, counterpart);
          UUID second = first.equals(kingdom) ? counterpart : kingdom;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO treaties(id,first_kingdom,second_kingdom,treaty_type,status,terms,expires_at,version,proposed_by,created_at) VALUES(?,?,?,?, 'PROPOSED',?::jsonb,?,0,?,?)")) {
            statement.setObject(1, id);
            statement.setObject(2, first);
            statement.setObject(3, second);
            statement.setString(4, type);
            statement.setString(5, termsJson);
            statement.setTimestamp(6, Timestamp.from(expiresAt));
            statement.setObject(7, actor);
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
          }
          return loadTreaty(connection, id);
        });
  }

  @Override
  public TreatySnapshot acceptTreaty(UUID treaty, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          TreatySnapshot current = lockTreaty(connection, treaty);
          if (!current.status().equals("PROPOSED"))
            throw new DomainException("treaty is not proposed");
          UUID proposerKingdom = actorKingdom(connection, proposedBy(connection, treaty));
          UUID accepting =
              current.first().equals(proposerKingdom) ? current.second() : current.first();
          requireKingdomAuthority(connection, accepting, actor);
          if (current.expiresAt() != null && !current.expiresAt().isAfter(now))
            throw new DomainException("treaty proposal expired");
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE treaties SET status='ACTIVE',accepted_by=?,accepted_at=?,version=version+1 WHERE id=?")) {
            statement.setObject(1, actor);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, treaty);
            statement.executeUpdate();
          }
          relation(
              connection, current.first(), current.second(), relationFor(current.type()), 50, now);
          history(
              connection, current.first(), "TREATY_ACTIVE", "{\"treaty\":\"" + treaty + "\"}", now);
          history(
              connection,
              current.second(),
              "TREATY_ACTIVE",
              "{\"treaty\":\"" + treaty + "\"}",
              now);
          return loadTreaty(connection, treaty);
        });
  }

  @Override
  public ResearchSnapshot startResearch(
      UUID kingdom, UUID actor, String branch, String project, long requiredPoints, Instant now) {
    return store.inTransaction(
        connection -> {
          requireKingdomAuthority(connection, kingdom, actor);
          if (requiredPoints <= 0) throw new DomainException("research points must be positive");
          ContentDefinition definition = researchDefinition(connection, project);
          requireEra(connection, kingdom, definition.era);
          if (!definition.branch.equalsIgnoreCase(branch)
              || definition.requirement != requiredPoints)
            throw new DomainException(
                "research branch or point requirement does not match catalog");
          if (definition.prerequisite != null
              && !completedProject(connection, kingdom, definition.prerequisite))
            throw new DomainException("research prerequisite is not completed");
          UUID id = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO research_projects(id,kingdom_id,branch,project_key,progress_points,required_points,status,started_at,version) VALUES(?,?,?,?,0,?,'ACTIVE',?,0)")) {
            statement.setObject(1, id);
            statement.setObject(2, kingdom);
            statement.setString(3, branch);
            statement.setString(4, project);
            statement.setLong(5, requiredPoints);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.executeUpdate();
          }
          return loadResearch(connection, id);
        });
  }

  @Override
  public WonderSnapshot startWonder(
      UUID kingdom,
      UUID actor,
      String wonderKey,
      String commodity,
      long requiredUnits,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireKingdomAuthority(connection, kingdom, actor);
          ContentDefinition definition = wonderDefinition(connection, wonderKey);
          requireEra(connection, kingdom, definition.era);
          if (requiredUnits <= 0) throw new DomainException("wonder requirement must be positive");
          if (!definition.commodity.equals(commodity) || definition.requirement != requiredUnits)
            throw new DomainException("wonder material or requirement does not match catalog");
          UUID id = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO world_wonders(id,wonder_key,kingdom_id,required_units,contributed_units,status,version,commodity_key,started_by,started_at) VALUES(?,?,?, ?,0,'ACTIVE',0,?,?,?)")) {
            statement.setObject(1, id);
            statement.setString(2, wonderKey);
            statement.setObject(3, kingdom);
            statement.setLong(4, requiredUnits);
            statement.setString(5, commodity);
            statement.setObject(6, actor);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.executeUpdate();
          }
          serverHistory(connection, "WONDER_STARTED", id, "{\"key\":\"" + wonderKey + "\"}", now);
          return loadWonder(connection, id);
        });
  }

  @Override
  public WonderSnapshot contributeWonder(
      UUID wonder, UUID city, UUID actor, long units, UUID idempotency, Instant now) {
    return store.inTransaction(
        connection -> {
          if (units <= 0) throw new DomainException("wonder contribution must be positive");
          WonderSnapshot current = lockWonder(connection, wonder);
          UUID kingdom = cityKingdom(connection, city);
          if (kingdom == null || !kingdom.equals(current.kingdom()))
            throw new DomainException("city is not part of the wonder kingdom");
          requireCityRole(connection, city, actor, CITY_FINANCE_ROLES);
          if (!current.status().equals("ACTIVE")) throw new DomainException("wonder is not active");
          if (current.contributed() + units > current.required())
            throw new DomainException("wonder contribution exceeds remaining requirement");
          if (contributionExists(connection, idempotency)) return current;
          UUID warehouse = warehouse(connection, city);
          lockStock(connection, warehouse, current.commodity());
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE warehouse_stock SET available_quantity=available_quantity-?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity>=?")) {
            statement.setLong(1, units);
            statement.setObject(2, warehouse);
            statement.setString(3, current.commodity());
            statement.setLong(4, units);
            if (statement.executeUpdate() != 1)
              throw new DomainException("insufficient wonder construction material");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO wonder_contributions(id,wonder_id,city_id,actor_id,commodity_key,units,idempotency_key,contributed_at) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, wonder);
            statement.setObject(3, city);
            statement.setObject(4, actor);
            statement.setString(5, current.commodity());
            statement.setLong(6, units);
            statement.setObject(7, idempotency);
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
          }
          boolean complete = current.contributed() + units == current.required();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE world_wonders SET contributed_units=contributed_units+?,status=?,completed_at=?,version=version+1 WHERE id=?")) {
            statement.setLong(1, units);
            statement.setString(2, complete ? "COMPLETED" : "ACTIVE");
            statement.setTimestamp(3, complete ? Timestamp.from(now) : null);
            statement.setObject(4, wonder);
            statement.executeUpdate();
          }
          if (complete) {
            ContentDefinition definition = wonderDefinition(connection, current.key());
            grantPrestige(connection, current.kingdom(), definition.prestige, "WORLD_WONDER", now);
            unlock(connection, current.kingdom(), "WONDER", current.key(), definition.effect, now);
            serverHistory(connection, "WONDER_COMPLETED", wonder, "{}", now);
          }
          return loadWonder(connection, wonder);
        });
  }

  @Override
  public MegaProjectSnapshot startMegaProject(
      UUID kingdom,
      UUID actor,
      String projectKey,
      String commodity,
      long requiredUnits,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireKingdomAuthority(connection, kingdom, actor);
          if (requiredUnits <= 0) throw new DomainException("project requirement must be positive");
          ContentDefinition definition = megaDefinition(connection, projectKey);
          requireEra(connection, kingdom, definition.era);
          if (!definition.commodity.equals(commodity) || definition.requirement != requiredUnits)
            throw new DomainException(
                "mega-project material or requirement does not match catalog");
          UUID id = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO mega_projects(id,kingdom_id,project_key,status,progress,target,version,commodity_key,started_by,started_at) VALUES(?,?,?,'ACTIVE',0,?,0,?,?,?)")) {
            statement.setObject(1, id);
            statement.setObject(2, kingdom);
            statement.setString(3, projectKey);
            statement.setLong(4, requiredUnits);
            statement.setString(5, commodity);
            statement.setObject(6, actor);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.executeUpdate();
          }
          serverHistory(connection, "MEGA_PROJECT_STARTED", id, "{}", now);
          return loadMegaProject(connection, id, false);
        });
  }

  @Override
  public MegaProjectSnapshot contributeMegaProject(
      UUID project, UUID city, UUID actor, long units, UUID idempotency, Instant now) {
    return store.inTransaction(
        connection -> {
          if (units <= 0) throw new DomainException("project contribution must be positive");
          MegaProjectSnapshot current = loadMegaProject(connection, project, true);
          UUID kingdom = cityKingdom(connection, city);
          if (kingdom == null || !kingdom.equals(current.kingdom()))
            throw new DomainException("city is not part of the project kingdom");
          requireCityRole(connection, city, actor, CITY_FINANCE_ROLES);
          if (!current.status().equals("ACTIVE"))
            throw new DomainException("project is not active");
          if (current.contributed() + units > current.required())
            throw new DomainException("project contribution exceeds remaining requirement");
          if (megaContributionExists(connection, idempotency)) return current;
          UUID warehouse = warehouse(connection, city);
          lockStock(connection, warehouse, current.commodity());
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE warehouse_stock SET available_quantity=available_quantity-?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity>=?")) {
            statement.setLong(1, units);
            statement.setObject(2, warehouse);
            statement.setString(3, current.commodity());
            statement.setLong(4, units);
            if (statement.executeUpdate() != 1)
              throw new DomainException("insufficient project construction material");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO mega_project_contributions(id,project_id,city_id,actor_id,commodity_key,units,idempotency_key,contributed_at) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, project);
            statement.setObject(3, city);
            statement.setObject(4, actor);
            statement.setString(5, current.commodity());
            statement.setLong(6, units);
            statement.setObject(7, idempotency);
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
          }
          boolean complete = current.contributed() + units == current.required();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE mega_projects SET progress=progress+?,status=?,completed_at=?,version=version+1 WHERE id=?")) {
            statement.setLong(1, units);
            statement.setString(2, complete ? "COMPLETED" : "ACTIVE");
            statement.setTimestamp(3, complete ? Timestamp.from(now) : null);
            statement.setObject(4, project);
            statement.executeUpdate();
          }
          if (complete) {
            ContentDefinition definition = megaDefinition(connection, current.key());
            grantPrestige(connection, current.kingdom(), definition.prestige, "MEGA_PROJECT", now);
            unlock(
                connection,
                current.kingdom(),
                "MEGA_PROJECT",
                current.key(),
                definition.effect,
                now);
            serverHistory(connection, "MEGA_PROJECT_COMPLETED", project, "{}", now);
          }
          return loadMegaProject(connection, project, false);
        });
  }

  @Override
  public List<KingdomSnapshot> kingdoms() {
    return store.inTransaction(
        connection -> {
          List<KingdomSnapshot> values = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT id FROM kingdoms ORDER BY prestige DESC,name");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              values.add(loadKingdom(connection, result.getObject(1, UUID.class)));
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<TreatySnapshot> treaties(UUID kingdom) {
    return store.inTransaction(
        connection -> {
          List<TreatySnapshot> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id FROM treaties WHERE first_kingdom=? OR second_kingdom=? ORDER BY created_at DESC")) {
            statement.setObject(1, kingdom);
            statement.setObject(2, kingdom);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(loadTreaty(connection, result.getObject(1, UUID.class)));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<ResearchSnapshot> research(UUID kingdom) {
    return store.inTransaction(
        connection -> {
          List<ResearchSnapshot> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id FROM research_projects WHERE kingdom_id=? ORDER BY started_at DESC")) {
            statement.setObject(1, kingdom);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(loadResearch(connection, result.getObject(1, UUID.class)));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<WonderSnapshot> wonders() {
    return store.inTransaction(
        connection -> {
          List<WonderSnapshot> values = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT id FROM world_wonders ORDER BY started_at DESC");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              values.add(loadWonder(connection, result.getObject(1, UUID.class)));
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<MegaProjectSnapshot> megaProjects() {
    return store.inTransaction(
        connection -> {
          List<MegaProjectSnapshot> values = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT id FROM mega_projects ORDER BY started_at DESC");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              values.add(loadMegaProject(connection, result.getObject(1, UUID.class), false));
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<GlobalObjectiveSnapshot> globalObjectives() {
    return store.inTransaction(
        connection -> {
          List<GlobalObjectiveSnapshot> values = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT id,objective_key,progress,target,status FROM global_objectives ORDER BY objective_key");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              values.add(
                  new GlobalObjectiveSnapshot(
                      result.getObject(1, UUID.class),
                      result.getString(2),
                      result.getLong(3),
                      result.getLong(4),
                      result.getString(5)));
          }
          return List.copyOf(values);
        });
  }

  @Override
  public CycleReport cycle(int maximumKingdoms, Instant now) {
    return store.inTransaction(
        connection -> {
          List<UUID> kingdoms = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id FROM kingdoms ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED")) {
            statement.setInt(1, maximumKingdoms);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) kingdoms.add(result.getObject(1, UUID.class));
            }
          }
          int researchCompleted = 0;
          int eras = 0;
          long prestige = 0;
          for (UUID kingdom : kingdoms) {
            long generated = generateResearch(connection, kingdom, now);
            if (applyResearch(connection, kingdom, now)) researchCompleted++;
            long gained = prosperousPrestige(connection, kingdom);
            if (gained > 0) {
              grantPrestige(connection, kingdom, gained, "PROSPEROUS_CITIES", now);
              prestige += gained;
            }
            if (advanceEra(connection, kingdom, now)) eras++;
          }
          expireTreaties(connection, now);
          refreshGlobalObjectives(connection, now);
          return new CycleReport(kingdoms.size(), researchCompleted, eras, prestige);
        });
  }

  private static long generateResearch(Connection connection, UUID kingdom, Instant now)
      throws SQLException {
    long points;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(sum(c.civilization/10),0)+coalesce(sum((SELECT count(*)*10 FROM city_buildings b WHERE b.city_id=c.id AND b.category='CULTURE' AND b.integrity>=40)),0) FROM kingdom_members m JOIN cities c ON c.id=m.city_id WHERE m.kingdom_id=?")) {
      statement.setObject(1, kingdom);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        points = Math.max(1, result.getLong(1));
      }
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE research_points SET available_points=available_points+?,lifetime_points=lifetime_points+?,last_generated_at=?,version=version+1 WHERE kingdom_id=?")) {
      statement.setLong(1, points);
      statement.setLong(2, points);
      statement.setTimestamp(3, Timestamp.from(now));
      statement.setObject(4, kingdom);
      statement.executeUpdate();
    }
    return points;
  }

  private static boolean applyResearch(Connection connection, UUID kingdom, Instant now)
      throws SQLException {
    UUID project;
    String projectKey;
    long progress;
    long required;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,progress_points,required_points,project_key FROM research_projects WHERE kingdom_id=? AND status='ACTIVE' ORDER BY started_at LIMIT 1 FOR UPDATE SKIP LOCKED")) {
      statement.setObject(1, kingdom);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return false;
        project = result.getObject(1, UUID.class);
        progress = result.getLong(2);
        required = result.getLong(3);
        projectKey = result.getString(4);
      }
    }
    long available;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT available_points FROM research_points WHERE kingdom_id=? FOR UPDATE")) {
      statement.setObject(1, kingdom);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        available = result.getLong(1);
      }
    }
    long spend = Math.min(available, required - progress);
    if (spend == 0) return false;
    boolean complete = progress + spend == required;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE research_points SET available_points=available_points-?,version=version+1 WHERE kingdom_id=?")) {
      statement.setLong(1, spend);
      statement.setObject(2, kingdom);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE research_projects SET progress_points=progress_points+?,status=?,completed_at=?,version=version+1 WHERE id=?")) {
      statement.setLong(1, spend);
      statement.setString(2, complete ? "COMPLETED" : "ACTIVE");
      statement.setTimestamp(3, complete ? Timestamp.from(now) : null);
      statement.setObject(4, project);
      statement.executeUpdate();
    }
    if (complete) {
      grantPrestige(connection, kingdom, 100, "RESEARCH_COMPLETED", now);
      ContentDefinition definition = researchDefinition(connection, projectKey);
      unlock(connection, kingdom, "RESEARCH", projectKey, definition.effect, now);
      serverHistory(
          connection, "RESEARCH_COMPLETED", project, "{\"project\":\"" + projectKey + "\"}", now);
    }
    return complete;
  }

  private static long prosperousPrestige(Connection connection, UUID kingdom) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM kingdom_members m JOIN cities c ON c.id=m.city_id WHERE m.kingdom_id=? AND c.prosperity>=75")) {
      statement.setObject(1, kingdom);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static boolean advanceEra(Connection connection, UUID kingdom, Instant now)
      throws SQLException {
    KingdomSnapshot current = loadKingdom(connection, kingdom);
    CivilizationProgression.Era next = null;
    if (current.era() == CivilizationProgression.Era.FRONTIER
        && totalPopulation(connection, kingdom) >= 25
        && averageProsperity(connection, kingdom) >= 55)
      next = CivilizationProgression.Era.EXPANSION;
    else if (current.era() == CivilizationProgression.Era.EXPANSION
        && infrastructureCount(connection, kingdom) >= 2)
      next = CivilizationProgression.Era.INDUSTRIAL;
    else if (current.era() == CivilizationProgression.Era.INDUSTRIAL
        && current.cities().size() >= 2) next = CivilizationProgression.Era.KINGDOM;
    else if (current.era() == CivilizationProgression.Era.KINGDOM
        && current.prestige() >= 1_000
        && completedResearch(connection, kingdom) >= 3)
      next = CivilizationProgression.Era.GOLDEN_AGE;
    if (next == null) return false;
    try (PreparedStatement statement =
        connection.prepareStatement("UPDATE kingdoms SET era=?,version=version+1 WHERE id=?")) {
      statement.setString(1, next.name());
      statement.setObject(2, kingdom);
      statement.executeUpdate();
    }
    history(connection, kingdom, "ERA_ADVANCED", "{\"era\":\"" + next + "\"}", now);
    serverHistory(connection, "KINGDOM_ERA", kingdom, "{\"era\":\"" + next + "\"}", now);
    return true;
  }

  private static void expireTreaties(Connection connection, Instant now) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE treaties SET status='EXPIRED',version=version+1 WHERE status IN ('PROPOSED','ACTIVE') AND expires_at<=?")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static KingdomSnapshot loadKingdom(Connection connection, UUID kingdom)
      throws SQLException {
    String name;
    CivilizationProgression.Era era;
    long prestige;
    UUID leader;
    long version;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT name,era,prestige,leader_player_id,version FROM kingdoms WHERE id=?")) {
      statement.setObject(1, kingdom);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("kingdom not found");
        name = result.getString(1);
        era = CivilizationProgression.Era.valueOf(result.getString(2));
        prestige = result.getLong(3);
        leader = result.getObject(4, UUID.class);
        version = result.getLong(5);
      }
    }
    List<UUID> cities = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT city_id FROM kingdom_members WHERE kingdom_id=? ORDER BY city_id")) {
      statement.setObject(1, kingdom);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) cities.add(result.getObject(1, UUID.class));
      }
    }
    long points = 0;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT available_points FROM research_points WHERE kingdom_id=?")) {
      statement.setObject(1, kingdom);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) points = result.getLong(1);
      }
    }
    return new KingdomSnapshot(
        kingdom, name, era, prestige, leader, List.copyOf(cities), points, version);
  }

  private static TreatySnapshot loadTreaty(Connection connection, UUID treaty) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT first_kingdom,second_kingdom,treaty_type,status,expires_at,version FROM treaties WHERE id=?")) {
      statement.setObject(1, treaty);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("treaty not found");
        Timestamp expires = result.getTimestamp(5);
        return new TreatySnapshot(
            treaty,
            result.getObject(1, UUID.class),
            result.getObject(2, UUID.class),
            result.getString(3),
            result.getString(4),
            expires == null ? null : expires.toInstant(),
            result.getLong(6));
      }
    }
  }

  private static TreatySnapshot lockTreaty(Connection connection, UUID treaty) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM treaties WHERE id=? FOR UPDATE")) {
      statement.setObject(1, treaty);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("treaty not found");
      }
    }
    return loadTreaty(connection, treaty);
  }

  private static ResearchSnapshot loadResearch(Connection connection, UUID project)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT kingdom_id,branch,project_key,progress_points,required_points,status FROM research_projects WHERE id=?")) {
      statement.setObject(1, project);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("research project not found");
        return new ResearchSnapshot(
            project,
            result.getObject(1, UUID.class),
            result.getString(2),
            result.getString(3),
            result.getLong(4),
            result.getLong(5),
            result.getString(6));
      }
    }
  }

  private static WonderSnapshot loadWonder(Connection connection, UUID wonder) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT kingdom_id,wonder_key,commodity_key,contributed_units,required_units,status FROM world_wonders WHERE id=?")) {
      statement.setObject(1, wonder);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("world wonder not found");
        return new WonderSnapshot(
            wonder,
            result.getObject(1, UUID.class),
            result.getString(2),
            result.getString(3),
            result.getLong(4),
            result.getLong(5),
            result.getString(6));
      }
    }
  }

  private static WonderSnapshot lockWonder(Connection connection, UUID wonder) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM world_wonders WHERE id=? FOR UPDATE")) {
      statement.setObject(1, wonder);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("world wonder not found");
      }
    }
    return loadWonder(connection, wonder);
  }

  private static MegaProjectSnapshot loadMegaProject(
      Connection connection, UUID project, boolean lock) throws SQLException {
    String suffix = lock ? " FOR UPDATE" : "";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT kingdom_id,project_key,commodity_key,progress,target,status FROM mega_projects WHERE id=?"
                + suffix)) {
      statement.setObject(1, project);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("mega project not found");
        return new MegaProjectSnapshot(
            project,
            result.getObject(1, UUID.class),
            result.getString(2),
            result.getString(3),
            result.getLong(4),
            result.getLong(5),
            result.getString(6));
      }
    }
  }

  private static void refreshGlobalObjectives(Connection connection, Instant now)
      throws SQLException {
    updateObjective(
        connection,
        "CONNECT_CAPITALS",
        globalScalar(connection, "SELECT count(*) FROM road_edges WHERE integrity>=40"),
        Math.max(1, globalScalar(connection, "SELECT count(*) FROM kingdoms")),
        now);
    updateObjective(
        connection,
        "BUILD_WORLD_WONDERS",
        globalScalar(connection, "SELECT count(*) FROM world_wonders WHERE status='COMPLETED'"),
        1,
        now);
    updateObjective(
        connection,
        "SURVIVE_WORLD_CRISIS",
        globalScalar(
            connection,
            "SELECT count(*) FROM world_events WHERE state IN ('RESOLVED','COOLDOWN','ARCHIVED') AND category='CRISIS'"),
        1,
        now);
    updateObjective(
        connection,
        "RESTORE_WAR_RUINS",
        globalScalar(connection, "SELECT count(*) FROM repair_orders WHERE status='COMPLETED'"),
        1,
        now);
  }

  private static void updateObjective(
      Connection connection, String key, long progress, long target, Instant now)
      throws SQLException {
    boolean completed = progress >= target;
    boolean changed;
    UUID id;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE global_objectives SET progress=?,target=?,status=?,version=version+1 WHERE objective_key=? AND (progress<>? OR target<>? OR status<>?) RETURNING id,status='COMPLETED'")) {
      statement.setLong(1, progress);
      statement.setLong(2, target);
      statement.setString(3, completed ? "COMPLETED" : "ACTIVE");
      statement.setString(4, key);
      statement.setLong(5, progress);
      statement.setLong(6, target);
      statement.setString(7, completed ? "COMPLETED" : "ACTIVE");
      try (ResultSet result = statement.executeQuery()) {
        changed = result.next();
        id = changed ? result.getObject(1, UUID.class) : null;
      }
    }
    if (changed && completed)
      serverHistory(connection, "GLOBAL_OBJECTIVE_COMPLETED", id, "{}", now);
  }

  private static long globalScalar(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getLong(1);
    }
  }

  private static void requireKingdomAuthority(Connection connection, UUID kingdom, UUID actor)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM kingdom_roles WHERE kingdom_id=? AND player_id=? AND role IN ('KING','COUNCIL','DIPLOMAT')")) {
      statement.setObject(1, kingdom);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("kingdom role does not allow this action");
      }
    }
  }

  private static void requireCityRole(
      Connection connection, UUID city, UUID actor, Set<String> roles) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=?")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !roles.contains(result.getString(1)))
          throw new DomainException("city role does not allow this action");
      }
    }
  }

  private static UUID cityKingdom(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT kingdom_id FROM kingdom_members WHERE city_id=?")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getObject(1, UUID.class) : null;
      }
    }
  }

  private static UUID actorKingdom(Connection connection, UUID actor) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT kingdom_id FROM kingdom_roles WHERE player_id=? LIMIT 1")) {
      statement.setObject(1, actor);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getObject(1, UUID.class) : null;
      }
    }
  }

  private static UUID proposedBy(Connection connection, UUID treaty) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT proposed_by FROM treaties WHERE id=?")) {
      statement.setObject(1, treaty);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static void insertMember(Connection connection, UUID kingdom, UUID city, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO kingdom_members(kingdom_id,city_id,joined_at) VALUES(?,?,?)")) {
      statement.setObject(1, kingdom);
      statement.setObject(2, city);
      statement.setTimestamp(3, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void relation(
      Connection connection,
      UUID first,
      UUID second,
      String relation,
      int reputationDelta,
      Instant now)
      throws SQLException {
    UUID ordered = orderedFirst(first, second);
    UUID other = ordered.equals(first) ? second : first;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO diplomatic_relations(first_kingdom,second_kingdom,relation,reputation,updated_at,version) VALUES(?,?,?,?,?,0) ON CONFLICT(first_kingdom,second_kingdom) DO UPDATE SET relation=excluded.relation,reputation=greatest(-1000,least(1000,diplomatic_relations.reputation+?)),updated_at=excluded.updated_at,version=diplomatic_relations.version+1")) {
      statement.setObject(1, ordered);
      statement.setObject(2, other);
      statement.setString(3, relation);
      statement.setInt(4, reputationDelta);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.setInt(6, reputationDelta);
      statement.executeUpdate();
    }
  }

  private static String relationFor(String treaty) {
    return switch (treaty) {
      case "MILITARY_ALLIANCE", "MUTUAL_DEFENCE" -> "ALLIED";
      case "TRADE", "OPEN_BORDERS", "RESOURCE_SHARING" -> "FRIENDLY";
      case "PEACE", "NON_AGGRESSION" -> "NEUTRAL";
      default -> "FRIENDLY";
    };
  }

  private static UUID orderedFirst(UUID first, UUID second) {
    return first.toString().compareTo(second.toString()) < 0 ? first : second;
  }

  private static void requireCity(Connection connection, UUID city) throws SQLException {
    exists(connection, "SELECT 1 FROM cities WHERE id=?", city, "city not found");
  }

  private static void requireKingdom(Connection connection, UUID kingdom) throws SQLException {
    exists(connection, "SELECT 1 FROM kingdoms WHERE id=?", kingdom, "kingdom not found");
  }

  private static void exists(Connection connection, String sql, UUID id, String message)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException(message);
      }
    }
  }

  private static UUID warehouse(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("city warehouse not found");
        return result.getObject(1, UUID.class);
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

  private static boolean contributionExists(Connection connection, UUID idempotency)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM wonder_contributions WHERE idempotency_key=?")) {
      statement.setObject(1, idempotency);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static boolean megaContributionExists(Connection connection, UUID idempotency)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM mega_project_contributions WHERE idempotency_key=?")) {
      statement.setObject(1, idempotency);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void grantPrestige(
      Connection connection, UUID kingdom, long amount, String reason, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE kingdoms SET prestige=prestige+?,version=version+1 WHERE id=?")) {
      statement.setLong(1, amount);
      statement.setObject(2, kingdom);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO kingdom_prestige(id,kingdom_id,amount,reason,occurred_at) VALUES(?,?,?,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, kingdom);
      statement.setLong(3, amount);
      statement.setString(4, reason);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static long totalPopulation(Connection connection, UUID kingdom) throws SQLException {
    return scalar(
        connection,
        "SELECT coalesce(sum(c.population),0) FROM kingdom_members m JOIN cities c ON c.id=m.city_id WHERE m.kingdom_id=?",
        kingdom);
  }

  private static long averageProsperity(Connection connection, UUID kingdom) throws SQLException {
    return scalar(
        connection,
        "SELECT coalesce(avg(c.prosperity),0)::bigint FROM kingdom_members m JOIN cities c ON c.id=m.city_id WHERE m.kingdom_id=?",
        kingdom);
  }

  private static long infrastructureCount(Connection connection, UUID kingdom) throws SQLException {
    return scalar(
        connection,
        "SELECT count(*) FROM kingdom_members m JOIN city_buildings b ON b.city_id=m.city_id WHERE m.kingdom_id=? AND b.category='INFRASTRUCTURE' AND b.integrity>=40",
        kingdom);
  }

  private static long completedResearch(Connection connection, UUID kingdom) throws SQLException {
    return scalar(
        connection,
        "SELECT count(*) FROM research_projects WHERE kingdom_id=? AND status='COMPLETED'",
        kingdom);
  }

  private static long scalar(Connection connection, String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static ContentDefinition researchDefinition(Connection connection, String key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT branch,required_era,required_points,prerequisite_key,effect::text FROM endgame_research_definitions WHERE project_key=? AND enabled")) {
      statement.setString(1, key);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next())
          throw new DomainException("research project is not in the endgame catalog");
        return new ContentDefinition(
            result.getString(1),
            result.getString(2),
            null,
            result.getLong(3),
            result.getString(4),
            100,
            result.getString(5));
      }
    }
  }

  private static ContentDefinition wonderDefinition(Connection connection, String key)
      throws SQLException {
    return materialDefinition(
        connection,
        "SELECT required_era,commodity_key,required_units,prestige_reward,effect::text FROM endgame_wonder_definitions WHERE wonder_key=? AND enabled",
        key,
        "wonder is not in the unique-wonder catalog");
  }

  private static ContentDefinition megaDefinition(Connection connection, String key)
      throws SQLException {
    return materialDefinition(
        connection,
        "SELECT required_era,commodity_key,required_units,prestige_reward,effect::text FROM endgame_mega_definitions WHERE project_key=? AND enabled",
        key,
        "mega project is not in the endgame catalog");
  }

  private static ContentDefinition materialDefinition(
      Connection connection, String sql, String key, String missing) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException(missing);
        return new ContentDefinition(
            null,
            result.getString(1),
            result.getString(2),
            result.getLong(3),
            null,
            result.getLong(4),
            result.getString(5));
      }
    }
  }

  private static void requireEra(Connection connection, UUID kingdom, String required)
      throws SQLException {
    CivilizationProgression.Era current = loadKingdom(connection, kingdom).era();
    CivilizationProgression.Era minimum = CivilizationProgression.Era.valueOf(required);
    if (current.ordinal() < minimum.ordinal())
      throw new DomainException("content requires kingdom era " + minimum);
  }

  private static boolean completedProject(Connection connection, UUID kingdom, String project)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM research_projects WHERE kingdom_id=? AND project_key=? AND status='COMPLETED'")) {
      statement.setObject(1, kingdom);
      statement.setString(2, project);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void unlock(
      Connection connection, UUID kingdom, String type, String key, String effect, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO kingdom_unlocks(kingdom_id,content_type,content_key,effect,unlocked_at) VALUES(?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING")) {
      statement.setObject(1, kingdom);
      statement.setString(2, type);
      statement.setString(3, key);
      statement.setString(4, effect);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void history(
      Connection connection, UUID kingdom, String event, String payload, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO kingdom_history(id,kingdom_id,event_type,payload,occurred_at) VALUES(?,?,?,?::jsonb,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, kingdom);
      statement.setString(3, event);
      statement.setString(4, payload);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void serverHistory(
      Connection connection, String event, UUID aggregate, String payload, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO server_history(id,event_type,aggregate_id,payload,occurred_at) VALUES(?,?,?,?::jsonb,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(2, event);
      statement.setObject(3, aggregate);
      statement.setString(4, payload);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private record ContentDefinition(
      String branch,
      String era,
      String commodity,
      long requirement,
      String prerequisite,
      long prestige,
      String effect) {}
}
