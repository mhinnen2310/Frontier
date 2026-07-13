package nl.frontier.city;

import java.util.Map;

public record BuildingSurvey(
    int width,
    int height,
    int depth,
    int nonAirBlocks,
    int interiorAirBlocks,
    int floorBlocks,
    int wallBlocks,
    int roofBlocks,
    int entranceBlocks,
    int lightBlocks,
    int storageBlocks,
    int bedBlocks,
    int farmlandBlocks,
    int waterBlocks,
    int cropBlocks,
    int craftingBlocks,
    int stallBlocks,
    int roadBlocks,
    Map<String, Integer> materials) {
  public BuildingSurvey {
    materials = Map.copyOf(materials);
  }

  public int footprint() {
    return Math.multiplyExact(width, depth);
  }

  public int volume() {
    return Math.multiplyExact(footprint(), height);
  }

  public double roofCoverage() {
    return footprint() == 0 ? 0 : (double) roofBlocks / footprint();
  }
}
