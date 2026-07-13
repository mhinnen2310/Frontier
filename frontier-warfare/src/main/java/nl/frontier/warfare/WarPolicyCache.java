package nl.frontier.warfare;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Lock-free event-thread view rebuilt from PostgreSQL by a bounded async supervisor. */
public final class WarPolicyCache {
  private final AtomicReference<State> state = new AtomicReference<>(State.empty());

  public void replace(CampaignGateway.WarPolicySnapshot snapshot) {
    Map<UUID, UUID> membership = new HashMap<>();
    snapshot.memberships().forEach(value -> membership.put(value.player(), value.city()));
    state.set(new State(Map.copyOf(membership), List.copyOf(snapshot.wars()), snapshot.loadedAt()));
  }

  public Optional<UUID> city(UUID player) {
    return Optional.ofNullable(state.get().memberships.get(player));
  }

  public boolean hostile(UUID firstPlayer, UUID secondPlayer) {
    State current = state.get();
    UUID first = current.memberships.get(firstPlayer);
    UUID second = current.memberships.get(secondPlayer);
    if (first == null || second == null || first.equals(second)) return false;
    return current.wars.stream()
        .anyMatch(
            war ->
                war.attacker().equals(first) && war.defender().equals(second)
                    || war.attacker().equals(second) && war.defender().equals(first));
  }

  public Optional<CampaignGateway.ActiveWar> hostileCampaign(UUID actor, UUID defendingCity) {
    State current = state.get();
    UUID actorCity = current.memberships.get(actor);
    if (actorCity == null || actorCity.equals(defendingCity)) return Optional.empty();
    return current.wars.stream()
        .filter(war -> war.attacker().equals(actorCity) && war.defender().equals(defendingCity))
        .findFirst();
  }

  public boolean friendly(UUID first, UUID second) {
    UUID a = state.get().memberships.get(first);
    UUID b = state.get().memberships.get(second);
    return a != null && a.equals(b);
  }

  public Instant loadedAt() {
    return state.get().loadedAt;
  }

  private record State(
      Map<UUID, UUID> memberships, List<CampaignGateway.ActiveWar> wars, Instant loadedAt) {
    static State empty() {
      return new State(Map.of(), List.of(), Instant.EPOCH);
    }
  }
}
