package nl.frontier.city;

import static nl.frontier.domain.Ids.PlayerId;
import static nl.frontier.domain.Ids.SettlementId;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import nl.frontier.domain.DomainException;

/** Settlement aggregate. Mutations are persisted with its optimistic {@link #version() version}. */
public final class Settlement {
  private final SettlementId id;
  private final Instant createdAt;
  private final Map<PlayerId, GovernmentRole> members = new HashMap<>();
  private String name;
  private PlayerId owner;
  private SettlementLevel level;
  private int population;
  private int prosperity;
  private int civilization;
  private long version;

  public Settlement(SettlementId id, String name, PlayerId owner, Instant createdAt) {
    this.id = Objects.requireNonNull(id);
    this.name = validateName(name);
    this.owner = Objects.requireNonNull(owner);
    this.createdAt = Objects.requireNonNull(createdAt);
    level = SettlementLevel.CAMP;
    population = 1;
    prosperity = 50;
    civilization = 1;
    members.put(owner, GovernmentRole.MAYOR);
  }

  public void addMember(PlayerId actor, PlayerId player, GovernmentRole role) {
    require(actor, CityPermission.INVITE_MEMBER);
    if (members.putIfAbsent(player, role) != null)
      throw new DomainException("player is already a member");
    version++;
  }

  public void changeRole(PlayerId actor, PlayerId player, GovernmentRole role) {
    require(actor, CityPermission.PROMOTE_MEMBER);
    if (player.equals(owner) && role != GovernmentRole.MAYOR)
      throw new DomainException("owner must remain mayor");
    if (!members.containsKey(player)) throw new DomainException("player is not a member");
    members.put(player, Objects.requireNonNull(role));
    version++;
  }

  public void removeMember(PlayerId actor, PlayerId player) {
    require(actor, CityPermission.REMOVE_MEMBER);
    if (player.equals(owner)) throw new DomainException("owner cannot be removed");
    if (members.remove(player) == null) throw new DomainException("player is not a member");
    version++;
  }

  public void transferOwnership(PlayerId actor, PlayerId newOwner) {
    if (!actor.equals(owner)) throw new DomainException("only the owner can transfer ownership");
    if (!members.containsKey(newOwner)) throw new DomainException("new owner must be a member");
    members.put(owner, GovernmentRole.CITIZEN);
    members.put(newOwner, GovernmentRole.MAYOR);
    owner = newOwner;
    version++;
  }

  public void upgrade(PlayerId actor, UpgradeEvidence evidence) {
    require(actor, CityPermission.EDIT_SETTINGS);
    SettlementLevel next = level.next();
    if (evidence.population() < requiredPopulation(next)
        || evidence.prosperity() < 50
        || evidence.civilization() < next.level() * 10
        || !evidence.requiredBuildingsPresent()) {
      throw new DomainException("upgrade requirements are not met");
    }
    level = next;
    version++;
  }

  public void applySimulation(int populationDelta, int prosperityDelta, int civilizationDelta) {
    population = Math.max(0, Math.addExact(population, populationDelta));
    prosperity = clamp(Math.addExact(prosperity, prosperityDelta));
    civilization = clamp(Math.addExact(civilization, civilizationDelta));
    version++;
  }

  public boolean can(PlayerId player, CityPermission permission) {
    GovernmentRole role = members.get(player);
    return role != null && role.permissions().contains(permission);
  }

  public void require(PlayerId player, CityPermission permission) {
    if (!can(player, permission))
      throw new DomainException("missing city permission " + permission);
  }

  private static int requiredPopulation(SettlementLevel level) {
    return switch (level) {
      case CAMP -> 1;
      case OUTPOST -> 10;
      case SETTLEMENT -> 25;
      case TOWN -> 60;
      case CITY -> 120;
      case CAPITAL -> 250;
    };
  }

  private static int clamp(int value) {
    return Math.max(0, Math.min(100, value));
  }

  private static String validateName(String value) {
    String clean = Objects.requireNonNull(value).strip();
    if (clean.length() < 3 || clean.length() > 32)
      throw new IllegalArgumentException("name must contain 3-32 characters");
    return clean;
  }

  public SettlementId id() {
    return id;
  }

  public String name() {
    return name;
  }

  public PlayerId owner() {
    return owner;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public SettlementLevel level() {
    return level;
  }

  public int population() {
    return population;
  }

  public int prosperity() {
    return prosperity;
  }

  public int civilization() {
    return civilization;
  }

  public long version() {
    return version;
  }

  public Map<PlayerId, GovernmentRole> members() {
    return Map.copyOf(members);
  }

  public record UpgradeEvidence(
      int population, int prosperity, int civilization, boolean requiredBuildingsPresent) {}
}
