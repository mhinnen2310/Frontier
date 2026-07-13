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
import nl.frontier.city.BuildingState;
import nl.frontier.city.BuildingType;
import nl.frontier.city.BuildingValidationContext;
import nl.frontier.city.BuildingValidationGateway;
import nl.frontier.city.BuildingValidationResult;
import nl.frontier.city.DistrictBuildingPolicy;
import nl.frontier.city.DistrictType;
import nl.frontier.city.SettlementGateway;
import nl.frontier.domain.DomainException;

public final class PostgresBuildingValidationGateway implements BuildingValidationGateway {
  private static final Set<String> ROLES = Set.of("MAYOR", "ARCHITECT");
  private final TransactionalStore store;

  public PostgresBuildingValidationGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public BuildingValidationContext context(
      UUID city,
      UUID actor,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String districtKey) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          return new BuildingValidationContext(
              claimedBounds(connection, city, bounds),
              overlaps(connection, city, bounds, null),
              districtCompatible(connection, city, type, bounds, districtKey),
              false);
        });
  }

  @Override
  public BuildingValidationContext revalidationContext(
      UUID city,
      UUID actor,
      UUID building,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String districtKey) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          loadBuilding(connection, city, building, false);
          return new BuildingValidationContext(
              claimedBounds(connection, city, bounds),
              overlaps(connection, city, bounds, building),
              districtCompatible(connection, city, type, bounds, districtKey),
              false);
        });
  }

  @Override
  public void authorize(UUID city, UUID actor) {
    store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          return null;
        });
  }

  @Override
  public RegisteredBuilding building(UUID city, UUID actor, UUID building) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          return loadBuilding(connection, city, building, false);
        });
  }

  @Override
  public RegisteredBuilding register(
      UUID city,
      UUID actor,
      SettlementGateway.Bounds bounds,
      String districtKey,
      BuildingValidationResult validation,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          requireClaimedBounds(connection, city, bounds);
          if (!validation.valid()) throw new DomainException("building validation did not pass");
          if (overlaps(connection, city, bounds, null))
            throw new DomainException("building overlaps during registration");
          if (!districtCompatible(connection, city, validation.type(), bounds, districtKey))
            throw new DomainException("building district changed during registration");
          UUID building = UUID.randomUUID();
          String report = report(validation);
          UUID district = districtId(districtKey);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO city_buildings(id,city_id,category,district_id,bounds,integrity,status,version,building_type,validation_report,last_validated_at,validation_version) VALUES(?,?,?,?,?::jsonb,100,'PLANNED',0,?,?::jsonb,?,1)")) {
            statement.setObject(1, building);
            statement.setObject(2, city);
            statement.setString(3, validation.type().category().name());
            statement.setObject(4, district);
            statement.setString(5, bounds.json());
            statement.setString(6, validation.type().name());
            statement.setString(7, report);
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
          }
          transition(connection, building, null, BuildingState.PLANNED, report, actor, now);
          transition(
              connection,
              building,
              BuildingState.PLANNED,
              BuildingState.UNDER_CONSTRUCTION,
              report,
              actor,
              now);
          transition(
              connection,
              building,
              BuildingState.UNDER_CONSTRUCTION,
              BuildingState.VALIDATING,
              report,
              actor,
              now);
          transition(
              connection,
              building,
              BuildingState.VALIDATING,
              BuildingState.ACTIVE,
              report,
              actor,
              now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_buildings SET status='ACTIVE',version=version+1 WHERE id=? AND status='PLANNED'")) {
            statement.setObject(1, building);
            statement.executeUpdate();
          }
          audit(
              connection,
              actor,
              "BUILDING_VALIDATED",
              building,
              report,
              "physical building registration",
              now);
          return new RegisteredBuilding(
              building,
              city,
              validation.type(),
              BuildingState.ACTIVE,
              100,
              validation.violations(),
              bounds,
              districtKey);
        });
  }

  @Override
  public RegisteredBuilding revalidate(
      UUID city, UUID actor, UUID building, BuildingValidationResult validation, Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          RegisteredBuilding current = loadBuilding(connection, city, building, true);
          if (current.state() == BuildingState.ABANDONED
              || current.state() == BuildingState.DESTROYED)
            throw new DomainException("terminal building cannot be revalidated");
          if (!validation.valid() || validation.type() != current.type())
            throw new DomainException("building revalidation did not pass");
          requireClaimedBounds(connection, city, current.bounds());
          if (overlaps(connection, city, current.bounds(), building))
            throw new DomainException("building overlaps during revalidation");
          if (!districtCompatible(
              connection, city, current.type(), current.bounds(), current.district()))
            throw new DomainException("building district changed during revalidation");
          BuildingState target = stateForIntegrity(current.integrity());
          String report = report(validation);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_buildings SET status=?,validation_report=?::jsonb,last_validated_at=?,validation_version=validation_version+1,version=version+1 WHERE id=? AND city_id=?")) {
            statement.setString(1, target.name());
            statement.setString(2, report);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setObject(4, building);
            statement.setObject(5, city);
            if (statement.executeUpdate() != 1)
              throw new DomainException("building changed during revalidation");
          }
          transition(connection, building, current.state(), target, report, actor, now);
          audit(
              connection,
              actor,
              "BUILDING_REVALIDATED",
              building,
              report,
              "physical building revalidation",
              now);
          return new RegisteredBuilding(
              building,
              city,
              current.type(),
              target,
              current.integrity(),
              validation.violations(),
              current.bounds(),
              current.district());
        });
  }

  @Override
  public RegisteredBuilding unregister(UUID city, UUID actor, UUID building, Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          RegisteredBuilding current = loadBuilding(connection, city, building, true);
          if (current.state() == BuildingState.ABANDONED) return current;
          requireUnregisterSafe(connection, building);
          String report = "{\"reason\":\"unregistered\"}";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_buildings SET status='ABANDONED',district_id=NULL,version=version+1 WHERE id=? AND city_id=?")) {
            statement.setObject(1, building);
            statement.setObject(2, city);
            if (statement.executeUpdate() != 1)
              throw new DomainException("building changed during unregister");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE building_transfer_proposals SET status='CANCELLED',resolved_at=? WHERE building_id=? AND status='PENDING'")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, building);
            statement.executeUpdate();
          }
          transition(
              connection, building, current.state(), BuildingState.ABANDONED, report, actor, now);
          audit(
              connection,
              actor,
              "BUILDING_UNREGISTERED",
              building,
              report,
              "player unregistered building",
              now);
          return new RegisteredBuilding(
              building,
              city,
              current.type(),
              BuildingState.ABANDONED,
              current.integrity(),
              List.of(),
              current.bounds(),
              null);
        });
  }

  @Override
  public TransferProposal proposeTransfer(
      UUID city, UUID actor, UUID building, UUID targetCity, Instant now, Instant expiresAt) {
    return store.inTransaction(
        connection -> {
          requireMayor(connection, city, actor);
          if (city.equals(targetCity)) throw new DomainException("target settlement must differ");
          RegisteredBuilding current = loadBuilding(connection, city, building, true);
          if (current.state() == BuildingState.ABANDONED
              || current.state() == BuildingState.DESTROYED)
            throw new DomainException("terminal building cannot be transferred");
          requireUnregisterSafe(connection, building);
          requireCity(connection, targetCity);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE building_transfer_proposals SET status='EXPIRED',resolved_at=? WHERE building_id=? AND status='PENDING' AND expires_at<=?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, building);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT 1 FROM building_transfer_proposals WHERE building_id=? AND status='PENDING' AND expires_at>? LIMIT 1")) {
            statement.setObject(1, building);
            statement.setTimestamp(2, Timestamp.from(now));
            try (ResultSet result = statement.executeQuery()) {
              if (result.next())
                throw new DomainException("building already has a pending transfer");
            }
          }
          UUID proposal = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO building_transfer_proposals(id,building_id,source_city_id,target_city_id,requested_by,status,created_at,expires_at) VALUES(?,?,?,?,?,'PENDING',?,?)")) {
            statement.setObject(1, proposal);
            statement.setObject(2, building);
            statement.setObject(3, city);
            statement.setObject(4, targetCity);
            statement.setObject(5, actor);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setTimestamp(7, Timestamp.from(expiresAt));
            statement.executeUpdate();
          }
          audit(
              connection,
              actor,
              "BUILDING_TRANSFER_PROPOSED",
              building,
              "{\"proposal\":\"" + proposal + "\",\"targetCity\":\"" + targetCity + "\"}",
              "mayor proposed building ownership transfer",
              now);
          return new TransferProposal(
              proposal, building, city, targetCity, actor, "PENDING", expiresAt);
        });
  }

  @Override
  public RegisteredBuilding acceptTransfer(UUID actor, UUID proposal, Instant now) {
    return store.inTransaction(
        connection -> {
          UUID building;
          UUID sourceCity;
          UUID targetCity;
          String status;
          Instant expiresAt;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT building_id,source_city_id,target_city_id,status,expires_at FROM building_transfer_proposals WHERE id=? FOR UPDATE")) {
            statement.setObject(1, proposal);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("building transfer proposal not found");
              building = result.getObject(1, UUID.class);
              sourceCity = result.getObject(2, UUID.class);
              targetCity = result.getObject(3, UUID.class);
              status = result.getString(4);
              expiresAt = result.getTimestamp(5).toInstant();
            }
          }
          if (!status.equals("PENDING"))
            throw new DomainException("building transfer proposal is not pending");
          if (!expiresAt.isAfter(now))
            throw new DomainException("building transfer proposal expired");
          requireMayor(connection, targetCity, actor);
          RegisteredBuilding current = loadBuilding(connection, sourceCity, building, true);
          requireClaimedBounds(connection, sourceCity, current.bounds());
          transferParcelClaims(connection, sourceCity, targetCity, current.bounds(), current.id());
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_buildings SET city_id=?,district_id=NULL,version=version+1 WHERE id=? AND city_id=?")) {
            statement.setObject(1, targetCity);
            statement.setObject(2, building);
            statement.setObject(3, sourceCity);
            if (statement.executeUpdate() != 1)
              throw new DomainException("building ownership changed during transfer");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE building_transfer_proposals SET status='ACCEPTED',accepted_by=?,resolved_at=? WHERE id=? AND status='PENDING'")) {
            statement.setObject(1, actor);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, proposal);
            if (statement.executeUpdate() != 1)
              throw new DomainException("building transfer proposal changed");
          }
          String report =
              "{\"proposal\":\""
                  + proposal
                  + "\",\"sourceCity\":\""
                  + sourceCity
                  + "\",\"targetCity\":\""
                  + targetCity
                  + "\"}";
          transition(connection, building, current.state(), current.state(), report, actor, now);
          audit(
              connection,
              actor,
              "BUILDING_TRANSFER_ACCEPTED",
              building,
              report,
              "target mayor accepted building ownership transfer",
              now);
          return new RegisteredBuilding(
              building,
              targetCity,
              current.type(),
              current.state(),
              current.integrity(),
              List.of(),
              current.bounds(),
              null);
        });
  }

  @Override
  public List<HistoryEntry> history(UUID city, UUID actor, UUID building, int limit) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          loadBuilding(connection, city, building, false);
          List<HistoryEntry> history = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT from_state,to_state,report::text,actor_id,occurred_at FROM building_validation_history WHERE building_id=? ORDER BY occurred_at DESC,id DESC LIMIT ?")) {
            statement.setObject(1, building);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                String from = result.getString(1);
                history.add(
                    new HistoryEntry(
                        from == null ? null : BuildingState.valueOf(from),
                        BuildingState.valueOf(result.getString(2)),
                        result.getString(3),
                        result.getObject(4, UUID.class),
                        result.getTimestamp(5).toInstant()));
              }
            }
          }
          return List.copyOf(history);
        });
  }

  private static RegisteredBuilding loadBuilding(
      Connection connection, UUID city, UUID building, boolean forUpdate) throws SQLException {
    String lock = forUpdate ? " FOR UPDATE" : "";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT building_type,status,integrity,district_id,(bounds->>'world')::uuid,(bounds->>'minX')::int,(bounds->>'minY')::int,(bounds->>'minZ')::int,(bounds->>'maxX')::int,(bounds->>'maxY')::int,(bounds->>'maxZ')::int FROM city_buildings WHERE id=? AND city_id=?"
                + lock)) {
      statement.setObject(1, building);
      statement.setObject(2, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("building not found in settlement");
        String type = result.getString(1);
        if (type == null) throw new DomainException("legacy building has no validator type");
        UUID district = result.getObject(4, UUID.class);
        return new RegisteredBuilding(
            building,
            city,
            BuildingType.valueOf(type),
            BuildingState.valueOf(result.getString(2)),
            result.getInt(3),
            List.of(),
            new SettlementGateway.Bounds(
                result.getObject(5, UUID.class),
                result.getInt(6),
                result.getInt(7),
                result.getInt(8),
                result.getInt(9),
                result.getInt(10),
                result.getInt(11)),
            district == null ? null : district.toString());
      }
    }
  }

  private static void requireRole(Connection connection, UUID city, UUID actor)
      throws SQLException {
    String role = role(connection, city, actor);
    if (role == null || !ROLES.contains(role))
      throw new DomainException("settlement role cannot manage buildings");
  }

  private static void requireMayor(Connection connection, UUID city, UUID actor)
      throws SQLException {
    if (!"MAYOR".equals(role(connection, city, actor)))
      throw new DomainException("only the settlement mayor may transfer building ownership");
  }

  private static String role(Connection connection, UUID city, UUID actor) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=?")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getString(1) : null;
      }
    }
  }

  private static void requireCity(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM cities WHERE id=?")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("target settlement not found");
      }
    }
  }

  private static void requireUnregisterSafe(Connection connection, UUID building)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM warehouses WHERE building_id=? AND status='ACTIVE') OR EXISTS(SELECT 1 FROM builder_depots WHERE building_id=? AND status='ACTIVE') OR EXISTS(SELECT 1 FROM production_orders WHERE building_id=? AND status NOT IN ('COMPLETED','CANCELLED','FAILED')) OR EXISTS(SELECT 1 FROM workers WHERE assigned_building=? OR housing_building=?)")) {
      for (int index = 1; index <= 5; index++) statement.setObject(index, building);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        if (result.getBoolean(1))
          throw new DomainException(
              "building has active warehouse, depot, production or worker dependencies");
      }
    }
  }

  private static void requireClaimedBounds(
      Connection connection, UUID city, SettlementGateway.Bounds bounds) throws SQLException {
    if (!claimedBounds(connection, city, bounds))
      throw new DomainException("every building chunk must be controlled by the settlement");
  }

  private static boolean claimedBounds(
      Connection connection, UUID city, SettlementGateway.Bounds bounds) throws SQLException {
    int minChunkX = Math.floorDiv(bounds.minX(), 16);
    int maxChunkX = Math.floorDiv(bounds.maxX(), 16);
    int minChunkZ = Math.floorDiv(bounds.minZ(), 16);
    int maxChunkZ = Math.floorDiv(bounds.maxZ(), 16);
    long expected = Math.multiplyExact(maxChunkX - minChunkX + 1L, maxChunkZ - minChunkZ + 1L);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM city_claims WHERE city_id=? AND world_id=? AND chunk_x BETWEEN ? AND ? AND chunk_z BETWEEN ? AND ? AND state<>'WILDERNESS'")) {
      statement.setObject(1, city);
      statement.setObject(2, bounds.world());
      statement.setInt(3, minChunkX);
      statement.setInt(4, maxChunkX);
      statement.setInt(5, minChunkZ);
      statement.setInt(6, maxChunkZ);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1) == expected;
      }
    }
  }

  private static void transferParcelClaims(
      Connection connection,
      UUID sourceCity,
      UUID targetCity,
      SettlementGateway.Bounds bounds,
      UUID transferredBuilding)
      throws SQLException {
    int minChunkX = Math.floorDiv(bounds.minX(), 16);
    int maxChunkX = Math.floorDiv(bounds.maxX(), 16);
    int minChunkZ = Math.floorDiv(bounds.minZ(), 16);
    int maxChunkZ = Math.floorDiv(bounds.maxZ(), 16);
    SettlementGateway.Bounds parcel =
        new SettlementGateway.Bounds(
            bounds.world(),
            minChunkX * 16,
            -64,
            minChunkZ * 16,
            maxChunkX * 16 + 15,
            320,
            maxChunkZ * 16 + 15);
    if (overlaps(connection, sourceCity, parcel, transferredBuilding))
      throw new DomainException("building parcel contains another registered building");
    if (overlaps(connection, targetCity, parcel, null))
      throw new DomainException("target settlement has an overlapping building");
    long expected = Math.multiplyExact(maxChunkX - minChunkX + 1L, maxChunkZ - minChunkZ + 1L);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE city_claims SET city_id=? WHERE city_id=? AND world_id=? AND chunk_x BETWEEN ? AND ? AND chunk_z BETWEEN ? AND ? AND state<>'WILDERNESS'")) {
      statement.setObject(1, targetCity);
      statement.setObject(2, sourceCity);
      statement.setObject(3, bounds.world());
      statement.setInt(4, minChunkX);
      statement.setInt(5, maxChunkX);
      statement.setInt(6, minChunkZ);
      statement.setInt(7, maxChunkZ);
      if (statement.executeUpdate() != expected)
        throw new DomainException("building parcel claims changed during transfer");
    }
  }

  private static boolean overlaps(
      Connection connection, UUID city, SettlementGateway.Bounds bounds, UUID excludedBuilding)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM city_buildings WHERE city_id=? AND (?::uuid IS NULL OR id<>?::uuid) AND (bounds->>'world')::uuid=? AND NOT ((bounds->>'maxX')::int<? OR (bounds->>'minX')::int>? OR (bounds->>'maxY')::int<? OR (bounds->>'minY')::int>? OR (bounds->>'maxZ')::int<? OR (bounds->>'minZ')::int>?) AND status NOT IN ('DESTROYED','ABANDONED') LIMIT 1")) {
      statement.setObject(1, city);
      statement.setString(2, excludedBuilding == null ? null : excludedBuilding.toString());
      statement.setString(3, excludedBuilding == null ? null : excludedBuilding.toString());
      statement.setObject(4, bounds.world());
      statement.setInt(5, bounds.minX());
      statement.setInt(6, bounds.maxX());
      statement.setInt(7, bounds.minY());
      statement.setInt(8, bounds.maxY());
      statement.setInt(9, bounds.minZ());
      statement.setInt(10, bounds.maxZ());
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static boolean districtCompatible(
      Connection connection,
      UUID city,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String districtKey)
      throws SQLException {
    if (districtKey == null || districtKey.isBlank()) return true;
    UUID district = districtId(districtKey);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT d.district_type FROM districts d JOIN district_regions r ON r.district_id=d.id WHERE d.id=? AND d.city_id=? AND d.status='ACTIVE' AND r.world_id=? AND r.min_x<=? AND r.max_x>=? AND r.min_y<=? AND r.max_y>=? AND r.min_z<=? AND r.max_z>=?")) {
      statement.setObject(1, district);
      statement.setObject(2, city);
      statement.setObject(3, bounds.world());
      statement.setInt(4, bounds.minX());
      statement.setInt(5, bounds.maxX());
      statement.setInt(6, bounds.minY());
      statement.setInt(7, bounds.maxY());
      statement.setInt(8, bounds.minZ());
      statement.setInt(9, bounds.maxZ());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return false;
        return DistrictBuildingPolicy.compatible(DistrictType.valueOf(result.getString(1)), type);
      }
    }
  }

  private static UUID districtId(String districtKey) {
    if (districtKey == null || districtKey.isBlank()) return null;
    try {
      return UUID.fromString(districtKey);
    } catch (IllegalArgumentException invalid) {
      throw new DomainException("district must be a district UUID");
    }
  }

  private static BuildingState stateForIntegrity(int integrity) {
    if (integrity == 0) return BuildingState.DESTROYED;
    if (integrity < 15) return BuildingState.DISABLED;
    if (integrity < 90) return BuildingState.DAMAGED;
    return BuildingState.ACTIVE;
  }

  private static String report(BuildingValidationResult validation) {
    var inspection = validation.inspection();
    var survey = inspection.survey();
    return "{\"type\":\""
        + validation.type()
        + "\",\"valid\":true,\"dimensions\":\""
        + survey.width()
        + "x"
        + survey.height()
        + "x"
        + survey.depth()
        + "\",\"nonAir\":"
        + survey.nonAirBlocks()
        + ",\"roadBlocks\":"
        + survey.roadBlocks()
        + ",\"floorCoveragePercent\":"
        + inspection.floorCoveragePercent()
        + ",\"wallCoveragePercent\":"
        + inspection.wallCoveragePercent()
        + ",\"roofCoveragePercent\":"
        + inspection.roofCoveragePercent()
        + "}";
  }

  private static void transition(
      Connection connection,
      UUID building,
      BuildingState from,
      BuildingState to,
      String report,
      UUID actor,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO building_validation_history(id,building_id,from_state,to_state,report,actor_id,occurred_at) VALUES(?,?,?,?,?::jsonb,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, building);
      statement.setString(3, from == null ? null : from.name());
      statement.setString(4, to.name());
      statement.setString(5, report);
      statement.setObject(6, actor);
      statement.setTimestamp(7, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void audit(
      Connection connection,
      UUID actor,
      String action,
      UUID building,
      String report,
      String reason,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,new_value,reason,occurred_at) VALUES(?,?,?,'BUILDING',?,?::jsonb,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, actor);
      statement.setString(3, action);
      statement.setObject(4, building);
      statement.setString(5, report);
      statement.setString(6, reason);
      statement.setTimestamp(7, Timestamp.from(now));
      statement.executeUpdate();
    }
  }
}
