package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.city.BuildingType;
import nl.frontier.city.BuildingValidationGateway;
import nl.frontier.city.BuildingValidator;
import nl.frontier.city.SettlementGateway;
import nl.frontier.domain.DomainException;

public final class PostgresBuildingValidationGateway implements BuildingValidationGateway {
  private static final Set<String> ROLES = Set.of("MAYOR", "ARCHITECT");
  private final TransactionalStore store;

  public PostgresBuildingValidationGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public BuildingValidator.ValidationContext context(
      UUID city,
      UUID actor,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String districtKey) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          requireClaimedBounds(connection, city, bounds);
          return new BuildingValidator.ValidationContext(
              overlaps(connection, city, bounds),
              districtCompatible(connection, city, type, bounds, districtKey),
              false);
        });
  }

  @Override
  public RegisteredBuilding register(
      UUID city,
      UUID actor,
      SettlementGateway.Bounds bounds,
      String districtKey,
      BuildingValidator.ValidationResult validation,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          requireClaimedBounds(connection, city, bounds);
          if (!validation.valid()) throw new DomainException("building validation did not pass");
          if (overlaps(connection, city, bounds))
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
          transition(connection, building, null, "PLANNED", report, actor, now);
          transition(connection, building, "PLANNED", "UNDER_CONSTRUCTION", report, actor, now);
          transition(connection, building, "UNDER_CONSTRUCTION", "VALIDATING", report, actor, now);
          transition(connection, building, "VALIDATING", "ACTIVE", report, actor, now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_buildings SET status='ACTIVE',version=version+1 WHERE id=? AND status='PLANNED'")) {
            statement.setObject(1, building);
            statement.executeUpdate();
          }
          audit(connection, actor, building, report, now);
          return new RegisteredBuilding(
              building, city, validation.type(), "ACTIVE", 100, validation.violations());
        });
  }

  private static void requireRole(Connection connection, UUID city, UUID actor)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=?")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !ROLES.contains(result.getString(1)))
          throw new DomainException("settlement role cannot register buildings");
      }
    }
  }

  private static void requireClaimedBounds(
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
        if (result.getLong(1) != expected)
          throw new DomainException("every building chunk must be controlled by the settlement");
      }
    }
  }

  private static boolean overlaps(Connection connection, UUID city, SettlementGateway.Bounds bounds)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM city_buildings WHERE city_id=? AND (bounds->>'world')::uuid=? AND NOT ((bounds->>'maxX')::int<? OR (bounds->>'minX')::int>? OR (bounds->>'maxY')::int<? OR (bounds->>'minY')::int>? OR (bounds->>'maxZ')::int<? OR (bounds->>'minZ')::int>?) AND status<>'DESTROYED' LIMIT 1")) {
      statement.setObject(1, city);
      statement.setObject(2, bounds.world());
      statement.setInt(3, bounds.minX());
      statement.setInt(4, bounds.maxX());
      statement.setInt(5, bounds.minY());
      statement.setInt(6, bounds.maxY());
      statement.setInt(7, bounds.minZ());
      statement.setInt(8, bounds.maxZ());
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
        String districtType = result.getString(1);
        return switch (type) {
          case WAREHOUSE ->
              Set.of("INDUSTRIAL", "COMMERCIAL", "LOGISTICS", "HARBOR").contains(districtType);
          case HOUSING -> districtType.equals("RESIDENTIAL");
          case FARM -> districtType.equals("AGRICULTURAL");
          case BUILDER_GUILD -> Set.of("INDUSTRIAL", "GOVERNMENT").contains(districtType);
          case MARKET -> Set.of("COMMERCIAL", "HARBOR").contains(districtType);
          case BARRACKS -> districtType.equals("MILITARY");
        };
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

  private static String report(BuildingValidator.ValidationResult validation) {
    var survey = validation.survey();
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
        + "}";
  }

  private static void transition(
      Connection connection,
      UUID building,
      String from,
      String to,
      String report,
      UUID actor,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO building_validation_history(id,building_id,from_state,to_state,report,actor_id,occurred_at) VALUES(?,?,?,?,?::jsonb,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, building);
      statement.setString(3, from);
      statement.setString(4, to);
      statement.setString(5, report);
      statement.setObject(6, actor);
      statement.setTimestamp(7, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void audit(
      Connection connection, UUID actor, UUID building, String report, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,new_value,reason,occurred_at) VALUES(?,?,'BUILDING_VALIDATED','BUILDING',?,?::jsonb,'physical validator framework',?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, actor);
      statement.setObject(3, building);
      statement.setString(4, report);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }
}
