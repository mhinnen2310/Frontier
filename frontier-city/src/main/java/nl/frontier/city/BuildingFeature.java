package nl.frontier.city;

import java.util.Map;

/** Functional block groups used by configurable building definitions. */
public enum BuildingFeature {
  STORAGE("storage blocks"),
  BED("beds"),
  FARMLAND("farmland blocks"),
  WATER("water blocks"),
  CROP("planted crops"),
  CRAFTING("crafting stations"),
  STALL("market stalls"),
  LIGHT("light sources"),
  INTERIOR_AIR("interior air blocks"),
  BELL("bells"),
  LECTERN("lecterns"),
  SEATING("seats"),
  FURNACE("furnaces"),
  RAIL("rail blocks"),
  LADDER("ladders or scaffolding");

  private final String displayName;

  BuildingFeature(String displayName) {
    this.displayName = displayName;
  }

  public String configKey() {
    return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }

  public String displayName() {
    return displayName;
  }

  public int count(BuildingSurvey survey) {
    return switch (this) {
      case STORAGE -> survey.storageBlocks();
      case BED -> survey.bedBlocks();
      case FARMLAND -> survey.farmlandBlocks();
      case WATER -> survey.waterBlocks();
      case CROP -> survey.cropBlocks();
      case CRAFTING -> survey.craftingBlocks();
      case STALL -> survey.stallBlocks();
      case LIGHT -> survey.lightBlocks();
      case INTERIOR_AIR -> survey.interiorAirBlocks();
      case BELL -> material(survey.materials(), "minecraft:bell");
      case LECTERN -> material(survey.materials(), "minecraft:lectern");
      case SEATING -> suffix(survey.materials(), "_stairs");
      case FURNACE ->
          material(survey.materials(), "minecraft:furnace")
              + material(survey.materials(), "minecraft:blast_furnace")
              + material(survey.materials(), "minecraft:smoker");
      case RAIL -> suffix(survey.materials(), "rail");
      case LADDER ->
          material(survey.materials(), "minecraft:ladder")
              + material(survey.materials(), "minecraft:scaffolding");
    };
  }

  private static int material(Map<String, Integer> materials, String key) {
    return materials.getOrDefault(key, 0);
  }

  private static int suffix(Map<String, Integer> materials, String suffix) {
    return materials.entrySet().stream()
        .filter(entry -> entry.getKey().endsWith(suffix))
        .mapToInt(Map.Entry::getValue)
        .sum();
  }
}
