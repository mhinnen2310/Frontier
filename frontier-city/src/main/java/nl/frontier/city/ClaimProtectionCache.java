package nl.frontier.city;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class ClaimProtectionCache {
  private final AtomicReference<State> state = new AtomicReference<>(State.empty());

  public void replace(ClaimProtectionGateway.Snapshot snapshot) {
    Map<MemberKey, GovernmentRole> members = new HashMap<>();
    snapshot
        .members()
        .forEach(value -> members.put(new MemberKey(value.city(), value.player()), value.role()));
    Map<OverrideKey, Boolean> overrides = new HashMap<>();
    snapshot
        .overrides()
        .forEach(
            value ->
                overrides.put(
                    new OverrideKey(value.city(), value.player(), value.action()),
                    value.allowed()));
    state.set(
        new State(
            Map.copyOf(snapshot.claims()),
            Map.copyOf(snapshot.owners()),
            Map.copyOf(members),
            Map.copyOf(overrides),
            snapshot.loadedAt()));
  }

  public Optional<UUID> city(UUID world, int chunkX, int chunkZ) {
    return Optional.ofNullable(
        state.get().claims.get(new ClaimProtectionGateway.ClaimKey(world, chunkX, chunkZ)));
  }

  public Optional<GovernmentRole> role(UUID city, UUID player) {
    return Optional.ofNullable(state.get().members.get(new MemberKey(city, player)));
  }

  public boolean owner(UUID city, UUID player) {
    return player.equals(state.get().owners.get(city));
  }

  public Optional<Boolean> override(UUID city, UUID player, ClaimProtectionService.Action action) {
    return Optional.ofNullable(state.get().overrides.get(new OverrideKey(city, player, action)));
  }

  public Instant loadedAt() {
    return state.get().loadedAt;
  }

  private record MemberKey(UUID city, UUID player) {}

  private record OverrideKey(UUID city, UUID player, ClaimProtectionService.Action action) {}

  private record State(
      Map<ClaimProtectionGateway.ClaimKey, UUID> claims,
      Map<UUID, UUID> owners,
      Map<MemberKey, GovernmentRole> members,
      Map<OverrideKey, Boolean> overrides,
      Instant loadedAt) {
    private static State empty() {
      return new State(Map.of(), Map.of(), Map.of(), Map.of(), Instant.EPOCH);
    }
  }
}
