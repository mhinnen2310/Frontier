package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ContractGateway {
  ContractSnapshot postDelivery(
      UUID city,
      UUID actor,
      UUID destinationWarehouse,
      String commodity,
      long quantity,
      long rewardMinor,
      Instant deadline,
      UUID idempotency,
      Instant now);

  ContractSnapshot accept(UUID contract, UUID player, Instant now);

  ContractSnapshot deliver(UUID contract, UUID player, UUID idempotency, Instant now);

  List<ContractSnapshot> available(Instant now);

  record ContractSnapshot(
      UUID id,
      UUID issuer,
      UUID assignee,
      String type,
      String commodity,
      long quantity,
      long rewardMinor,
      String status,
      Instant deadline) {}
}
