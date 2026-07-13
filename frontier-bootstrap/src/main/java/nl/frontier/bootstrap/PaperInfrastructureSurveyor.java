package nl.frontier.bootstrap;

import java.util.Map;
import java.util.Set;
import nl.frontier.economy.InfrastructureGateway;
import nl.frontier.economy.InfrastructureSurvey;
import org.bukkit.Bukkit;
import org.bukkit.Material;

final class PaperInfrastructureSurveyor {
  private static final Map<Material, Integer> SURFACES =
      Map.of(
          Material.STONE_BRICKS, 100,
          Material.COBBLESTONE, 80,
          Material.ANDESITE, 75,
          Material.GRAVEL, 60,
          Material.DIRT_PATH, 50,
          Material.PACKED_MUD, 65);
  private static final Set<Material> WATER = Set.of(Material.WATER, Material.BUBBLE_COLUMN);

  InfrastructureSurvey survey(InfrastructureGateway.Context context) {
    var from = context.from();
    var to = context.to();
    var world = Bukkit.getWorld(from.world());
    if (world == null) throw new IllegalStateException("infrastructure world is not loaded");
    int deltaX = to.x() - from.x();
    int deltaZ = to.z() - from.z();
    int samples = Math.max(Math.abs(deltaX), Math.abs(deltaZ)) + 1;
    if (samples > 257)
      throw new IllegalArgumentException("infrastructure survey exceeds 256 blocks");
    int connected = 0;
    int minimumWidth = Integer.MAX_VALUE;
    int bridges = 0;
    int tunnels = 0;
    int quality = 0;
    int broken = 0;
    int destroyedBridges = 0;
    Integer previousY = null;
    double maximumSlope = 0;
    boolean perpendicularX = Math.abs(deltaZ) >= Math.abs(deltaX);
    for (int index = 0; index < samples; index++) {
      double fraction = samples == 1 ? 0 : (double) index / (samples - 1);
      int x = (int) Math.round(from.x() + deltaX * fraction);
      int z = (int) Math.round(from.z() + deltaZ * fraction);
      int expectedY = (int) Math.round(from.y() + (to.y() - from.y()) * fraction);
      int y = surfaceY(world, x, expectedY, z);
      if (y == Integer.MIN_VALUE) {
        broken++;
        Material below = world.getBlockAt(x, expectedY - 1, z).getType();
        if (below.isAir() || WATER.contains(below)) destroyedBridges++;
        continue;
      }
      connected++;
      Material material = world.getBlockAt(x, y, z).getType();
      quality += SURFACES.getOrDefault(material, 0);
      minimumWidth = Math.min(minimumWidth, width(world, x, y, z, perpendicularX));
      Material below = world.getBlockAt(x, y - 1, z).getType();
      if (below.isAir() || WATER.contains(below)) bridges++;
      if (world.getBlockAt(x, y + 2, z).getType().isSolid()) tunnels++;
      if (previousY != null) maximumSlope = Math.max(maximumSlope, Math.abs(y - previousY));
      previousY = y;
    }
    return new InfrastructureSurvey(
        samples,
        connected,
        minimumWidth == Integer.MAX_VALUE ? 0 : minimumWidth,
        bridges,
        tunnels,
        connected == 0 ? 0 : quality / connected,
        maximumSlope,
        broken,
        destroyedBridges);
  }

  private static int surfaceY(org.bukkit.World world, int x, int expectedY, int z) {
    for (int offset = 0; offset <= 3; offset++) {
      int above = expectedY + offset;
      if (SURFACES.containsKey(world.getBlockAt(x, above, z).getType())) return above;
      int below = expectedY - offset;
      if (offset > 0 && SURFACES.containsKey(world.getBlockAt(x, below, z).getType())) return below;
    }
    return Integer.MIN_VALUE;
  }

  private static int width(org.bukkit.World world, int x, int y, int z, boolean perpendicularX) {
    int width = 1;
    for (int direction : new int[] {-1, 1}) {
      for (int offset = 1; offset <= 8; offset++) {
        int testX = perpendicularX ? x + direction * offset : x;
        int testZ = perpendicularX ? z : z + direction * offset;
        if (!SURFACES.containsKey(world.getBlockAt(testX, y, testZ).getType())) break;
        width++;
      }
    }
    return width;
  }
}
