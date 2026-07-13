package nl.frontier.economy;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record InfrastructureValidationPolicy(
    int maximumLength,
    int corridorRadius,
    int verticalTolerance,
    int maximumSnapshotColumns,
    int minimumConnectivityPercent,
    int minimumWidth,
    int minimumSurfaceQuality,
    double maximumSlope,
    int maximumBrokenPercent,
    int minimumBridgeSamples,
    int minimumTunnelSamples,
    int minimumGateSamples,
    Map<String, Integer> surfaceQualities,
    Set<String> gateMaterials) {
  public InfrastructureValidationPolicy {
    if (maximumLength < 1 || maximumLength > 2_048)
      throw new IllegalArgumentException("maximumLength must be 1-2048");
    if (corridorRadius < 1 || corridorRadius > 16)
      throw new IllegalArgumentException("corridorRadius must be 1-16");
    if (verticalTolerance < 1 || verticalTolerance > 16)
      throw new IllegalArgumentException("verticalTolerance must be 1-16");
    if (maximumSnapshotColumns < 32 || maximumSnapshotColumns > 1_000_000)
      throw new IllegalArgumentException("maximumSnapshotColumns must be 32-1000000");
    if (minimumConnectivityPercent < 1 || minimumConnectivityPercent > 100)
      throw new IllegalArgumentException("minimumConnectivityPercent must be 1-100");
    if (minimumWidth < 1 || minimumWidth > 32)
      throw new IllegalArgumentException("minimumWidth must be 1-32");
    if (minimumSurfaceQuality < 1 || minimumSurfaceQuality > 100)
      throw new IllegalArgumentException("minimumSurfaceQuality must be 1-100");
    if (!Double.isFinite(maximumSlope) || maximumSlope <= 0 || maximumSlope > 16)
      throw new IllegalArgumentException("maximumSlope must be greater than 0 and at most 16");
    if (maximumBrokenPercent < 0 || maximumBrokenPercent > 100)
      throw new IllegalArgumentException("maximumBrokenPercent must be 0-100");
    if (minimumBridgeSamples < 1 || minimumTunnelSamples < 1 || minimumGateSamples < 1)
      throw new IllegalArgumentException("physical sample minimums must be positive");
    if (surfaceQualities == null || surfaceQualities.isEmpty())
      throw new IllegalArgumentException("at least one road surface profile is required");
    java.util.LinkedHashMap<String, Integer> normalized = new java.util.LinkedHashMap<>();
    surfaceQualities.forEach(
        (material, quality) -> {
          String key = normalize(material);
          if (key.isBlank() || quality == null || quality < 1 || quality > 100)
            throw new IllegalArgumentException("surface quality must be 1-100");
          normalized.put(key, quality);
        });
    surfaceQualities = Map.copyOf(normalized);
    if (gateMaterials == null || gateMaterials.isEmpty())
      throw new IllegalArgumentException("at least one gate material is required");
    gateMaterials =
        gateMaterials.stream()
            .map(InfrastructureValidationPolicy::normalize)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public int quality(String material) {
    return surfaceQualities.getOrDefault(normalize(material), 0);
  }

  public boolean gate(String material) {
    return gateMaterials.contains(normalize(material));
  }

  public static InfrastructureValidationPolicy defaults() {
    return new InfrastructureValidationPolicy(
        256,
        6,
        3,
        65_536,
        85,
        2,
        40,
        1.5,
        10,
        2,
        2,
        1,
        Map.of(
            "STONE_BRICKS", 100,
            "COBBLESTONE", 80,
            "ANDESITE", 75,
            "GRAVEL", 60,
            "DIRT_PATH", 50,
            "PACKED_MUD", 65),
        Set.of("IRON_BARS", "OAK_FENCE_GATE", "IRON_DOOR"));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
  }
}
