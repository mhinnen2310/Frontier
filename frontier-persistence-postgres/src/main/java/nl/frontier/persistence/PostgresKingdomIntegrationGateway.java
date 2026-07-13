package nl.frontier.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.world.KingdomIntegrationGateway;

public final class PostgresKingdomIntegrationGateway implements KingdomIntegrationGateway {
  private static final Set<String> GOVERNANCE = Set.of("KING", "COUNCIL");
  private static final Set<String> WAR_AUTHORITY = Set.of("KING", "MARSHAL");
  private final TransactionalStore store;

  public PostgresKingdomIntegrationGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public KingdomReport report(UUID kingdom) {
    return store.inTransaction(connection -> report(connection, kingdom));
  }

  @Override
  public void assignRole(UUID kingdom, UUID actor, UUID player, Role role, Instant now) {
    store.inTransaction(
        connection -> {
          requireRole(connection, kingdom, actor, Set.of("KING"));
          if (!kingdomPlayer(connection, kingdom, player))
            throw new DomainException("player is not a citizen of this kingdom");
          if (role == Role.KING) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE kingdom_roles SET role='COUNCIL',granted_at=? WHERE kingdom_id=? AND role='KING'")) {
              statement.setTimestamp(1, Timestamp.from(now));
              statement.setObject(2, kingdom);
              statement.executeUpdate();
            }
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE kingdoms SET leader_player_id=?,version=version+1 WHERE id=?")) {
              statement.setObject(1, player);
              statement.setObject(2, kingdom);
              statement.executeUpdate();
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO kingdom_roles(kingdom_id,player_id,role,granted_at) VALUES(?,?,?,?) ON CONFLICT(kingdom_id,player_id) DO UPDATE SET role=excluded.role,granted_at=excluded.granted_at")) {
            statement.setObject(1, kingdom);
            statement.setObject(2, player);
            statement.setString(3, role.name());
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
          }
          history(connection, kingdom, "ROLE_ASSIGNED", json("player", player, "role", role), now);
          return null;
        });
  }

  @Override
  public Vote createVote(
      UUID kingdom, UUID actor, String kind, String subjectJson, Instant closesAt, Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, kingdom, actor, GOVERNANCE);
          int members =
              scalar(
                  connection, "SELECT count(*) FROM kingdom_members WHERE kingdom_id=?", kingdom);
          UUID id = UUID.randomUUID();
          int required = members / 2 + 1;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO political_votes(id,kingdom_id,vote_key,status,closes_at,result,version,created_by,subject,required_yes,created_at) VALUES(?,?,?,'OPEN',?,NULL,0,?,?::jsonb,?,?)")) {
            statement.setObject(1, id);
            statement.setObject(2, kingdom);
            statement.setString(3, kind);
            statement.setTimestamp(4, Timestamp.from(closesAt));
            statement.setObject(5, actor);
            statement.setString(6, subjectJson);
            statement.setInt(7, required);
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
          }
          history(connection, kingdom, "VOTE_OPENED", json("vote", id, "kind", kind), now);
          return vote(connection, id);
        });
  }

  @Override
  public Vote castVote(UUID vote, UUID city, UUID actor, boolean yes, Instant now) {
    return store.inTransaction(
        connection -> {
          Vote current = lockVote(connection, vote);
          if (!current.status().equals("OPEN")) throw new DomainException("vote is not open");
          Instant closes = voteCloses(connection, vote);
          if (!closes.isAfter(now)) throw new DomainException("vote has closed");
          requireMemberMayor(connection, current.kingdom(), city, actor);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO kingdom_vote_ballots(vote_id,city_id,actor_id,choice,cast_at) VALUES(?,?,?,?,?) ON CONFLICT(vote_id,city_id) DO UPDATE SET actor_id=excluded.actor_id,choice=excluded.choice,cast_at=excluded.cast_at")) {
            statement.setObject(1, vote);
            statement.setObject(2, city);
            statement.setObject(3, actor);
            statement.setBoolean(4, yes);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
          }
          Vote counted = vote(connection, vote);
          if (counted.yes() >= counted.requiredYes()) {
            closeVote(connection, vote, "PASSED", counted, now);
          } else {
            int members =
                scalar(
                    connection,
                    "SELECT count(*) FROM kingdom_members WHERE kingdom_id=?",
                    counted.kingdom());
            if (counted.no() > members - counted.requiredYes())
              closeVote(connection, vote, "REJECTED", counted, now);
          }
          return vote(connection, vote);
        });
  }

  @Override
  public WarApproval approveWar(
      UUID kingdom,
      UUID actor,
      UUID targetCity,
      String approvalType,
      Instant expiresAt,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, kingdom, actor, WAR_AUTHORITY);
          requireCity(connection, targetCity);
          UUID targetKingdom = cityKingdom(connection, targetCity);
          if (kingdom.equals(targetKingdom) && !approvalType.equals("SECESSION"))
            throw new DomainException("attacking a member requires a secession civil war");
          if (activeNonAggression(connection, kingdom, targetKingdom, now))
            throw new DomainException("an active non-aggression treaty blocks approval");
          UUID id = UUID.randomUUID();
          insertApproval(connection, id, kingdom, targetCity, approvalType, actor, expiresAt, now);
          history(
              connection,
              kingdom,
              "WAR_APPROVED",
              json("target", targetCity, "type", approvalType),
              now);
          return new WarApproval(id, kingdom, targetCity, approvalType, expiresAt);
        });
  }

  @Override
  public TreasuryResult deposit(
      UUID kingdom, UUID city, UUID actor, long amount, UUID idempotency, Instant now) {
    return transfer(kingdom, city, actor, amount, idempotency, true, now);
  }

  @Override
  public TreasuryResult withdraw(
      UUID kingdom, UUID city, UUID actor, long amount, UUID idempotency, Instant now) {
    return transfer(kingdom, city, actor, amount, idempotency, false, now);
  }

  @Override
  public void setTaxRate(UUID kingdom, UUID actor, int basisPoints, Instant now) {
    store.inTransaction(
        connection -> {
          requireRole(connection, kingdom, actor, GOVERNANCE);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO kingdom_tax_policy(kingdom_id,rate_basis_points,updated_by,updated_at,version) VALUES(?,?,?,?,0) ON CONFLICT(kingdom_id) DO UPDATE SET rate_basis_points=excluded.rate_basis_points,updated_by=excluded.updated_by,updated_at=excluded.updated_at,version=kingdom_tax_policy.version+1")) {
            statement.setObject(1, kingdom);
            statement.setInt(2, basisPoints);
            statement.setObject(3, actor);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
          }
          history(connection, kingdom, "TAX_RATE_CHANGED", json("basisPoints", basisPoints), now);
          return null;
        });
  }

  @Override
  public TaxReport collectTaxes(UUID kingdom, LocalDate date, Instant now) {
    return store.inTransaction(
        connection -> {
          int rate = taxRate(connection, kingdom);
          int assessed = 0;
          int paid = 0;
          long collected = 0;
          for (UUID city : memberCities(connection, kingdom)) {
            if (taxExists(connection, kingdom, city, date)) continue;
            assessed++;
            UUID cityAccount = account(connection, "CITY", city);
            UUID kingdomAccount = account(connection, "KINGDOM", kingdom);
            long cityBalance = balance(connection, cityAccount);
            long amount = Math.min(cityBalance, Math.multiplyExact(cityBalance, rate) / 10_000);
            String status = amount > 0 ? "PAID" : "ASSESSED";
            if (amount > 0) {
              move(
                  connection,
                  cityAccount,
                  kingdomAccount,
                  null,
                  amount,
                  UUID.nameUUIDFromBytes(
                      (kingdom + ":tax:" + city + ":" + date).getBytes(StandardCharsets.UTF_8)),
                  "KINGDOM_TAX",
                  now);
              paid++;
              collected += amount;
            }
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO kingdom_tax_assessments(id,kingdom_id,city_id,assessment_date,amount_minor,status,occurred_at) VALUES(?,?,?,?,?,?,?)")) {
              statement.setObject(1, UUID.randomUUID());
              statement.setObject(2, kingdom);
              statement.setObject(3, city);
              statement.setDate(4, Date.valueOf(date));
              statement.setLong(5, amount);
              statement.setString(6, status);
              statement.setTimestamp(7, Timestamp.from(now));
              statement.executeUpdate();
            }
          }
          return new TaxReport(assessed, paid, collected);
        });
  }

  @Override
  public void setPolicy(UUID kingdom, UUID actor, String key, String value, Instant now) {
    store.inTransaction(
        connection -> {
          requireRole(connection, kingdom, actor, GOVERNANCE);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO kingdom_policies(kingdom_id,policy_key,policy_value,updated_by,updated_at,version) VALUES(?,?,?,?,?,0) ON CONFLICT(kingdom_id,policy_key) DO UPDATE SET policy_value=excluded.policy_value,updated_by=excluded.updated_by,updated_at=excluded.updated_at,version=kingdom_policies.version+1")) {
            statement.setObject(1, kingdom);
            statement.setString(2, key);
            statement.setString(3, value);
            statement.setObject(4, actor);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
          }
          history(connection, kingdom, "POLICY_CHANGED", json("key", key, "value", value), now);
          return null;
        });
  }

  @Override
  public Secession requestSecession(UUID kingdom, UUID city, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          requireMemberMayor(connection, kingdom, city, actor);
          if (scalar(connection, "SELECT count(*) FROM kingdom_members WHERE kingdom_id=?", kingdom)
              < 2) throw new DomainException("the last member cannot secede");
          String peaceful = policy(connection, kingdom, "PEACEFUL_SECESSION");
          UUID id = UUID.randomUUID();
          String status = "ALLOW".equalsIgnoreCase(peaceful) ? "COMPLETED" : "CONTESTED";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO kingdom_secessions(id,kingdom_id,city_id,status,requested_by,requested_at,resolved_at) VALUES(?,?,?,?,?,?,?)")) {
            statement.setObject(1, id);
            statement.setObject(2, kingdom);
            statement.setObject(3, city);
            statement.setString(4, status);
            statement.setObject(5, actor);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setTimestamp(7, status.equals("COMPLETED") ? Timestamp.from(now) : null);
            statement.executeUpdate();
          }
          if (status.equals("COMPLETED")) {
            removeMember(connection, kingdom, city);
          } else {
            insertApproval(
                connection,
                UUID.randomUUID(),
                kingdom,
                city,
                "SECESSION",
                actor,
                now.plusSeconds(604_800),
                now);
          }
          history(connection, kingdom, "SECESSION_" + status, json("city", city), now);
          return new Secession(id, kingdom, city, status);
        });
  }

  @Override
  public GovernanceCycle cycle(Instant now) {
    return store.inTransaction(
        connection -> {
          int votes;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE political_votes SET status='REJECTED',result=jsonb_build_object('reason','EXPIRED'),version=version+1 WHERE status='OPEN' AND closes_at<=?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            votes = statement.executeUpdate();
          }
          int treaties;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE treaties SET status='EXPIRED',version=version+1 WHERE status IN ('PROPOSED','ACTIVE') AND expires_at IS NOT NULL AND expires_at<=?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            treaties = statement.executeUpdate();
          }
          return new GovernanceCycle(votes, treaties);
        });
  }

  private TreasuryResult transfer(
      UUID kingdom,
      UUID city,
      UUID actor,
      long amount,
      UUID idempotency,
      boolean deposit,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireMemberMayor(connection, kingdom, city, actor);
          if (!deposit) requireRole(connection, kingdom, actor, GOVERNANCE);
          UUID cityAccount = account(connection, "CITY", city);
          UUID kingdomAccount = account(connection, "KINGDOM", kingdom);
          if (!ledgerExists(connection, childKey(idempotency, "debit"))) {
            move(
                connection,
                deposit ? cityAccount : kingdomAccount,
                deposit ? kingdomAccount : cityAccount,
                actor,
                amount,
                idempotency,
                deposit ? "KINGDOM_DEPOSIT" : "KINGDOM_WITHDRAWAL",
                now);
          }
          return new TreasuryResult(
              balance(connection, kingdomAccount), balance(connection, cityAccount));
        });
  }

  private static void move(
      Connection connection,
      UUID from,
      UUID to,
      UUID actor,
      long amount,
      UUID idempotency,
      String type,
      Instant now)
      throws SQLException {
    long fromBalance = balance(connection, from);
    long toBalance = balance(connection, to);
    if (fromBalance < amount) throw new DomainException("insufficient treasury balance");
    updateBalance(connection, from, fromBalance - amount);
    updateBalance(connection, to, Math.addExact(toBalance, amount));
    ledger(
        connection,
        from,
        actor,
        type + "_DEBIT",
        -amount,
        fromBalance - amount,
        idempotency,
        childKey(idempotency, "debit"),
        now);
    ledger(
        connection,
        to,
        actor,
        type + "_CREDIT",
        amount,
        toBalance + amount,
        idempotency,
        childKey(idempotency, "credit"),
        now);
  }

  private static void closeVote(
      Connection connection, UUID vote, String status, Vote count, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE political_votes SET status=?,result=jsonb_build_object('yes',?,'no',?),version=version+1 WHERE id=?")) {
      statement.setString(1, status);
      statement.setInt(2, count.yes());
      statement.setInt(3, count.no());
      statement.setObject(4, vote);
      statement.executeUpdate();
    }
    history(connection, count.kingdom(), "VOTE_" + status, json("vote", vote), now);
  }

  private static Vote lockVote(Connection connection, UUID vote) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM political_votes WHERE id=? FOR UPDATE")) {
      statement.setObject(1, vote);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("vote not found");
      }
    }
    return vote(connection, vote);
  }

  private static Vote vote(Connection connection, UUID vote) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT v.kingdom_id,v.vote_key,v.status,v.required_yes,count(*) FILTER(WHERE b.choice),count(*) FILTER(WHERE NOT b.choice) FROM political_votes v LEFT JOIN kingdom_vote_ballots b ON b.vote_id=v.id WHERE v.id=? GROUP BY v.kingdom_id,v.vote_key,v.status,v.required_yes")) {
      statement.setObject(1, vote);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("vote not found");
        return new Vote(
            vote,
            result.getObject(1, UUID.class),
            result.getString(2),
            result.getString(3),
            result.getInt(5),
            result.getInt(6),
            result.getInt(4));
      }
    }
  }

  private static Instant voteCloses(Connection connection, UUID vote) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT closes_at FROM political_votes WHERE id=?")) {
      statement.setObject(1, vote);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getTimestamp(1).toInstant();
      }
    }
  }

  private static KingdomReport report(Connection connection, UUID kingdom) throws SQLException {
    UUID account = account(connection, "KINGDOM", kingdom);
    List<String> roles =
        strings(
            connection,
            "SELECT player_id||':'||role FROM kingdom_roles WHERE kingdom_id=? ORDER BY role,player_id",
            kingdom);
    List<String> policies =
        strings(
            connection,
            "SELECT policy_key||'='||policy_value FROM kingdom_policies WHERE kingdom_id=? ORDER BY policy_key",
            kingdom);
    List<String> projects =
        strings(
            connection,
            "SELECT project_key||':'||status||':'||progress||'/'||target FROM mega_projects WHERE kingdom_id=? ORDER BY project_key",
            kingdom);
    return new KingdomReport(
        kingdom,
        balance(connection, account),
        taxRate(connection, kingdom),
        roles,
        policies,
        projects);
  }

  private static List<String> strings(Connection connection, String sql, UUID id)
      throws SQLException {
    List<String> values = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) values.add(result.getString(1));
      }
    }
    return List.copyOf(values);
  }

  private static List<UUID> memberCities(Connection connection, UUID kingdom) throws SQLException {
    List<UUID> values = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT city_id FROM kingdom_members WHERE kingdom_id=? ORDER BY city_id FOR UPDATE")) {
      statement.setObject(1, kingdom);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) values.add(result.getObject(1, UUID.class));
      }
    }
    return values;
  }

  private static boolean activeNonAggression(
      Connection connection, UUID kingdom, UUID target, Instant now) throws SQLException {
    if (target == null) return false;
    UUID first = kingdom.toString().compareTo(target.toString()) < 0 ? kingdom : target;
    UUID second = first.equals(kingdom) ? target : kingdom;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM treaties WHERE first_kingdom=? AND second_kingdom=? AND treaty_type IN ('NON_AGGRESSION','ALLIANCE') AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)")) {
      statement.setObject(1, first);
      statement.setObject(2, second);
      statement.setTimestamp(3, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void insertApproval(
      Connection connection,
      UUID id,
      UUID kingdom,
      UUID target,
      String type,
      UUID actor,
      Instant expires,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO kingdom_war_approvals(id,kingdom_id,target_city_id,approval_type,approved_by,approved_at,expires_at) VALUES(?,?,?,?,?,?,?)")) {
      statement.setObject(1, id);
      statement.setObject(2, kingdom);
      statement.setObject(3, target);
      statement.setString(4, type);
      statement.setObject(5, actor);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.setTimestamp(7, Timestamp.from(expires));
      statement.executeUpdate();
    }
  }

  private static void requireRole(
      Connection connection, UUID kingdom, UUID actor, Set<String> roles) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM kingdom_roles WHERE kingdom_id=? AND player_id=?")) {
      statement.setObject(1, kingdom);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !roles.contains(result.getString(1)))
          throw new DomainException("kingdom role does not allow this action");
      }
    }
  }

  private static void requireMemberMayor(Connection connection, UUID kingdom, UUID city, UUID actor)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM kingdom_members k JOIN city_members m ON m.city_id=k.city_id WHERE k.kingdom_id=? AND k.city_id=? AND m.player_id=? AND m.role IN ('MAYOR','TREASURER')")) {
      statement.setObject(1, kingdom);
      statement.setObject(2, city);
      statement.setObject(3, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next())
          throw new DomainException("member city role does not allow this action");
      }
    }
  }

  private static boolean kingdomPlayer(Connection connection, UUID kingdom, UUID player)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM kingdom_members k JOIN city_members m ON m.city_id=k.city_id WHERE k.kingdom_id=? AND m.player_id=?")) {
      statement.setObject(1, kingdom);
      statement.setObject(2, player);
      try (ResultSet r = statement.executeQuery()) {
        return r.next();
      }
    }
  }

  private static UUID cityKingdom(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT kingdom_id FROM kingdom_members WHERE city_id=?")) {
      statement.setObject(1, city);
      try (ResultSet r = statement.executeQuery()) {
        return r.next() ? r.getObject(1, UUID.class) : null;
      }
    }
  }

  private static void requireCity(Connection connection, UUID city) throws SQLException {
    if (scalar(connection, "SELECT count(*) FROM cities WHERE id=?", city) == 0)
      throw new DomainException("city not found");
  }

  private static int taxRate(Connection c, UUID k) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT rate_basis_points FROM kingdom_tax_policy WHERE kingdom_id=?")) {
      s.setObject(1, k);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? r.getInt(1) : 0;
      }
    }
  }

  private static String policy(Connection c, UUID k, String key) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT policy_value FROM kingdom_policies WHERE kingdom_id=? AND policy_key=?")) {
      s.setObject(1, k);
      s.setString(2, key);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? r.getString(1) : null;
      }
    }
  }

  private static boolean taxExists(Connection c, UUID k, UUID city, LocalDate d)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT 1 FROM kingdom_tax_assessments WHERE kingdom_id=? AND city_id=? AND assessment_date=?")) {
      s.setObject(1, k);
      s.setObject(2, city);
      s.setDate(3, Date.valueOf(d));
      try (ResultSet r = s.executeQuery()) {
        return r.next();
      }
    }
  }

  private static UUID account(Connection c, String type, UUID owner) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id FROM accounts WHERE owner_type=? AND owner_id=? FOR UPDATE")) {
      s.setString(1, type);
      s.setObject(2, owner);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException(type.toLowerCase() + " treasury not found");
        return r.getObject(1, UUID.class);
      }
    }
  }

  private static long balance(Connection c, UUID account) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT balance_minor FROM accounts WHERE id=?")) {
      s.setObject(1, account);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  private static void updateBalance(Connection c, UUID account, long value) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("UPDATE accounts SET balance_minor=?,version=version+1 WHERE id=?")) {
      s.setLong(1, value);
      s.setObject(2, account);
      s.executeUpdate();
    }
  }

  private static boolean ledgerExists(Connection c, UUID key) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT 1 FROM ledger_entries WHERE idempotency_key=?")) {
      s.setObject(1, key);
      try (ResultSet r = s.executeQuery()) {
        return r.next();
      }
    }
  }

  private static void ledger(
      Connection c,
      UUID account,
      UUID actor,
      String type,
      long amount,
      long after,
      UUID reference,
      UUID key,
      Instant now)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
      s.setObject(1, UUID.randomUUID());
      s.setObject(2, account);
      s.setObject(3, actor);
      s.setString(4, type);
      s.setLong(5, amount);
      s.setLong(6, after);
      s.setObject(7, reference);
      s.setObject(8, key);
      s.setTimestamp(9, Timestamp.from(now));
      s.executeUpdate();
    }
  }

  private static UUID childKey(UUID root, String suffix) {
    return UUID.nameUUIDFromBytes((root + ":" + suffix).getBytes(StandardCharsets.UTF_8));
  }

  private static int scalar(Connection c, String sql, UUID id) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      s.setObject(1, id);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        return r.getInt(1);
      }
    }
  }

  private static void removeMember(Connection c, UUID kingdom, UUID city) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("DELETE FROM kingdom_members WHERE kingdom_id=? AND city_id=?")) {
      s.setObject(1, kingdom);
      s.setObject(2, city);
      s.executeUpdate();
    }
  }

  private static String json(Object... values) {
    StringBuilder b = new StringBuilder("{");
    for (int i = 0; i < values.length; i += 2) {
      if (i > 0) b.append(',');
      b.append('"')
          .append(values[i])
          .append("\":\"")
          .append(String.valueOf(values[i + 1]).replace("\\", "\\\\").replace("\"", "\\\""))
          .append('"');
    }
    return b.append('}').toString();
  }

  private static void history(Connection c, UUID kingdom, String event, String payload, Instant now)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "INSERT INTO kingdom_history(id,kingdom_id,event_type,payload,occurred_at) VALUES(?,?,?,?::jsonb,?)")) {
      s.setObject(1, UUID.randomUUID());
      s.setObject(2, kingdom);
      s.setString(3, event);
      s.setString(4, payload);
      s.setTimestamp(5, Timestamp.from(now));
      s.executeUpdate();
    }
  }
}
