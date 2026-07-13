package nl.frontier.city;

public enum SettlementLevel {
  CAMP(1, 4, 0, 0),
  OUTPOST(2, 12, 0, 1),
  SETTLEMENT(3, 30, 2, 2),
  TOWN(4, 60, 5, 4),
  CITY(5, 120, 10, 8),
  CAPITAL(6, 200, 16, 12);

  private final int level;
  private final int claims;
  private final int builders;
  private final int districts;

  SettlementLevel(int level, int claims, int builders, int districts) {
    this.level = level;
    this.claims = claims;
    this.builders = builders;
    this.districts = districts;
  }

  public int level() {
    return level;
  }

  public int claims() {
    return claims;
  }

  public int builders() {
    return builders;
  }

  public int districts() {
    return districts;
  }

  public SettlementLevel next() {
    if (this == CAPITAL) throw new IllegalStateException("capital is the maximum level");
    return values()[ordinal() + 1];
  }
}
