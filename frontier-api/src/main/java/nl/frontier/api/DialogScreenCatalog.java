package nl.frontier.api;

import static nl.frontier.api.FrontierUi.Screen.*;

import java.util.List;
import nl.frontier.api.FrontierUi.Screen;

public final class DialogScreenCatalog {
  private DialogScreenCatalog() {}

  public record Action(String label, String command, boolean mutation) {}

  public static String title(Screen screen) {
    return switch (screen) {
      case FRONTIER -> "Frontier";
      case SETTLEMENT -> "Settlement";
      case BUILDINGS -> "Architect & Buildings";
      case DISTRICT -> "Districts";
      case KINGDOM -> "Kingdom";
      case TREASURY -> "Treasury";
      case REPAIR -> "Repair";
      case WAR -> "War";
      case MARKET -> "Market";
      case WORKERS -> "Workers & Population";
      case CONTRACTS -> "Contracts";
      case INFRASTRUCTURE -> "Infrastructure";
      case HISTORY -> "History";
      case REPORTS -> "Reports";
      case SETTINGS -> "Settings";
    };
  }

  public static List<Action> actions(Screen screen) {
    return switch (screen) {
      case FRONTIER ->
          List.of(
              nav("Settlement", SETTLEMENT),
              nav("Buildings", BUILDINGS),
              nav("Districts", DISTRICT),
              nav("Kingdom", KINGDOM),
              nav("Treasury", TREASURY),
              nav("Repair", REPAIR),
              nav("War", WAR),
              nav("Market", MARKET),
              nav("Workers", WORKERS),
              nav("Contracts", CONTRACTS),
              nav("Infrastructure", INFRASTRUCTURE),
              nav("History", HISTORY),
              nav("Reports", REPORTS),
              nav("Settings", SETTINGS));
      case SETTLEMENT ->
          List.of(
              read("Overview", "frontier city info"),
              mutate("Found settlement", "frontier city create $(city_name)"),
              mutate("Claim current chunk", "frontier city claim"),
              mutate("Upgrade settlement", "frontier city upgrade"),
              read("Population", "frontier population"),
              nav("Buildings", BUILDINGS),
              nav("Districts", DISTRICT),
              back());
      case BUILDINGS ->
          List.of(
              mutate(
                  "Start selection",
                  "frontier city building start $(building_type) $(district_id)"),
              read("Preview report", "frontier city building preview"),
              mutate("Confirm registration", "frontier city building confirm"),
              mutate("Cancel selection", "frontier city building cancel"),
              mutate("Revalidate", "frontier city building revalidate $(building_id)"),
              read("History", "frontier city building history $(building_id) 20"),
              mutate("Unregister", "frontier city building unregister $(building_id) confirm"),
              mutate(
                  "Propose transfer",
                  "frontier city building transfer $(building_id) $(target_city)"),
              mutate("Accept transfer", "frontier city building transfer-accept $(proposal_id)"),
              back());
      case DISTRICT ->
          List.of(
              read("District list", "frontier district list"),
              read("Open district", "frontier district info $(district_name)"),
              mutate(
                  "Create district",
                  "frontier district create $(district_type) $(radius) $(new_district_name)"),
              read("District reports", "frontier district view $(district_name) reports"),
              back());
      case KINGDOM ->
          List.of(
              read("Kingdom list", "frontier kingdom list"),
              read("Overview", "frontier kingdom overview $(kingdom_id)"),
              mutate("Create kingdom", "frontier kingdom create $(kingdom_name)"),
              mutate(
                  "Approve war",
                  "frontier kingdom war-approve $(kingdom_id) $(target_city) CAMPAIGN"),
              mutate("Set tax", "frontier kingdom tax $(kingdom_id) $(tax_basis_points)"),
              mutate(
                  "Set policy",
                  "frontier kingdom policy $(kingdom_id) $(policy_key) $(policy_value)"),
              back());
      case TREASURY ->
          List.of(
              read("Settlement balance", "frontier treasury status"),
              read("Player balance", "frontier balance"),
              read("Audit", "frontier treasury audit 20"),
              mutate("Deposit", "frontier treasury deposit $(amount)"),
              mutate("Withdraw", "frontier treasury withdraw $(amount)"),
              mutate("Pay player", "frontier treasury pay $(player_name) $(amount)"),
              back());
      case REPAIR ->
          List.of(
              read("Repair orders", "frontier repair list"),
              mutate("Get quote", "frontier repair quote $(campaign_id)"),
              mutate("Buy repair", "frontier repair buy $(campaign_id)"),
              read("Builder Guild", "frontier guild overview"),
              read("Guild repair queue", "frontier guild queue"),
              mutate(
                  "Set project priority",
                  "frontier guild priority $(repair_id) $(repair_priority)"),
              mutate("Emergency repair", "frontier guild emergency $(repair_id)"),
              mutate(
                  "Deliver held materials",
                  "frontier guild deliver $(repair_id) $(material_amount)"),
              mutate("Boost project", "frontier guild boost $(repair_id) $(boost_points)"),
              read("Inspect looked-at block", "frontier guild inspect"),
              mutate("Resolve conflict", "frontier guild resolve $(conflict_id)"),
              mutate("Controlled repair mode", "frontier guild assist $(repair_id)"),
              back());
      case WAR ->
          List.of(
              read("Campaigns", "frontier war list"),
              mutate(
                  "Declare campaign",
                  "frontier war declare $(target_city) $(war_type) $(objective_type) $(target)"),
              mutate("Ceasefire", "frontier war ceasefire $(campaign_id) DIALOG"),
              mutate("Resolve", "frontier war resolve $(campaign_id) DIALOG"),
              back());
      case MARKET ->
          List.of(
              read("Open orders", "frontier market list"),
              read("Warehouse", "frontier market warehouse"),
              mutate("Buy order", "frontier market buy $(commodity) $(quantity) $(unit_price)"),
              mutate("Sell order", "frontier market sell $(commodity) $(quantity) $(unit_price)"),
              back());
      case WORKERS ->
          List.of(
              read("Workers", "frontier workers"),
              read("Population", "frontier population"),
              read("Production", "frontier production list"),
              mutate("Hire worker", "frontier production hire $(profession) $(skill) $(salary)"),
              back());
      case CONTRACTS ->
          List.of(
              read("Available", "frontier contracts list"),
              read("Harbor jobs", "frontier harbor jobs"),
              mutate("Accept", "frontier contracts accept $(contract_id)"),
              mutate("Deliver", "frontier contracts deliver $(contract_id)"),
              back());
      case INFRASTRUCTURE ->
          List.of(
              read("Shipments", "frontier logistics list"),
              read("Caravans", "frontier caravan list"),
              mutate("Register node", "frontier logistics node $(node_type)"),
              mutate("Escort caravan", "frontier caravan escort $(shipment_id)"),
              back());
      case HISTORY ->
          List.of(
              read("Commercial history", "frontier economy history"),
              read("Treasury history", "frontier treasury audit 50"),
              read("District history", "frontier district view $(district_id) history"),
              back());
      case REPORTS ->
          List.of(
              read("Health", "frontier status"),
              read("World regions", "frontier world regions"),
              read("World events", "frontier world events"),
              read("Dynamic events", "frontier events list"),
              read("Kingdom rankings", "frontier endgame rankings"),
              read("Settlement", "frontier city info"),
              read("Population", "frontier population"),
              back());
      case SETTINGS ->
          List.of(
              read("Refresh UI", "frontier menu settings"),
              read("Command help", "frontier help"),
              read("Health", "frontier status"),
              back());
    };
  }

  private static Action nav(String label, Screen screen) {
    return read(label, "frontier menu " + screen.name().toLowerCase());
  }

  private static Action back() {
    return nav("Back to Frontier", Screen.FRONTIER);
  }

  private static Action read(String label, String command) {
    return new Action(label, command, false);
  }

  private static Action mutate(String label, String command) {
    return new Action(label, command, true);
  }
}
