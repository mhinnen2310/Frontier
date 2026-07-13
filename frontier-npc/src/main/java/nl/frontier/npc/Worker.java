package nl.frontier.npc;

import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Ids.WorkerId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;
import nl.frontier.domain.Money;

/** Authoritative worker state; a Paper entity is only a replaceable presentation. */
public final class Worker {
  public enum Profession {
    BUILDER,
    GUARD,
    MERCHANT,
    FARMER,
    MINER,
    BLACKSMITH,
    COURIER,
    DOCTOR,
    ARCHITECT,
    SCHOLAR,
    STABLE_MASTER,
    LUMBERJACK
  }

  public enum State {
    IDLE,
    CLAIM_TASK,
    FETCH,
    NAVIGATE,
    FACE_TARGET,
    ANIMATE,
    VALIDATE,
    PLACE,
    CONFIRM,
    REST,
    PAUSED,
    DESPAWNED
  }

  private final WorkerId id;
  private final SettlementId home;
  private final Profession profession;
  private final int skill;
  private final Money salary;
  private State state = State.IDLE;
  private UUID task;
  private Instant leaseExpiresAt;
  private UUID presentationEntity;
  private long version;

  public Worker(WorkerId id, SettlementId home, Profession profession, int skill, Money salary) {
    this.id = Objects.requireNonNull(id);
    this.home = Objects.requireNonNull(home);
    this.profession = Objects.requireNonNull(profession);
    this.salary = Objects.requireNonNull(salary);
    if (skill < 1 || skill > 100) throw new IllegalArgumentException("worker skill must be 1-100");
    this.skill = skill;
  }

  public void claim(UUID taskId, Instant leaseUntil) {
    if (state != State.IDLE && state != State.CLAIM_TASK)
      throw new DomainException("worker is unavailable");
    task = Objects.requireNonNull(taskId);
    leaseExpiresAt = Objects.requireNonNull(leaseUntil);
    state = State.FETCH;
    version++;
  }

  public void move(State next) {
    if (task == null && next != State.IDLE && next != State.DESPAWNED)
      throw new DomainException("worker has no task");
    state = Objects.requireNonNull(next);
    version++;
  }

  public void renew(Instant value) {
    if (task == null) throw new DomainException("worker has no lease");
    if (!value.isAfter(leaseExpiresAt)) throw new DomainException("lease must move forward");
    leaseExpiresAt = value;
    version++;
  }

  public void recoverExpired(Instant now) {
    if (task != null && !leaseExpiresAt.isAfter(now)) {
      task = null;
      leaseExpiresAt = null;
      state = State.IDLE;
      version++;
    }
  }

  public void materialized(UUID entityId) {
    presentationEntity = Objects.requireNonNull(entityId);
    version++;
  }

  public void presentationRemoved() {
    presentationEntity = null;
    version++;
  }

  public WorkerId id() {
    return id;
  }

  public SettlementId home() {
    return home;
  }

  public Profession profession() {
    return profession;
  }

  public int skill() {
    return skill;
  }

  public Money salary() {
    return salary;
  }

  public State state() {
    return state;
  }

  public UUID task() {
    return task;
  }

  public UUID presentationEntity() {
    return presentationEntity;
  }

  public long version() {
    return version;
  }
}
