package nl.frontier.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.city.ClaimProtectionGateway;
import nl.frontier.city.ClaimProtectionService;
import nl.frontier.city.GovernmentRole;

public final class PostgresClaimProtectionGateway implements ClaimProtectionGateway {
  private final TransactionalStore store;

  public PostgresClaimProtectionGateway(TransactionalStore store) {
    this.store = store;
  }

  @java.lang.Override
  public Snapshot load(Instant now) {
    return store.inTransaction(
        connection -> {
          Map<ClaimKey, UUID> claims = new HashMap<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT world_id,chunk_x,chunk_z,city_id FROM city_claims WHERE city_id IS NOT NULL AND state<>'WILDERNESS'");
              ResultSet result = statement.executeQuery()) {
            while (result.next()) {
              claims.put(
                  new ClaimKey(result.getObject(1, UUID.class), result.getInt(2), result.getInt(3)),
                  result.getObject(4, UUID.class));
            }
          }
          Map<UUID, UUID> owners = new HashMap<>();
          try (PreparedStatement statement =
                  connection.prepareStatement("SELECT id,owner_id FROM cities");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              owners.put(result.getObject(1, UUID.class), result.getObject(2, UUID.class));
          }
          List<Member> members = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement("SELECT city_id,player_id,role FROM city_members");
              ResultSet result = statement.executeQuery()) {
            while (result.next()) {
              members.add(
                  new Member(
                      result.getObject(1, UUID.class),
                      result.getObject(2, UUID.class),
                      GovernmentRole.valueOf(result.getString(3))));
            }
          }
          List<ClaimProtectionGateway.Override> overrides = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT city_id,player_id,permission_key,allowed FROM city_permission_overrides WHERE permission_key LIKE 'PROTECTION_%'");
              ResultSet result = statement.executeQuery()) {
            while (result.next()) {
              String key =
                  result.getString(3).substring("PROTECTION_".length()).toUpperCase(Locale.ROOT);
              try {
                overrides.add(
                    new ClaimProtectionGateway.Override(
                        result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class),
                        ClaimProtectionService.Action.valueOf(key),
                        result.getBoolean(4)));
              } catch (IllegalArgumentException ignored) {
                // Unknown future permission keys are ignored by older plugin versions.
              }
            }
          }
          return new Snapshot(
              Map.copyOf(claims),
              Map.copyOf(owners),
              List.copyOf(members),
              List.copyOf(overrides),
              now);
        });
  }
}
