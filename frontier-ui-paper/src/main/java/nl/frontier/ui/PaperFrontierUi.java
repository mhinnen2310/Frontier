package nl.frontier.ui;

import static nl.frontier.domain.Ids.PlayerId;
import static nl.frontier.domain.Ids.RepairOrderId;
import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Ids.WarId;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import nl.frontier.api.FrontierUi;
import org.bukkit.Server;
import org.bukkit.entity.Player;

@SuppressWarnings("UnstableApiUsage")
public final class PaperFrontierUi implements FrontierUi {
  private final Server server;
  private final ActionTokenRegistry actionTokens = new ActionTokenRegistry();

  public PaperFrontierUi(Server server) {
    this.server = Objects.requireNonNull(server);
  }

  @Override
  public void openSettlement(PlayerId player, SettlementId settlement) {
    open(
        player,
        "Settlement",
        "Settlement " + settlement.value(),
        List.of(
            command("Overview", "frontier city info"),
            command("Treasury", "frontier treasury"),
            oneUse(player, settlement.value(), "Claim this chunk", "frontier city claim"),
            oneUse(player, settlement.value(), "Upgrade", "frontier city upgrade")));
  }

  @Override
  public void openTreasury(PlayerId player, SettlementId settlement) {
    open(
        player,
        "Treasury",
        "Audited treasury for " + settlement.value(),
        List.of(command("Refresh", "frontier treasury")));
  }

  @Override
  public void openWar(PlayerId player, WarId war) {
    open(
        player,
        "Campaign",
        "Campaign " + war.value(),
        List.of(command("Status", "frontier war status")));
  }

  @Override
  public void openRepair(PlayerId player, RepairOrderId repair) {
    open(
        player,
        "Repair",
        "Repair order " + repair.value(),
        List.of(command("Status", "frontier repair status")));
  }

  @Override
  public void openMarket(PlayerId player, SettlementId settlement) {
    open(
        player,
        "Market",
        "Local market for " + settlement.value(),
        List.of(command("Browse", "frontier market browse")));
  }

  private void open(PlayerId playerId, String title, String message, List<ActionButton> actions) {
    Player player = server.getPlayer(playerId.value());
    if (player == null) return;
    Dialog dialog =
        Dialog.create(
            builder ->
                builder
                    .empty()
                    .base(
                        DialogBase.builder(Component.text(title))
                            .body(List.of(DialogBody.plainMessage(Component.text(message))))
                            .build())
                    .type(
                        DialogType.multiAction(actions)
                            .columns(Math.min(2, actions.size()))
                            .build()));
    player.showDialog(dialog);
  }

  private static ActionButton command(String label, String command) {
    return ActionButton.builder(Component.text(label))
        .action(DialogAction.commandTemplate(command))
        .build();
  }

  private ActionButton oneUse(PlayerId playerId, UUID aggregate, String label, String command) {
    UUID token =
        actionTokens.issue(
            playerId.value(), command, aggregate, Duration.ofMinutes(2), Instant.now());
    return ActionButton.builder(Component.text(label))
        .action(
            DialogAction.customClick(
                (view, audience) -> {
                  if (audience instanceof Player player
                      && actionTokens.consume(
                          token, player.getUniqueId(), command, aggregate, Instant.now()))
                    player.performCommand(command);
                },
                ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build()))
        .build();
  }
}
