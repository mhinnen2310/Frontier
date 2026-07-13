package nl.frontier.repair;

import static nl.frontier.domain.Ids.BuildingId;
import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Ids.WarId;
import static nl.frontier.domain.Position.BlockPos;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Captures the first original state for each war/position and never container contents. */
public final class DamageJournal {
  private final Map<Key, Entry> entries = new HashMap<>();

  public synchronized Entry capture(
      WarId war,
      SettlementId settlement,
      BuildingId building,
      BlockPos position,
      String originalBlockData,
      String damagedBlockData,
      UUID source,
      String cause,
      Instant now) {
    Key key = new Key(war, position);
    return entries.computeIfAbsent(
        key,
        ignored ->
            new Entry(
                UUID.randomUUID(),
                war,
                settlement,
                building,
                position,
                validateData(originalBlockData),
                validateData(damagedBlockData),
                source,
                cause,
                now,
                RepairState.UNPLANNED));
  }

  public synchronized void mark(UUID journalId, RepairState state) {
    Map.Entry<Key, Entry> found =
        entries.entrySet().stream()
            .filter(entry -> entry.getValue().id().equals(journalId))
            .findFirst()
            .orElseThrow();
    Entry old = found.getValue();
    found.setValue(
        new Entry(
            old.id(),
            old.war(),
            old.settlement(),
            old.building(),
            old.position(),
            old.originalBlockData(),
            old.damagedBlockData(),
            old.source(),
            old.cause(),
            old.timestamp(),
            state));
  }

  public synchronized Map<Key, Entry> snapshot() {
    return Map.copyOf(entries);
  }

  private static String validateData(String value) {
    String result = Objects.requireNonNull(value).strip();
    if (result.isBlank()) throw new IllegalArgumentException("block data is required");
    return result;
  }

  public enum RepairState {
    UNPLANNED,
    PLANNED,
    PREPARED,
    REPAIRED,
    CONFLICT,
    IGNORED
  }

  public record Key(WarId war, BlockPos position) {}

  public record Entry(
      UUID id,
      WarId war,
      SettlementId settlement,
      BuildingId building,
      BlockPos position,
      String originalBlockData,
      String damagedBlockData,
      UUID source,
      String cause,
      Instant timestamp,
      RepairState repairState) {}
}
