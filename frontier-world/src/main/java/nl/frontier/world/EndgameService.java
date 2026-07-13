package nl.frontier.world;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class EndgameService {
  private final EndgameGateway gateway;

  public EndgameService(EndgameGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public List<EndgameGateway.Definition> catalog() {
    return gateway.catalog();
  }

  public List<EndgameGateway.Ranking> rankings(int limit) {
    if (limit < 1 || limit > 100) throw new DomainException("ranking limit must be 1-100");
    return gateway.rankings(limit);
  }

  public List<EndgameGateway.HistoryEntry> history(int limit) {
    if (limit < 1 || limit > 200) throw new DomainException("history limit must be 1-200");
    return gateway.worldHistory(limit);
  }

  public List<String> unlocks(UUID kingdom) {
    return gateway.unlocks(kingdom);
  }
}
