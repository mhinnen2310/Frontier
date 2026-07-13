package nl.frontier.domain;

/** Non-negative currency amount stored in minor units. */
public record Money(long cents) implements Comparable<Money> {
  public static final Money ZERO = new Money(0);

  public Money {
    if (cents < 0) throw new IllegalArgumentException("money cannot be negative");
  }

  public Money plus(Money other) {
    return new Money(Math.addExact(cents, other.cents));
  }

  public Money minus(Money other) {
    if (other.cents > cents) throw new IllegalStateException("insufficient funds");
    return new Money(Math.subtractExact(cents, other.cents));
  }

  public Money multiply(long quantity) {
    if (quantity < 0) throw new IllegalArgumentException("quantity cannot be negative");
    return new Money(Math.multiplyExact(cents, quantity));
  }

  @Override
  public int compareTo(Money other) {
    return Long.compare(cents, other.cents);
  }
}
