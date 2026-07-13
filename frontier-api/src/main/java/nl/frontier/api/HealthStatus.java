package nl.frontier.api;

import java.util.Map;

public record HealthStatus(boolean healthy, Map<String, String> components) {
  public HealthStatus {
    components = Map.copyOf(components);
  }
}
