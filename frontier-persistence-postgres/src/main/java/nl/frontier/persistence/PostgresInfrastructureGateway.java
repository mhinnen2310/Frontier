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
import nl.frontier.domain.DomainException;
import nl.frontier.economy.InfrastructureGateway;
import nl.frontier.economy.InfrastructureValidator;

public final class PostgresInfrastructureGateway implements InfrastructureGateway {
  private static final Set<String> ROLES = Set.of("MAYOR", "ARCHITECT");
  private final TransactionalStore store;

  public PostgresInfrastructureGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public Context context(UUID city, UUID actor, UUID from, UUID to) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          return context(connection, city, from, to, false);
        });
  }

  @Override
  public Edge register(
      UUID city,
      UUID actor,
      UUID from,
      UUID to,
      int importance,
      InfrastructureValidator.Validation validation,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          Context context = context(connection, city, from, to, true);
          if (!validation.valid()) throw new DomainException("infrastructure survey is invalid");
          UUID edge = UUID.randomUUID();
          double distance =
              Math.sqrt(
                  Math.pow(context.from().x() - context.to().x(), 2)
                      + Math.pow(context.from().y() - context.to().y(), 2)
                      + Math.pow(context.from().z() - context.to().z(), 2));
          var survey = validation.survey();
          long capacity =
              Math.multiplyExact(validation.capacity(), 100L + roadCapacityBonus(connection, city))
                  / 100L;
          String report =
              "{\"samples\":"
                  + survey.samples()
                  + ",\"connected\":"
                  + survey.connectedSamples()
                  + ",\"destroyedBridges\":"
                  + survey.destroyedBridges()
                  + "}";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO road_edges(id,from_node,to_node,distance,capacity,integrity,version,infrastructure_type,traffic,importance,owner_city,minimum_width,surface_quality,maximum_slope,broken_segments,bridge_segments,tunnel_segments,validation_report,validated_at) VALUES(?,?,?,?,?,?,0,?,0,?,?,?,?,?,?,?,?,?::jsonb,?)")) {
            statement.setObject(1, edge);
            statement.setObject(2, from);
            statement.setObject(3, to);
            statement.setDouble(4, distance);
            statement.setLong(5, capacity);
            statement.setInt(6, validation.health());
            statement.setString(7, validation.type().name());
            statement.setInt(8, importance);
            statement.setObject(9, city);
            statement.setInt(10, survey.minimumWidth());
            statement.setInt(11, survey.surfaceQuality());
            statement.setDouble(12, survey.maximumSlope());
            statement.setInt(13, survey.brokenSegments());
            statement.setInt(14, survey.bridgeSamples());
            statement.setInt(15, survey.tunnelSamples());
            statement.setString(16, report);
            statement.setTimestamp(17, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,new_value,reason,occurred_at) VALUES(?,?,'PHYSICAL_INFRASTRUCTURE_VALIDATED','ROAD_EDGE',?,?::jsonb,'physical route survey',?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, actor);
            statement.setObject(3, edge);
            statement.setString(4, report);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
          }
          return new Edge(
              edge,
              from,
              to,
              validation.type(),
              validation.health(),
              capacity,
              0,
              importance,
              city);
        });
  }

  private static int roadCapacityBonus(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(max((u.effect->>'roadCapacityPercent')::int),0) FROM kingdom_members m JOIN kingdom_unlocks u ON u.kingdom_id=m.kingdom_id WHERE m.city_id=? AND u.content_type='RESEARCH'")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static Context context(Connection connection, UUID city, UUID from, UUID to, boolean lock)
      throws SQLException {
    if (from.equals(to)) throw new DomainException("infrastructure edge needs two nodes");
    Point first = point(connection, from, lock);
    Point second = point(connection, to, lock);
    UUID owner = owner(connection, from);
    if (!owner.equals(city)) throw new DomainException("origin node belongs to another settlement");
    if (!first.world().equals(second.world()))
      throw new DomainException("infrastructure nodes are in different worlds");
    double horizontal = Math.hypot(first.x() - second.x(), first.z() - second.z());
    if (horizontal > 256)
      throw new DomainException("physical infrastructure is limited to 256 blocks per edge");
    return new Context(city, first, second);
  }

  private static Point point(Connection connection, UUID node, boolean lock) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT world_id,x,y,z FROM road_nodes WHERE id=?" + (lock ? " FOR SHARE" : ""))) {
      statement.setObject(1, node);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("road node not found");
        return new Point(
            result.getObject(1, UUID.class), result.getInt(2), result.getInt(3), result.getInt(4));
      }
    }
  }

  private static UUID owner(Connection connection, UUID node) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT city_id FROM road_nodes WHERE id=?")) {
      statement.setObject(1, node);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("road node not found");
        return result.getObject(1, UUID.class);
      }
    }
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
          throw new DomainException("settlement role cannot register infrastructure");
      }
    }
  }
}
