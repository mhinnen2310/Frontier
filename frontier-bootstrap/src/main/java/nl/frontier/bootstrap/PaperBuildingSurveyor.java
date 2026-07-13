package nl.frontier.bootstrap;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import nl.frontier.city.BuildingSurvey;
import nl.frontier.city.BuildingValidationPolicy;
import nl.frontier.city.SettlementGateway;
import org.bukkit.Bukkit;
import org.bukkit.Material;

final class PaperBuildingSurveyor {
  private static final Set<Material> STORAGE =
      Set.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);
  private static final Set<Material> WATER = Set.of(Material.WATER, Material.BUBBLE_COLUMN);
  private static final Set<Material> CROPS =
      Set.of(
          Material.WHEAT,
          Material.CARROTS,
          Material.POTATOES,
          Material.BEETROOTS,
          Material.NETHER_WART,
          Material.MELON_STEM,
          Material.PUMPKIN_STEM);
  private static final Set<Material> CRAFTING =
      Set.of(
          Material.CRAFTING_TABLE,
          Material.STONECUTTER,
          Material.ANVIL,
          Material.CHIPPED_ANVIL,
          Material.DAMAGED_ANVIL,
          Material.SMITHING_TABLE);
  private static final Set<Material> STALLS =
      Set.of(Material.BARREL, Material.LECTERN, Material.CAULDRON);
  private static final Set<Material> ROAD =
      Set.of(
          Material.DIRT_PATH,
          Material.GRAVEL,
          Material.COBBLESTONE,
          Material.STONE_BRICKS,
          Material.ANDESITE);
  private final BuildingValidationPolicy policy;

  PaperBuildingSurveyor(BuildingValidationPolicy policy) {
    this.policy = policy;
  }

  BuildingSurvey survey(SettlementGateway.Bounds bounds) {
    var world = Bukkit.getWorld(bounds.world());
    if (world == null) throw new IllegalStateException("building world is not loaded");
    int width = bounds.maxX() - bounds.minX() + 1;
    int height = bounds.maxY() - bounds.minY() + 1;
    int depth = bounds.maxZ() - bounds.minZ() + 1;
    long volume = Math.multiplyExact(Math.multiplyExact((long) width, height), depth);
    if (width <= 0
        || height <= 0
        || depth <= 0
        || width > policy.maximumWidth()
        || height > policy.maximumHeight()
        || depth > policy.maximumDepth()
        || volume > policy.maximumVolume())
      throw new IllegalArgumentException("building selection exceeds configured scan bounds");
    int nonAir = 0;
    int interiorAir = 0;
    int floor = 0;
    int walls = 0;
    int roof = 0;
    int entrances = 0;
    int lights = 0;
    int storage = 0;
    int beds = 0;
    int farmland = 0;
    int water = 0;
    int crops = 0;
    int crafting = 0;
    int stalls = 0;
    int roads = 0;
    Map<String, Integer> materials = new HashMap<>();
    for (int x = bounds.minX() - 1; x <= bounds.maxX() + 1; x++) {
      for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
        for (int z = bounds.minZ() - 1; z <= bounds.maxZ() + 1; z++) {
          boolean inside =
              x >= bounds.minX() && x <= bounds.maxX() && z >= bounds.minZ() && z <= bounds.maxZ();
          Material material = world.getBlockAt(x, y, z).getType();
          if (!inside) {
            if (ROAD.contains(material)) roads++;
            continue;
          }
          materials.merge(material.getKey().asString(), 1, Integer::sum);
          if (!material.isAir()) nonAir++;
          if (y == bounds.minY() && material.isSolid()) floor++;
          boolean wall =
              y > bounds.minY()
                  && y < bounds.maxY()
                  && (x == bounds.minX()
                      || x == bounds.maxX()
                      || z == bounds.minZ()
                      || z == bounds.maxZ());
          if (wall && material.isSolid()) walls++;
          if (y == bounds.maxY() && !material.isAir()) roof++;
          boolean interior =
              x > bounds.minX()
                  && x < bounds.maxX()
                  && y > bounds.minY()
                  && y < bounds.maxY()
                  && z > bounds.minZ()
                  && z < bounds.maxZ();
          if (interior && material.isAir()) interiorAir++;
          String name = material.name();
          if (name.contains("DOOR") || name.contains("FENCE_GATE")) entrances++;
          if (name.contains("TORCH")
              || name.contains("LANTERN")
              || name.contains("GLOWSTONE")
              || name.contains("SHROOMLIGHT")) lights++;
          if (STORAGE.contains(material)) storage++;
          if (name.endsWith("_BED")) beds++;
          if (material == Material.FARMLAND) farmland++;
          if (WATER.contains(material)) water++;
          if (CROPS.contains(material)) crops++;
          if (CRAFTING.contains(material)) crafting++;
          if (STALLS.contains(material)) stalls++;
        }
      }
    }
    return new BuildingSurvey(
        width,
        height,
        depth,
        nonAir,
        interiorAir,
        floor,
        walls,
        roof,
        entrances,
        lights,
        storage,
        beds,
        farmland,
        water,
        crops,
        crafting,
        stalls,
        roads,
        materials);
  }
}
