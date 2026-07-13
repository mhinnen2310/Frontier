package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.city.GovernmentRole;
import nl.frontier.city.SettlementLifecycleGateway;
import nl.frontier.domain.DomainException;

public final class PostgresSettlementLifecycleGateway implements SettlementLifecycleGateway {
  private final TransactionalStore store;

  public PostgresSettlementLifecycleGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public void validateCore(CoreLocation core) {
    store.inTransaction(
        connection -> {
          requireCoreLocation(connection, core, 128, 256);
          return null;
        });
  }

  @Override
  public FoundingExpedition createExpedition(
      UUID leader, String name, String charter, Instant now, Instant expiresAt) {
    return store.inTransaction(
        connection -> {
          if (scalar(connection, "SELECT count(*) FROM city_members WHERE player_id=?", leader) > 0)
            throw new DomainException("you already belong to a settlement");
          if (scalar(
                  connection,
                  "SELECT count(*) FROM settlement_founding_expedition_members WHERE player_id=? AND status='ACCEPTED'",
                  leader)
              > 0) throw new DomainException("you already joined an active founding expedition");
          if (scalar(
                  connection,
                  "SELECT count(*) FROM settlement_founding_expeditions WHERE leader_id=? AND status NOT IN ('CANCELLED','EXPIRED','COMPLETED','REVIEW_REQUIRED')",
                  leader)
              > 0) throw new DomainException("you already lead an active founding expedition");
          UUID expedition = UUID.randomUUID();
          UUID city = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO settlement_founding_expeditions(id,city_id,leader_id,settlement_name,charter_text,status,created_at,updated_at,expires_at) VALUES(?,?,?,?,?,'RECRUITING',?,?,?)",
              expedition,
              city,
              leader,
              name,
              charter,
              now,
              now,
              expiresAt);
          update(
              connection,
              "INSERT INTO settlement_founding_expedition_members(expedition_id,player_id,status,invited_by,invited_at,accepted_at) VALUES(?,?,'ACCEPTED',?,?,?)",
              expedition,
              leader,
              leader,
              now,
              now);
          expeditionHistory(connection, expedition, "EXPEDITION_CREATED", leader, "{}", now);
          return expedition(connection, expedition, false);
        });
  }

  @Override
  public FoundingExpedition inviteFounder(
      UUID expedition, UUID actor, UUID player, Instant now, Instant expiresAt) {
    return store.inTransaction(
        connection -> {
          FoundingExpedition current = expedition(connection, expedition, true);
          requireExpeditionLeader(current, actor);
          requireExpeditionOpen(current, now, "RECRUITING", "LOCATION_SELECTED");
          if (scalar(connection, "SELECT count(*) FROM city_members WHERE player_id=?", player) > 0)
            throw new DomainException("founder already belongs to a settlement");
          update(
              connection,
              "INSERT INTO settlement_founding_expedition_members(expedition_id,player_id,status,invited_by,invited_at) VALUES(?,?,'INVITED',?,?) ON CONFLICT(expedition_id,player_id) DO UPDATE SET status=CASE WHEN settlement_founding_expedition_members.status='ACCEPTED' THEN 'ACCEPTED' ELSE 'INVITED' END,invited_by=excluded.invited_by,invited_at=excluded.invited_at",
              expedition,
              player,
              actor,
              now);
          update(
              connection,
              "UPDATE settlement_founding_expeditions SET expires_at=greatest(expires_at,?),updated_at=?,version=version+1 WHERE id=?",
              expiresAt,
              now,
              expedition);
          expeditionHistory(
              connection,
              expedition,
              "FOUNDER_INVITED",
              actor,
              "{\"player\":\"" + player + "\"}",
              now);
          return expedition(connection, expedition, false);
        });
  }

  @Override
  public FoundingExpedition acceptFounder(UUID expedition, UUID player, Instant now) {
    return store.inTransaction(
        connection -> {
          FoundingExpedition current = expedition(connection, expedition, true);
          requireExpeditionOpen(current, now, "RECRUITING", "LOCATION_SELECTED");
          if (scalar(connection, "SELECT count(*) FROM city_members WHERE player_id=?", player) > 0)
            throw new DomainException("you already belong to a settlement");
          if (scalar(
                  connection,
                  "SELECT count(*) FROM settlement_founding_expedition_members WHERE player_id=? AND status='ACCEPTED' AND expedition_id<>?",
                  player,
                  expedition)
              > 0) throw new DomainException("you already confirmed another founding expedition");
          int changed =
              updateCount(
                  connection,
                  "UPDATE settlement_founding_expedition_members SET status='ACCEPTED',accepted_at=? WHERE expedition_id=? AND player_id=? AND status='INVITED'",
                  now,
                  expedition,
                  player);
          if (changed != 1) throw new DomainException("founding invitation is not active");
          expeditionHistory(connection, expedition, "FOUNDER_ACCEPTED", player, "{}", now);
          return expedition(connection, expedition, false);
        });
  }

  @Override
  public FoundingExpedition selectCore(
      UUID expedition,
      UUID actor,
      CoreLocation core,
      int minimumDistance,
      int harborExclusionRadius,
      Instant now) {
    return store.inTransaction(
        connection -> {
          FoundingExpedition current = expedition(connection, expedition, true);
          requireExpeditionLeader(current, actor);
          requireExpeditionOpen(current, now, "RECRUITING", "LOCATION_SELECTED");
          requireCoreLocation(connection, core, minimumDistance, harborExclusionRadius, expedition);
          update(
              connection,
              "UPDATE settlement_founding_expeditions SET world_id=?,x=?,y=?,z=?,minimum_core_distance=?,harbor_exclusion_radius=?,status='LOCATION_SELECTED',updated_at=?,version=version+1 WHERE id=?",
              core.world(),
              core.x(),
              core.y(),
              core.z(),
              minimumDistance,
              harborExclusionRadius,
              now,
              expedition);
          expeditionHistory(connection, expedition, "CORE_SELECTED", actor, "{}", now);
          return expedition(connection, expedition, false);
        });
  }

  @Override
  public FoundingReservation reserveExpedition(
      UUID expedition,
      UUID actor,
      long feeMinor,
      int minimumFounders,
      Instant now,
      Instant expiresAt) {
    return store.inTransaction(
        connection -> {
          FoundingExpedition current = expedition(connection, expedition, true);
          requireExpeditionLeader(current, actor);
          if (List.of("FEE_RESERVED", "MATERIALS_CLAIMED", "MATERIALS_RESERVED", "CORE_PLACED")
              .contains(current.status())) return reservationForExpedition(connection, expedition);
          requireExpeditionOpen(current, now, "LOCATION_SELECTED");
          if (current.acceptedFounders() < minimumFounders)
            throw new DomainException(
                "founding requires " + minimumFounders + " confirmed founder(s)");
          if (current.core() == null) throw new DomainException("founding location is missing");
          int minimumDistance =
              (int)
                  scalar(
                      connection,
                      "SELECT minimum_core_distance FROM settlement_founding_expeditions WHERE id=?",
                      expedition);
          int harborExclusionRadius =
              (int)
                  scalar(
                      connection,
                      "SELECT harbor_exclusion_radius FROM settlement_founding_expeditions WHERE id=?",
                      expedition);
          requireCoreLocation(
              connection, current.core(), minimumDistance, harborExclusionRadius, expedition);
          if (scalar(
                  connection,
                  "SELECT count(*) FROM settlement_founding_expedition_members m JOIN city_members c ON c.player_id=m.player_id WHERE m.expedition_id=? AND m.status='ACCEPTED'",
                  expedition)
              > 0) throw new DomainException("an accepted founder already joined a settlement");
          UUID account = account(connection, actor);
          long balance = balance(connection, account);
          if (balance < feeMinor)
            throw new DomainException("founding fee requires " + feeMinor + " cents");
          UUID reservation = UUID.randomUUID();
          update(
              connection,
              "UPDATE accounts SET balance_minor=balance_minor-?,version=version+1 WHERE id=?",
              feeMinor,
              account);
          update(
              connection,
              "INSERT INTO settlement_founding_reservations(id,player_id,fee_minor,status,created_at,expires_at,expedition_id) VALUES(?,?,?,'FEE_RESERVED',?,?,?)",
              reservation,
              actor,
              feeMinor,
              now,
              expiresAt,
              expedition);
          update(
              connection,
              "UPDATE settlement_founding_expeditions SET status='FEE_RESERVED',minimum_founders=?,updated_at=?,version=version+1 WHERE id=?",
              minimumFounders,
              now,
              expedition);
          ledger(
              connection,
              account,
              actor,
              -feeMinor,
              balance - feeMinor,
              reservation,
              "FOUNDING_FEE",
              now);
          expeditionHistory(connection, expedition, "FEE_RESERVED", actor, "{}", now);
          return new FoundingReservation(reservation, actor, feeMinor, "FEE_RESERVED", expiresAt);
        });
  }

  @Override
  public boolean claimMaterials(UUID expedition, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          FoundingExpedition current = expedition(connection, expedition, true);
          requireExpeditionLeader(current, actor);
          if (!current.status().equals("FEE_RESERVED")) return false;
          FoundingReservation reservation = reservationForExpedition(connection, expedition);
          if (reservation.expiresAt().isBefore(now))
            throw new DomainException("founding reservation expired");
          update(
              connection,
              "UPDATE settlement_founding_expeditions SET status='MATERIALS_CLAIMED',updated_at=?,version=version+1 WHERE id=?",
              now,
              expedition);
          update(
              connection,
              "UPDATE settlement_founding_reservations SET status='MATERIALS_CLAIMED',version=version+1 WHERE expedition_id=?",
              expedition);
          expeditionHistory(connection, expedition, "MATERIALS_CLAIMED", actor, "{}", now);
          return true;
        });
  }

  @Override
  public void confirmMaterials(UUID expedition, UUID actor, Instant now) {
    transitionExpedition(
        expedition, actor, "MATERIALS_CLAIMED", "MATERIALS_RESERVED", "MATERIALS_RESERVED", now);
  }

  @Override
  public void releaseMaterials(UUID expedition, UUID actor, Instant now) {
    store.inTransaction(
        connection -> {
          FoundingExpedition current = expedition(connection, expedition, true);
          requireExpeditionLeader(current, actor);
          if (!List.of("MATERIALS_CLAIMED", "MATERIALS_RESERVED").contains(current.status()))
            throw new DomainException("founding materials are not reserved");
          refundReservation(
              connection, reservationForExpedition(connection, expedition).id(), actor, now);
          update(
              connection,
              "UPDATE settlement_founding_expeditions SET status='LOCATION_SELECTED',updated_at=?,version=version+1 WHERE id=?",
              now,
              expedition);
          expeditionHistory(connection, expedition, "MATERIALS_RELEASED", actor, "{}", now);
          return null;
        });
  }

  @Override
  public void confirmCorePlacement(UUID expedition, UUID actor, Instant now) {
    transitionExpedition(
        expedition, actor, "MATERIALS_RESERVED", "CORE_PLACED", "CORE_PLACED", now);
  }

  @Override
  public void completeExpedition(UUID expedition, UUID city, UUID actor, Instant now) {
    store.inTransaction(
        connection -> {
          FoundingExpedition current = expedition(connection, expedition, true);
          requireExpeditionLeader(current, actor);
          if (!current.city().equals(city))
            throw new DomainException("expedition city identity mismatch");
          if (current.status().equals("COMPLETED")) return null;
          if (!current.status().equals("CORE_PLACED"))
            throw new DomainException("settlement core placement is not confirmed");
          int minimumFounders =
              (int)
                  scalar(
                      connection,
                      "SELECT minimum_founders FROM settlement_founding_expeditions WHERE id=?",
                      expedition);
          List<UUID> founders =
              ids(
                  connection,
                  "SELECT player_id FROM settlement_founding_expedition_members WHERE expedition_id=? AND status='ACCEPTED' ORDER BY CASE WHEN player_id=? THEN 0 ELSE 1 END,accepted_at,player_id",
                  expedition,
                  actor);
          if (founders.size() < minimumFounders)
            throw new DomainException("minimum founders have not accepted");
          if (scalar(
                  connection,
                  "SELECT count(*) FROM city_members c JOIN settlement_founding_expedition_members m ON m.player_id=c.player_id WHERE m.expedition_id=? AND m.status='ACCEPTED'",
                  expedition)
              > 0) throw new DomainException("an accepted founder already joined a settlement");
          CoreLocation core = current.core();
          if (core == null) throw new DomainException("expedition core is missing");
          SettlementBootstrapOperations.create(
              connection,
              city,
              actor,
              current.name(),
              core.world(),
              Math.floorDiv(core.x(), 16),
              Math.floorDiv(core.z(), 16),
              now);
          update(
              connection,
              "INSERT INTO settlement_cores(city_id,world_id,x,y,z,status,placed_at) VALUES(?,?,?,?,?,'ACTIVE',?) ON CONFLICT(city_id) DO NOTHING",
              city,
              core.world(),
              core.x(),
              core.y(),
              core.z(),
              now);
          FoundingReservation reservation = reservationForExpedition(connection, expedition);
          update(
              connection,
              "INSERT INTO settlement_charters(city_id,charter_text,founding_fee_minor,minimum_founders,ratified_at) VALUES(?,?,?,?,?) ON CONFLICT(city_id) DO NOTHING",
              city,
              current.charter(),
              reservation.feeMinor(),
              minimumFounders,
              now);
          int order = 1;
          for (UUID founder : founders) {
            update(
                connection,
                "INSERT INTO settlement_founders(city_id,player_id,founder_order,accepted_at) VALUES(?,?,?,?) ON CONFLICT(city_id,player_id) DO NOTHING",
                city,
                founder,
                order++,
                now);
            update(
                connection,
                "INSERT INTO city_members(city_id,player_id,role,joined_at) VALUES(?,?,?,?) ON CONFLICT(player_id) DO NOTHING",
                city,
                founder,
                founder.equals(actor) ? "MAYOR" : "CITIZEN",
                now);
            update(
                connection,
                "INSERT INTO settlement_member_activity(city_id,player_id,last_active_at) VALUES(?,?,?) ON CONFLICT(city_id,player_id) DO UPDATE SET last_active_at=excluded.last_active_at",
                city,
                founder,
                now);
          }
          update(
              connection,
              "UPDATE cities SET population=greatest(population,?),last_active_at=?,version=version+1 WHERE id=?",
              founders.size(),
              now,
              city);
          update(
              connection,
              "UPDATE settlement_founding_reservations SET status='COMPLETED',city_id=?,version=version+1 WHERE expedition_id=?",
              city,
              expedition);
          update(
              connection,
              "UPDATE settlement_founding_expeditions SET status='COMPLETED',updated_at=?,version=version+1 WHERE id=?",
              now,
              expedition);
          update(
              connection,
              "UPDATE settlement_founding_expedition_members SET status=CASE WHEN status='ACCEPTED' THEN 'FOUNDED' ELSE 'EXPIRED' END WHERE expedition_id=?",
              expedition);
          history(
              connection,
              city,
              "FOUNDED",
              actor,
              "{\"expedition\":\"" + expedition + "\",\"founders\":" + founders.size() + "}",
              now);
          expeditionHistory(connection, expedition, "FOUNDING_COMPLETED", actor, "{}", now);
          return null;
        });
  }

  @Override
  public FoundingExpedition cancelExpedition(UUID expedition, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          FoundingExpedition current = expedition(connection, expedition, true);
          requireExpeditionLeader(current, actor);
          if (List.of("MATERIALS_CLAIMED", "MATERIALS_RESERVED", "CORE_PLACED", "COMPLETED")
              .contains(current.status()))
            throw new DomainException("founding can no longer be cancelled after material claim");
          Optional<UUID> reservation = expeditionReservationId(connection, expedition);
          if (reservation.isPresent()) refundReservation(connection, reservation.get(), actor, now);
          update(
              connection,
              "UPDATE settlement_founding_expeditions SET status='CANCELLED',updated_at=?,version=version+1 WHERE id=?",
              now,
              expedition);
          update(
              connection,
              "UPDATE settlement_founding_expedition_members SET status='CANCELLED' WHERE expedition_id=?",
              expedition);
          expeditionHistory(connection, expedition, "EXPEDITION_CANCELLED", actor, "{}", now);
          return expedition(connection, expedition, false);
        });
  }

  @Override
  public Optional<FoundingExpedition> activeExpedition(UUID player, Instant now) {
    return store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT e.id FROM settlement_founding_expeditions e LEFT JOIN settlement_founding_expedition_members m ON m.expedition_id=e.id AND m.player_id=? WHERE (e.leader_id=? OR m.player_id IS NOT NULL) AND e.status NOT IN ('CANCELLED','EXPIRED','COMPLETED','REVIEW_REQUIRED') AND (e.expires_at>? OR e.status IN ('MATERIALS_CLAIMED','MATERIALS_RESERVED','CORE_PLACED')) ORDER BY CASE WHEN e.leader_id=? THEN 0 ELSE 1 END,e.created_at LIMIT 1")) {
            statement.setObject(1, player);
            statement.setObject(2, player);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setObject(4, player);
            try (ResultSet result = statement.executeQuery()) {
              return result.next()
                  ? Optional.of(expedition(connection, result.getObject(1, UUID.class), false))
                  : Optional.empty();
            }
          }
        });
  }

  @Override
  public List<FoundingExpedition> pendingExpeditions(int limit) {
    return store.inTransaction(
        connection -> {
          List<FoundingExpedition> pending = new ArrayList<>();
          for (UUID id :
              ids(
                  connection,
                  "SELECT id FROM settlement_founding_expeditions WHERE status IN ('MATERIALS_RESERVED','CORE_PLACED') ORDER BY updated_at LIMIT ?",
                  limit)) pending.add(expedition(connection, id, false));
          return List.copyOf(pending);
        });
  }

  @Override
  public FoundingReservation reserveFounding(
      UUID player, long feeMinor, Instant now, Instant expiresAt) {
    return store.inTransaction(
        connection -> {
          if (scalar(connection, "SELECT count(*) FROM city_members WHERE player_id=?", player) > 0)
            throw new DomainException("you already belong to a settlement");
          UUID account = account(connection, player);
          long balance = balance(connection, account);
          if (balance < feeMinor) throw new DomainException("founding fee requires 2500 cents");
          UUID reservation = UUID.randomUUID();
          update(
              connection,
              "UPDATE accounts SET balance_minor=balance_minor-?,version=version+1 WHERE id=?",
              feeMinor,
              account);
          update(
              connection,
              "INSERT INTO settlement_founding_reservations(id,player_id,fee_minor,status,created_at,expires_at) VALUES(?,?,?,'RESERVED',?,?)",
              reservation,
              player,
              feeMinor,
              now,
              expiresAt);
          ledger(
              connection,
              account,
              player,
              -feeMinor,
              balance - feeMinor,
              reservation,
              "FOUNDING_FEE",
              now);
          return new FoundingReservation(reservation, player, feeMinor, "RESERVED", expiresAt);
        });
  }

  @Override
  public void completeFounding(
      UUID reservation,
      UUID city,
      UUID founder,
      CoreLocation core,
      String charter,
      int minimumFounders,
      Instant now) {
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT player_id,status,expires_at,fee_minor FROM settlement_founding_reservations WHERE id=? FOR UPDATE")) {
            statement.setObject(1, reservation);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("founding reservation not found");
              if (!founder.equals(result.getObject(1, UUID.class)))
                throw new DomainException("founding reservation belongs to another player");
              if (!result.getString(2).equals("RESERVED")) return null;
              if (result.getTimestamp(3).toInstant().isBefore(now))
                throw new DomainException("founding reservation expired");
            }
          }
          requireMayor(connection, city, founder);
          requireCoreDistance(connection, core);
          update(
              connection,
              "INSERT INTO settlement_cores(city_id,world_id,x,y,z,status,placed_at) VALUES(?,?,?,?,?,'ACTIVE',?)",
              city,
              core.world(),
              core.x(),
              core.y(),
              core.z(),
              now);
          update(
              connection,
              "INSERT INTO settlement_charters(city_id,charter_text,founding_fee_minor,minimum_founders,ratified_at) SELECT ?,?,fee_minor,?,? FROM settlement_founding_reservations WHERE id=?",
              city,
              charter,
              minimumFounders,
              now,
              reservation);
          update(
              connection,
              "INSERT INTO settlement_founders(city_id,player_id,founder_order,accepted_at) VALUES(?,?,1,?)",
              city,
              founder,
              now);
          if (scalar(connection, "SELECT count(*) FROM settlement_founders WHERE city_id=?", city)
              < minimumFounders) throw new DomainException("minimum founders have not accepted");
          update(
              connection,
              "INSERT INTO settlement_member_activity(city_id,player_id,last_active_at) VALUES(?,?,?) ON CONFLICT(city_id,player_id) DO UPDATE SET last_active_at=excluded.last_active_at",
              city,
              founder,
              now);
          update(
              connection,
              "UPDATE settlement_founding_reservations SET status='COMPLETED',city_id=?,version=version+1 WHERE id=?",
              city,
              reservation);
          update(
              connection,
              "UPDATE cities SET lifecycle_status='ACTIVE',last_active_at=?,version=version+1 WHERE id=?",
              now,
              city);
          history(
              connection,
              city,
              "FOUNDED",
              founder,
              "{\"core\":\"" + core.x() + "," + core.y() + "," + core.z() + "\"}",
              now);
          return null;
        });
  }

  @Override
  public void cancelFounding(UUID reservation, UUID player, Instant now) {
    store.inTransaction(
        connection -> {
          refundReservation(connection, reservation, player, now);
          return null;
        });
  }

  @Override
  public void touch(UUID player, Instant now) {
    store.inTransaction(
        connection -> {
          update(
              connection,
              "INSERT INTO settlement_member_activity(city_id,player_id,last_active_at) SELECT city_id,player_id,? FROM city_members WHERE player_id=? ON CONFLICT(city_id,player_id) DO UPDATE SET last_active_at=excluded.last_active_at",
              now,
              player);
          update(
              connection,
              "UPDATE cities SET last_active_at=? FROM city_members m WHERE m.city_id=cities.id AND m.player_id=?",
              now,
              player);
          return null;
        });
  }

  @Override
  public LifecycleSnapshot transfer(UUID city, UUID actor, UUID successor, Instant now) {
    return store.inTransaction(
        connection -> {
          requireActiveLifecycle(connection, city);
          return transfer(connection, city, actor, successor, "OWNERSHIP_TRANSFERRED", now);
        });
  }

  @Override
  public LifecycleSnapshot succeed(
      UUID city,
      UUID actor,
      Instant mayorInactiveBefore,
      Set<GovernmentRole> allowedSuccessorRoles,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireActiveLifecycle(connection, city);
          UUID owner = owner(connection, city, true);
          requireMember(connection, city, actor);
          String role =
              text(
                  connection,
                  "SELECT role FROM city_members WHERE city_id=? AND player_id=?",
                  city,
                  actor);
          if (allowedSuccessorRoles.stream().noneMatch(value -> value.name().equals(role)))
            throw new DomainException("only an active settlement officer can succeed the mayor");
          Instant active = memberActivity(connection, city, owner);
          if (active.isAfter(mayorInactiveBefore))
            throw new DomainException("mayor inactivity period has not elapsed");
          return transfer(connection, city, owner, actor, "MAYOR_SUCCEEDED", now);
        });
  }

  @Override
  public LifecycleSnapshot abandon(UUID city, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          requireActiveLifecycle(connection, city);
          return ruin(connection, city, actor, "ABANDONED", now);
        });
  }

  @Override
  public DisbandRequest requestDisband(
      UUID city, UUID actor, Instant now, Instant confirmsAfter, Instant expiresAt) {
    return store.inTransaction(
        connection -> {
          requireMayor(connection, city, actor);
          LifecycleSnapshot current = snapshot(connection, city);
          if (!current.status().equals("ACTIVE"))
            throw new DomainException("settlement is not active");
          update(
              connection,
              "UPDATE settlement_disband_requests SET status='EXPIRED',version=version+1 WHERE city_id=? AND status='REQUESTED' AND expires_at<?",
              city,
              now);
          if (scalar(
                  connection,
                  "SELECT count(*) FROM settlement_disband_requests WHERE city_id=? AND status='REQUESTED'",
                  city)
              > 0) throw new DomainException("a disband request is already pending");
          UUID request = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO settlement_disband_requests(id,city_id,requested_by,status,requested_at,confirms_after,expires_at) VALUES(?,?,?,'REQUESTED',?,?,?)",
              request,
              city,
              actor,
              now,
              confirmsAfter,
              expiresAt);
          history(
              connection,
              city,
              "DISBAND_REQUESTED",
              actor,
              "{\"request\":\"" + request + "\"}",
              now);
          return new DisbandRequest(request, city, actor, "REQUESTED", confirmsAfter, expiresAt);
        });
  }

  @Override
  public LifecycleSnapshot confirmDisband(UUID request, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          UUID city = disbandRequestCity(connection, request);
          if (!owner(connection, city, true).equals(actor))
            throw new DomainException("only the mayor can change settlement lifecycle");
          UUID requestedBy;
          String status;
          Instant confirmsAfter;
          Instant expiresAt;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT city_id,requested_by,status,confirms_after,expires_at FROM settlement_disband_requests WHERE id=? FOR UPDATE")) {
            statement.setObject(1, request);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("disband request not found");
              if (!city.equals(result.getObject(1, UUID.class)))
                throw new DomainException("disband request settlement changed unexpectedly");
              requestedBy = result.getObject(2, UUID.class);
              status = result.getString(3);
              confirmsAfter = result.getTimestamp(4).toInstant();
              expiresAt = result.getTimestamp(5).toInstant();
            }
          }
          if (!requestedBy.equals(actor))
            throw new DomainException("only the requesting mayor can confirm disbanding");
          if (!status.equals("REQUESTED"))
            throw new DomainException("disband request is no longer pending");
          if (now.isBefore(confirmsAfter))
            throw new DomainException("disband confirmation cooldown has not elapsed");
          if (now.isAfter(expiresAt)) {
            update(
                connection,
                "UPDATE settlement_disband_requests SET status='EXPIRED',version=version+1 WHERE id=?",
                request);
            throw new DomainException("disband request expired");
          }
          if (scalar(
                  connection,
                  "SELECT count(*) FROM campaigns WHERE (attacker_city_id=? OR defender_city_id=?) AND phase<>'ENDED'",
                  city,
                  city)
              > 0) throw new DomainException("cannot disband during an active campaign");
          update(
              connection,
              "UPDATE settlement_disband_requests SET status='CONFIRMED',confirmed_at=?,version=version+1 WHERE id=?",
              now,
              request);
          return ruin(connection, city, actor, "DISBANDED", now);
        });
  }

  @Override
  public LifecycleSnapshot recoverRuins(UUID city, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          requireMayor(connection, city, actor);
          LifecycleSnapshot before = snapshot(connection, city);
          if (!before.status().equals("RUINS"))
            throw new DomainException("settlement is not ruins");
          if (before.ruinsUntil() == null || before.ruinsUntil().isBefore(now))
            throw new DomainException("ruins recovery window expired");
          update(
              connection,
              "UPDATE city_claims c SET city_id=r.city_id,state=r.previous_state,influence=CASE WHEN r.previous_state='CAPITAL' THEN 100 ELSE 25 END,version=c.version+1 FROM settlement_ruin_claims r WHERE r.city_id=? AND c.world_id=r.world_id AND c.chunk_x=r.chunk_x AND c.chunk_z=r.chunk_z AND c.city_id IS NULL AND c.state='WILDERNESS'",
              city);
          if (scalar(
                  connection,
                  "SELECT count(*) FROM city_claims WHERE city_id=? AND state='CAPITAL'",
                  city)
              == 0) throw new DomainException("settlement capital claim is no longer recoverable");
          update(
              connection,
              "UPDATE cities SET lifecycle_status='ACTIVE',abandoned_at=NULL,ruins_until=NULL,last_active_at=?,version=version+1 WHERE id=?",
              now,
              city);
          update(
              connection,
              "UPDATE settlement_cores SET status='ACTIVE',version=version+1 WHERE city_id=?",
              city);
          update(
              connection,
              "UPDATE warehouses SET status='ACTIVE',version=version+1 WHERE city_id=? AND status='RUINS'",
              city);
          update(
              connection,
              "UPDATE market_orders o SET status=r.previous_status,version=o.version+1 FROM settlement_ruin_market_orders r WHERE r.city_id=? AND r.order_id=o.id AND o.status='FROZEN_RUINS'",
              city);
          update(connection, "DELETE FROM settlement_ruin_market_orders WHERE city_id=?", city);
          update(
              connection,
              "INSERT INTO dirty_settlements(city_id,reason,enqueued_at) VALUES(?,'RUINS_RECOVERED',?) ON CONFLICT(city_id) DO UPDATE SET reason=excluded.reason,enqueued_at=excluded.enqueued_at",
              city,
              now);
          update(
              connection,
              "INSERT INTO city_simulation_state(city_id,next_cycle_at) VALUES(?,?) ON CONFLICT(city_id) DO NOTHING",
              city,
              now);
          update(
              connection,
              "INSERT INTO city_world_simulation_state(city_id,region_key,next_cycle_at) SELECT c.city_id,c.world_id::text||':'||floor(c.chunk_x/32.0)::int||':'||floor(c.chunk_z/32.0)::int,? FROM city_claims c WHERE c.city_id=? AND c.state='CAPITAL' LIMIT 1 ON CONFLICT(city_id) DO NOTHING",
              now,
              city);
          history(connection, city, "RUINS_RECOVERED", actor, "{}", now);
          return snapshot(connection, city);
        });
  }

  @Override
  public MergeProposal proposeMerge(
      UUID source, UUID actor, UUID target, Instant now, Instant expiresAt) {
    return store.inTransaction(
        connection -> {
          requireMayor(connection, source, actor);
          requireActiveLifecycle(connection, source);
          requireActiveLifecycle(connection, target);
          owner(connection, target, false);
          UUID proposal = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO settlement_merge_proposals(id,source_city,target_city,proposed_by,status,created_at,expires_at) VALUES(?,?,?,?,'PROPOSED',?,?)",
              proposal,
              source,
              target,
              actor,
              now,
              expiresAt);
          history(
              connection, source, "MERGE_PROPOSED", actor, "{\"target\":\"" + target + "\"}", now);
          return new MergeProposal(proposal, source, target, "PROPOSED", expiresAt);
        });
  }

  @Override
  public LifecycleSnapshot acceptMerge(UUID proposal, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          UUID source;
          UUID target;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT source_city,target_city,status,expires_at FROM settlement_merge_proposals WHERE id=? FOR UPDATE")) {
            statement.setObject(1, proposal);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("merge proposal not found");
              source = result.getObject(1, UUID.class);
              target = result.getObject(2, UUID.class);
              if (!result.getString(3).equals("PROPOSED")
                  || result.getTimestamp(4).toInstant().isBefore(now))
                throw new DomainException("merge proposal is no longer active");
            }
          }
          lockCities(connection, source, target);
          requireActiveLifecycle(connection, source);
          requireActiveLifecycle(connection, target);
          requireMayor(connection, target, actor);
          if (scalar(
                  connection,
                  "SELECT count(*) FROM campaigns WHERE (attacker_city_id IN (?,?) OR defender_city_id IN (?,?)) AND phase<>'ENDED'",
                  source,
                  target,
                  source,
                  target)
              > 0) throw new DomainException("cannot merge during an active campaign");
          if (scalar(
                  connection,
                  "SELECT count(*) FROM kingdom_members WHERE city_id IN (?,?)",
                  source,
                  target)
              > 0) throw new DomainException("leave kingdoms before merging settlements");
          List<UUID> sourceMembers =
              ids(
                  connection,
                  "SELECT player_id FROM city_members WHERE city_id=? ORDER BY joined_at",
                  source);
          UUID sourceAccount = cityAccount(connection, source);
          UUID targetAccount = cityAccount(connection, target);
          long sourceBalance = balance(connection, sourceAccount);
          long targetBalance = balance(connection, targetAccount);
          update(
              connection,
              "UPDATE accounts SET balance_minor=0,version=version+1 WHERE id=?",
              sourceAccount);
          update(
              connection,
              "UPDATE accounts SET balance_minor=?,version=version+1 WHERE id=?",
              Math.addExact(sourceBalance, targetBalance),
              targetAccount);
          update(
              connection,
              "UPDATE city_claims SET city_id=?,state=CASE WHEN state='CAPITAL' THEN 'INFLUENCED' ELSE state END WHERE city_id=?",
              target,
              source);
          update(
              connection,
              "UPDATE city_buildings SET city_id=?,district_id=NULL WHERE city_id=?",
              target,
              source);
          update(connection, "UPDATE workers SET city_id=? WHERE city_id=?", target, source);
          UUID sourceWarehouse = warehouse(connection, source);
          UUID targetWarehouse = warehouse(connection, target);
          update(
              connection,
              "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity,version) SELECT ?,commodity_key,available_quantity,0,0 FROM warehouse_stock WHERE warehouse_id=? ON CONFLICT(warehouse_id,commodity_key) DO UPDATE SET available_quantity=warehouse_stock.available_quantity+excluded.available_quantity,version=warehouse_stock.version+1",
              targetWarehouse,
              sourceWarehouse);
          update(
              connection,
              "UPDATE warehouse_stock SET available_quantity=0,version=version+1 WHERE warehouse_id=?",
              sourceWarehouse);
          update(
              connection,
              "UPDATE warehouses SET capacity=capacity+(SELECT capacity FROM warehouses WHERE id=?),version=version+1 WHERE id=?",
              sourceWarehouse,
              targetWarehouse);
          update(
              connection,
              "UPDATE warehouses SET status='MERGED',version=version+1 WHERE id=?",
              sourceWarehouse);
          update(
              connection,
              "UPDATE market_orders SET settlement_id=?,owner_id=?,version=version+1 WHERE settlement_id=? AND status IN ('OPEN','PARTIAL')",
              target,
              target,
              source);
          update(connection, "UPDATE road_nodes SET city_id=? WHERE city_id=?", target, source);
          update(connection, "UPDATE districts SET city_id=? WHERE city_id=?", target, source);
          update(connection, "DELETE FROM city_members WHERE city_id=?", source);
          for (UUID member : sourceMembers)
            update(
                connection,
                "INSERT INTO city_members(city_id,player_id,role,joined_at) VALUES(?,?,'CITIZEN',?) ON CONFLICT(player_id) DO NOTHING",
                target,
                member,
                now);
          update(
              connection,
              "UPDATE cities SET lifecycle_status='MERGED',abandoned_at=?,ruins_until=?,version=version+1 WHERE id=?",
              now,
              now.plusSeconds(30L * 86_400L),
              source);
          update(
              connection,
              "UPDATE settlement_cores SET status='MERGED',version=version+1 WHERE city_id=?",
              source);
          update(
              connection,
              "UPDATE city_invitations SET status='REVOKED',version=version+1 WHERE city_id=? AND status='PENDING'",
              source);
          update(
              connection,
              "UPDATE settlement_disband_requests SET status='CANCELLED',version=version+1 WHERE city_id IN (?,?) AND status='REQUESTED'",
              source,
              target);
          update(connection, "DELETE FROM dirty_settlements WHERE city_id=?", source);
          update(connection, "DELETE FROM city_simulation_state WHERE city_id=?", source);
          update(connection, "DELETE FROM city_world_simulation_state WHERE city_id=?", source);
          update(
              connection,
              "UPDATE settlement_merge_proposals SET status='ACCEPTED',accepted_by=?,version=version+1 WHERE id=?",
              actor,
              proposal);
          history(connection, source, "MERGED", actor, "{\"target\":\"" + target + "\"}", now);
          history(
              connection, target, "MERGE_ACCEPTED", actor, "{\"source\":\"" + source + "\"}", now);
          return snapshot(connection, target);
        });
  }

  @Override
  public RecoveryReport recoverInactive(
      Instant settlementInactiveBefore, Instant mayorInactiveBefore, Instant now, int limit) {
    return store.inTransaction(
        connection -> {
          int refunded = 0, successions = 0, abandoned = 0;
          List<UUID> expiredExpeditions =
              ids(
                  connection,
                  "SELECT id FROM settlement_founding_expeditions WHERE status IN ('RECRUITING','LOCATION_SELECTED','FEE_RESERVED') AND expires_at<? ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
                  now,
                  limit);
          for (UUID expedition : expiredExpeditions) {
            Optional<UUID> reservation = expeditionReservationId(connection, expedition);
            if (reservation.isPresent()) {
              refundReservation(connection, reservation.get(), null, now);
              refunded++;
            }
            update(
                connection,
                "UPDATE settlement_founding_expeditions SET status='EXPIRED',updated_at=?,version=version+1 WHERE id=?",
                now,
                expedition);
            update(
                connection,
                "UPDATE settlement_founding_expedition_members SET status='EXPIRED' WHERE expedition_id=?",
                expedition);
            expeditionHistory(connection, expedition, "EXPEDITION_EXPIRED", null, "{}", now);
          }
          List<UUID> reservations =
              ids(
                  connection,
                  "SELECT id FROM settlement_founding_reservations WHERE status='RESERVED' AND expires_at<? ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
                  now,
                  limit);
          for (UUID id : reservations) {
            refundReservation(connection, id, null, now);
            refunded++;
          }
          update(
              connection,
              "UPDATE settlement_disband_requests SET status='EXPIRED',version=version+1 WHERE status='REQUESTED' AND expires_at<?",
              now);
          List<UUID> inactiveMayors =
              ids(
                  connection,
                  "SELECT c.id FROM cities c LEFT JOIN settlement_member_activity a ON a.city_id=c.id AND a.player_id=c.owner_id WHERE c.lifecycle_status='ACTIVE' AND coalesce(a.last_active_at,c.last_active_at)<? ORDER BY coalesce(a.last_active_at,c.last_active_at) LIMIT ? FOR UPDATE OF c SKIP LOCKED",
                  mayorInactiveBefore,
                  limit);
          for (UUID city : inactiveMayors) {
            UUID current = owner(connection, city, false);
            UUID successor = activeSuccessor(connection, city, current, mayorInactiveBefore);
            if (successor != null) {
              transfer(connection, city, current, successor, "AUTOMATIC_SUCCESSION", now);
              successions++;
            }
          }
          List<UUID> cities =
              ids(
                  connection,
                  "SELECT id FROM cities WHERE lifecycle_status='ACTIVE' AND last_active_at<? ORDER BY last_active_at LIMIT ? FOR UPDATE SKIP LOCKED",
                  settlementInactiveBefore,
                  limit);
          for (UUID city : cities) {
            UUID current = owner(connection, city, false);
            ruin(connection, city, current, "INACTIVE_ABANDONMENT", now);
            abandoned++;
          }
          return new RecoveryReport(abandoned, successions, refunded);
        });
  }

  @Override
  public List<HistoryEntry> history(UUID city, UUID actor, int limit) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor);
          List<HistoryEntry> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT event_type,actor_id,payload::text,occurred_at FROM settlement_lifecycle_history WHERE city_id=? ORDER BY occurred_at DESC LIMIT ?")) {
            statement.setObject(1, city);
            statement.setInt(2, Math.max(1, Math.min(100, limit)));
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new HistoryEntry(
                        result.getString(1),
                        result.getObject(2, UUID.class),
                        result.getString(3),
                        result.getTimestamp(4).toInstant()));
            }
          }
          return List.copyOf(values);
        });
  }

  private void transitionExpedition(
      UUID expedition,
      UUID actor,
      String expected,
      String target,
      String reservationStatus,
      Instant now) {
    store.inTransaction(
        connection -> {
          FoundingExpedition current = expedition(connection, expedition, true);
          requireExpeditionLeader(current, actor);
          if (current.status().equals(target)) return null;
          if (!current.status().equals(expected))
            throw new DomainException(
                "founding expedition is " + current.status() + ", expected " + expected);
          update(
              connection,
              "UPDATE settlement_founding_expeditions SET status=?,updated_at=?,version=version+1 WHERE id=?",
              target,
              now,
              expedition);
          update(
              connection,
              "UPDATE settlement_founding_reservations SET status=?,version=version+1 WHERE expedition_id=?",
              reservationStatus,
              expedition);
          expeditionHistory(connection, expedition, target, actor, "{}", now);
          return null;
        });
  }

  private static FoundingExpedition expedition(Connection connection, UUID id, boolean lock)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT e.id,e.city_id,e.leader_id,e.settlement_name,e.charter_text,e.status,e.world_id,e.x,e.y,e.z,(SELECT count(*) FROM settlement_founding_expedition_members m WHERE m.expedition_id=e.id AND m.status='ACCEPTED') AS accepted,e.expires_at,(SELECT r.id FROM settlement_founding_reservations r WHERE r.expedition_id=e.id ORDER BY r.created_at DESC LIMIT 1) AS reservation_id FROM settlement_founding_expeditions e WHERE e.id=?"
                + (lock ? " FOR UPDATE OF e" : ""))) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("founding expedition not found");
        UUID world = result.getObject(7, UUID.class);
        CoreLocation core =
            world == null
                ? null
                : new CoreLocation(world, result.getInt(8), result.getInt(9), result.getInt(10));
        return new FoundingExpedition(
            result.getObject(1, UUID.class),
            result.getObject(2, UUID.class),
            result.getObject(3, UUID.class),
            result.getString(4),
            result.getString(5),
            result.getString(6),
            core,
            result.getInt(11),
            result.getTimestamp(12).toInstant(),
            result.getObject(13, UUID.class));
      }
    }
  }

  private static FoundingReservation reservationForExpedition(
      Connection connection, UUID expedition) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,player_id,fee_minor,status,expires_at FROM settlement_founding_reservations WHERE expedition_id=? ORDER BY created_at DESC LIMIT 1 FOR UPDATE")) {
      statement.setObject(1, expedition);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("founding reservation not found");
        return new FoundingReservation(
            result.getObject(1, UUID.class),
            result.getObject(2, UUID.class),
            result.getLong(3),
            result.getString(4),
            result.getTimestamp(5).toInstant());
      }
    }
  }

  private static Optional<UUID> expeditionReservationId(Connection connection, UUID expedition)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM settlement_founding_reservations WHERE expedition_id=? ORDER BY created_at DESC LIMIT 1")) {
      statement.setObject(1, expedition);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(result.getObject(1, UUID.class)) : Optional.empty();
      }
    }
  }

  private static void requireExpeditionLeader(FoundingExpedition expedition, UUID actor) {
    if (!expedition.leader().equals(actor))
      throw new DomainException("only the expedition leader can perform this action");
  }

  private static void requireExpeditionOpen(
      FoundingExpedition expedition, Instant now, String... allowedStatuses) {
    if (expedition.expiresAt().isBefore(now))
      throw new DomainException("founding expedition expired");
    if (!List.of(allowedStatuses).contains(expedition.status()))
      throw new DomainException("founding expedition is " + expedition.status());
  }

  private static void expeditionHistory(
      Connection connection, UUID expedition, String event, UUID actor, String payload, Instant now)
      throws SQLException {
    update(
        connection,
        "INSERT INTO settlement_founding_expedition_history(id,expedition_id,event_type,actor_id,payload,occurred_at) VALUES(?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        expedition,
        event,
        actor,
        payload,
        now);
  }

  @Override
  public void reviewExpedition(UUID expedition, UUID actor, String reason, Instant now) {
    store.inTransaction(
        connection -> {
          FoundingExpedition current = expedition(connection, expedition, true);
          requireExpeditionLeader(current, actor);
          if (current.status().equals("COMPLETED")) return null;
          update(
              connection,
              "UPDATE settlement_founding_expeditions SET status='REVIEW_REQUIRED',updated_at=?,version=version+1 WHERE id=?",
              now,
              expedition);
          update(
              connection,
              "UPDATE settlement_founding_reservations SET status='REVIEW_REQUIRED',version=version+1 WHERE id=?",
              reservationForExpedition(connection, expedition).id());
          update(
              connection,
              "INSERT INTO settlement_founding_expedition_history(id,expedition_id,event_type,actor_id,payload,occurred_at) VALUES(?,?, 'REVIEW_REQUIRED',?,jsonb_build_object('reason',?),?)",
              UUID.randomUUID(),
              expedition,
              actor,
              reason,
              now);
          return null;
        });
  }

  private static LifecycleSnapshot transfer(
      Connection c, UUID city, UUID actor, UUID successor, String event, Instant now)
      throws SQLException {
    UUID owner = owner(c, city, true);
    if (!owner.equals(actor)) throw new DomainException("only the mayor can transfer ownership");
    requireActiveLifecycle(c, city);
    requireMember(c, city, successor);
    update(
        c, "UPDATE city_members SET role='CITIZEN' WHERE city_id=? AND player_id=?", city, owner);
    update(
        c, "UPDATE city_members SET role='MAYOR' WHERE city_id=? AND player_id=?", city, successor);
    update(
        c,
        "UPDATE cities SET owner_id=?,last_active_at=?,version=version+1 WHERE id=?",
        successor,
        now,
        city);
    update(
        c,
        "UPDATE settlement_disband_requests SET status='CANCELLED',version=version+1 WHERE city_id=? AND status='REQUESTED'",
        city);
    history(c, city, event, actor, "{\"successor\":\"" + successor + "\"}", now);
    return snapshot(c, city);
  }

  private static void requireCoreDistance(Connection connection, CoreLocation core)
      throws SQLException {
    if (scalar(
            connection,
            "SELECT count(*) FROM settlement_cores WHERE world_id=? AND ((x-?::int)::bigint*(x-?::int)::bigint+(z-?::int)::bigint*(z-?::int)::bigint)<?",
            core.world(),
            core.x(),
            core.x(),
            core.z(),
            core.z(),
            128L * 128L)
        > 0) throw new DomainException("settlement core must be at least 128 blocks away");
  }

  private static void requireCoreLocation(
      Connection connection, CoreLocation core, int minimumDistance, int harborExclusionRadius)
      throws SQLException {
    requireCoreLocation(connection, core, minimumDistance, harborExclusionRadius, null);
  }

  private static void requireCoreLocation(
      Connection connection,
      CoreLocation core,
      int minimumDistance,
      int harborExclusionRadius,
      UUID excludedExpedition)
      throws SQLException {
    if (scalar(
            connection,
            "SELECT count(*) FROM settlement_cores WHERE world_id=? AND ((x-?::int)::bigint*(x-?::int)::bigint+(z-?::int)::bigint*(z-?::int)::bigint)<?",
            core.world(),
            core.x(),
            core.x(),
            core.z(),
            core.z(),
            (long) minimumDistance * minimumDistance)
        > 0)
      throw new DomainException(
          "settlement core must be at least " + minimumDistance + " blocks away");
    if (scalar(
            connection,
            "SELECT count(*) FROM road_nodes WHERE node_type='HARBOR' AND world_id=? AND ((x-?::int)::bigint*(x-?::int)::bigint+(z-?::int)::bigint*(z-?::int)::bigint)<?",
            core.world(),
            core.x(),
            core.x(),
            core.z(),
            core.z(),
            (long) harborExclusionRadius * harborExclusionRadius)
        > 0)
      throw new DomainException(
          "settlement core must be outside the "
              + harborExclusionRadius
              + " block Harbor exclusion zone");
    if (scalar(
            connection,
            "SELECT count(*) FROM city_claims WHERE world_id=? AND chunk_x=? AND chunk_z=? AND city_id IS NOT NULL",
            core.world(),
            Math.floorDiv(core.x(), 16),
            Math.floorDiv(core.z(), 16))
        > 0) throw new DomainException("settlement core chunk is already controlled");
    if (scalar(
            connection,
            "SELECT count(*) FROM settlement_founding_expeditions WHERE world_id=? AND id<>coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid) AND status NOT IN ('CANCELLED','EXPIRED','COMPLETED','REVIEW_REQUIRED') AND ((x-?::int)::bigint*(x-?::int)::bigint+(z-?::int)::bigint*(z-?::int)::bigint)<?",
            core.world(),
            excludedExpedition,
            core.x(),
            core.x(),
            core.z(),
            core.z(),
            (long) minimumDistance * minimumDistance)
        > 0) throw new DomainException("another founding expedition selected a nearby core");
  }

  private static LifecycleSnapshot ruin(
      Connection c, UUID city, UUID actor, String event, Instant now) throws SQLException {
    requireMayor(c, city, actor);
    requireActiveLifecycle(c, city);
    Instant until = now.plusSeconds(30L * 86_400L);
    update(
        c,
        "INSERT INTO settlement_ruin_claims(city_id,world_id,chunk_x,chunk_z,previous_state,abandoned_at) SELECT city_id,world_id,chunk_x,chunk_z,state,? FROM city_claims WHERE city_id=? ON CONFLICT(city_id,world_id,chunk_x,chunk_z) DO UPDATE SET previous_state=excluded.previous_state,abandoned_at=excluded.abandoned_at",
        now,
        city);
    update(
        c,
        "UPDATE cities SET lifecycle_status='RUINS',abandoned_at=?,ruins_until=?,version=version+1 WHERE id=?",
        now,
        until,
        city);
    update(
        c,
        "UPDATE city_claims SET city_id=NULL,state='WILDERNESS',influence=0,version=version+1 WHERE city_id=?",
        city);
    update(
        c,
        "UPDATE city_buildings SET status=CASE WHEN status='DESTROYED' THEN status ELSE 'DISABLED' END,version=version+1 WHERE city_id=?",
        city);
    update(
        c,
        "UPDATE warehouses SET status='RUINS',version=version+1 WHERE city_id=? AND status='ACTIVE'",
        city);
    update(
        c,
        "INSERT INTO settlement_ruin_market_orders(city_id,order_id,previous_status,frozen_at) SELECT settlement_id,id,status,? FROM market_orders WHERE settlement_id=? AND status IN ('OPEN','PARTIAL') ON CONFLICT(city_id,order_id) DO UPDATE SET previous_status=excluded.previous_status,frozen_at=excluded.frozen_at",
        now,
        city);
    update(
        c,
        "UPDATE market_orders SET status='FROZEN_RUINS',version=version+1 WHERE settlement_id=? AND status IN ('OPEN','PARTIAL')",
        city);
    update(c, "UPDATE settlement_cores SET status='RUINS',version=version+1 WHERE city_id=?", city);
    update(c, "DELETE FROM dirty_settlements WHERE city_id=?", city);
    update(c, "DELETE FROM city_simulation_state WHERE city_id=?", city);
    update(c, "DELETE FROM city_world_simulation_state WHERE city_id=?", city);
    update(
        c,
        "UPDATE settlement_disband_requests SET status='CANCELLED',version=version+1 WHERE city_id=? AND status='REQUESTED'",
        city);
    history(c, city, event, actor, "{\"ruinsUntil\":\"" + until + "\"}", now);
    return snapshot(c, city);
  }

  private static void refundReservation(Connection c, UUID id, UUID player, Instant now)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT player_id,fee_minor,status FROM settlement_founding_reservations WHERE id=? FOR UPDATE")) {
      s.setObject(1, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("founding reservation not found");
        UUID owner = r.getObject(1, UUID.class);
        if (player != null && !player.equals(owner))
          throw new DomainException("founding reservation belongs to another player");
        if (!List.of("RESERVED", "FEE_RESERVED", "MATERIALS_CLAIMED", "MATERIALS_RESERVED")
            .contains(r.getString(3))) return;
        long fee = r.getLong(2), before = balance(c, account(c, owner));
        UUID account = account(c, owner);
        update(
            c,
            "UPDATE accounts SET balance_minor=balance_minor+?,version=version+1 WHERE id=?",
            fee,
            account);
        ledger(c, account, owner, fee, before + fee, id, "FOUNDING_REFUND", now);
        update(
            c,
            "UPDATE settlement_founding_reservations SET status='REFUNDED',version=version+1 WHERE id=?",
            id);
      }
    }
  }

  private static UUID activeSuccessor(Connection c, UUID city, UUID owner, Instant cutoff)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT m.player_id FROM city_members m JOIN settlement_member_activity a ON a.city_id=m.city_id AND a.player_id=m.player_id WHERE m.city_id=? AND m.player_id<>? AND m.role IN ('TREASURER','GENERAL','ARCHITECT','BUILDER_MASTER','DIPLOMAT') AND a.last_active_at>=? ORDER BY CASE m.role WHEN 'ARCHITECT' THEN 0 WHEN 'TREASURER' THEN 1 ELSE 2 END,m.joined_at LIMIT 1")) {
      s.setObject(1, city);
      s.setObject(2, owner);
      s.setTimestamp(3, Timestamp.from(cutoff));
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? r.getObject(1, UUID.class) : null;
      }
    }
  }

  private static Instant memberActivity(Connection c, UUID city, UUID player) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT coalesce(a.last_active_at,c.last_active_at) FROM cities c LEFT JOIN settlement_member_activity a ON a.city_id=c.id AND a.player_id=? WHERE c.id=?")) {
      s.setObject(1, player);
      s.setObject(2, city);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("member activity missing");
        return r.getTimestamp(1).toInstant();
      }
    }
  }

  private static LifecycleSnapshot snapshot(Connection c, UUID city) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id,owner_id,lifecycle_status,abandoned_at,ruins_until FROM cities WHERE id=?")) {
      s.setObject(1, city);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("settlement not found");
        return new LifecycleSnapshot(
            city,
            r.getObject(2, UUID.class),
            r.getString(3),
            r.getTimestamp(4) == null ? null : r.getTimestamp(4).toInstant(),
            r.getTimestamp(5) == null ? null : r.getTimestamp(5).toInstant());
      }
    }
  }

  private static UUID owner(Connection c, UUID city, boolean lock) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT owner_id FROM cities WHERE id=?" + (lock ? " FOR UPDATE" : ""))) {
      s.setObject(1, city);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("settlement not found");
        return r.getObject(1, UUID.class);
      }
    }
  }

  private static void requireMayor(Connection c, UUID city, UUID actor) throws SQLException {
    if (!owner(c, city, true).equals(actor))
      throw new DomainException("only the mayor can change settlement lifecycle");
  }

  private static void requireActiveLifecycle(Connection c, UUID city) throws SQLException {
    if (!snapshot(c, city).status().equals("ACTIVE"))
      throw new DomainException("settlement is not active");
  }

  private static void requireMember(Connection c, UUID city, UUID actor) throws SQLException {
    if (scalar(c, "SELECT count(*) FROM city_members WHERE city_id=? AND player_id=?", city, actor)
        != 1) throw new DomainException("not a settlement member");
  }

  private static UUID disbandRequestCity(Connection c, UUID request) throws SQLException {
    try (PreparedStatement statement =
        c.prepareStatement("SELECT city_id FROM settlement_disband_requests WHERE id=?")) {
      statement.setObject(1, request);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("disband request not found");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static void lockCities(Connection c, UUID first, UUID second) throws SQLException {
    try (PreparedStatement statement =
        c.prepareStatement("SELECT id FROM cities WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
      statement.setObject(1, first);
      statement.setObject(2, second);
      try (ResultSet result = statement.executeQuery()) {
        int found = 0;
        while (result.next()) found++;
        if (found != 2) throw new DomainException("merge settlement not found");
      }
    }
  }

  private static UUID account(Connection c, UUID player) throws SQLException {
    update(
        c,
        "INSERT INTO accounts(id,owner_type,owner_id,balance_minor) VALUES(?,'PLAYER',?,0) ON CONFLICT(owner_type,owner_id) DO NOTHING",
        UUID.randomUUID(),
        player);
    return accountByOwner(c, "PLAYER", player);
  }

  private static UUID warehouse(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("active settlement warehouse missing");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static UUID cityAccount(Connection c, UUID city) throws SQLException {
    return accountByOwner(c, "CITY", city);
  }

  private static UUID accountByOwner(Connection c, String type, UUID owner) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id FROM accounts WHERE owner_type=? AND owner_id=? FOR UPDATE")) {
      s.setString(1, type);
      s.setObject(2, owner);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("account missing");
        return r.getObject(1, UUID.class);
      }
    }
  }

  private static long balance(Connection c, UUID account) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT balance_minor FROM accounts WHERE id=? FOR UPDATE")) {
      s.setObject(1, account);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("account missing");
        return r.getLong(1);
      }
    }
  }

  private static void ledger(
      Connection c,
      UUID account,
      UUID actor,
      long amount,
      long after,
      UUID key,
      String type,
      Instant now)
      throws SQLException {
    update(
        c,
        "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at,description) VALUES(?,?,?,?,?,?,?,?,?,'settlement founding lifecycle')",
        UUID.randomUUID(),
        account,
        actor,
        type,
        amount,
        after,
        key,
        UUID.nameUUIDFromBytes(
            (key + ":" + type).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        now);
  }

  private static void history(
      Connection c, UUID city, String event, UUID actor, String payload, Instant now)
      throws SQLException {
    update(
        c,
        "INSERT INTO settlement_lifecycle_history(id,city_id,event_type,actor_id,payload,occurred_at) VALUES(?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        city,
        event,
        actor,
        payload,
        now);
  }

  private static List<UUID> ids(Connection c, String sql, Object... args) throws SQLException {
    List<UUID> ids = new ArrayList<>();
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) ids.add(r.getObject(1, UUID.class));
      }
    }
    return ids;
  }

  private static long scalar(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  private static String text(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("required record not found");
        return r.getString(1);
      }
    }
  }

  private static void update(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      s.executeUpdate();
    }
  }

  private static int updateCount(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      return s.executeUpdate();
    }
  }

  private static void bind(PreparedStatement s, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      Object v = args[i];
      if (v instanceof Instant instant) s.setTimestamp(i + 1, Timestamp.from(instant));
      else s.setObject(i + 1, v);
    }
  }
}
