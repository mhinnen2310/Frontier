package nl.frontier.city;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ClaimProtectionServiceTest {
  @Test
  void coversEveryProtectionActionForOutsidersMembersRolesCampaignsAndBypass() {
    UUID world = UUID.randomUUID();
    UUID city = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID citizen = UUID.randomUUID();
    UUID architect = UUID.randomUUID();
    UUID recruit = UUID.randomUUID();
    UUID outsider = UUID.randomUUID();
    ClaimProtectionCache cache = new ClaimProtectionCache();
    cache.replace(
        new ClaimProtectionGateway.Snapshot(
            Map.of(new ClaimProtectionGateway.ClaimKey(world, 1, 2), city),
            Map.of(city, owner),
            List.of(
                new ClaimProtectionGateway.Member(city, citizen, GovernmentRole.CITIZEN),
                new ClaimProtectionGateway.Member(city, architect, GovernmentRole.ARCHITECT),
                new ClaimProtectionGateway.Member(city, recruit, GovernmentRole.RECRUIT)),
            List.of(),
            Instant.now()));
    ClaimProtectionService service =
        new ClaimProtectionService(cache, (player, defending) -> player.equals(outsider));

    for (ClaimProtectionService.Action action : ClaimProtectionService.Action.values()) {
      assertTrue(decide(service, world, owner, action, false));
      assertTrue(decide(service, world, outsider, action, true));
      if (action == ClaimProtectionService.Action.BREAK)
        assertTrue(decide(service, world, outsider, action, false));
      else assertFalse(decide(service, world, outsider, action, false));
    }
    assertTrue(decide(service, world, citizen, ClaimProtectionService.Action.CONTAINER, false));
    assertFalse(decide(service, world, citizen, ClaimProtectionService.Action.REDSTONE, false));
    assertTrue(decide(service, world, architect, ClaimProtectionService.Action.REDSTONE, false));
    assertTrue(decide(service, world, recruit, ClaimProtectionService.Action.INTERACT, false));
    assertFalse(decide(service, world, recruit, ClaimProtectionService.Action.BUILD, false));
  }

  @Test
  void explicitOverrideWinsForMember() {
    UUID world = UUID.randomUUID();
    UUID city = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    ClaimProtectionCache cache = new ClaimProtectionCache();
    cache.replace(
        new ClaimProtectionGateway.Snapshot(
            Map.of(new ClaimProtectionGateway.ClaimKey(world, 1, 2), city),
            Map.of(),
            List.of(new ClaimProtectionGateway.Member(city, member, GovernmentRole.CITIZEN)),
            List.of(
                new ClaimProtectionGateway.Override(
                    city, member, ClaimProtectionService.Action.CONTAINER, false),
                new ClaimProtectionGateway.Override(
                    city, member, ClaimProtectionService.Action.REDSTONE, true)),
            Instant.now()));
    ClaimProtectionService service = new ClaimProtectionService(cache, (player, claim) -> false);
    assertFalse(decide(service, world, member, ClaimProtectionService.Action.CONTAINER, false));
    assertTrue(decide(service, world, member, ClaimProtectionService.Action.REDSTONE, false));
  }

  @Test
  void exploitMatrixDeniesEveryAuditedListenerVector() {
    UUID world = UUID.randomUUID();
    UUID city = UUID.randomUUID();
    UUID outsider = UUID.randomUUID();
    ClaimProtectionCache cache = new ClaimProtectionCache();
    cache.replace(
        new ClaimProtectionGateway.Snapshot(
            Map.of(new ClaimProtectionGateway.ClaimKey(world, 1, 2), city),
            Map.of(),
            List.of(),
            List.of(),
            Instant.now()));
    ClaimProtectionService service = new ClaimProtectionService(cache, (player, claim) -> false);
    List<ExploitVector> vectors =
        List.of(
            new ExploitVector("block-place", ClaimProtectionService.Action.BUILD),
            new ExploitVector("inventory-open", ClaimProtectionService.Action.CONTAINER),
            new ExploitVector("inventory-move", ClaimProtectionService.Action.AUTOMATION),
            new ExploitVector("door", ClaimProtectionService.Action.INTERACT),
            new ExploitVector("button", ClaimProtectionService.Action.INTERACT),
            new ExploitVector("lever", ClaimProtectionService.Action.INTERACT),
            new ExploitVector("hanging-place", ClaimProtectionService.Action.HANGING),
            new ExploitVector("hanging-break", ClaimProtectionService.Action.HANGING),
            new ExploitVector("armor-stand", ClaimProtectionService.Action.ENTITY),
            new ExploitVector("bucket-empty", ClaimProtectionService.Action.BUCKET),
            new ExploitVector("bucket-fill", ClaimProtectionService.Action.BUCKET),
            new ExploitVector("fire-spread", ClaimProtectionService.Action.FIRE),
            new ExploitVector("ignite", ClaimProtectionService.Action.FIRE),
            new ExploitVector("crop-trample", ClaimProtectionService.Action.TRAMPLE),
            new ExploitVector("entity-interact", ClaimProtectionService.Action.ENTITY),
            new ExploitVector("vehicle-place", ClaimProtectionService.Action.VEHICLE),
            new ExploitVector("redstone", ClaimProtectionService.Action.REDSTONE));
    assertTrue(vectors.stream().map(ExploitVector::name).distinct().count() == vectors.size());
    vectors.forEach(
        vector ->
            assertFalse(decide(service, world, outsider, vector.action(), false), vector.name()));
  }

  private static boolean decide(
      ClaimProtectionService service,
      UUID world,
      UUID actor,
      ClaimProtectionService.Action action,
      boolean bypass) {
    return service
        .authorize(new ClaimProtectionService.Request(world, 1, 2, actor, action, bypass))
        .allowed();
  }

  private record ExploitVector(String name, ClaimProtectionService.Action action) {}
}
