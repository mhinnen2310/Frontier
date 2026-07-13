package nl.frontier.repair;

import static nl.frontier.domain.Ids.RepairOrderId;
import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Ids.WarId;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import nl.frontier.domain.DomainException;
import nl.frontier.domain.Money;

public final class RepairOrder {
  public enum Status {
    DRAFT,
    WAITING_PAYMENT,
    REGISTERED,
    RESERVED,
    REPAIRING,
    QUEUED,
    ACTIVE,
    PAUSED_UNSAFE,
    PAUSED_MATERIAL,
    REVIEW_REQUIRED,
    COMPLETED,
    ARCHIVED,
    CANCELLED
  }

  public enum Priority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW,
    COSMETIC
  }

  private static final Map<Status, Set<Status>> TRANSITIONS =
      Map.ofEntries(
          Map.entry(Status.DRAFT, EnumSet.of(Status.WAITING_PAYMENT, Status.CANCELLED)),
          Map.entry(
              Status.WAITING_PAYMENT,
              EnumSet.of(Status.REGISTERED, Status.QUEUED, Status.CANCELLED)),
          Map.entry(
              Status.REGISTERED,
              EnumSet.of(Status.RESERVED, Status.PAUSED_MATERIAL, Status.CANCELLED)),
          Map.entry(
              Status.RESERVED,
              EnumSet.of(Status.REPAIRING, Status.PAUSED_MATERIAL, Status.CANCELLED)),
          Map.entry(
              Status.REPAIRING,
              EnumSet.of(
                  Status.PAUSED_UNSAFE,
                  Status.PAUSED_MATERIAL,
                  Status.REVIEW_REQUIRED,
                  Status.COMPLETED,
                  Status.CANCELLED)),
          Map.entry(
              Status.QUEUED, EnumSet.of(Status.ACTIVE, Status.PAUSED_MATERIAL, Status.CANCELLED)),
          Map.entry(
              Status.ACTIVE,
              EnumSet.of(
                  Status.PAUSED_UNSAFE,
                  Status.PAUSED_MATERIAL,
                  Status.REVIEW_REQUIRED,
                  Status.COMPLETED,
                  Status.CANCELLED)),
          Map.entry(Status.PAUSED_UNSAFE, EnumSet.of(Status.ACTIVE, Status.CANCELLED)),
          Map.entry(
              Status.PAUSED_MATERIAL,
              EnumSet.of(Status.QUEUED, Status.ACTIVE, Status.RESERVED, Status.CANCELLED)),
          Map.entry(
              Status.REVIEW_REQUIRED,
              EnumSet.of(Status.ACTIVE, Status.REPAIRING, Status.CANCELLED)),
          Map.entry(Status.COMPLETED, EnumSet.of(Status.ARCHIVED)),
          Map.entry(Status.ARCHIVED, EnumSet.noneOf(Status.class)),
          Map.entry(Status.CANCELLED, EnumSet.noneOf(Status.class)));

  private final RepairOrderId id;
  private final SettlementId settlement;
  private final WarId war;
  private final Money estimate;
  private final int totalTasks;
  private Priority priority;
  private Status status = Status.DRAFT;
  private int completedTasks;
  private long version;

  public RepairOrder(
      RepairOrderId id,
      SettlementId settlement,
      WarId war,
      Money estimate,
      int totalTasks,
      Priority priority) {
    this.id = Objects.requireNonNull(id);
    this.settlement = Objects.requireNonNull(settlement);
    this.war = Objects.requireNonNull(war);
    this.estimate = Objects.requireNonNull(estimate);
    this.priority = Objects.requireNonNull(priority);
    if (totalTasks <= 0) throw new IllegalArgumentException("repair requires tasks");
    this.totalTasks = totalTasks;
  }

  public void requestPayment() {
    move(Status.WAITING_PAYMENT);
  }

  public void paymentAndMaterialsCommitted() {
    move(Status.QUEUED);
  }

  public void register() {
    move(Status.REGISTERED);
  }

  public void reserve() {
    move(Status.RESERVED);
  }

  public void beginRepair() {
    move(Status.REPAIRING);
  }

  public void activate() {
    move(Status.ACTIVE);
  }

  public void pauseUnsafe() {
    move(Status.PAUSED_UNSAFE);
  }

  public void pauseMaterial() {
    move(Status.PAUSED_MATERIAL);
  }

  public void reviewConflict() {
    move(Status.REVIEW_REQUIRED);
  }

  public void cancel() {
    move(Status.CANCELLED);
  }

  public void completeTask() {
    if (status != Status.ACTIVE && status != Status.REPAIRING)
      throw new DomainException("tasks require an active repair");
    completedTasks = Math.addExact(completedTasks, 1);
    if (completedTasks > totalTasks) throw new DomainException("repair progress exceeds plan");
    version++;
    if (completedTasks == totalTasks) move(Status.COMPLETED);
  }

  public void archive() {
    move(Status.ARCHIVED);
  }

  public void reprioritize(Priority value) {
    if (status == Status.COMPLETED || status == Status.CANCELLED)
      throw new DomainException("closed order cannot be reprioritized");
    priority = Objects.requireNonNull(value);
    version++;
  }

  private void move(Status target) {
    if (!TRANSITIONS.get(status).contains(target))
      throw new DomainException("invalid repair transition " + status + " -> " + target);
    status = target;
    version++;
  }

  public RepairOrderId id() {
    return id;
  }

  public SettlementId settlement() {
    return settlement;
  }

  public WarId war() {
    return war;
  }

  public Money estimate() {
    return estimate;
  }

  public Priority priority() {
    return priority;
  }

  public Status status() {
    return status;
  }

  public int totalTasks() {
    return totalTasks;
  }

  public int completedTasks() {
    return completedTasks;
  }

  public long version() {
    return version;
  }
}
