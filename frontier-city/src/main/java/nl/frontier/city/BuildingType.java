package nl.frontier.city;

public enum BuildingType {
  TOWN_HALL(Building.Category.GOVERNMENT),
  WAREHOUSE(Building.Category.ECONOMIC),
  HOUSING(Building.Category.RESIDENTIAL),
  FARM(Building.Category.AGRICULTURE),
  BUILDER_GUILD(Building.Category.INDUSTRY),
  MARKET(Building.Category.ECONOMIC),
  BARRACKS(Building.Category.MILITARY),
  WORKSHOP(Building.Category.INDUSTRY),
  MINE_ENTRANCE(Building.Category.INDUSTRY),
  WATCHTOWER(Building.Category.MILITARY);

  private final Building.Category category;

  BuildingType(Building.Category category) {
    this.category = category;
  }

  public Building.Category category() {
    return category;
  }
}
