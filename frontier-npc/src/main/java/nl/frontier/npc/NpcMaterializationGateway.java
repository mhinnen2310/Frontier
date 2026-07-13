package nl.frontier.npc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persistent worker identity is authoritative; the entity UUID is a replaceable projection. */
public interface NpcMaterializationGateway {
  List<Candidate> candidates(Set<UUID> onlinePlayers, int maximumPerSettlement);

  List<Binding> retirements(Set<UUID> onlinePlayers);

  void bind(UUID worker, UUID entity, Instant now);

  void unbind(UUID worker, UUID entity, Instant now);

  record Candidate(
      UUID worker,
      UUID city,
      String profession,
      int skill,
      UUID world,
      int x,
      int y,
      int z,
      UUID entity) {}

  record Binding(UUID worker, UUID entity) {}
}
