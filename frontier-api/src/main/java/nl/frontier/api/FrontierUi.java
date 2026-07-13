package nl.frontier.api;

import static nl.frontier.domain.Ids.PlayerId;
import static nl.frontier.domain.Ids.RepairOrderId;
import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Ids.WarId;

public interface FrontierUi {
  void openSettlement(PlayerId player, SettlementId settlement);

  void openTreasury(PlayerId player, SettlementId settlement);

  void openWar(PlayerId player, WarId war);

  void openRepair(PlayerId player, RepairOrderId repair);

  void openMarket(PlayerId player, SettlementId settlement);

  void openDistrict(PlayerId player, java.util.UUID district, String view, String summary);
}
