package nl.frontier.api;

import java.time.Instant;

public interface OutboxDispatcher {
  DispatchReport dispatch(int maximum, Instant now);

  record DispatchReport(int published, int remaining, long oldestLagSeconds) {}
}
