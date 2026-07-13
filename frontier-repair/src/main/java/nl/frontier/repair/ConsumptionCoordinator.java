package nl.frontier.repair;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import nl.frontier.domain.DomainException;

/** Two-phase stock-to-world protocol used to reconcile a crash around block placement. */
public final class ConsumptionCoordinator {
  private final Map<UUID, State> states = new HashMap<>();

  public synchronized State prepare(UUID receipt) {
    State current = states.get(receipt);
    if (current == State.COMMITTED) return current;
    if (current != null) throw new DomainException("receipt is already prepared");
    states.put(receipt, State.PREPARED);
    return State.PREPARED;
  }

  public synchronized State commit(UUID receipt, boolean targetBlockPresent) {
    State current = states.get(receipt);
    if (current == State.COMMITTED) return current;
    if (current != State.PREPARED || !targetBlockPresent)
      throw new DomainException("cannot commit material consumption");
    states.put(receipt, State.COMMITTED);
    return State.COMMITTED;
  }

  public synchronized State release(UUID receipt, boolean targetBlockPresent) {
    if (targetBlockPresent)
      throw new DomainException("placed block requires committed consumption");
    State current = states.get(receipt);
    if (current == State.COMMITTED)
      throw new DomainException("committed consumption cannot be released");
    states.put(receipt, State.RELEASED);
    return State.RELEASED;
  }

  public enum State {
    PREPARED,
    COMMITTED,
    RELEASED
  }
}
