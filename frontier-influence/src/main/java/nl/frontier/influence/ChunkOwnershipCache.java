package nl.frontier.influence;

import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Position.ChunkPos;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** O(1) hot-path ownership lookup; only committed database deltas update this cache. */
public final class ChunkOwnershipCache {
  private final Map<ChunkPos, Entry> entries = new ConcurrentHashMap<>();

  public Optional<Entry> get(ChunkPos position) {
    return Optional.ofNullable(entries.get(position));
  }

  public void replace(Map<ChunkPos, Entry> snapshot) {
    entries.clear();
    entries.putAll(snapshot);
  }

  public void apply(Map<ChunkPos, Entry> deltas) {
    deltas.forEach(
        (key, value) -> {
          if (value.state() == TerritoryState.WILDERNESS) entries.remove(key);
          else entries.put(key, value);
        });
  }

  public int size() {
    return entries.size();
  }

  public record Entry(SettlementId owner, TerritoryState state) {}
}
