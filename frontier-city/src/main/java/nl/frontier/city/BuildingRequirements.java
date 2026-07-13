package nl.frontier.city;

import java.util.Map;

/** Safe, shape-agnostic minimums for one building type. */
public record BuildingRequirements(
    int minimumWidth,
    int minimumHeight,
    int minimumDepth,
    boolean enclosureRequired,
    boolean entranceRequired,
    boolean roadRequired,
    Map<BuildingFeature, Integer> functionalMinimums) {
  public BuildingRequirements {
    functionalMinimums = Map.copyOf(functionalMinimums);
  }
}
