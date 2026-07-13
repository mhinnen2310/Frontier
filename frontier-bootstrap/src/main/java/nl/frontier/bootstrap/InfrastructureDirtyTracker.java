package nl.frontier.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import nl.frontier.economy.InfrastructureGateway;

/**
 * Bounded, deduplicating hand-off from Paper events to the transactional infrastructure service.
 */
final class InfrastructureDirtyTracker {
  private final int maximumQueue;
  private final LinkedHashMap<Key, InfrastructureGateway.ChangedBlock> pending =
      new LinkedHashMap<>();

  InfrastructureDirtyTracker(int maximumQueue) {
    if (maximumQueue < 1) throw new IllegalArgumentException("maximumQueue must be positive");
    this.maximumQueue = maximumQueue;
  }

  synchronized boolean offer(UUID world, int x, int y, int z, String reason) {
    Key key = new Key(world, x, y, z);
    if (!pending.containsKey(key) && pending.size() >= maximumQueue) return false;
    pending.put(key, new InfrastructureGateway.ChangedBlock(world, x, y, z, reason));
    return true;
  }

  synchronized List<InfrastructureGateway.ChangedBlock> drain(int maximum) {
    int count = Math.min(maximum, pending.size());
    List<InfrastructureGateway.ChangedBlock> changes = new ArrayList<>(count);
    var iterator = pending.entrySet().iterator();
    while (iterator.hasNext() && changes.size() < count) {
      changes.add(iterator.next().getValue());
      iterator.remove();
    }
    return List.copyOf(changes);
  }

  synchronized int size() {
    return pending.size();
  }

  private record Key(UUID world, int x, int y, int z) {}
}
