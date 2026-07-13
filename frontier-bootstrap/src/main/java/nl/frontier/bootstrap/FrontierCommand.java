package nl.frontier.bootstrap;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.frontier.api.AdminDiagnostics;
import nl.frontier.api.FrontierUi;
import nl.frontier.api.HealthStatus;
import nl.frontier.api.RecoveryCoordinator;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.city.BuildingType;
import nl.frontier.city.DistrictApplicationService;
import nl.frontier.city.DistrictGateway;
import nl.frontier.city.DistrictType;
import nl.frontier.city.GovernmentRole;
import nl.frontier.city.PopulationService;
import nl.frontier.city.SettlementApplicationService;
import nl.frontier.city.SettlementGateway;
import nl.frontier.city.SettlementLifecycleService;
import nl.frontier.domain.Ids.PlayerId;
import nl.frontier.domain.Ids.RepairOrderId;
import nl.frontier.domain.Ids.SettlementId;
import nl.frontier.domain.Ids.WarId;
import nl.frontier.economy.CaravanService;
import nl.frontier.economy.CommercialService;
import nl.frontier.economy.ContractGateway;
import nl.frontier.economy.EconomyApplicationService;
import nl.frontier.economy.EconomyGateway;
import nl.frontier.economy.FinanceApplicationService;
import nl.frontier.economy.FinanceGateway;
import nl.frontier.economy.HarborApplicationService;
import nl.frontier.economy.InfrastructureHealthService;
import nl.frontier.economy.InfrastructureType;
import nl.frontier.economy.LogisticsGateway;
import nl.frontier.economy.MarketEngine;
import nl.frontier.economy.ProductionApplicationService;
import nl.frontier.observability.BuildInformationService;
import nl.frontier.observability.FrontierMetrics;
import nl.frontier.repair.BuilderGuildGateway;
import nl.frontier.repair.RepairGateway;
import nl.frontier.repair.RepairOrder;
import nl.frontier.warfare.CampaignGateway;
import nl.frontier.warfare.CampaignOutcomeGateway;
import nl.frontier.warfare.CampaignOutcomeService;
import nl.frontier.warfare.WarCampaign;
import nl.frontier.world.CivilizationGateway;
import nl.frontier.world.DynamicEventService;
import nl.frontier.world.EndgameService;
import nl.frontier.world.KingdomIntegrationGateway;
import nl.frontier.world.KingdomIntegrationService;
import nl.frontier.world.WorldSimulationGateway;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class FrontierCommand implements CommandExecutor, TabCompleter {
  private static final List<String> ROOTS =
      List.of(
          "menu",
          "help",
          "status",
          "balance",
          "pay",
          "harbor",
          "city",
          "district",
          "caravan",
          "population",
          "workers",
          "events",
          "endgame",
          "economy",
          "treasury",
          "production",
          "logistics",
          "contracts",
          "war",
          "repair",
          "guild",
          "world",
          "kingdom",
          "market",
          "admin");
  private final Supplier<HealthStatus> health;
  private final RecoveryCoordinator recovery;
  private final AdminDiagnostics diagnostics;
  private final BuildInformationService buildInformation;
  private final ConfigRegistry configs;
  private final FrontierMetrics metrics;
  private final CommandRateLimiter rateLimiter;
  private final SettlementApplicationService settlements;
  private final BuildingRegistrationCoordinator buildingRegistrations;
  private final DistrictApplicationService districts;
  private final SettlementLifecycleService settlementLifecycle;
  private final SettlementFoundingCoordinator founding;
  private final InfrastructureRegistrationCoordinator infrastructureRegistrations;
  private final InfrastructureHealthService infrastructureHealth;
  private final CaravanService caravans;
  private final PopulationService population;
  private final CommercialService commerce;
  private final FinanceApplicationService finance;
  private final HarborApplicationService harbor;
  private final EconomyApplicationService economy;
  private final ProductionApplicationService production;
  private final LogisticsGateway logistics;
  private final ContractGateway contracts;
  private final CampaignGateway campaigns;
  private final CampaignOutcomeService campaignOutcomes;
  private final RepairGateway repairs;
  private final BuilderGuildGateway guilds;
  private final BuilderGuildCoordinator guildCoordinator;
  private final WorldSimulationGateway worldSimulation;
  private final CivilizationGateway civilization;
  private final KingdomIntegrationService kingdomIntegration;
  private final DynamicEventService dynamicEvents;
  private final EndgameService endgame;
  private final Duration campaignPreparation;
  private final Duration campaignMaximumDuration;
  private final long campaignDeclarationCost;
  private final SchedulerFacade schedulers;
  private final FrontierUi ui;
  private final PaperPresentationService presentation;

  public FrontierCommand(
      Supplier<HealthStatus> health,
      RecoveryCoordinator recovery,
      AdminDiagnostics diagnostics,
      BuildInformationService buildInformation,
      ConfigRegistry configs,
      FrontierMetrics metrics,
      CommandRateLimiter rateLimiter,
      SettlementApplicationService settlements,
      BuildingRegistrationCoordinator buildingRegistrations,
      DistrictApplicationService districts,
      SettlementLifecycleService settlementLifecycle,
      SettlementFoundingCoordinator founding,
      InfrastructureRegistrationCoordinator infrastructureRegistrations,
      InfrastructureHealthService infrastructureHealth,
      CaravanService caravans,
      PopulationService population,
      CommercialService commerce,
      FinanceApplicationService finance,
      HarborApplicationService harbor,
      EconomyApplicationService economy,
      ProductionApplicationService production,
      LogisticsGateway logistics,
      ContractGateway contracts,
      CampaignGateway campaigns,
      CampaignOutcomeService campaignOutcomes,
      RepairGateway repairs,
      BuilderGuildGateway guilds,
      BuilderGuildCoordinator guildCoordinator,
      WorldSimulationGateway worldSimulation,
      CivilizationGateway civilization,
      KingdomIntegrationService kingdomIntegration,
      DynamicEventService dynamicEvents,
      EndgameService endgame,
      Duration campaignPreparation,
      Duration campaignMaximumDuration,
      long campaignDeclarationCost,
      SchedulerFacade schedulers,
      FrontierUi ui,
      PaperPresentationService presentation) {
    this.health = Objects.requireNonNull(health);
    this.recovery = Objects.requireNonNull(recovery);
    this.diagnostics = Objects.requireNonNull(diagnostics);
    this.buildInformation = Objects.requireNonNull(buildInformation);
    this.configs = Objects.requireNonNull(configs);
    this.metrics = Objects.requireNonNull(metrics);
    this.rateLimiter = Objects.requireNonNull(rateLimiter);
    this.settlements = Objects.requireNonNull(settlements);
    this.buildingRegistrations = Objects.requireNonNull(buildingRegistrations);
    this.districts = Objects.requireNonNull(districts);
    this.settlementLifecycle = Objects.requireNonNull(settlementLifecycle);
    this.founding = Objects.requireNonNull(founding);
    this.infrastructureRegistrations = Objects.requireNonNull(infrastructureRegistrations);
    this.infrastructureHealth = Objects.requireNonNull(infrastructureHealth);
    this.caravans = Objects.requireNonNull(caravans);
    this.population = Objects.requireNonNull(population);
    this.commerce = Objects.requireNonNull(commerce);
    this.finance = Objects.requireNonNull(finance);
    this.harbor = Objects.requireNonNull(harbor);
    this.economy = Objects.requireNonNull(economy);
    this.production = Objects.requireNonNull(production);
    this.logistics = Objects.requireNonNull(logistics);
    this.contracts = Objects.requireNonNull(contracts);
    this.campaigns = Objects.requireNonNull(campaigns);
    this.campaignOutcomes = Objects.requireNonNull(campaignOutcomes);
    this.repairs = Objects.requireNonNull(repairs);
    this.guilds = Objects.requireNonNull(guilds);
    this.guildCoordinator = Objects.requireNonNull(guildCoordinator);
    this.worldSimulation = Objects.requireNonNull(worldSimulation);
    this.civilization = Objects.requireNonNull(civilization);
    this.kingdomIntegration = Objects.requireNonNull(kingdomIntegration);
    this.dynamicEvents = Objects.requireNonNull(dynamicEvents);
    this.endgame = Objects.requireNonNull(endgame);
    this.campaignPreparation = Objects.requireNonNull(campaignPreparation);
    this.campaignMaximumDuration = Objects.requireNonNull(campaignMaximumDuration);
    this.campaignDeclarationCost = campaignDeclarationCost;
    this.schedulers = Objects.requireNonNull(schedulers);
    this.ui = Objects.requireNonNull(ui);
    this.presentation = Objects.requireNonNull(presentation);
  }

  @Override
  public boolean onCommand(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String label,
      String[] args) {
    metrics.command();
    if (sender instanceof Player player
        && !rateLimiter.allow(player.getUniqueId(), Instant.now())) {
      metrics.rateLimited();
      player.sendMessage(
          Component.text("Too many Frontier commands; wait a moment.", NamedTextColor.RED));
      return true;
    }
    if (args.length == 0) {
      if (sender instanceof Player player)
        ui.openMenu(new PlayerId(player.getUniqueId()), FrontierUi.Screen.FRONTIER);
      else help(sender);
      return true;
    }
    String root = args[0].toLowerCase(Locale.ROOT);
    if (root.equals("menu") && sender instanceof Player player) {
      FrontierUi.Screen screen = FrontierUi.Screen.FRONTIER;
      if (args.length > 1) {
        try {
          screen = FrontierUi.Screen.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
          player.sendMessage(Component.text("Unknown Frontier menu.", NamedTextColor.RED));
          return true;
        }
      }
      ui.openMenu(new PlayerId(player.getUniqueId()), screen);
      return true;
    }
    if (root.equals("help")) {
      help(sender);
      return true;
    }
    if (root.equals("status")
        || root.equals("admin") && args.length > 1 && args[1].equalsIgnoreCase("health")) {
      asyncHealth(sender);
      return true;
    }
    if (root.equals("admin") && args.length > 1 && args[1].equalsIgnoreCase("recover")) {
      recover(sender);
      return true;
    }
    if (root.equals("admin") && args.length > 1) {
      admin(sender, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    String module = moduleForRoot(root);
    if (module != null && !configs.enabled(module)) {
      sender.sendMessage(
          Component.text(
              "Frontier module '" + module + "' is disabled by server configuration.",
              NamedTextColor.RED));
      return true;
    }
    if (root.equals("city") && sender instanceof Player player) {
      city(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("district") && sender instanceof Player player) {
      district(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("caravan") && sender instanceof Player player) {
      caravan(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("population") && sender instanceof Player player) {
      population(player);
      return true;
    }
    if (root.equals("workers") && sender instanceof Player player) {
      workers(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("events") && sender instanceof Player player) {
      dynamicEvents(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("endgame") && sender instanceof Player player) {
      endgame(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("economy") && sender instanceof Player player) {
      commercial(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("balance") && sender instanceof Player player) {
      execute(
          player,
          () -> finance.balance(player.getUniqueId(), Instant.now()),
          balance -> "Wallet: " + balance + " cents");
      return true;
    }
    if (root.equals("pay") && sender instanceof Player player) {
      pay(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("harbor") && sender instanceof Player player) {
      harbor(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("treasury") && sender instanceof Player player) {
      treasury(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("market") && sender instanceof Player player) {
      market(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("production") && sender instanceof Player player) {
      production(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("logistics") && sender instanceof Player player) {
      logistics(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("contracts") && sender instanceof Player player) {
      contracts(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("war") && sender instanceof Player player) {
      war(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("repair") && sender instanceof Player player) {
      repair(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("guild") && sender instanceof Player player) {
      guild(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("world")) {
      world(sender, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    if (root.equals("kingdom") && sender instanceof Player player) {
      kingdom(player, Arrays.copyOfRange(args, 1, args.length));
      return true;
    }
    sender.sendMessage(
        Component.text(
            "This feature is not exposed in the current sprint yet.", NamedTextColor.YELLOW));
    help(sender);
    return true;
  }

  private void city(Player player, String[] args) {
    if (args.length == 0) {
      openCity(player);
      return;
    }
    if (args[0].equalsIgnoreCase("info")) {
      cityInfo(player);
      return;
    }

    String action = args[0].toLowerCase(Locale.ROOT);
    try {
      switch (action) {
        case "create" -> {
          if (args.length < 2)
            throw new IllegalArgumentException("usage: /frontier city create <name> [| <charter>]");
          createExpedition(player, Arrays.copyOfRange(args, 1, args.length));
        }
        case "expedition" -> expedition(player, args);
        case "invite" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier city invite <online-player>");
          Player target = player.getServer().getPlayerExact(args[1]);
          if (target == null) throw new IllegalArgumentException("target player must be online");
          withCity(
              player,
              city ->
                  settlements.invite(
                      city.id(), player.getUniqueId(), target.getUniqueId(), Instant.now()),
              invitation -> "Invitation created: " + invitation.id());
        }
        case "invite-revoke" -> {
          if (args.length != 2)
            throw new IllegalArgumentException(
                "usage: /frontier city invite-revoke <invitation-uuid>");
          UUID invitation = UUID.fromString(args[1]);
          withCity(
              player,
              city ->
                  settlements.revokeInvitation(
                      city.id(), player.getUniqueId(), invitation, Instant.now()),
              value -> "Invitation revoked: " + value.id());
        }
        case "accept" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier city accept <invitation-uuid>");
          UUID invitation = UUID.fromString(args[1]);
          execute(
              player,
              () -> settlements.accept(invitation, player.getUniqueId(), Instant.now()),
              city -> "Joined " + city.name());
        }
        case "role" -> role(player, args);
        case "leave" ->
            withCity(
                player,
                city -> settlements.leave(city.id(), player.getUniqueId(), Instant.now()),
                value -> "You left the settlement.");
        case "kick" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier city kick <player>");
          UUID target = resolvePlayer(player, args[1]);
          withCity(
              player,
              city -> settlements.kick(city.id(), player.getUniqueId(), target, Instant.now()),
              value -> "Member removed: " + value.player());
        }
        case "ban" -> {
          if (args.length < 3)
            throw new IllegalArgumentException("usage: /frontier city ban <player> <reason>");
          UUID target = resolvePlayer(player, args[1]);
          String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
          withCity(
              player,
              city ->
                  settlements.ban(city.id(), player.getUniqueId(), target, reason, Instant.now()),
              value -> "Player banned: " + value.player());
        }
        case "unban" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier city unban <player>");
          UUID target = resolvePlayer(player, args[1]);
          withCity(
              player,
              city -> {
                settlements.unban(city.id(), player.getUniqueId(), target, Instant.now());
                return target;
              },
              value -> "Player unbanned: " + value);
        }
        case "members" ->
            withCity(
                player,
                city -> settlements.members(city.id(), player.getUniqueId()),
                values ->
                    values.stream()
                        .map(value -> value.player() + " (" + value.role() + ")")
                        .collect(java.util.stream.Collectors.joining(" | ")));
        case "claim" ->
            withCity(
                player,
                city ->
                    settlements.claim(
                        city.id(),
                        player.getUniqueId(),
                        player.getWorld().getUID(),
                        player.getChunk().getX(),
                        player.getChunk().getZ(),
                        city.level(),
                        Instant.now()),
                claim -> "Chunk is now " + claim.state());
        case "building" -> building(player, args);
        case "upgrade" ->
            withCity(
                player,
                city ->
                    settlements.upgrade(
                        city.id(), player.getUniqueId(), upgradeKey(city), Instant.now()),
                upgraded -> "Settlement upgraded to " + upgraded.level());
        case "policy" -> policy(player, args);
        case "transfer" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier city transfer <player>");
          UUID successor = resolvePlayer(player, args[1]);
          withCity(
              player,
              city ->
                  settlementLifecycle.transfer(
                      city.id(), player.getUniqueId(), successor, Instant.now()),
              value -> "Settlement ownership transferred to " + value.owner());
        }
        case "succession" ->
            withCity(
                player,
                city ->
                    settlementLifecycle.succession(city.id(), player.getUniqueId(), Instant.now()),
                value -> "Mayor succession completed: " + value.owner());
        case "abandon" ->
            withCity(
                player,
                city -> settlementLifecycle.abandon(city.id(), player.getUniqueId(), Instant.now()),
                value -> "Settlement abandoned; ruins remain until " + value.ruinsUntil());
        case "disband" -> disband(player, args);
        case "recover" ->
            withCity(
                player,
                city ->
                    settlementLifecycle.recoverRuins(
                        city.id(), player.getUniqueId(), Instant.now()),
                value -> "Settlement recovered from ruins.");
        case "merge" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier city merge <target-city-uuid>");
          UUID target = UUID.fromString(args[1]);
          withCity(
              player,
              city ->
                  settlementLifecycle.merge(city.id(), player.getUniqueId(), target, Instant.now()),
              value -> "Merge proposal " + value.id() + " expires " + value.expiresAt());
        }
        case "merge-accept" -> {
          if (args.length != 2)
            throw new IllegalArgumentException(
                "usage: /frontier city merge-accept <proposal-uuid>");
          UUID proposal = UUID.fromString(args[1]);
          execute(
              player,
              () -> settlementLifecycle.acceptMerge(proposal, player.getUniqueId(), Instant.now()),
              value -> "Settlement merge completed into " + value.city());
        }
        case "history" ->
            withCity(
                player,
                city -> settlementLifecycle.history(city.id(), player.getUniqueId()),
                values ->
                    values.isEmpty()
                        ? "No settlement lifecycle history."
                        : values.stream()
                            .limit(10)
                            .map(value -> value.event() + " " + value.payload())
                            .collect(java.util.stream.Collectors.joining(" | ")));
        default ->
            throw new IllegalArgumentException(
                "city actions: create, expedition, info, invite, invite-revoke, accept, members, leave, kick, ban, unban, role, claim, building, upgrade, policy, transfer, succession, abandon, disband, recover, merge, merge-accept, history");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void disband(Player player, String[] args) {
    if (args.length < 2)
      throw new IllegalArgumentException(
          "usage: /frontier city disband <request|confirm> [request-uuid]");
    switch (args[1].toLowerCase(Locale.ROOT)) {
      case "request" ->
          withCity(
              player,
              city ->
                  settlementLifecycle.requestDisband(
                      city.id(), player.getUniqueId(), Instant.now()),
              value ->
                  "Disband request "
                      + value.id()
                      + "; confirm after "
                      + value.confirmsAfter()
                      + " and before "
                      + value.expiresAt());
      case "confirm" -> {
        if (args.length != 3)
          throw new IllegalArgumentException(
              "usage: /frontier city disband confirm <request-uuid>");
        UUID request = UUID.fromString(args[2]);
        execute(
            player,
            () -> settlementLifecycle.confirmDisband(request, player.getUniqueId(), Instant.now()),
            value -> "Settlement disbanded; ruins remain until " + value.ruinsUntil());
      }
      default ->
          throw new IllegalArgumentException(
              "usage: /frontier city disband <request|confirm> [request-uuid]");
    }
  }

  private void openCity(Player player) {
    schedulers
        .async(() -> settlements.city(player.getUniqueId()))
        .whenComplete(
            (result, failure) ->
                schedulers.global(
                    () -> {
                      if (failure != null) {
                        player.sendMessage(
                            Component.text(rootMessage(failure), NamedTextColor.RED));
                      } else if (result.isEmpty()) {
                        player.sendMessage(
                            Component.text(
                                "Start a founding expedition with /frontier city create <name>",
                                NamedTextColor.YELLOW));
                      } else {
                        ui.openSettlement(
                            new PlayerId(player.getUniqueId()),
                            new SettlementId(result.orElseThrow().id()));
                      }
                    }));
  }

  private void role(Player player, String[] args) {
    if (args.length != 3)
      throw new IllegalArgumentException("usage: /frontier city role <online-player> <role>");
    Player target = player.getServer().getPlayerExact(args[1]);
    if (target == null) throw new IllegalArgumentException("target player must be online");
    GovernmentRole role = GovernmentRole.valueOf(args[2].toUpperCase(Locale.ROOT));
    withCity(
        player,
        city -> {
          settlements.role(
              city.id(), player.getUniqueId(), target.getUniqueId(), role, Instant.now());
          return role;
        },
        value -> "Role changed to " + value);
  }

  private void building(Player player, String[] args) {
    if (args.length < 2) throw buildingUsage();
    String action = args[1].toLowerCase(Locale.ROOT);
    switch (action) {
      case "start" -> {
        if (args.length < 3 || args.length > 4) throw buildingUsage();
        BuildingType type = parseBuildingType(args[2]);
        String district = args.length == 4 ? args[3] : null;
        withCityEntity(
            player, city -> buildingRegistrations.start(player, city.id(), type, district));
      }
      case "preview", "report" -> buildingRegistrations.preview(player);
      case "confirm" -> buildingRegistrations.confirm(player);
      case "cancel" -> buildingRegistrations.cancel(player);
      case "revalidate" -> {
        if (args.length != 3)
          throw new IllegalArgumentException(
              "usage: /frontier city building revalidate <building-uuid>");
        UUID building = UUID.fromString(args[2]);
        withCityEntity(
            player, city -> buildingRegistrations.revalidate(player, city.id(), building));
      }
      case "unregister" -> {
        if (args.length != 4 || !args[3].equalsIgnoreCase("confirm"))
          throw new IllegalArgumentException(
              "usage: /frontier city building unregister <building-uuid> confirm");
        UUID building = UUID.fromString(args[2]);
        withCityEntity(
            player, city -> buildingRegistrations.unregister(player, city.id(), building));
      }
      case "history" -> {
        if (args.length < 3 || args.length > 4)
          throw new IllegalArgumentException(
              "usage: /frontier city building history <building-uuid> [limit]");
        UUID building = UUID.fromString(args[2]);
        int limit = args.length == 4 ? Integer.parseInt(args[3]) : 20;
        withCityEntity(
            player, city -> buildingRegistrations.history(player, city.id(), building, limit));
      }
      case "transfer" -> {
        if (args.length != 4)
          throw new IllegalArgumentException(
              "usage: /frontier city building transfer <building-uuid> <target-city-uuid>");
        UUID building = UUID.fromString(args[2]);
        UUID targetCity = UUID.fromString(args[3]);
        withCityEntity(
            player,
            city -> buildingRegistrations.proposeTransfer(player, city.id(), building, targetCity));
      }
      case "transfer-accept" -> {
        if (args.length != 3)
          throw new IllegalArgumentException(
              "usage: /frontier city building transfer-accept <proposal-uuid>");
        buildingRegistrations.acceptTransfer(player, UUID.fromString(args[2]));
      }
      default -> legacyBuildingRegistration(player, args);
    }
  }

  private void legacyBuildingRegistration(Player player, String[] args) {
    if (args.length > 4) throw buildingUsage();
    BuildingType type = parseBuildingType(args[1]);
    int radius = args.length >= 3 ? Integer.parseInt(args[2]) : 4;
    String district = args.length == 4 ? args[3] : null;
    if (radius < 1 || radius > 16)
      throw new IllegalArgumentException("building radius must be 1-16");
    var location = player.getLocation();
    SettlementGateway.Bounds bounds =
        new SettlementGateway.Bounds(
            player.getWorld().getUID(),
            location.getBlockX() - radius,
            Math.max(-64, location.getBlockY() - 4),
            location.getBlockZ() - radius,
            location.getBlockX() + radius,
            Math.min(320, location.getBlockY() + 8),
            location.getBlockZ() + radius);
    withCityEntity(
        player,
        city ->
            buildingRegistrations.register(
                player,
                city.id(),
                type,
                bounds,
                district,
                registered ->
                    player.sendMessage(
                        Component.text(
                            "Validated and activated "
                                + registered.type()
                                + " building "
                                + registered.id(),
                            NamedTextColor.GREEN)),
                failure ->
                    player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED))));
  }

  private static BuildingType parseBuildingType(String value) {
    return BuildingType.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
  }

  private static IllegalArgumentException buildingUsage() {
    return new IllegalArgumentException(
        "usage: /frontier city building start <type> [district-uuid] | preview | confirm | cancel | revalidate | unregister | history | transfer | transfer-accept");
  }

  private void district(Player player, String[] args) {
    try {
      if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
        withCity(
            player,
            city -> districts.list(city.id(), player.getUniqueId()),
            values ->
                values.isEmpty()
                    ? "No districts. Use /frontier district create <type> <name>."
                    : values.stream()
                        .map(value -> value.name() + " [" + value.type() + "]")
                        .collect(java.util.stream.Collectors.joining(" | ")));
        return;
      }
      String action = args[0].toLowerCase(Locale.ROOT);
      switch (action) {
        case "create" -> {
          if (args.length < 3)
            throw new IllegalArgumentException(
                "usage: /frontier district create <type> [radius] <name>");
          DistrictType type = DistrictType.valueOf(args[1].toUpperCase(Locale.ROOT));
          int radius = 16;
          int nameStart = 2;
          if (args.length >= 4) {
            try {
              radius = districtRadius(args[2]);
              nameStart = 3;
            } catch (NumberFormatException ignored) {
              // Radius is optional; a non-number starts the district name.
            }
          }
          String name = String.join(" ", Arrays.copyOfRange(args, nameStart, args.length));
          SettlementGateway.Bounds bounds = districtBounds(player, radius);
          withCity(
              player,
              city ->
                  districts.create(
                      city.id(), player.getUniqueId(), name, type, bounds, Instant.now()),
              value ->
                  "Created "
                      + value.type()
                      + " district "
                      + value.name()
                      + " ("
                      + value.id()
                      + ")");
        }
        case "delete" -> {
          String reference = districtReference(args, 1, "delete <district>");
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                districts.delete(district, player.getUniqueId(), Instant.now());
                return reference;
              },
              value -> "Deleted district " + value);
        }
        case "resize" -> {
          String reference = districtReference(args, 1, "resize <district> <radius>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district resize <district> <radius>");
          SettlementGateway.Bounds bounds = districtBounds(player, districtRadius(args[2]));
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                return districts.resize(district, player.getUniqueId(), bounds, Instant.now());
              },
              value -> "Resized " + value.name());
        }
        case "rename" -> {
          String reference = districtReference(args, 1, "rename <district> <name>");
          if (args.length < 3)
            throw new IllegalArgumentException(
                "usage: /frontier district rename <district> <name>");
          String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                return districts.rename(district, player.getUniqueId(), name, Instant.now());
              },
              value -> "Renamed district to " + value.name());
        }
        case "manager", "manager-assign", "manager-transfer" -> {
          String reference = districtReference(args, 1, action + " <district> <player>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district " + action + " <district> <player>");
          UUID manager = resolvePlayer(player, args[2]);
          execute(
              player,
              () -> {
                var district = districts.resolve(player.getUniqueId(), reference);
                return districts.manager(
                    district.id(),
                    player.getUniqueId(),
                    manager,
                    action.equals("manager-transfer"),
                    Instant.now());
              },
              value -> "District manager is now " + value.manager());
        }
        case "manager-remove" -> {
          String reference = districtReference(args, 1, "manager-remove <district>");
          if (args.length != 2)
            throw new IllegalArgumentException(
                "usage: /frontier district manager-remove <district>");
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                return districts.removeManager(district, player.getUniqueId(), Instant.now());
              },
              value -> "Removed district manager from " + value.name());
        }
        case "budget" -> {
          String reference = districtReference(args, 1, "budget <district> <cents>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district budget <district> <cents>");
          long amount = Long.parseLong(args[2]);
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                return districts.budget(district, player.getUniqueId(), amount, Instant.now());
              },
              value -> "District budget is " + value.budgetMinor() + " cents");
        }
        case "priority" -> {
          String reference = districtReference(args, 1, "priority <district> <0-100>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district priority <district> <0-100>");
          int priority = Integer.parseInt(args[2]);
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                return districts.priority(district, player.getUniqueId(), priority, Instant.now());
              },
              value -> "District priority is " + value.priority());
        }
        case "production-priority", "repair-priority" -> {
          String reference = districtReference(args, 1, action + " <district> <0-100>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district " + action + " <district> <0-100>");
          int priority = Integer.parseInt(args[2]);
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                return action.equals("production-priority")
                    ? districts.productionPriority(
                        district, player.getUniqueId(), priority, Instant.now())
                    : districts.repairPriority(
                        district, player.getUniqueId(), priority, Instant.now());
              },
              value ->
                  action.equals("production-priority")
                      ? "Production priority is " + value.productionPriority()
                      : "Repair priority is " + value.repairPriority());
        }
        case "policy" -> {
          String reference = districtReference(args, 1, "policy <district> <key> <value>");
          if (args.length != 4)
            throw new IllegalArgumentException(
                "usage: /frontier district policy <district> <key> <value>");
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                return districts.policy(
                    district, player.getUniqueId(), args[2], args[3], Instant.now());
              },
              value -> "District policies: " + value.policies());
        }
        case "worker-assign" -> {
          String reference =
              districtReference(args, 1, "worker-assign <district> <worker-uuid> [priority]");
          if (args.length < 3 || args.length > 4)
            throw new IllegalArgumentException(
                "usage: /frontier district worker-assign <district> <worker-uuid> [priority]");
          UUID worker = UUID.fromString(args[2]);
          int priority = args.length == 4 ? Integer.parseInt(args[3]) : 50;
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                return districts.worker(
                    district, player.getUniqueId(), worker, priority, Instant.now());
              },
              value -> "Assigned worker " + value.worker());
        }
        case "worker-remove" -> {
          String reference = districtReference(args, 1, "worker-remove <district> <worker-uuid>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district worker-remove <district> <worker-uuid>");
          UUID worker = UUID.fromString(args[2]);
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                districts.removeWorker(district, player.getUniqueId(), worker, Instant.now());
                return worker;
              },
              value -> "Removed worker " + value);
        }
        case "building-assign", "building-remove" -> {
          String reference = districtReference(args, 1, action + " <district> <building-uuid>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district " + action + " <district> <building-uuid>");
          UUID building = UUID.fromString(args[2]);
          execute(
              player,
              () -> {
                UUID district = districts.resolve(player.getUniqueId(), reference).id();
                if (action.equals("building-assign"))
                  return districts
                      .building(district, player.getUniqueId(), building, Instant.now())
                      .building();
                districts.removeBuilding(district, player.getUniqueId(), building, Instant.now());
                return building;
              },
              value ->
                  (action.equals("building-assign") ? "Assigned building " : "Removed building ")
                      + value);
        }
        case "view", "info", "select" -> {
          String reference = districtReference(args, 1, action + " <district> [view]");
          String view = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "overview";
          if (!java.util.Set.of(
                  "overview",
                  "manager",
                  "budget",
                  "workers",
                  "buildings",
                  "production",
                  "maintenance",
                  "reports",
                  "policies",
                  "history")
              .contains(view)) throw new IllegalArgumentException("unknown district dialog view");
          schedulers
              .async(
                  () -> {
                    UUID district = districts.resolve(player.getUniqueId(), reference).id();
                    return districts.report(district, player.getUniqueId());
                  })
              .whenComplete(
                  (report, failure) ->
                      schedulers.global(
                          () -> {
                            if (failure != null)
                              player.sendMessage(
                                  Component.text(rootMessage(failure), NamedTextColor.RED));
                            else
                              ui.openDistrict(
                                  new PlayerId(player.getUniqueId()),
                                  report.district().id(),
                                  view,
                                  districtSummary(report, view));
                          }));
        }
        default ->
            throw new IllegalArgumentException(
                "district actions: list, create, select, info, rename, resize, delete, manager, manager-remove, budget, building-assign, worker-assign, production-priority, repair-priority, policy, view");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private static String districtReference(String[] args, int index, String usage) {
    if (args.length <= index)
      throw new IllegalArgumentException("usage: /frontier district " + usage);
    return args[index];
  }

  private static int districtRadius(String raw) {
    int radius = Integer.parseInt(raw);
    if (radius < 1 || radius > 64)
      throw new IllegalArgumentException("district radius must be 1-64");
    return radius;
  }

  private static SettlementGateway.Bounds districtBounds(Player player, int radius) {
    var location = player.getLocation();
    return new SettlementGateway.Bounds(
        player.getWorld().getUID(),
        location.getBlockX() - radius,
        -64,
        location.getBlockZ() - radius,
        location.getBlockX() + radius,
        320,
        location.getBlockZ() + radius);
  }

  private static String districtSummary(DistrictGateway.DistrictReport report, String view) {
    var district = report.district();
    return switch (view) {
      case "budget" ->
          "Budget " + district.budgetMinor() + " cents; spent " + report.budgetSpentMinor();
      case "workers" ->
          report.workerAssignments().isEmpty()
              ? "No assigned workers; efficiency bonus "
                  + district.bonuses().workerEfficiency()
                  + "%"
              : report.workerAssignments().stream()
                  .map(value -> value.worker() + " priority " + value.priority())
                  .collect(java.util.stream.Collectors.joining(" | "));
      case "buildings" ->
          report.buildingAssignments().isEmpty()
              ? "No assigned buildings."
              : report.buildingAssignments().stream()
                  .map(value -> value.type() + " " + value.building() + " [" + value.status() + "]")
                  .collect(java.util.stream.Collectors.joining(" | "));
      case "manager" ->
          "Manager " + district.manager() + "; " + report.members() + " district memberships";
      case "production" ->
          "Production priority "
              + district.productionPriority()
              + "; production bonus "
              + district.bonuses().production()
              + "%; specialization "
              + specializationStatus(report);
      case "maintenance" ->
          "Repair priority "
              + district.repairPriority()
              + "; maintenance "
              + district.maintenanceMinor()
              + " cents/day; maintenance bonus "
              + district.bonuses().maintenance()
              + "%; penalty "
              + report.specialization().maintenancePenaltyPercent()
              + "%; specialization "
              + specializationStatus(report);
      case "reports" ->
          "Stored "
              + report.storedUnits()
              + " units; effective bonuses "
              + district.bonuses()
              + "; "
              + specializationStatus(report)
              + "; wage penalty "
              + report.specialization().wagePenaltyPercent()
              + "%; market slots +"
              + report.specialization().marketOrderCapacityBonus()
              + "; warehouse capacity +"
              + report.specialization().warehouseCapacityBonusPercent()
              + "%";
      case "policies" -> "Policies " + district.policies();
      case "history" ->
          report.history().isEmpty()
              ? "No history."
              : report.history().stream()
                  .limit(8)
                  .map(entry -> entry.action() + " " + entry.details())
                  .collect(java.util.stream.Collectors.joining(" | "));
      default ->
          district.name()
              + " ["
              + district.type()
              + "] priority "
              + district.priority()
              + "; status "
              + district.status()
              + "; tier "
              + district.tier()
              + "; center "
              + district.center().x()
              + ","
              + district.center().z()
              + "; manager "
              + district.manager();
    };
  }

  private static String specializationStatus(DistrictGateway.DistrictReport report) {
    var value = report.specialization();
    return value.active()
        ? "active ("
            + value.validBuildings()
            + " buildings, "
            + value.infrastructureNodes()
            + " infrastructure, "
            + value.compatibleAdjacencies()
            + " adjacency, factor "
            + value.effectiveFactorPercent()
            + "%)"
        : "inactive (requires a valid compatible building and connected infrastructure)";
  }

  private void policy(Player player, String[] args) {
    if (args.length != 3 || !args[1].equalsIgnoreCase("tax"))
      throw new IllegalArgumentException("usage: /frontier city policy tax <LOW|STANDARD|HIGH>");
    withCity(
        player,
        city -> {
          settlements.taxPolicy(city.id(), player.getUniqueId(), args[2], Instant.now());
          return args[2].toUpperCase(Locale.ROOT);
        },
        value -> "Tax policy changed to " + value);
  }

  private void createExpedition(Player player, String[] values) {
    if (!player.hasPermission("frontier.city.create")) {
      player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
      return;
    }
    int separator = -1;
    for (int index = 0; index < values.length; index++)
      if (values[index].equals("|")) {
        separator = index;
        break;
      }
    String name =
        String.join(" ", Arrays.copyOfRange(values, 0, separator < 0 ? values.length : separator));
    String charter =
        separator < 0
            ? "Charter of " + name + ": mutual prosperity, safety, and fair government."
            : String.join(" ", Arrays.copyOfRange(values, separator + 1, values.length));
    founding.createExpedition(
        player,
        name,
        charter,
        expedition -> {
          player.sendMessage(
              Component.text(
                  "Founding expedition created: "
                      + expedition.id()
                      + ". Invite/confirm founders, stand at the core location, then use /frontier city expedition found "
                      + expedition.id(),
                  NamedTextColor.GREEN));
        },
        failure -> player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED)));
  }

  private void expedition(Player player, String[] args) {
    if (!player.hasPermission("frontier.city.create"))
      throw new IllegalArgumentException("No permission.");
    if (args.length < 2)
      throw new IllegalArgumentException(
          "usage: /frontier city expedition <status|create|invite|accept|found|cancel>");
    switch (args[1].toLowerCase(Locale.ROOT)) {
      case "status" ->
          executeOptional(
              player,
              () -> settlementLifecycle.activeExpedition(player.getUniqueId(), Instant.now()),
              value ->
                  "Expedition "
                      + value.id()
                      + ": "
                      + value.status()
                      + ", founders="
                      + value.acceptedFounders()
                      + ", expires="
                      + value.expiresAt(),
              "No active founding expedition.");
      case "create" -> {
        if (args.length < 3)
          throw new IllegalArgumentException(
              "usage: /frontier city expedition create <name> [| <charter>]");
        createExpedition(player, Arrays.copyOfRange(args, 2, args.length));
      }
      case "invite" -> {
        if (args.length != 4)
          throw new IllegalArgumentException(
              "usage: /frontier city expedition invite <expedition-uuid> <online-player>");
        UUID expedition = UUID.fromString(args[2]);
        Player target = player.getServer().getPlayerExact(args[3]);
        if (target == null) throw new IllegalArgumentException("target player must be online");
        execute(
            player,
            () ->
                settlementLifecycle.inviteFounder(
                    expedition, player.getUniqueId(), target.getUniqueId(), Instant.now()),
            value -> "Founder invited to expedition " + value.id());
      }
      case "accept" -> {
        if (args.length != 3)
          throw new IllegalArgumentException(
              "usage: /frontier city expedition accept <expedition-uuid>");
        UUID expedition = UUID.fromString(args[2]);
        execute(
            player,
            () ->
                settlementLifecycle.acceptFounder(expedition, player.getUniqueId(), Instant.now()),
            value -> "Founder confirmation recorded; accepted=" + value.acceptedFounders());
      }
      case "found" -> {
        if (args.length != 3)
          throw new IllegalArgumentException(
              "usage: /frontier city expedition found <expedition-uuid>");
        founding.found(
            player,
            UUID.fromString(args[2]),
            city -> {
              player.sendMessage(
                  Component.text("Settlement founded: " + city.name(), NamedTextColor.GREEN));
              presentation.settlementFounded(player, city.name());
            },
            failure ->
                player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED)));
      }
      case "cancel" -> {
        if (args.length != 3)
          throw new IllegalArgumentException(
              "usage: /frontier city expedition cancel <expedition-uuid>");
        UUID expedition = UUID.fromString(args[2]);
        execute(
            player,
            () ->
                settlementLifecycle.cancelExpedition(
                    expedition, player.getUniqueId(), Instant.now()),
            value -> "Founding expedition cancelled; reserved fee refunded.");
      }
      default ->
          throw new IllegalArgumentException(
              "usage: /frontier city expedition <status|create|invite|accept|found|cancel>");
    }
  }

  private void cityInfo(Player player) {
    executeOptional(
        player,
        () -> settlements.city(player.getUniqueId()),
        city ->
            "=== "
                + city.name()
                + " ===\nLevel: "
                + city.level()
                + "\nPopulation: "
                + city.population()
                + "\nProsperity: "
                + city.prosperity()
                + "/100\nCivilization: "
                + city.civilization()
                + "/100");
  }

  private void pay(Player player, String[] args) {
    try {
      if (args.length != 2)
        throw new IllegalArgumentException("usage: /frontier pay <player> <cents>");
      UUID target = resolvePlayer(player, args[0]);
      long amount = Long.parseLong(args[1]);
      execute(
          player,
          () -> finance.pay(player.getUniqueId(), target, amount, UUID.randomUUID(), Instant.now()),
          FrontierCommand::transferText);
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void treasury(Player player, String[] args) {
    String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
    try {
      switch (action) {
        case "status" ->
            withCity(
                player,
                city -> settlements.treasury(city.id()),
                balance -> "Treasury: " + balance + " cents");
        case "deposit" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier treasury deposit <cents>");
          long amount = Long.parseLong(args[1]);
          withCity(
              player,
              city ->
                  finance.deposit(
                      player.getUniqueId(), city.id(), amount, UUID.randomUUID(), Instant.now()),
              FrontierCommand::transferText);
        }
        case "withdraw" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier treasury withdraw <cents>");
          long amount = Long.parseLong(args[1]);
          withCity(
              player,
              city ->
                  finance.withdraw(
                      city.id(), player.getUniqueId(), amount, UUID.randomUUID(), Instant.now()),
              FrontierCommand::transferText);
        }
        case "pay" -> {
          if (args.length != 3)
            throw new IllegalArgumentException("usage: /frontier treasury pay <player> <cents>");
          UUID target = resolvePlayer(player, args[1]);
          long amount = Long.parseLong(args[2]);
          withCity(
              player,
              city ->
                  finance.settlementPay(
                      city.id(),
                      player.getUniqueId(),
                      target,
                      amount,
                      UUID.randomUUID(),
                      Instant.now()),
              FrontierCommand::transferText);
        }
        case "audit", "history" -> {
          int limit = args.length == 2 ? Integer.parseInt(args[1]) : 10;
          withCity(
              player,
              city -> finance.audit(city.id(), player.getUniqueId(), limit),
              lines ->
                  lines.isEmpty()
                      ? "No treasury entries."
                      : lines.stream()
                          .map(FrontierCommand::ledgerText)
                          .collect(java.util.stream.Collectors.joining("\n")));
        }
        default ->
            throw new IllegalArgumentException(
                "treasury actions: status, deposit, withdraw, pay, history");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void harbor(Player player, String[] args) {
    String action = args.length == 0 ? "tutorial" : args[0].toLowerCase(Locale.ROOT);
    try {
      switch (action) {
        case "tutorial" ->
            execute(
                player,
                () -> harbor.onboard(player.getUniqueId(), Instant.now()),
                tutorial ->
                    "Frontier Harbor: take a starter contract with /frontier harbor jobs, complete it with /frontier harbor work, then create and complete a /frontier city expedition before filling its treasury. Wallet="
                        + tutorial.balanceMinor());
        case "jobs" ->
            execute(
                player,
                () -> harbor.jobs(player.getUniqueId(), Instant.now()),
                jobs ->
                    jobs.stream()
                        .map(
                            job ->
                                job.id()
                                    + " "
                                    + job.jobType()
                                    + " "
                                    + job.rewardMinor()
                                    + "c "
                                    + job.status()
                                    + " — "
                                    + job.description())
                        .collect(java.util.stream.Collectors.joining("\n")));
        case "work" -> {
          if (args.length > 2)
            throw new IllegalArgumentException("usage: /frontier harbor work [starter-job-uuid]");
          execute(
              player,
              () ->
                  args.length == 2
                      ? harbor.complete(
                          player.getUniqueId(), UUID.fromString(args[1]), Instant.now())
                      : harbor.completeFirst(player.getUniqueId(), Instant.now()),
              receipt ->
                  "Starter contract paid "
                      + receipt.rewardMinor()
                      + " cents; wallet="
                      + receipt.playerBalanceMinor());
        }
        case "status" ->
            execute(
                player,
                () -> harbor.status(Instant.now()),
                value ->
                    value.name()
                        + ": budget="
                        + value.budgetRemainingMinor()
                        + ", jobs="
                        + value.openJobs()
                        + ", buy orders="
                        + value.openBuyOrders()
                        + ", sell orders="
                        + value.openSellOrders());
        default ->
            throw new IllegalArgumentException("harbor actions: tutorial, jobs, work, status");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private static String transferText(FinanceGateway.TransferReceipt receipt) {
    return receipt.transferType()
        + ": "
        + receipt.amountMinor()
        + " cents; source="
        + receipt.sourceBalanceMinor()
        + ", destination="
        + receipt.destinationBalanceMinor();
  }

  private static String ledgerText(FinanceGateway.LedgerLine line) {
    return line.occurredAt()
        + " "
        + line.type()
        + " "
        + line.amountMinor()
        + " => "
        + line.balanceAfterMinor();
  }

  private static UUID resolvePlayer(Player requester, String name) {
    Player online = requester.getServer().getPlayerExact(name);
    if (online != null) return online.getUniqueId();
    for (org.bukkit.OfflinePlayer candidate : requester.getServer().getOfflinePlayers()) {
      if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(name))
        return candidate.getUniqueId();
    }
    throw new IllegalArgumentException("player must have joined this server before");
  }

  private void market(Player player, String[] args) {
    if (args.length == 0) {
      execute(
          player,
          () ->
              settlements
                  .city(player.getUniqueId())
                  .orElseThrow(
                      () -> new IllegalStateException("you do not belong to a settlement")),
          city -> {
            ui.openMarket(new PlayerId(player.getUniqueId()), new SettlementId(city.id()));
            return "Opened market.";
          });
      return;
    }
    if (args[0].equalsIgnoreCase("list")) {
      withCity(
          player,
          city -> economy.openOrders(city.id()),
          orders -> {
            if (orders.isEmpty()) return "No open settlement orders.";
            return orders.stream()
                .map(
                    order ->
                        order.id()
                            + " "
                            + order.side()
                            + " "
                            + order.remaining()
                            + "x "
                            + order.commodity()
                            + " @ "
                            + order.unitPriceMinor())
                .collect(java.util.stream.Collectors.joining("\n"));
          });
      return;
    }
    try {
      switch (args[0].toLowerCase(Locale.ROOT)) {
        case "warehouse" ->
            withCity(
                player,
                city -> economy.warehouse(city.id(), player.getUniqueId(), Instant.now()),
                FrontierCommand::warehouseText);
        case "deposit" -> depositHeldItem(player, args);
        case "buy", "sell" -> placeOrder(player, args);
        case "cancel" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier market cancel <order-uuid>");
          UUID order = UUID.fromString(args[1]);
          withCity(
              player,
              city -> {
                economy.cancel(city.id(), player.getUniqueId(), order, Instant.now());
                return order;
              },
              value -> "Cancelled market order " + value);
        }
        default ->
            throw new IllegalArgumentException(
                "market actions: list, warehouse, deposit, buy, sell, cancel");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void placeOrder(Player player, String[] args) {
    if (args.length != 4)
      throw new IllegalArgumentException(
          "usage: /frontier market <buy|sell> <commodity> <quantity> <unit-price-cents>");
    MarketEngine.Side side = MarketEngine.Side.valueOf(args[0].toUpperCase(Locale.ROOT));
    long quantity = Long.parseLong(args[2]);
    long price = Long.parseLong(args[3]);
    withCity(
        player,
        city ->
            economy.order(
                city.id(),
                player.getUniqueId(),
                side,
                args[1],
                quantity,
                price,
                UUID.randomUUID(),
                Instant.now()),
        order -> "Market order placed: " + order.id());
  }

  private void depositHeldItem(Player player, String[] args) {
    if (args.length != 2)
      throw new IllegalArgumentException("usage: /frontier market deposit <quantity>");
    int quantity = Integer.parseInt(args[1]);
    var hand = player.getInventory().getItemInMainHand();
    if (hand.getType().isAir() || quantity <= 0 || hand.getAmount() < quantity)
      throw new IllegalArgumentException("hold at least that many items in your main hand");
    String commodity = hand.getType().getKey().asString();
    var compensation = hand.clone();
    compensation.setAmount(quantity);
    hand.setAmount(hand.getAmount() - quantity);
    UUID playerId = player.getUniqueId();
    schedulers
        .async(
            () -> {
              SettlementGateway.CitySnapshot city =
                  settlements
                      .city(playerId)
                      .orElseThrow(
                          () -> new IllegalStateException("you do not belong to a settlement"));
              return economy.deposit(city.id(), playerId, commodity, quantity, Instant.now());
            })
        .whenComplete(
            (warehouse, failure) ->
                schedulers.global(
                    () -> {
                      if (failure != null) {
                        player.getInventory().addItem(compensation);
                        player.sendMessage(
                            Component.text(rootMessage(failure), NamedTextColor.RED));
                      } else {
                        player.sendMessage(
                            Component.text(
                                "Deposited " + quantity + " " + commodity, NamedTextColor.GREEN));
                      }
                    }));
  }

  private static String warehouseText(EconomyGateway.WarehouseSnapshot warehouse) {
    if (warehouse.stock().isEmpty())
      return "Warehouse " + warehouse.id() + " is empty (capacity " + warehouse.capacity() + ").";
    return "Warehouse "
        + warehouse.id()
        + "\n"
        + warehouse.stock().stream()
            .map(
                stock ->
                    stock.commodity()
                        + ": "
                        + stock.available()
                        + " available, "
                        + stock.reserved()
                        + " reserved")
            .collect(java.util.stream.Collectors.joining("\n"));
  }

  private void production(Player player, String[] args) {
    if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
      withCity(
          player,
          city -> production.orders(city.id()),
          orders ->
              orders.isEmpty()
                  ? "No production orders."
                  : orders.stream()
                      .map(
                          order ->
                              order.id()
                                  + " "
                                  + order.recipe()
                                  + " "
                                  + order.status()
                                  + " "
                                  + order.progress()
                                  + "/"
                                  + order.target())
                      .collect(java.util.stream.Collectors.joining("\n")));
      return;
    }
    try {
      switch (args[0].toLowerCase(Locale.ROOT)) {
        case "queue" -> {
          if (args.length < 4 || args.length > 5)
            throw new IllegalArgumentException(
                "usage: /frontier production queue <building-uuid> <recipe> <quantity> [priority]");
          UUID building = UUID.fromString(args[1]);
          int quantity = Integer.parseInt(args[3]);
          int priority = args.length == 5 ? Integer.parseInt(args[4]) : 50;
          withCity(
              player,
              city ->
                  production.queue(
                      city.id(),
                      player.getUniqueId(),
                      building,
                      args[2],
                      quantity,
                      priority,
                      UUID.randomUUID(),
                      Instant.now()),
              order -> "Production order queued: " + order.id() + " (" + order.status() + ")");
        }
        case "hire" -> {
          if (args.length != 4)
            throw new IllegalArgumentException(
                "usage: /frontier production hire <profession> <skill> <daily-salary-cents>");
          int skill = Integer.parseInt(args[2]);
          long salary = Long.parseLong(args[3]);
          withCity(
              player,
              city ->
                  production.hire(
                      city.id(), player.getUniqueId(), args[1], skill, salary, Instant.now()),
              worker -> "Worker hired: " + worker.id() + " (" + worker.profession() + ")");
        }
        default -> throw new IllegalArgumentException("production actions: list, queue, hire");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void logistics(Player player, String[] args) {
    if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
      withCity(
          player,
          city -> logistics.shipments(city.id()),
          shipments ->
              shipments.isEmpty()
                  ? "No shipments."
                  : shipments.stream()
                      .map(
                          shipment ->
                              shipment.id()
                                  + " "
                                  + shipment.status()
                                  + " "
                                  + shipment.quantity()
                                  + "x "
                                  + shipment.commodity())
                      .collect(java.util.stream.Collectors.joining("\n")));
      return;
    }
    try {
      switch (args[0].toLowerCase(Locale.ROOT)) {
        case "node" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier logistics node <type>");
          var location = player.getLocation();
          withCity(
              player,
              city ->
                  logistics.registerNode(
                      city.id(),
                      player.getUniqueId(),
                      player.getWorld().getUID(),
                      location.getBlockX(),
                      location.getBlockY(),
                      location.getBlockZ(),
                      args[1].toUpperCase(Locale.ROOT),
                      Instant.now()),
              node -> "Road node registered: " + node.id());
        }
        case "connect" -> {
          if (args.length < 4 || args.length > 5)
            throw new IllegalArgumentException(
                "usage: /frontier logistics connect <from-node> <to-node> <type> [importance]");
          UUID from = UUID.fromString(args[1]);
          UUID to = UUID.fromString(args[2]);
          InfrastructureType type = InfrastructureType.valueOf(args[3].toUpperCase(Locale.ROOT));
          int importance = args.length == 5 ? Integer.parseInt(args[4]) : 50;
          schedulers
              .async(() -> settlements.city(player.getUniqueId()))
              .whenComplete(
                  (city, error) ->
                      schedulers.forEntity(
                          player.getUniqueId(),
                          () -> {
                            if (error != null)
                              player.sendMessage(
                                  Component.text(rootMessage(error), NamedTextColor.RED));
                            else if (city.isEmpty())
                              player.sendMessage(
                                  Component.text(
                                      "You are not in a settlement.", NamedTextColor.RED));
                            else
                              infrastructureRegistrations.register(
                                  player,
                                  city.orElseThrow().id(),
                                  from,
                                  to,
                                  type,
                                  importance,
                                  edge ->
                                      player.sendMessage(
                                          Component.text(
                                              "Validated "
                                                  + edge.type()
                                                  + " edge "
                                                  + edge.id()
                                                  + " health="
                                                  + edge.health()
                                                  + " capacity="
                                                  + edge.capacity(),
                                              NamedTextColor.GREEN)),
                                  failure ->
                                      player.sendMessage(
                                          Component.text(
                                              rootMessage(failure), NamedTextColor.RED)));
                          },
                          () -> {}));
        }
        case "ship" -> {
          if (args.length != 9)
            throw new IllegalArgumentException(
                "usage: /frontier logistics ship <origin-warehouse> <destination-warehouse> <origin-node> <destination-node> <commodity> <quantity> <carrier> <declared-value>");
          UUID originWarehouse = UUID.fromString(args[1]);
          UUID destinationWarehouse = UUID.fromString(args[2]);
          UUID originNode = UUID.fromString(args[3]);
          UUID destinationNode = UUID.fromString(args[4]);
          long quantity = Long.parseLong(args[6]);
          long declaredValue = Long.parseLong(args[8]);
          if (quantity <= 0 || declaredValue < 0)
            throw new IllegalArgumentException("invalid shipment quantity or value");
          withCity(
              player,
              city ->
                  logistics.createShipment(
                      city.id(),
                      player.getUniqueId(),
                      originWarehouse,
                      destinationWarehouse,
                      originNode,
                      destinationNode,
                      args[5].toLowerCase(Locale.ROOT),
                      quantity,
                      args[7].toUpperCase(Locale.ROOT),
                      declaredValue,
                      UUID.randomUUID(),
                      Instant.now()),
              shipment -> "Shipment created: " + shipment.id() + " (" + shipment.status() + ")");
        }
        case "maintenance" ->
            withCity(
                player,
                city -> infrastructureHealth.maintenance(city.id()),
                orders ->
                    orders.isEmpty()
                        ? "No infrastructure maintenance orders."
                        : orders.stream()
                            .map(
                                order ->
                                    order.id()
                                        + " "
                                        + order.priority()
                                        + " "
                                        + order.status()
                                        + " edge="
                                        + order.edge()
                                        + " estimate="
                                        + order.estimateMinor())
                            .collect(java.util.stream.Collectors.joining("\n")));
        case "warnings" ->
            withCity(
                player,
                city -> infrastructureHealth.warnings(city.id()),
                warnings ->
                    warnings.isEmpty()
                        ? "No active infrastructure warnings."
                        : warnings.stream()
                            .map(
                                warning ->
                                    warning.severity()
                                        + " edge="
                                        + warning.edge()
                                        + " "
                                        + warning.message())
                            .collect(java.util.stream.Collectors.joining("\n")));
        case "maintain" -> {
          if (args.length != 2)
            throw new IllegalArgumentException(
                "usage: /frontier logistics maintain <maintenance-order>");
          UUID maintenance = UUID.fromString(args[1]);
          withCity(
              player,
              city ->
                  repairs.purchaseInfrastructure(
                      city.id(),
                      player.getUniqueId(),
                      maintenance,
                      UUID.randomUUID(),
                      Instant.now()),
              repair ->
                  "Infrastructure repair funded: " + repair.id() + " (" + repair.status() + ")");
        }
        default ->
            throw new IllegalArgumentException(
                "logistics actions: list, node, connect, ship, maintenance, warnings, maintain");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void caravan(Player player, String[] args) {
    try {
      String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
      switch (action) {
        case "list" ->
            execute(
                player,
                () -> caravans.presentations(100),
                values ->
                    values.isEmpty()
                        ? "No active caravans."
                        : values.stream()
                            .map(
                                value ->
                                    value.shipment()
                                        + " "
                                        + value.state()
                                        + " health="
                                        + value.health()
                                        + " cargo="
                                        + value.cargo())
                            .collect(java.util.stream.Collectors.joining(" | ")));
        case "escort" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier caravan escort <shipment-uuid>");
          UUID shipment = UUID.fromString(args[1]);
          execute(
              player,
              () -> caravans.escort(shipment, player.getUniqueId(), Instant.now()),
              value -> "You are escorting caravan " + value.shipment());
        }
        default -> throw new IllegalArgumentException("caravan actions: list, escort");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void population(Player player) {
    withCity(
        player,
        city -> population.report(city.id(), player.getUniqueId()),
        value ->
            "=== Population Report ===\nPopulation: "
                + value.population()
                + "/"
                + value.housingCapacity()
                + "\nFood security: "
                + value.foodSecurity()
                + "%\nSafety: "
                + value.safety()
                + "%\nProsperity: "
                + value.prosperity()
                + "\nBirths / deaths: "
                + value.births()
                + "/"
                + value.deaths()
                + "\nImmigration / emigration: "
                + value.immigration()
                + "/"
                + value.emigration());
  }

  private void workers(Player player, String[] args) {
    try {
      String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
      switch (action) {
        case "list" ->
            withCity(
                player,
                city -> population.workers(city.id(), player.getUniqueId()),
                values ->
                    values.isEmpty()
                        ? "No settlement workers."
                        : values.stream()
                            .map(FrontierCommand::workerLine)
                            .collect(java.util.stream.Collectors.joining("\n")));
        case "assign" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier workers assign <worker> <building|none>");
          UUID worker = UUID.fromString(args[1]);
          withCity(
              player,
              city ->
                  args[2].equalsIgnoreCase("none")
                      ? population.clearBuilding(
                          city.id(), player.getUniqueId(), worker, Instant.now())
                      : population.assignBuilding(
                          city.id(),
                          player.getUniqueId(),
                          worker,
                          UUID.fromString(args[2]),
                          Instant.now()),
              value -> "Worker updated: " + workerLine(value));
        }
        case "wage" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier workers wage <worker> <daily-cents>");
          UUID worker = UUID.fromString(args[1]);
          long wage = Long.parseLong(args[2]);
          withCity(
              player,
              city ->
                  population.setWage(city.id(), player.getUniqueId(), worker, wage, Instant.now()),
              value -> "Worker updated: " + workerLine(value));
        }
        default -> throw new IllegalArgumentException("worker actions: list, assign, wage");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private static String workerLine(nl.frontier.city.PopulationGateway.WorkerProfile value) {
    return value.id()
        + " "
        + value.profession()
        + " skill="
        + value.skill()
        + " morale="
        + value.morale()
        + " wage="
        + value.salaryMinor()
        + " building="
        + value.assignedBuilding()
        + " district="
        + value.assignedDistrict()
        + " status="
        + value.status()
        + " task="
        + value.currentTask()
        + " xp="
        + value.experience();
  }

  private void commercial(Player player, String[] args) {
    try {
      String action = args.length == 0 ? "companies" : args[0].toLowerCase(Locale.ROOT);
      switch (action) {
        case "companies" ->
            execute(
                player,
                () -> commerce.companies(player.getUniqueId()),
                values ->
                    values.isEmpty()
                        ? "You do not own a company."
                        : values.stream()
                            .map(
                                value ->
                                    value.id()
                                        + " "
                                        + value.name()
                                        + " balance="
                                        + value.balanceMinor())
                            .collect(java.util.stream.Collectors.joining(" | ")));
        case "company-create" -> {
          if (args.length < 3)
            throw new IllegalArgumentException(
                "usage: /frontier economy company-create <capital-cents> <name>");
          long capital = Long.parseLong(args[1]);
          String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
          withCity(
              player,
              city ->
                  commerce.createCompany(
                      player.getUniqueId(),
                      city.id(),
                      name,
                      capital,
                      UUID.randomUUID(),
                      Instant.now()),
              value -> "Company created: " + value.name() + " (" + value.id() + ")");
        }
        case "invoice" -> {
          if (args.length != 5)
            throw new IllegalArgumentException(
                "usage: /frontier economy invoice <company-uuid> <player> <cents> <due-days>");
          UUID company = UUID.fromString(args[1]);
          UUID target = resolvePlayer(player, args[2]);
          long amount = Long.parseLong(args[3]);
          int days = Integer.parseInt(args[4]);
          execute(
              player,
              () ->
                  commerce.invoice(
                      company, player.getUniqueId(), target, amount, days, Instant.now()),
              value -> "Invoice issued: " + value.id());
        }
        case "invoice-pay" -> {
          if (args.length != 2)
            throw new IllegalArgumentException(
                "usage: /frontier economy invoice-pay <invoice-uuid>");
          UUID invoice = UUID.fromString(args[1]);
          execute(
              player,
              () ->
                  commerce.payInvoice(
                      invoice, player.getUniqueId(), UUID.randomUUID(), Instant.now()),
              value -> "Invoice paid: " + value.id());
        }
        case "loan" -> {
          if (args.length != 4)
            throw new IllegalArgumentException(
                "usage: /frontier economy loan <company-uuid> <cents> <annual-basis-points>");
          UUID company = UUID.fromString(args[1]);
          long amount = Long.parseLong(args[2]);
          int rate = Integer.parseInt(args[3]);
          execute(
              player,
              () ->
                  commerce.borrow(
                      company,
                      player.getUniqueId(),
                      amount,
                      rate,
                      UUID.randomUUID(),
                      Instant.now()),
              value -> "Loan created: " + value.id());
        }
        case "loan-repay" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier economy loan-repay <loan-uuid> <cents>");
          UUID loan = UUID.fromString(args[1]);
          long amount = Long.parseLong(args[2]);
          execute(
              player,
              () ->
                  commerce.repay(
                      loan, player.getUniqueId(), amount, UUID.randomUUID(), Instant.now()),
              value ->
                  "Loan outstanding: "
                      + value.outstandingMinor()
                      + " + "
                      + value.accruedInterestMinor()
                      + " interest");
        }
        case "tax" -> {
          if (args.length != 2)
            throw new IllegalArgumentException(
                "usage: /frontier economy tax <business-basis-points>");
          int rate = Integer.parseInt(args[1]);
          withCity(
              player,
              city -> commerce.tax(city.id(), player.getUniqueId(), rate, Instant.now()),
              value -> "Business tax is " + value.basisPoints() + " basis points");
        }
        case "procure" -> {
          if (args.length != 4)
            throw new IllegalArgumentException(
                "usage: /frontier economy procure <commodity> <quantity> <max-unit-cents>");
          long quantity = Long.parseLong(args[2]);
          long price = Long.parseLong(args[3]);
          withCity(
              player,
              city ->
                  commerce.procure(
                      city.id(), player.getUniqueId(), args[1], quantity, price, Instant.now()),
              value -> "Procurement posted: " + value.id());
        }
        case "fulfill" -> {
          if (args.length != 4)
            throw new IllegalArgumentException(
                "usage: /frontier economy fulfill <procurement-uuid> <company-uuid> <quantity>");
          UUID procurement = UUID.fromString(args[1]);
          UUID company = UUID.fromString(args[2]);
          long quantity = Long.parseLong(args[3]);
          execute(
              player,
              () ->
                  commerce.fulfill(
                      procurement, company, player.getUniqueId(), quantity, Instant.now()),
              value ->
                  "Procurement "
                      + value.status()
                      + " "
                      + value.fulfilled()
                      + "/"
                      + value.quantity());
        }
        case "emergency" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier economy emergency <commodity> <quantity>");
          long quantity = Long.parseLong(args[2]);
          withCity(
              player,
              city ->
                  commerce.emergencyBuy(
                      city.id(),
                      player.getUniqueId(),
                      args[1],
                      quantity,
                      UUID.randomUUID(),
                      Instant.now()),
              value -> "Emergency purchase cost " + value.totalMinor() + " cents");
        }
        case "history" ->
            withCity(
                player,
                city -> commerce.history(city.id(), player.getUniqueId()),
                values ->
                    values.isEmpty()
                        ? "No commercial history."
                        : values.stream()
                            .limit(20)
                            .map(value -> value.type() + " " + value.details())
                            .collect(java.util.stream.Collectors.joining(" | ")));
        default ->
            throw new IllegalArgumentException(
                "economy actions: companies, company-create, invoice, invoice-pay, loan, loan-repay, tax, procure, fulfill, emergency, history");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void contracts(Player player, String[] args) {
    if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
      execute(
          player,
          () -> contracts.available(Instant.now()),
          values ->
              values.isEmpty()
                  ? "No available contracts."
                  : values.stream()
                      .map(
                          contract ->
                              contract.id()
                                  + " "
                                  + contract.status()
                                  + " "
                                  + contract.quantity()
                                  + "x "
                                  + contract.commodity()
                                  + " reward "
                                  + contract.rewardMinor())
                      .collect(java.util.stream.Collectors.joining("\n")));
      return;
    }
    try {
      switch (args[0].toLowerCase(Locale.ROOT)) {
        case "post" -> {
          if (args.length != 6)
            throw new IllegalArgumentException(
                "usage: /frontier contracts post <destination-warehouse> <commodity> <quantity> <reward-cents> <deadline-minutes>");
          UUID warehouse = UUID.fromString(args[1]);
          long quantity = Long.parseLong(args[3]);
          long reward = Long.parseLong(args[4]);
          long minutes = Long.parseLong(args[5]);
          withCity(
              player,
              city ->
                  contracts.postDelivery(
                      city.id(),
                      player.getUniqueId(),
                      warehouse,
                      args[2].toLowerCase(Locale.ROOT),
                      quantity,
                      reward,
                      Instant.now().plusSeconds(Math.multiplyExact(minutes, 60)),
                      UUID.randomUUID(),
                      Instant.now()),
              contract -> "Delivery contract posted: " + contract.id());
        }
        case "accept" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier contracts accept <contract-uuid>");
          UUID contract = UUID.fromString(args[1]);
          execute(
              player,
              () -> contracts.accept(contract, player.getUniqueId(), Instant.now()),
              value -> "Contract accepted: " + value.id());
        }
        case "deliver" -> {
          if (args.length != 2)
            throw new IllegalArgumentException(
                "usage: /frontier contracts deliver <contract-uuid>");
          UUID contract = UUID.fromString(args[1]);
          execute(
              player,
              () ->
                  contracts.deliver(
                      contract, player.getUniqueId(), UUID.randomUUID(), Instant.now()),
              value -> "Contract completed and paid: " + value.id());
        }
        default ->
            throw new IllegalArgumentException("contract actions: list, post, accept, deliver");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void war(Player player, String[] args) {
    if (args.length == 0) {
      withCity(
          player,
          city -> campaigns.campaigns(city.id()),
          values -> {
            if (values.isEmpty()) return "No campaigns.";
            ui.openWar(new PlayerId(player.getUniqueId()), new WarId(values.getFirst().id()));
            return "Opened campaign.";
          });
      return;
    }
    if (args[0].equalsIgnoreCase("list")) {
      withCity(
          player,
          city -> campaigns.campaigns(city.id()),
          values ->
              values.isEmpty()
                  ? "No campaigns."
                  : values.stream()
                      .map(
                          campaign ->
                              campaign.id()
                                  + " "
                                  + campaign.type()
                                  + " "
                                  + campaign.phase()
                                  + " attacker="
                                  + campaign.attacker()
                                  + " defender="
                                  + campaign.defender())
                      .collect(java.util.stream.Collectors.joining("\n")));
      return;
    }
    try {
      switch (args[0].toLowerCase(Locale.ROOT)) {
        case "declare" -> {
          if (args.length < 4 || args.length > 5)
            throw new IllegalArgumentException(
                "usage: /frontier war declare <defender-city-uuid> <type> <objective-type> [target]");
          UUID defender = UUID.fromString(args[1]);
          WarCampaign.Type type = WarCampaign.Type.valueOf(args[2].toUpperCase(Locale.ROOT));
          long target = args.length == 5 ? Long.parseLong(args[4]) : 100;
          var location = player.getLocation();
          CampaignGateway.ObjectiveSpec objective =
              new CampaignGateway.ObjectiveSpec(
                  args[3].toUpperCase(Locale.ROOT),
                  player.getWorld().getUID(),
                  location.getBlockX() - 16,
                  Math.max(player.getWorld().getMinHeight(), location.getBlockY() - 32),
                  location.getBlockZ() - 16,
                  location.getBlockX() + 16,
                  Math.min(player.getWorld().getMaxHeight() - 1, location.getBlockY() + 32),
                  location.getBlockZ() + 16,
                  target,
                  1);
          withCity(
              player,
              city ->
                  campaigns.declare(
                      city.id(),
                      player.getUniqueId(),
                      defender,
                      type,
                      objective,
                      campaignDeclarationCost,
                      campaignPreparation,
                      campaignMaximumDuration,
                      UUID.randomUUID(),
                      Instant.now()),
              campaign ->
                  "Campaign declared: "
                      + campaign.id()
                      + "; active after "
                      + campaign.scheduledActiveAt());
        }
        case "outcome" -> {
          if (args.length < 3 || args.length > 4)
            throw new IllegalArgumentException(
                "usage: /frontier war outcome <campaign-uuid> <outcome> [amount-cents]");
          UUID campaign = UUID.fromString(args[1]);
          CampaignOutcomeGateway.Outcome outcome =
              CampaignOutcomeGateway.Outcome.valueOf(args[2].toUpperCase(Locale.ROOT));
          long amount = args.length == 4 ? Long.parseLong(args[3]) : 0;
          execute(
              player,
              () ->
                  campaignOutcomes.apply(
                      campaign, player.getUniqueId(), outcome, amount, Instant.now()),
              value ->
                  "Campaign outcome "
                      + value.outcome()
                      + " winner="
                      + value.winner()
                      + " transferred claims/buildings/roads/workers/storage="
                      + value.claims()
                      + "/"
                      + value.buildings()
                      + "/"
                      + value.roads()
                      + "/"
                      + value.workers()
                      + "/"
                      + value.storageUnits());
        }
        case "ceasefire", "resume", "resolve", "end" -> {
          if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException(
                "usage: /frontier war <ceasefire|resume|resolve|end> <campaign-uuid> [reason]");
          UUID campaign = UUID.fromString(args[1]);
          String reason = args.length == 3 ? args[2] : "PLAYER_REQUEST";
          execute(
              player,
              () ->
                  switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "ceasefire" ->
                        campaigns.ceasefire(campaign, player.getUniqueId(), Instant.now());
                    case "resume" ->
                        campaigns.resume(campaign, player.getUniqueId(), Instant.now());
                    case "resolve" ->
                        campaigns.resolve(campaign, player.getUniqueId(), reason, Instant.now());
                    default -> campaigns.end(campaign, player.getUniqueId(), reason, Instant.now());
                  },
              value -> "Campaign is now " + value.phase());
        }
        default ->
            throw new IllegalArgumentException(
                "war actions: list, declare, ceasefire, resume, resolve, outcome, end");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void repair(Player player, String[] args) {
    if (args.length == 0) {
      withCity(
          player,
          city -> repairs.orders(city.id()),
          values -> {
            if (values.isEmpty()) return "No repair orders.";
            ui.openRepair(
                new PlayerId(player.getUniqueId()), new RepairOrderId(values.getFirst().id()));
            return "Opened repair order.";
          });
      return;
    }
    if (args[0].equalsIgnoreCase("list")) {
      withCity(
          player,
          city -> repairs.orders(city.id()),
          values ->
              values.isEmpty()
                  ? "No repair orders."
                  : values.stream()
                      .map(
                          order ->
                              order.id()
                                  + " "
                                  + order.status()
                                  + " "
                                  + order.completedTasks()
                                  + "/"
                                  + order.totalTasks()
                                  + (order.shortages().isEmpty()
                                      ? ""
                                      : " shortages=" + order.shortages()))
                      .collect(java.util.stream.Collectors.joining("\n")));
      return;
    }
    try {
      if (!args[0].equalsIgnoreCase("quote") && !args[0].equalsIgnoreCase("buy"))
        throw new IllegalArgumentException("repair actions: list, quote, buy");
      if (args.length < 2 || args.length > 3)
        throw new IllegalArgumentException(
            "usage: /frontier repair <quote|buy> <campaign-uuid> [priority]");
      UUID campaign = UUID.fromString(args[1]);
      RepairOrder.Priority priority =
          args.length == 3
              ? RepairOrder.Priority.valueOf(args[2].toUpperCase(Locale.ROOT))
              : RepairOrder.Priority.NORMAL;
      if (args[0].equalsIgnoreCase("quote")) {
        withCity(
            player,
            city ->
                repairs.quote(city.id(), player.getUniqueId(), campaign, priority, Instant.now()),
            quote ->
                "Repair quote: "
                    + quote.tasks()
                    + " blocks, labor "
                    + quote.laborCostMinor()
                    + ", materials "
                    + quote.materialCostMinor()
                    + ", total "
                    + quote.totalCostMinor()
                    + ", requirements "
                    + quote.requirements());
      } else {
        withCity(
            player,
            city ->
                repairs.purchase(
                    city.id(),
                    player.getUniqueId(),
                    campaign,
                    priority,
                    UUID.randomUUID(),
                    Instant.now()),
            order -> "Repair purchased: " + order.id() + " (" + order.status() + ")");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void guild(Player player, String[] args) {
    String action = args.length == 0 ? "overview" : args[0].toLowerCase(Locale.ROOT);
    try {
      switch (action) {
        case "overview", "queue" ->
            withCity(
                player,
                city -> guilds.overview(city.id(), player.getUniqueId(), Instant.now()),
                overview -> {
                  String projects =
                      overview.projects().isEmpty()
                          ? "none"
                          : overview.projects().stream()
                              .map(
                                  project ->
                                      project.id()
                                          + " "
                                          + project.priority()
                                          + " "
                                          + project.completed()
                                          + "/"
                                          + project.total()
                                          + (project.blockedReasons().isEmpty()
                                              ? ""
                                              : " blocked=" + project.blockedReasons()))
                              .collect(java.util.stream.Collectors.joining("; "));
                  return "Builder Guild tier "
                      + overview.tier()
                      + ": depot "
                      + overview.stored()
                      + "/"
                      + overview.capacity()
                      + ", teams "
                      + overview.teams().size()
                      + "/"
                      + overview.teamCapacity()
                      + ", builders "
                      + overview.availableBuilders()
                      + ", shortage "
                      + overview.workerShortage()
                      + ", foreman "
                      + (overview.foreman() == null ? "none" : overview.foreman())
                      + "\nProjects: "
                      + projects;
                });
        case "foreman" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier guild foreman <worker-uuid>");
          UUID worker = UUID.fromString(args[1]);
          withCity(
              player,
              city -> guilds.appointForeman(city.id(), player.getUniqueId(), worker, Instant.now()),
              overview -> "Builder Guild foreman is now " + overview.foreman());
        }
        case "team" -> {
          if (args.length < 4)
            throw new IllegalArgumentException(
                "usage: /frontier guild team <name> <foreman-uuid> <builder-uuid...>");
          String name = args[1];
          UUID foreman = UUID.fromString(args[2]);
          List<UUID> builders = Arrays.stream(args, 3, args.length).map(UUID::fromString).toList();
          withCity(
              player,
              city ->
                  guilds.createTeam(
                      city.id(), player.getUniqueId(), name, foreman, builders, Instant.now()),
              team ->
                  "Created builder team "
                      + team.name()
                      + " with "
                      + team.builders()
                      + "/"
                      + team.capacity()
                      + " builders.");
        }
        case "priority" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier guild priority <repair-uuid> <priority>");
          UUID order = UUID.fromString(args[1]);
          RepairOrder.Priority priority =
              RepairOrder.Priority.valueOf(args[2].toUpperCase(Locale.ROOT));
          withCity(
              player,
              city ->
                  guilds.prioritize(
                      city.id(), player.getUniqueId(), order, priority, Instant.now()),
              project -> "Repair priority is now " + project.priority() + ".");
        }
        case "emergency" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier guild emergency <repair-uuid>");
          UUID order = UUID.fromString(args[1]);
          withCity(
              player,
              city -> guilds.emergency(city.id(), player.getUniqueId(), order, Instant.now()),
              project -> "Emergency repair activated for " + project.id() + ".");
        }
        case "deliver" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier guild deliver <repair-uuid> <amount>");
          UUID order = UUID.fromString(args[1]);
          int amount = Integer.parseInt(args[2]);
          withCityEntity(
              player, city -> guildCoordinator.deliver(player, city.id(), order, amount));
        }
        case "boost" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier guild boost <repair-uuid> <points>");
          UUID order = UUID.fromString(args[1]);
          int points = Integer.parseInt(args[2]);
          withCity(
              player,
              city ->
                  guilds.boost(
                      city.id(),
                      player.getUniqueId(),
                      order,
                      points,
                      UUID.randomUUID(),
                      Instant.now()),
              contribution -> "Project boosted by " + contribution.units() + " points.");
        }
        case "inspect" ->
            withCityEntity(
                player,
                city -> {
                  var target = player.getTargetBlockExact(8);
                  if (target == null) {
                    player.sendMessage(
                        Component.text(
                            "Look at a repair block within 8 blocks.", NamedTextColor.RED));
                    return;
                  }
                  execute(
                      player,
                      () ->
                          guilds.inspect(
                              city.id(),
                              player.getUniqueId(),
                              target.getWorld().getUID(),
                              target.getX(),
                              target.getY(),
                              target.getZ(),
                              Instant.now()),
                      zone ->
                          "Repair task "
                              + zone.task()
                              + " is "
                              + zone.taskStatus()
                              + "; target="
                              + zone.targetData()
                              + (zone.blockedReason() == null
                                  ? ""
                                  : "; blocked=" + zone.blockedReason())
                              + (zone.conflict() == null ? "" : "; conflict=" + zone.conflict()));
                });
        case "resolve" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier guild resolve <conflict-uuid>");
          UUID conflict = UUID.fromString(args[1]);
          withCity(
              player,
              city ->
                  guilds.resolveConflict(city.id(), player.getUniqueId(), conflict, Instant.now()),
              zone -> "Conflict resolved; task " + zone.task() + " is " + zone.taskStatus() + ".");
        }
        case "assist" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier guild assist <repair-uuid>");
          UUID order = UUID.fromString(args[1]);
          withCityEntity(player, city -> guildCoordinator.begin(player, city.id(), order));
        }
        default ->
            throw new IllegalArgumentException(
                "guild actions: overview, queue, foreman, team, priority, emergency, deliver, boost, inspect, resolve, assist");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void dynamicEvents(Player player, String[] args) {
    String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
    try {
      switch (action) {
        case "list" ->
            execute(
                player,
                () -> dynamicEvents.available(player.getUniqueId(), Instant.now()),
                values ->
                    values.isEmpty()
                        ? "No open dynamic events."
                        : values.stream()
                            .map(
                                value ->
                                    value.id()
                                        + " "
                                        + value.key()
                                        + " "
                                        + value.state()
                                        + " "
                                        + value.progress()
                                        + "/"
                                        + value.target()
                                        + (value.joined() ? " JOINED" : ""))
                            .collect(java.util.stream.Collectors.joining("\n")));
        case "join" -> {
          if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("usage: /frontier events join <event-uuid> [role]");
          execute(
              player,
              () ->
                  dynamicEvents.join(
                      UUID.fromString(args[1]),
                      player.getUniqueId(),
                      args.length == 3 ? args[2] : "RESPONDER",
                      Instant.now()),
              value -> "Joined " + value.key() + " event " + value.id());
        }
        case "respond" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier events respond <event-uuid> <contribution>");
          execute(
              player,
              () ->
                  dynamicEvents.respond(
                      UUID.fromString(args[1]),
                      player.getUniqueId(),
                      Long.parseLong(args[2]),
                      Instant.now()),
              value ->
                  "Event response "
                      + value.progress()
                      + "/"
                      + value.target()
                      + " state="
                      + value.state());
        }
        default -> throw new IllegalArgumentException("event actions: list, join, respond");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void endgame(Player player, String[] args) {
    String action = args.length == 0 ? "rankings" : args[0].toLowerCase(Locale.ROOT);
    try {
      switch (action) {
        case "catalog" ->
            execute(
                player,
                endgame::catalog,
                values ->
                    values.stream()
                        .map(
                            value ->
                                value.contentType()
                                    + " "
                                    + value.key()
                                    + " era="
                                    + value.requiredEra()
                                    + " requirement="
                                    + value.requirement()
                                    + " effect="
                                    + value.effect())
                        .collect(java.util.stream.Collectors.joining("\n")));
        case "rankings" -> {
          int limit = args.length > 1 ? Integer.parseInt(args[1]) : 10;
          execute(
              player,
              () -> endgame.rankings(limit),
              values ->
                  values.stream()
                      .map(
                          value ->
                              "#"
                                  + value.rank()
                                  + " "
                                  + value.name()
                                  + " score="
                                  + value.score()
                                  + " prestige="
                                  + value.prestige()
                                  + " era="
                                  + value.era())
                      .collect(java.util.stream.Collectors.joining("\n")));
        }
        case "history" -> {
          int limit = args.length > 1 ? Integer.parseInt(args[1]) : 25;
          execute(
              player,
              () -> endgame.history(limit),
              values ->
                  values.stream()
                      .map(
                          value ->
                              value.occurredAt()
                                  + " "
                                  + value.eventType()
                                  + " "
                                  + value.aggregate())
                      .collect(java.util.stream.Collectors.joining("\n")));
        }
        case "unlocks" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier endgame unlocks <kingdom-uuid>");
          execute(
              player,
              () -> endgame.unlocks(UUID.fromString(args[1])),
              values -> values.isEmpty() ? "No endgame unlocks." : String.join("\n", values));
        }
        default ->
            throw new IllegalArgumentException(
                "endgame actions: catalog, rankings, history, unlocks");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void world(CommandSender sender, String[] args) {
    String action = args.length == 0 ? "regions" : args[0].toLowerCase(Locale.ROOT);
    schedulers
        .async(
            () ->
                switch (action) {
                  case "season" -> "Current season: " + worldSimulation.season(Instant.now());
                  case "events" -> {
                    var events = worldSimulation.events(true);
                    yield events.isEmpty()
                        ? "No active world events."
                        : events.stream()
                            .map(
                                event ->
                                    event.id()
                                        + " "
                                        + event.key()
                                        + " "
                                        + event.state()
                                        + " "
                                        + event.progress()
                                        + "/"
                                        + event.target())
                            .collect(java.util.stream.Collectors.joining("\n"));
                  }
                  case "regions" -> {
                    var regions = worldSimulation.regions();
                    yield regions.isEmpty()
                        ? "No simulated regions yet."
                        : regions.stream()
                            .map(
                                region ->
                                    region.key()
                                        + " population="
                                        + region.population()
                                        + " prosperity="
                                        + Math.round(region.prosperity())
                                        + " stability="
                                        + Math.round(region.stability())
                                        + " roads="
                                        + Math.round(region.roadIntegrity())
                                        + " season="
                                        + region.season()
                                        + " weather="
                                        + region.weather()
                                        + "("
                                        + region.weatherSeverity()
                                        + ")")
                            .collect(java.util.stream.Collectors.joining("\n"));
                  }
                  default ->
                      throw new IllegalArgumentException("world actions: regions, events, season");
                })
        .whenComplete(
            (message, failure) ->
                schedulers.global(
                    () ->
                        sender.sendMessage(
                            Component.text(
                                failure == null ? message : rootMessage(failure),
                                failure == null ? NamedTextColor.AQUA : NamedTextColor.RED))));
  }

  private void kingdom(Player player, String[] args) {
    String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
    try {
      switch (action) {
        case "list" ->
            execute(
                player,
                civilization::kingdoms,
                values ->
                    values.isEmpty()
                        ? "No kingdoms."
                        : values.stream()
                            .map(
                                kingdom ->
                                    kingdom.id()
                                        + " "
                                        + kingdom.name()
                                        + " "
                                        + kingdom.era()
                                        + " prestige="
                                        + kingdom.prestige()
                                        + " cities="
                                        + kingdom.cities().size())
                            .collect(java.util.stream.Collectors.joining("\n")));
        case "create" -> {
          if (args.length < 2)
            throw new IllegalArgumentException("usage: /frontier kingdom create <name>");
          withCity(
              player,
              city ->
                  civilization.createKingdom(
                      city.id(),
                      player.getUniqueId(),
                      String.join(" ", Arrays.copyOfRange(args, 1, args.length)),
                      Instant.now()),
              kingdom -> "Kingdom founded: " + kingdom.name() + " (" + kingdom.id() + ")");
        }
        case "invite" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom invite <kingdom-uuid> <city-uuid>");
          UUID kingdom = UUID.fromString(args[1]);
          UUID city = UUID.fromString(args[2]);
          execute(
              player,
              () ->
                  civilization.inviteCity(
                      kingdom,
                      player.getUniqueId(),
                      city,
                      Instant.now().plusSeconds(172_800),
                      Instant.now()),
              invite -> "Kingdom invitation: " + invite.id());
        }
        case "accept" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier kingdom accept <invitation-uuid>");
          UUID invitation = UUID.fromString(args[1]);
          withCity(
              player,
              city ->
                  civilization.acceptInvitation(
                      invitation, city.id(), player.getUniqueId(), Instant.now()),
              kingdom -> "Joined kingdom " + kingdom.name());
        }
        case "treaty" -> {
          if (args.length < 4 || args.length > 5)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom treaty <kingdom-uuid> <counterpart-uuid> <type> [days]");
          UUID kingdom = UUID.fromString(args[1]);
          UUID counterpart = UUID.fromString(args[2]);
          long days = args.length == 5 ? Long.parseLong(args[4]) : 7;
          execute(
              player,
              () ->
                  civilization.proposeTreaty(
                      kingdom,
                      player.getUniqueId(),
                      counterpart,
                      args[3].toUpperCase(Locale.ROOT),
                      "{}",
                      Instant.now().plusSeconds(Math.multiplyExact(days, 86_400)),
                      Instant.now()),
              treaty -> "Treaty proposed: " + treaty.id());
        }
        case "treaty-accept" -> {
          if (args.length != 2)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom treaty-accept <treaty-uuid>");
          UUID treaty = UUID.fromString(args[1]);
          execute(
              player,
              () -> civilization.acceptTreaty(treaty, player.getUniqueId(), Instant.now()),
              value -> "Treaty is now " + value.status());
        }
        case "treaties" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier kingdom treaties <kingdom-uuid>");
          UUID kingdom = UUID.fromString(args[1]);
          execute(
              player,
              () -> civilization.treaties(kingdom),
              values ->
                  values.isEmpty()
                      ? "No treaties."
                      : values.stream()
                          .map(value -> value.id() + " " + value.type() + " " + value.status())
                          .collect(java.util.stream.Collectors.joining("\n")));
        }
        case "overview" -> {
          if (args.length != 2)
            throw new IllegalArgumentException("usage: /frontier kingdom overview <kingdom-uuid>");
          execute(
              player,
              () -> kingdomIntegration.report(UUID.fromString(args[1])),
              value ->
                  "Kingdom treasury="
                      + value.treasuryMinor()
                      + " tax="
                      + value.taxBasisPoints()
                      + "bp\nRoles: "
                      + String.join(", ", value.roles())
                      + "\nPolicies: "
                      + String.join(", ", value.policies())
                      + "\nProjects: "
                      + String.join(", ", value.projects()));
        }
        case "role" -> {
          if (args.length != 4)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom role <kingdom-uuid> <player-uuid> <king|council|marshal|diplomat>");
          execute(
              player,
              () -> {
                kingdomIntegration.assignRole(
                    UUID.fromString(args[1]),
                    player.getUniqueId(),
                    UUID.fromString(args[2]),
                    KingdomIntegrationGateway.Role.valueOf(args[3].toUpperCase(Locale.ROOT)),
                    Instant.now());
                return "Kingdom role assigned.";
              },
              value -> value);
        }
        case "vote" -> {
          if (args.length < 3 || args.length > 4)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom vote <kingdom-uuid> <kind> [hours]");
          long hours = args.length == 4 ? Long.parseLong(args[3]) : 24;
          execute(
              player,
              () ->
                  kingdomIntegration.createVote(
                      UUID.fromString(args[1]),
                      player.getUniqueId(),
                      args[2],
                      "{}",
                      Instant.now().plusSeconds(Math.multiplyExact(hours, 3_600)),
                      Instant.now()),
              value -> "Vote opened: " + value.id() + " requiredYes=" + value.requiredYes());
        }
        case "vote-cast" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom vote-cast <vote-uuid> <yes|no>");
          boolean yes =
              switch (args[2].toLowerCase(Locale.ROOT)) {
                case "yes" -> true;
                case "no" -> false;
                default -> throw new IllegalArgumentException("vote must be yes or no");
              };
          withCity(
              player,
              city ->
                  kingdomIntegration.castVote(
                      UUID.fromString(args[1]),
                      city.id(),
                      player.getUniqueId(),
                      yes,
                      Instant.now()),
              value -> "Vote " + value.status() + " yes=" + value.yes() + " no=" + value.no());
        }
        case "war-approve" -> {
          if (args.length < 3 || args.length > 4)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom war-approve <kingdom-uuid> <target-city-uuid> [type]");
          execute(
              player,
              () ->
                  kingdomIntegration.approveWar(
                      UUID.fromString(args[1]),
                      player.getUniqueId(),
                      UUID.fromString(args[2]),
                      args.length == 4 ? args[3] : "CAMPAIGN",
                      Instant.now().plusSeconds(86_400),
                      Instant.now()),
              value -> "War approval: " + value.id());
        }
        case "deposit", "withdraw" -> {
          if (args.length != 4)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom " + action + " <kingdom-uuid> <city-uuid> <cents>");
          UUID kingdom = UUID.fromString(args[1]);
          UUID city = UUID.fromString(args[2]);
          long amount = Long.parseLong(args[3]);
          execute(
              player,
              () ->
                  action.equals("deposit")
                      ? kingdomIntegration.deposit(
                          kingdom,
                          city,
                          player.getUniqueId(),
                          amount,
                          UUID.randomUUID(),
                          Instant.now())
                      : kingdomIntegration.withdraw(
                          kingdom,
                          city,
                          player.getUniqueId(),
                          amount,
                          UUID.randomUUID(),
                          Instant.now()),
              value ->
                  "Kingdom treasury="
                      + value.kingdomBalanceMinor()
                      + " city treasury="
                      + value.cityBalanceMinor());
        }
        case "tax" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom tax <kingdom-uuid> <basis-points>");
          execute(
              player,
              () -> {
                kingdomIntegration.setTaxRate(
                    UUID.fromString(args[1]),
                    player.getUniqueId(),
                    Integer.parseInt(args[2]),
                    Instant.now());
                return "Kingdom tax updated.";
              },
              value -> value);
        }
        case "tax-collect" -> {
          if (args.length != 2)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom tax-collect <kingdom-uuid>");
          execute(
              player,
              () ->
                  kingdomIntegration.collectTaxes(
                      UUID.fromString(args[1]), LocalDate.now(), Instant.now()),
              value ->
                  "Taxes assessed="
                      + value.assessed()
                      + " paid="
                      + value.paid()
                      + " collected="
                      + value.collectedMinor());
        }
        case "policy" -> {
          if (args.length != 4)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom policy <kingdom-uuid> <key> <value>");
          execute(
              player,
              () -> {
                kingdomIntegration.setPolicy(
                    UUID.fromString(args[1]),
                    player.getUniqueId(),
                    args[2],
                    args[3],
                    Instant.now());
                return "Kingdom policy updated.";
              },
              value -> value);
        }
        case "secede" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom secede <kingdom-uuid> <city-uuid>");
          execute(
              player,
              () ->
                  kingdomIntegration.requestSecession(
                      UUID.fromString(args[1]),
                      UUID.fromString(args[2]),
                      player.getUniqueId(),
                      Instant.now()),
              value -> "Secession is " + value.status() + " (" + value.id() + ")");
        }
        case "research" -> {
          if (args.length != 5)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom research <kingdom-uuid> <branch> <project> <points>");
          UUID kingdom = UUID.fromString(args[1]);
          long points = Long.parseLong(args[4]);
          execute(
              player,
              () ->
                  civilization.startResearch(
                      kingdom,
                      player.getUniqueId(),
                      args[2].toUpperCase(Locale.ROOT),
                      args[3].toLowerCase(Locale.ROOT),
                      points,
                      Instant.now()),
              project -> "Research started: " + project.id());
        }
        case "wonder" -> {
          if (args.length != 5)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom wonder <kingdom-uuid> <key> <commodity> <units>");
          UUID kingdom = UUID.fromString(args[1]);
          long units = Long.parseLong(args[4]);
          execute(
              player,
              () ->
                  civilization.startWonder(
                      kingdom,
                      player.getUniqueId(),
                      args[2].toLowerCase(Locale.ROOT),
                      args[3].toLowerCase(Locale.ROOT),
                      units,
                      Instant.now()),
              wonder -> "World wonder started: " + wonder.id());
        }
        case "contribute" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom contribute <wonder-uuid> <units>");
          UUID wonder = UUID.fromString(args[1]);
          long units = Long.parseLong(args[2]);
          withCity(
              player,
              city ->
                  civilization.contributeWonder(
                      wonder,
                      city.id(),
                      player.getUniqueId(),
                      units,
                      UUID.randomUUID(),
                      Instant.now()),
              value -> "Wonder progress: " + value.contributed() + "/" + value.required());
        }
        case "wonders" ->
            execute(
                player,
                civilization::wonders,
                values ->
                    values.isEmpty()
                        ? "No world wonders."
                        : values.stream()
                            .map(
                                value ->
                                    value.id()
                                        + " "
                                        + value.key()
                                        + " "
                                        + value.status()
                                        + " "
                                        + value.contributed()
                                        + "/"
                                        + value.required())
                            .collect(java.util.stream.Collectors.joining("\n")));
        case "mega" -> {
          if (args.length != 5)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom mega <kingdom-uuid> <key> <commodity> <units>");
          UUID kingdom = UUID.fromString(args[1]);
          long units = Long.parseLong(args[4]);
          execute(
              player,
              () ->
                  civilization.startMegaProject(
                      kingdom,
                      player.getUniqueId(),
                      args[2].toLowerCase(Locale.ROOT),
                      args[3].toLowerCase(Locale.ROOT),
                      units,
                      Instant.now()),
              project -> "Mega project started: " + project.id());
        }
        case "mega-contribute" -> {
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier kingdom mega-contribute <project-uuid> <units>");
          UUID project = UUID.fromString(args[1]);
          long units = Long.parseLong(args[2]);
          withCity(
              player,
              city ->
                  civilization.contributeMegaProject(
                      project,
                      city.id(),
                      player.getUniqueId(),
                      units,
                      UUID.randomUUID(),
                      Instant.now()),
              value -> "Mega project progress: " + value.contributed() + "/" + value.required());
        }
        case "projects" ->
            execute(
                player,
                civilization::megaProjects,
                values ->
                    values.isEmpty()
                        ? "No mega projects."
                        : values.stream()
                            .map(
                                value ->
                                    value.id()
                                        + " "
                                        + value.key()
                                        + " "
                                        + value.status()
                                        + " "
                                        + value.contributed()
                                        + "/"
                                        + value.required())
                            .collect(java.util.stream.Collectors.joining("\n")));
        case "objectives" ->
            execute(
                player,
                civilization::globalObjectives,
                values ->
                    values.stream()
                        .map(
                            value ->
                                value.key()
                                    + " "
                                    + value.status()
                                    + " "
                                    + value.progress()
                                    + "/"
                                    + value.target())
                        .collect(java.util.stream.Collectors.joining("\n")));
        default ->
            throw new IllegalArgumentException(
                "kingdom actions: list, create, invite, accept, overview, role, vote, vote-cast, war-approve, deposit, withdraw, tax, tax-collect, policy, secede, treaty, treaty-accept, treaties, research, wonder, contribute, wonders, mega, mega-contribute, projects, objectives");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private void asyncHealth(CommandSender sender) {
    schedulers
        .async(health::get)
        .whenComplete(
            (status, failure) ->
                schedulers.global(
                    () -> {
                      if (failure != null) {
                        sender.sendMessage(
                            Component.text(rootMessage(failure), NamedTextColor.RED));
                        return;
                      }
                      sender.sendMessage(
                          Component.text(
                              "Frontier: " + (status.healthy() ? "healthy" : "degraded"),
                              status.healthy() ? NamedTextColor.GREEN : NamedTextColor.RED));
                      status
                          .components()
                          .forEach(
                              (key, value) ->
                                  sender.sendMessage(
                                      Component.text(
                                          "- " + key + ": " + value, NamedTextColor.GRAY)));
                    }));
  }

  private void recover(CommandSender sender) {
    if (!sender.hasPermission("frontier.admin")) {
      sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
      return;
    }
    schedulers
        .async(recovery::recover)
        .whenComplete(
            (report, failure) ->
                schedulers.global(
                    () ->
                        sender.sendMessage(
                            Component.text(
                                failure == null ? "Recovery scan: " + report : rootMessage(failure),
                                failure == null ? NamedTextColor.GREEN : NamedTextColor.RED))));
  }

  private void admin(CommandSender sender, String[] args) {
    if (!sender.hasPermission("frontier.admin")) {
      sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
      return;
    }
    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "config" -> adminConfig(sender, Arrays.copyOfRange(args, 1, args.length));
      case "build" ->
          schedulers
              .async(buildInformation::report)
              .whenComplete((rows, failure) -> adminRows(sender, rows, failure));
      case "metrics" -> {
        Map<String, Number> values = metrics.snapshot();
        sender.sendMessage(Component.text("Frontier metrics", NamedTextColor.GOLD));
        values.forEach(
            (key, value) ->
                sender.sendMessage(Component.text("- " + key + ": " + value, NamedTextColor.GRAY)));
      }
      case "inspect" -> {
        if (args.length < 3) {
          sender.sendMessage(
              Component.text("usage: /frontier admin inspect <type> <uuid>", NamedTextColor.RED));
          return;
        }
        schedulers
            .async(() -> diagnostics.inspect(args[1], UUID.fromString(args[2])))
            .whenComplete((rows, failure) -> adminRows(sender, rows, failure));
      }
      case "settlement", "influence", "road", "repair", "campaign", "worker", "economy" -> {
        if (args.length != 2) {
          sender.sendMessage(
              Component.text("usage: /frontier admin " + args[0] + " <uuid>", NamedTextColor.RED));
          return;
        }
        String viewer = args[0].toLowerCase(Locale.ROOT);
        schedulers
            .async(() -> diagnostics.viewer(viewer, UUID.fromString(args[1])))
            .whenComplete((rows, failure) -> adminRows(sender, rows, failure));
      }
      case "heatmap" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage(
              Component.text("heatmap requires an in-world player", NamedTextColor.RED));
          return;
        }
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 6;
        UUID world = player.getWorld().getUID();
        int chunkX = player.getLocation().getBlockX() >> 4;
        int chunkZ = player.getLocation().getBlockZ() >> 4;
        schedulers
            .async(() -> diagnostics.heatmap(world, chunkX, chunkZ, radius))
            .whenComplete((rows, failure) -> adminRows(sender, rows, failure));
      }
      case "chunk" -> {
        UUID world;
        int chunkX;
        int chunkZ;
        if (args.length == 1 && sender instanceof Player player) {
          world = player.getWorld().getUID();
          chunkX = player.getLocation().getBlockX() >> 4;
          chunkZ = player.getLocation().getBlockZ() >> 4;
        } else if (args.length == 4) {
          world = UUID.fromString(args[1]);
          chunkX = Integer.parseInt(args[2]);
          chunkZ = Integer.parseInt(args[3]);
        } else {
          sender.sendMessage(
              Component.text(
                  "usage: /frontier admin chunk [world-uuid chunk-x chunk-z]", NamedTextColor.RED));
          return;
        }
        UUID selectedWorld = world;
        int selectedX = chunkX;
        int selectedZ = chunkZ;
        schedulers
            .async(() -> diagnostics.chunkOwnership(selectedWorld, selectedX, selectedZ))
            .whenComplete((rows, failure) -> adminRows(sender, rows, failure));
      }
      case "live" ->
          schedulers
              .async(diagnostics::liveMetrics)
              .whenComplete(
                  (databaseMetrics, failure) -> {
                    List<String> rows = new java.util.ArrayList<>();
                    metrics.snapshot().forEach((key, value) -> rows.add(key + "=" + value));
                    if (databaseMetrics != null)
                      databaseMetrics.forEach((key, value) -> rows.add(key + "=" + value));
                    adminRows(sender, rows, failure);
                  });
      case "security" ->
          schedulers
              .async(diagnostics::securityAudit)
              .whenComplete((rows, failure) -> adminRows(sender, rows, failure));
      case "performance" ->
          schedulers
              .async(diagnostics::performanceAudit)
              .whenComplete(
                  (databaseRows, failure) -> {
                    List<String> rows = new java.util.ArrayList<>();
                    if (databaseRows != null) rows.addAll(databaseRows);
                    Runtime runtime = Runtime.getRuntime();
                    rows.add("memory.usedBytes=" + (runtime.totalMemory() - runtime.freeMemory()));
                    rows.add("memory.committedBytes=" + runtime.totalMemory());
                    rows.add("memory.maxBytes=" + runtime.maxMemory());
                    if (schedulers instanceof PaperSchedulerFacade paper) {
                      PaperSchedulerFacade.SchedulerStats stats = paper.stats();
                      rows.add("scheduler.poolSize=" + stats.poolSize());
                      rows.add("scheduler.activeAsync=" + stats.activeAsync());
                      rows.add("scheduler.queuedAsync=" + stats.queuedAsync());
                      rows.add("scheduler.completedAsync=" + stats.executorCompleted());
                      rows.add(
                          "scheduler.averageAsyncMs=" + nanosToMillis(stats.averageAsyncNanos()));
                      rows.add(
                          "scheduler.maximumAsyncMs=" + nanosToMillis(stats.maximumAsyncNanos()));
                      rows.add(
                          "scheduler.averageQueueMs=" + nanosToMillis(stats.averageQueueNanos()));
                      rows.add(
                          "scheduler.maximumQueueMs=" + nanosToMillis(stats.maximumQueueNanos()));
                      rows.add("ticks.profiledTasks=" + stats.regionTasks());
                      rows.add("ticks.averageTaskMs=" + nanosToMillis(stats.averageRegionNanos()));
                      rows.add("ticks.maximumTaskMs=" + nanosToMillis(stats.maximumRegionNanos()));
                      paper
                          .namedStats()
                          .forEach(
                              (name, timing) ->
                                  rows.add(
                                      "subsystem."
                                          + name
                                          + ".count="
                                          + timing.count()
                                          + " avgMs="
                                          + nanosToMillis(timing.averageNanos())
                                          + " maxMs="
                                          + nanosToMillis(timing.maximumNanos())));
                    }
                    adminRows(sender, rows, failure);
                  });
      case "audit" -> {
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        schedulers
            .async(() -> diagnostics.audit(limit))
            .whenComplete((rows, failure) -> adminRows(sender, rows, failure));
      }
      case "snapshot" ->
          schedulers
              .async(diagnostics::snapshot)
              .whenComplete(
                  (snapshot, failure) ->
                      adminRows(
                          sender,
                          snapshot == null
                              ? List.of()
                              : List.of(
                                  "counts=" + snapshot.counts(),
                                  "oldestOutboxLagSeconds=" + snapshot.oldestOutboxLagSeconds()),
                          failure));
      default ->
          sender.sendMessage(
              Component.text(
                  "admin actions: build, config, health, recover, metrics, live, security, performance, snapshot, inspect, audit, settlement, influence, road, repair, campaign, worker, economy, heatmap, chunk",
                  NamedTextColor.RED));
    }
  }

  private void adminConfig(CommandSender sender, String[] args) {
    if (args.length == 0) {
      sender.sendMessage(
          Component.text(
              "usage: /frontier admin config <validate|reload|show>", NamedTextColor.RED));
      return;
    }
    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "validate" ->
          schedulers
              .async(configs::validate)
              .whenComplete((rows, failure) -> adminRows(sender, rows, failure));
      case "reload" ->
          schedulers
              .async(configs::reload)
              .whenComplete(
                  (report, failure) ->
                      adminRows(
                          sender,
                          report == null
                              ? List.of()
                              : List.of(
                                  "reload=" + report.classification(),
                                  "changed=" + report.changedKeys(),
                                  "running configuration is unchanged until the required restart"),
                          failure));
      case "show" -> {
        if (args.length != 2) {
          sender.sendMessage(
              Component.text(
                  "usage: /frontier admin config show <global|module>", NamedTextColor.RED));
          return;
        }
        schedulers
            .async(() -> configs.show(args[1]))
            .whenComplete((rows, failure) -> adminRows(sender, rows, failure));
      }
      default ->
          sender.sendMessage(
              Component.text("config actions: validate, reload, show", NamedTextColor.RED));
    }
  }

  private void adminRows(CommandSender sender, List<String> rows, Throwable failure) {
    schedulers.global(
        () -> {
          if (failure != null) {
            metrics.failure();
            sender.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED));
            return;
          }
          if (rows.isEmpty()) sender.sendMessage(Component.text("No rows.", NamedTextColor.YELLOW));
          else rows.forEach(row -> sender.sendMessage(Component.text(row, NamedTextColor.GRAY)));
        });
  }

  private static double nanosToMillis(long nanos) {
    return Math.round(nanos / 10_000.0) / 100.0;
  }

  private <T> void withCity(
      Player player,
      java.util.function.Function<SettlementGateway.CitySnapshot, T> work,
      java.util.function.Function<T, String> success) {
    execute(
        player,
        () -> {
          SettlementGateway.CitySnapshot city =
              settlements
                  .city(player.getUniqueId())
                  .orElseThrow(
                      () -> new IllegalStateException("you do not belong to a settlement"));
          return work.apply(city);
        },
        success);
  }

  private void withCityEntity(
      Player player, java.util.function.Consumer<SettlementGateway.CitySnapshot> work) {
    schedulers
        .async(() -> settlements.city(player.getUniqueId()))
        .whenComplete(
            (city, failure) ->
                schedulers.forEntity(
                    player.getUniqueId(),
                    () -> {
                      if (failure != null) {
                        player.sendMessage(
                            Component.text(rootMessage(failure), NamedTextColor.RED));
                      } else if (city.isEmpty()) {
                        player.sendMessage(
                            Component.text("You are not in a settlement.", NamedTextColor.RED));
                      } else {
                        work.accept(city.orElseThrow());
                      }
                    },
                    () -> {}));
  }

  private <T> void execute(
      Player player,
      java.util.function.Supplier<T> work,
      java.util.function.Function<T, String> success) {
    schedulers
        .async(work)
        .whenComplete(
            (result, failure) ->
                schedulers.global(
                    () -> {
                      if (failure != null)
                        player.sendMessage(
                            Component.text(rootMessage(failure), NamedTextColor.RED));
                      else
                        player.sendMessage(
                            Component.text(success.apply(result), NamedTextColor.GREEN));
                    }));
  }

  private <T> void executeOptional(
      Player player,
      java.util.function.Supplier<java.util.Optional<T>> work,
      java.util.function.Function<T, String> success) {
    executeOptional(player, work, success, "You do not belong to a settlement.");
  }

  private <T> void executeOptional(
      Player player,
      java.util.function.Supplier<java.util.Optional<T>> work,
      java.util.function.Function<T, String> success,
      String emptyMessage) {
    schedulers
        .async(work)
        .whenComplete(
            (result, failure) ->
                schedulers.global(
                    () -> {
                      if (failure != null)
                        player.sendMessage(
                            Component.text(rootMessage(failure), NamedTextColor.RED));
                      else if (result.isEmpty())
                        player.sendMessage(Component.text(emptyMessage, NamedTextColor.YELLOW));
                      else
                        player.sendMessage(
                            Component.text(
                                success.apply(result.orElseThrow()), NamedTextColor.GOLD));
                    }));
  }

  private static UUID upgradeKey(SettlementGateway.CitySnapshot city) {
    return UUID.nameUUIDFromBytes(
        (city.id() + ":upgrade:" + city.level().next()).getBytes(StandardCharsets.UTF_8));
  }

  static String rootMessage(Throwable failure) {
    Throwable value = failure;
    while (value.getCause() != null) value = value.getCause();
    return value.getMessage() == null ? "Operation failed." : value.getMessage();
  }

  private static void help(CommandSender sender) {
    sender.sendMessage(
        Component.text(
            "/frontier or /frontier menu <screen> opens the complete Dialog UI",
            NamedTextColor.AQUA));
    sender.sendMessage(
        Component.text(
            "/frontier city create|expedition|info|invite|invite-revoke|accept|members|leave|kick|ban|unban|role|claim|building|upgrade|policy|transfer|succession|abandon|disband|recover|merge|merge-accept|history",
            NamedTextColor.GOLD));
    sender.sendMessage(
        Component.text(
            "/frontier district list|create|select|info|rename|resize|delete|manager|manager-remove|budget|priority|production-priority|repair-priority|policy|worker-assign|worker-remove|building-assign|building-remove|view",
            NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text(
            "/frontier balance | pay <player> <cents> | treasury status|deposit|withdraw|pay|history",
            NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text("/frontier harbor tutorial|jobs|work|status", NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text(
            "/frontier market list|warehouse|deposit|buy|sell|cancel", NamedTextColor.GRAY));
    sender.sendMessage(Component.text("/frontier production list|queue|hire", NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text("/frontier logistics list|node|connect|ship", NamedTextColor.GRAY));
    sender.sendMessage(Component.text("/frontier caravan list|escort", NamedTextColor.GRAY));
    sender.sendMessage(Component.text("/frontier events list|join|respond", NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text("/frontier endgame catalog|rankings|history|unlocks", NamedTextColor.GRAY));
    sender.sendMessage(Component.text("/frontier population | workers", NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text(
            "/frontier economy companies|company-create|invoice|invoice-pay|loan|loan-repay|tax|procure|fulfill|emergency|history",
            NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text("/frontier contracts list|post|accept|deliver", NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text(
            "/frontier war list|declare|ceasefire|resume|resolve|outcome|end",
            NamedTextColor.GRAY));
    sender.sendMessage(Component.text("/frontier repair list|quote|buy", NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text(
            "/frontier guild overview|queue|foreman|team|priority|emergency|deliver|boost|inspect|resolve|assist",
            NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text("/frontier world regions|events|season", NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text(
            "/frontier kingdom list|create|invite|treaty|research|wonder|mega|objectives",
            NamedTextColor.GRAY));
  }

  @Override
  public List<String> onTabComplete(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String alias,
      String[] args) {
    if (args.length == 1) return matching(ROOTS, args[0]);
    if (args.length == 2 && args[0].equalsIgnoreCase("admin"))
      return matching(
          List.of("health", "recover", "metrics", "snapshot", "inspect", "audit"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("treasury"))
      return matching(List.of("status", "deposit", "withdraw", "pay", "audit"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("harbor"))
      return matching(List.of("tutorial", "jobs", "work", "status"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("admin"))
      return matching(
          List.of(
              "health",
              "recover",
              "metrics",
              "live",
              "security",
              "performance",
              "snapshot",
              "inspect",
              "audit",
              "settlement",
              "influence",
              "road",
              "repair",
              "campaign",
              "worker",
              "economy",
              "heatmap",
              "chunk"),
          args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("city"))
      return matching(
          List.of(
              "create",
              "expedition",
              "info",
              "invite",
              "invite-revoke",
              "accept",
              "members",
              "leave",
              "kick",
              "ban",
              "unban",
              "role",
              "claim",
              "building",
              "upgrade",
              "policy",
              "transfer",
              "succession",
              "abandon",
              "disband",
              "recover",
              "merge",
              "merge-accept",
              "history"),
          args[1]);
    if (args.length == 3 && args[0].equalsIgnoreCase("city") && args[1].equalsIgnoreCase("disband"))
      return matching(List.of("request", "confirm"), args[2]);
    if (args.length == 3
        && args[0].equalsIgnoreCase("city")
        && args[1].equalsIgnoreCase("expedition"))
      return matching(List.of("status", "create", "invite", "accept", "found", "cancel"), args[2]);
    if (args.length == 3
        && args[0].equalsIgnoreCase("city")
        && args[1].equalsIgnoreCase("building"))
      return matching(
          java.util.stream.Stream.concat(
                  java.util.stream.Stream.of(
                      "start",
                      "preview",
                      "confirm",
                      "cancel",
                      "revalidate",
                      "unregister",
                      "history",
                      "transfer",
                      "transfer-accept"),
                  Arrays.stream(BuildingType.values())
                      .map(type -> type.name().toLowerCase(Locale.ROOT).replace('_', '-')))
              .toList(),
          args[2]);
    if (args.length == 4
        && args[0].equalsIgnoreCase("city")
        && args[1].equalsIgnoreCase("building")
        && args[2].equalsIgnoreCase("start"))
      return matching(
          Arrays.stream(BuildingType.values())
              .map(type -> type.name().toLowerCase(Locale.ROOT).replace('_', '-'))
              .toList(),
          args[3]);
    if (args.length == 5
        && args[0].equalsIgnoreCase("city")
        && args[1].equalsIgnoreCase("building")
        && args[2].equalsIgnoreCase("unregister")) return matching(List.of("confirm"), args[4]);
    if (args.length == 2 && args[0].equalsIgnoreCase("district"))
      return matching(
          List.of(
              "list",
              "create",
              "select",
              "info",
              "delete",
              "resize",
              "rename",
              "manager",
              "manager-assign",
              "manager-transfer",
              "manager-remove",
              "budget",
              "priority",
              "production-priority",
              "repair-priority",
              "policy",
              "worker-assign",
              "worker-remove",
              "building-assign",
              "building-remove",
              "view"),
          args[1]);
    if (args.length == 3
        && args[0].equalsIgnoreCase("district")
        && args[1].equalsIgnoreCase("create"))
      return matching(
          Arrays.stream(DistrictType.values())
              .map(type -> type.name().toLowerCase(Locale.ROOT))
              .toList(),
          args[2]);
    if (args.length == 2 && args[0].equalsIgnoreCase("market"))
      return matching(List.of("list", "warehouse", "deposit", "buy", "sell", "cancel"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("production"))
      return matching(List.of("list", "queue", "hire"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("logistics"))
      return matching(List.of("list", "node", "connect", "ship"), args[1]);
    if (args.length == 3
        && args[0].equalsIgnoreCase("logistics")
        && args[1].equalsIgnoreCase("node"))
      return matching(
          List.of(
              "road",
              "bridge",
              "tunnel",
              "gate",
              "harbor",
              "watchtower",
              "warehouse",
              "market",
              "depot"),
          args[2]);
    if (args.length == 5
        && args[0].equalsIgnoreCase("logistics")
        && args[1].equalsIgnoreCase("connect"))
      return matching(
          Arrays.stream(InfrastructureType.values())
              .map(type -> type.name().toLowerCase(Locale.ROOT))
              .toList(),
          args[4]);
    if (args.length == 2 && args[0].equalsIgnoreCase("caravan"))
      return matching(List.of("list", "escort"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("events"))
      return matching(List.of("list", "join", "respond"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("endgame"))
      return matching(List.of("catalog", "rankings", "history", "unlocks"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("economy"))
      return matching(
          List.of(
              "companies",
              "company-create",
              "invoice",
              "invoice-pay",
              "loan",
              "loan-repay",
              "tax",
              "procure",
              "fulfill",
              "emergency",
              "history"),
          args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("treasury"))
      return matching(List.of("status", "deposit", "withdraw", "pay", "history"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("contracts"))
      return matching(List.of("list", "post", "accept", "deliver"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("war"))
      return matching(
          List.of("list", "declare", "ceasefire", "resume", "resolve", "outcome", "end"), args[1]);
    if (args.length == 4 && args[0].equalsIgnoreCase("war") && args[1].equalsIgnoreCase("outcome"))
      return matching(
          Arrays.stream(CampaignOutcomeGateway.Outcome.values())
              .map(value -> value.name().toLowerCase(Locale.ROOT))
              .toList(),
          args[3]);
    if (args.length == 2 && args[0].equalsIgnoreCase("repair"))
      return matching(List.of("list", "quote", "buy"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("guild"))
      return matching(
          List.of(
              "overview",
              "queue",
              "foreman",
              "team",
              "priority",
              "emergency",
              "deliver",
              "boost",
              "inspect",
              "resolve",
              "assist"),
          args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("world"))
      return matching(List.of("regions", "events", "season"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("kingdom"))
      return matching(
          List.of(
              "list",
              "create",
              "invite",
              "accept",
              "treaty",
              "treaty-accept",
              "treaties",
              "research",
              "wonder",
              "contribute",
              "wonders",
              "mega",
              "mega-contribute",
              "projects",
              "objectives",
              "overview",
              "role",
              "vote",
              "vote-cast",
              "war-approve",
              "deposit",
              "withdraw",
              "tax",
              "tax-collect",
              "policy",
              "secede"),
          args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("menu"))
      return matching(
          Arrays.stream(FrontierUi.Screen.values())
              .map(value -> value.name().toLowerCase(Locale.ROOT))
              .toList(),
          args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("admin"))
      return matching(
          List.of(
              "build",
              "config",
              "health",
              "recover",
              "metrics",
              "live",
              "security",
              "performance",
              "snapshot",
              "inspect",
              "audit",
              "settlement",
              "influence",
              "road",
              "repair",
              "campaign",
              "worker",
              "economy",
              "heatmap",
              "chunk"),
          args[1]);
    if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("config"))
      return matching(List.of("validate", "reload", "show"), args[2]);
    if (args.length == 4
        && args[0].equalsIgnoreCase("admin")
        && args[1].equalsIgnoreCase("config")
        && args[2].equalsIgnoreCase("show")) {
      List<String> modules = new java.util.ArrayList<>(ConfigRegistry.MODULES);
      modules.add("global");
      return matching(modules, args[3]);
    }
    return List.of();
  }

  private static String moduleForRoot(String root) {
    return switch (root) {
      case "city" -> "settlements";
      case "district" -> "districts";
      case "balance", "pay", "harbor", "economy", "treasury", "production", "market", "contracts" ->
          "economy";
      case "logistics" -> "infrastructure";
      case "caravan" -> "caravans";
      case "population", "workers" -> "population";
      case "war" -> "warfare";
      case "repair" -> "repairs";
      case "world", "events" -> "world-simulation";
      case "kingdom", "endgame" -> "kingdoms";
      default -> null;
    };
  }

  private static List<String> matching(List<String> values, String prefix) {
    String normalized = prefix.toLowerCase(Locale.ROOT);
    return values.stream().filter(value -> value.startsWith(normalized)).toList();
  }
}
