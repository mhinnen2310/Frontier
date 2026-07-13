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
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.frontier.api.DialogScreenCatalog;
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
  public void openMenu(PlayerId player, Screen screen) {
    List<ActionButton> actions =
        DialogScreenCatalog.actions(screen).stream()
            .map(action -> command(action.label(), action.command(), action.mutation()))
            .toList();
    open(
        player,
        DialogScreenCatalog.title(screen),
        "Use this menu for the complete Frontier flow. Commands remain available as fallback.",
        inputs(screen),
        actions);
  }

  @Override
  public void openSettlement(PlayerId player, SettlementId settlement) {
    open(
        player,
        "Settlement",
        "Settlement " + settlement.value(),
        List.of(
            command("Overview", "frontier city info"),
            command("Treasury", "frontier menu treasury"),
            command("Districts", "frontier menu district"),
            oneUse(player, settlement.value(), "Claim this chunk", "frontier city claim"),
            oneUse(player, settlement.value(), "Upgrade", "frontier city upgrade"),
            command("Back", "frontier menu")));
  }

  @Override
  public void openTreasury(PlayerId player, SettlementId settlement) {
    open(
        player,
        "Treasury",
        "Audited treasury for " + settlement.value(),
        List.of(
            command("Balance", "frontier treasury status"),
            command("Audit", "frontier treasury audit 20"),
            command("Treasury menu", "frontier menu treasury"),
            command("Back", "frontier menu")));
  }

  @Override
  public void openWar(PlayerId player, WarId war) {
    open(
        player,
        "Campaign",
        "Campaign " + war.value(),
        List.of(
            command("Campaign list", "frontier war list"),
            command("War menu", "frontier menu war"),
            command("Back", "frontier menu")));
  }

  @Override
  public void openRepair(PlayerId player, RepairOrderId repair) {
    open(
        player,
        "Repair",
        "Repair order " + repair.value(),
        List.of(
            command("Repair list", "frontier repair list"),
            command("Repair menu", "frontier menu repair"),
            command("Back", "frontier menu")));
  }

  @Override
  public void openMarket(PlayerId player, SettlementId settlement) {
    open(
        player,
        "Market",
        "Local market for " + settlement.value(),
        List.of(
            command("Orders", "frontier market list"),
            command("Warehouse", "frontier market warehouse"),
            command("Market menu", "frontier menu market"),
            command("Back", "frontier menu")));
  }

  @Override
  public void openDistrict(PlayerId player, UUID district, String view, String summary) {
    String prefix = "frontier district view " + district + " ";
    open(
        player,
        "District " + view,
        summary,
        List.of(
            command("Overview", prefix + "overview"),
            command("Budget", prefix + "budget"),
            command("Workers", prefix + "workers"),
            command("Buildings", prefix + "buildings"),
            command("Reports", prefix + "reports"),
            command("Policies", prefix + "policies"),
            command("History", prefix + "history"),
            command("District menu", "frontier menu district"),
            command("Back", "frontier menu")));
  }

  private void open(PlayerId playerId, String title, String message, List<ActionButton> actions) {
    open(playerId, title, message, List.of(), actions);
  }

  private void open(
      PlayerId playerId,
      String title,
      String message,
      List<DialogInput> inputs,
      List<ActionButton> actions) {
    Player player = server.getPlayer(playerId.value());
    if (player == null) return;
    Dialog dialog =
        Dialog.create(
            builder ->
                builder
                    .empty()
                    .base(
                        DialogBase.builder(Component.text(title, NamedTextColor.GOLD))
                            .body(
                                List.of(
                                    DialogBody.plainMessage(
                                        Component.text(message, NamedTextColor.GRAY)
                                            .append(Component.newline())
                                            .append(
                                                Component.text(
                                                    "Select an action below. Submitted actions close this dialog.",
                                                    NamedTextColor.DARK_GRAY)))))
                            .inputs(inputs)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .build())
                    .type(
                        DialogType.multiAction(actions)
                            .columns(Math.min(2, actions.size()))
                            .build()));
    player.showDialog(dialog);
  }

  private static List<DialogInput> inputs(Screen screen) {
    return switch (screen) {
      case FRONTIER, REPORTS, SETTINGS -> List.of();
      case SETTLEMENT -> List.of(text("city_name", "Settlement name", "New Frontier"));
      case DISTRICT ->
          List.of(
              text("district_id", "District UUID", ""),
              text("district_name", "District name", "Central"));
      case KINGDOM ->
          List.of(
              text("kingdom_id", "Kingdom UUID", ""),
              text("kingdom_name", "Kingdom name", "New Kingdom"),
              text("target_city", "Target city UUID", ""),
              text("tax_basis_points", "Tax basis points", "100"),
              text("policy_key", "Policy key", "PEACEFUL_SECESSION"),
              text("policy_value", "Policy value", "DENY"));
      case TREASURY ->
          List.of(text("amount", "Amount in cents", "100"), text("player_name", "Player name", ""));
      case REPAIR ->
          List.of(
              text("campaign_id", "Campaign UUID", ""),
              text("repair_id", "Campaign UUID to purchase", ""));
      case WAR ->
          List.of(
              text("campaign_id", "Campaign UUID", ""),
              text("target_city", "Target city UUID", ""),
              text("war_type", "War type", "BORDER"),
              text("objective_type", "Objective type", "CONTROL"),
              text("target", "Objective target", "100"));
      case MARKET ->
          List.of(
              text("commodity", "Commodity", "minecraft:wheat"),
              text("quantity", "Quantity", "16"),
              text("unit_price", "Unit price in cents", "10"));
      case WORKERS ->
          List.of(
              text("profession", "Profession", "FARMER"),
              text("skill", "Skill 1-100", "50"),
              text("salary", "Daily salary in cents", "100"));
      case CONTRACTS -> List.of(text("contract_id", "Contract UUID", ""));
      case INFRASTRUCTURE ->
          List.of(text("node_type", "Node type", "ROAD"), text("shipment_id", "Shipment UUID", ""));
      case HISTORY -> List.of(text("district_id", "District UUID", ""));
    };
  }

  private static DialogInput text(String key, String label, String initial) {
    return DialogInput.text(key, Component.text(label))
        .width(300)
        .initial(initial)
        .maxLength(96)
        .build();
  }

  private static ActionButton command(String label, String command) {
    return command(label, command, false);
  }

  private static ActionButton command(String label, String command, boolean mutation) {
    return ActionButton.builder(
            Component.text(label, mutation ? NamedTextColor.YELLOW : NamedTextColor.AQUA))
        .tooltip(
            Component.text(
                mutation
                    ? "Submits a protected gameplay change. Check the fields first."
                    : "Opens or refreshes this Frontier report.",
                mutation ? NamedTextColor.GOLD : NamedTextColor.GRAY))
        .width(150)
        .action(DialogAction.commandTemplate(command))
        .build();
  }

  private ActionButton oneUse(PlayerId playerId, UUID aggregate, String label, String command) {
    UUID token =
        actionTokens.issue(
            playerId.value(), command, aggregate, Duration.ofMinutes(2), Instant.now());
    return ActionButton.builder(Component.text(label))
        .tooltip(
            Component.text(
                "Single-use protected action; expires in two minutes.", NamedTextColor.GOLD))
        .width(150)
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
