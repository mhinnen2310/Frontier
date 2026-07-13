package nl.frontier.economy;

import java.util.List;

public record InfrastructureSurvey(
    int samples,
    int connectedSamples,
    int minimumWidth,
    int bridgeSamples,
    int tunnelSamples,
    int surfaceQuality,
    double maximumSlope,
    int brokenSegments,
    int destroyedBridges,
    boolean endpointsConnected,
    int gateSamples,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ,
    List<RoutePoint> route) {
  public InfrastructureSurvey {
    route = List.copyOf(route);
    if (samples < 0 || connectedSamples < 0 || connectedSamples > samples)
      throw new IllegalArgumentException("invalid infrastructure sample counts");
    if (minimumWidth < 0
        || bridgeSamples < 0
        || tunnelSamples < 0
        || brokenSegments < 0
        || destroyedBridges < 0
        || gateSamples < 0)
      throw new IllegalArgumentException("infrastructure observations cannot be negative");
    if (surfaceQuality < 0 || surfaceQuality > 100)
      throw new IllegalArgumentException("surfaceQuality must be 0-100");
    if (!Double.isFinite(maximumSlope) || maximumSlope < 0)
      throw new IllegalArgumentException("maximumSlope must be finite and non-negative");
    if (minX > maxX || minY > maxY || minZ > maxZ)
      throw new IllegalArgumentException("infrastructure route bounds are inverted");
  }

  public InfrastructureSurvey(
      int samples,
      int connectedSamples,
      int minimumWidth,
      int bridgeSamples,
      int tunnelSamples,
      int surfaceQuality,
      double maximumSlope,
      int brokenSegments,
      int destroyedBridges,
      boolean endpointsConnected,
      int gateSamples,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ) {
    this(
        samples,
        connectedSamples,
        minimumWidth,
        bridgeSamples,
        tunnelSamples,
        surfaceQuality,
        maximumSlope,
        brokenSegments,
        destroyedBridges,
        endpointsConnected,
        gateSamples,
        minX,
        minY,
        minZ,
        maxX,
        maxY,
        maxZ,
        List.of());
  }

  public InfrastructureSurvey(
      int samples,
      int connectedSamples,
      int minimumWidth,
      int bridgeSamples,
      int tunnelSamples,
      int surfaceQuality,
      double maximumSlope,
      int brokenSegments,
      int destroyedBridges) {
    this(
        samples,
        connectedSamples,
        minimumWidth,
        bridgeSamples,
        tunnelSamples,
        surfaceQuality,
        maximumSlope,
        brokenSegments,
        destroyedBridges,
        true,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        List.of());
  }

  public double connectivity() {
    return samples == 0 ? 0 : (double) connectedSamples / samples;
  }

  public record RoutePoint(int sequence, int x, int y, int z, String targetData) {
    public RoutePoint {
      if (sequence < 0) throw new IllegalArgumentException("route sequence cannot be negative");
      targetData = java.util.Objects.requireNonNull(targetData);
    }

    public RoutePoint(int sequence, int x, int y, int z) {
      this(sequence, x, y, z, "minecraft:stone_bricks");
    }
  }
}
