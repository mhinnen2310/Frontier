package nl.frontier.city;

public enum DistrictType {
  RESIDENTIAL(new Bonuses(0, 20, 0, 0, 0, 5, 0)),
  AGRICULTURAL(new Bonuses(20, 0, 0, 0, 0, 5, 0)),
  INDUSTRIAL(new Bonuses(20, 0, 5, 0, 0, 10, 0)),
  COMMERCIAL(new Bonuses(0, 0, 0, 0, 20, 5, 0)),
  MILITARY(new Bonuses(0, 0, 0, 20, 0, 0, 10)),
  GOVERNMENT(new Bonuses(0, 0, 10, 5, 5, 0, 10)),
  LOGISTICS(new Bonuses(5, 0, 5, 0, 10, 10, 5)),
  HARBOR(new Bonuses(5, 0, 0, 0, 20, 5, 0)),
  MINING(new Bonuses(20, 0, 0, 0, 0, 5, 0)),
  FORESTRY(new Bonuses(15, 0, 5, 0, 0, 5, 0)),
  RESEARCH(new Bonuses(5, 0, 0, 0, 0, 20, 0)),
  CULTURE(new Bonuses(0, 10, 0, 0, 5, 10, 0));

  private final Bonuses bonuses;

  DistrictType(Bonuses bonuses) {
    this.bonuses = bonuses;
  }

  public Bonuses bonuses() {
    return bonuses;
  }

  public record Bonuses(
      int production,
      int housing,
      int maintenance,
      int defense,
      int trade,
      int workerEfficiency,
      int repairPriority) {}
}
