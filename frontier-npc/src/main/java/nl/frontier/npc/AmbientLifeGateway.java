package nl.frontier.npc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable ambient-scene lifecycle. Mannequins are disposable projections of these records. */
public interface AmbientLifeGateway {
  CycleReport cycle(
      Set<PlayerObservation> observers,
      Set<UUID> daylightWorlds,
      AmbientLifePolicy policy,
      int maximumWorkerPresentations,
      Instant announcementCutoff,
      Instant now);

  void bind(UUID scene, UUID entity, Instant now);

  void unbind(UUID scene, UUID entity, Instant now);

  record Scene(
      UUID id,
      UUID city,
      String type,
      String label,
      UUID world,
      int x,
      int y,
      int z,
      UUID entity) {}

  record Binding(UUID scene, UUID entity) {}

  record Announcement(UUID city, UUID world, int x, int z, String message) {}

  record CycleReport(
      int settlements,
      int budgetedScenes,
      List<Scene> scenes,
      List<Binding> retirements,
      List<Announcement> announcements) {
    public CycleReport {
      scenes = List.copyOf(scenes);
      retirements = List.copyOf(retirements);
      announcements = List.copyOf(announcements);
    }
  }
}
