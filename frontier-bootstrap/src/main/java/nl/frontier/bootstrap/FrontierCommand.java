package nl.frontier.bootstrap;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import nl.frontier.city.SettlementApplicationService;
import nl.frontier.city.SettlementGateway;
import nl.frontier.city.SettlementLifecycleService;
import nl.frontier.domain.Ids.PlayerId;
import nl.frontier.domain.Ids.RepairOrderId;
import nl.frontier.domain.Ids.SettlementId;
import nl.frontier.domain.Ids.WarId;
import nl.frontier.economy.CaravanService;
import nl.frontier.economy.ContractGateway;
import nl.frontier.economy.EconomyApplicationService;
import nl.frontier.economy.EconomyGateway;
import nl.frontier.economy.FinanceApplicationService;
import nl.frontier.economy.FinanceGateway;
import nl.frontier.economy.HarborApplicationService;
import nl.frontier.economy.InfrastructureType;
import nl.frontier.economy.LogisticsGateway;
import nl.frontier.economy.MarketEngine;
import nl.frontier.economy.ProductionApplicationService;
import nl.frontier.observability.FrontierMetrics;
import nl.frontier.repair.RepairGateway;
import nl.frontier.repair.RepairOrder;
import nl.frontier.warfare.CampaignGateway;
import nl.frontier.warfare.WarCampaign;
import nl.frontier.world.CivilizationGateway;
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
          "status",
          "balance",
          "pay",
          "harbor",
          "city",
          "district",
          "caravan",
          "treasury",
          "production",
          "logistics",
          "contracts",
          "war",
          "repair",
          "world",
          "kingdom",
          "market",
          "admin");
  private final Supplier<HealthStatus> health;
  private final RecoveryCoordinator recovery;
  private final AdminDiagnostics diagnostics;
  private final FrontierMetrics metrics;
  private final CommandRateLimiter rateLimiter;
  private final SettlementApplicationService settlements;
  private final BuildingRegistrationCoordinator buildingRegistrations;
  private final DistrictApplicationService districts;
  private final SettlementLifecycleService settlementLifecycle;
  private final SettlementFoundingCoordinator founding;
  private final InfrastructureRegistrationCoordinator infrastructureRegistrations;
  private final CaravanService caravans;
  private final FinanceApplicationService finance;
  private final HarborApplicationService harbor;
  private final EconomyApplicationService economy;
  private final ProductionApplicationService production;
  private final LogisticsGateway logistics;
  private final ContractGateway contracts;
  private final CampaignGateway campaigns;
  private final RepairGateway repairs;
  private final WorldSimulationGateway worldSimulation;
  private final CivilizationGateway civilization;
  private final Duration campaignPreparation;
  private final Duration campaignMaximumDuration;
  private final long campaignDeclarationCost;
  private final SchedulerFacade schedulers;
  private final FrontierUi ui;

  public FrontierCommand(
      Supplier<HealthStatus> health,
      RecoveryCoordinator recovery,
      AdminDiagnostics diagnostics,
      FrontierMetrics metrics,
      CommandRateLimiter rateLimiter,
      SettlementApplicationService settlements,
      BuildingRegistrationCoordinator buildingRegistrations,
      DistrictApplicationService districts,
      SettlementLifecycleService settlementLifecycle,
      SettlementFoundingCoordinator founding,
      InfrastructureRegistrationCoordinator infrastructureRegistrations,
      CaravanService caravans,
      FinanceApplicationService finance,
      HarborApplicationService harbor,
      EconomyApplicationService economy,
      ProductionApplicationService production,
      LogisticsGateway logistics,
      ContractGateway contracts,
      CampaignGateway campaigns,
      RepairGateway repairs,
      WorldSimulationGateway worldSimulation,
      CivilizationGateway civilization,
      Duration campaignPreparation,
      Duration campaignMaximumDuration,
      long campaignDeclarationCost,
      SchedulerFacade schedulers,
      FrontierUi ui) {
    this.health = Objects.requireNonNull(health);
    this.recovery = Objects.requireNonNull(recovery);
    this.diagnostics = Objects.requireNonNull(diagnostics);
    this.metrics = Objects.requireNonNull(metrics);
    this.rateLimiter = Objects.requireNonNull(rateLimiter);
    this.settlements = Objects.requireNonNull(settlements);
    this.buildingRegistrations = Objects.requireNonNull(buildingRegistrations);
    this.districts = Objects.requireNonNull(districts);
    this.settlementLifecycle = Objects.requireNonNull(settlementLifecycle);
    this.founding = Objects.requireNonNull(founding);
    this.infrastructureRegistrations = Objects.requireNonNull(infrastructureRegistrations);
    this.caravans = Objects.requireNonNull(caravans);
    this.finance = Objects.requireNonNull(finance);
    this.harbor = Objects.requireNonNull(harbor);
    this.economy = Objects.requireNonNull(economy);
    this.production = Objects.requireNonNull(production);
    this.logistics = Objects.requireNonNull(logistics);
    this.contracts = Objects.requireNonNull(contracts);
    this.campaigns = Objects.requireNonNull(campaigns);
    this.repairs = Objects.requireNonNull(repairs);
    this.worldSimulation = Objects.requireNonNull(worldSimulation);
    this.civilization = Objects.requireNonNull(civilization);
    this.campaignPreparation = Objects.requireNonNull(campaignPreparation);
    this.campaignMaximumDuration = Objects.requireNonNull(campaignMaximumDuration);
    this.campaignDeclarationCost = campaignDeclarationCost;
    this.schedulers = Objects.requireNonNull(schedulers);
    this.ui = Objects.requireNonNull(ui);
  }

  @Override
  public boolean onCommand(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String label,
      String[] args) {
    if (args.length == 0) {
      help(sender);
      return true;
    }
    metrics.command();
    if (sender instanceof Player player
        && !player.hasPermission("frontier.admin")
        && !rateLimiter.allow(player.getUniqueId(), Instant.now())) {
      metrics.rateLimited();
      player.sendMessage(
          Component.text("Too many Frontier commands; wait a moment.", NamedTextColor.RED));
      return true;
    }
    String root = args[0].toLowerCase(Locale.ROOT);
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
          createCity(player, Arrays.copyOfRange(args, 1, args.length));
        }
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
        case "disband" ->
            withCity(
                player,
                city -> settlementLifecycle.disband(city.id(), player.getUniqueId(), Instant.now()),
                value -> "Settlement disbanded; ruins remain until " + value.ruinsUntil());
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
                "city actions: create, info, invite, accept, role, claim, building, upgrade, policy, transfer, succession, abandon, disband, recover, merge, merge-accept, history");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
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
                                "Create a settlement with /frontier city create <name>",
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
    if (args.length < 2 || args.length > 4)
      throw new IllegalArgumentException(
          "usage: /frontier city building <type> [radius] [district-uuid]");
    BuildingType type = BuildingType.valueOf(args[1].toUpperCase(Locale.ROOT));
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
    schedulers
        .async(() -> settlements.city(player.getUniqueId()))
        .whenComplete(
            (city, error) ->
                schedulers.forEntity(
                    player.getUniqueId(),
                    () -> {
                      if (error != null) {
                        player.sendMessage(Component.text(rootMessage(error), NamedTextColor.RED));
                      } else if (city.isEmpty()) {
                        player.sendMessage(
                            Component.text("You are not in a settlement.", NamedTextColor.RED));
                      } else {
                        buildingRegistrations.register(
                            player,
                            city.orElseThrow().id(),
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
                                player.sendMessage(
                                    Component.text(rootMessage(failure), NamedTextColor.RED)));
                      }
                    },
                    () -> {}));
  }

  private void district(Player player, String[] args) {
    try {
      if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
        withCity(
            player,
            city -> districts.list(city.id(), player.getUniqueId()),
            values ->
                values.isEmpty()
                    ? "No districts. Use /frontier district create <type> <radius> <name>."
                    : values.stream()
                        .map(value -> value.name() + "=" + value.id() + " [" + value.type() + "]")
                        .collect(java.util.stream.Collectors.joining(" | ")));
        return;
      }
      String action = args[0].toLowerCase(Locale.ROOT);
      switch (action) {
        case "create" -> {
          if (args.length < 4)
            throw new IllegalArgumentException(
                "usage: /frontier district create <type> <radius> <name>");
          DistrictType type = DistrictType.valueOf(args[1].toUpperCase(Locale.ROOT));
          int radius = districtRadius(args[2]);
          String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
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
          UUID district = districtId(args, 1, "delete <district-uuid>");
          execute(
              player,
              () -> {
                districts.delete(district, player.getUniqueId(), Instant.now());
                return district;
              },
              value -> "Deleted district " + value);
        }
        case "resize" -> {
          UUID district = districtId(args, 1, "resize <district-uuid> <radius>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district resize <district-uuid> <radius>");
          SettlementGateway.Bounds bounds = districtBounds(player, districtRadius(args[2]));
          execute(
              player,
              () -> districts.resize(district, player.getUniqueId(), bounds, Instant.now()),
              value -> "Resized " + value.name());
        }
        case "rename" -> {
          UUID district = districtId(args, 1, "rename <district-uuid> <name>");
          if (args.length < 3)
            throw new IllegalArgumentException(
                "usage: /frontier district rename <district-uuid> <name>");
          String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
          execute(
              player,
              () -> districts.rename(district, player.getUniqueId(), name, Instant.now()),
              value -> "Renamed district to " + value.name());
        }
        case "manager-assign", "manager-transfer" -> {
          UUID district = districtId(args, 1, action + " <district-uuid> <player>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district " + action + " <district-uuid> <player>");
          UUID manager = resolvePlayer(player, args[2]);
          boolean transfer = action.equals("manager-transfer");
          execute(
              player,
              () ->
                  districts.manager(
                      district, player.getUniqueId(), manager, transfer, Instant.now()),
              value -> "District manager is now " + value.manager());
        }
        case "budget" -> {
          UUID district = districtId(args, 1, "budget <district-uuid> <cents>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district budget <district-uuid> <cents>");
          long amount = Long.parseLong(args[2]);
          execute(
              player,
              () -> districts.budget(district, player.getUniqueId(), amount, Instant.now()),
              value -> "District budget is " + value.budgetMinor() + " cents");
        }
        case "priority" -> {
          UUID district = districtId(args, 1, "priority <district-uuid> <0-100>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district priority <district-uuid> <0-100>");
          int priority = Integer.parseInt(args[2]);
          execute(
              player,
              () -> districts.priority(district, player.getUniqueId(), priority, Instant.now()),
              value -> "District priority is " + value.priority());
        }
        case "policy" -> {
          UUID district = districtId(args, 1, "policy <district-uuid> <key> <value>");
          if (args.length != 4)
            throw new IllegalArgumentException(
                "usage: /frontier district policy <district-uuid> <key> <value>");
          execute(
              player,
              () ->
                  districts.policy(district, player.getUniqueId(), args[2], args[3], Instant.now()),
              value -> "District policies: " + value.policies());
        }
        case "worker-assign" -> {
          UUID district =
              districtId(args, 1, "worker-assign <district-uuid> <worker-uuid> [priority]");
          if (args.length < 3 || args.length > 4)
            throw new IllegalArgumentException(
                "usage: /frontier district worker-assign <district-uuid> <worker-uuid> [priority]");
          UUID worker = UUID.fromString(args[2]);
          int priority = args.length == 4 ? Integer.parseInt(args[3]) : 50;
          execute(
              player,
              () ->
                  districts.worker(district, player.getUniqueId(), worker, priority, Instant.now()),
              value -> "Assigned worker " + value.worker());
        }
        case "worker-remove" -> {
          UUID district = districtId(args, 1, "worker-remove <district-uuid> <worker-uuid>");
          if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: /frontier district worker-remove <district-uuid> <worker-uuid>");
          UUID worker = UUID.fromString(args[2]);
          execute(
              player,
              () -> {
                districts.removeWorker(district, player.getUniqueId(), worker, Instant.now());
                return worker;
              },
              value -> "Removed worker " + value);
        }
        case "view" -> {
          UUID district = districtId(args, 1, "view <district-uuid> [view]");
          String view = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "overview";
          if (!java.util.Set.of(
                  "overview", "budget", "workers", "buildings", "reports", "policies", "history")
              .contains(view)) throw new IllegalArgumentException("unknown district dialog view");
          schedulers
              .async(() -> districts.report(district, player.getUniqueId()))
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
                                  district,
                                  view,
                                  districtSummary(report, view));
                          }));
        }
        default ->
            throw new IllegalArgumentException(
                "district actions: list, create, delete, resize, rename, manager-assign, manager-transfer, budget, priority, policy, worker-assign, worker-remove, view");
      }
    } catch (IllegalArgumentException failure) {
      player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED));
    }
  }

  private static UUID districtId(String[] args, int index, String usage) {
    if (args.length <= index)
      throw new IllegalArgumentException("usage: /frontier district " + usage);
    return UUID.fromString(args[index]);
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
          report.workers()
              + " assigned workers; efficiency bonus "
              + district.bonuses().workerEfficiency()
              + "%";
      case "buildings" -> report.buildings() + " active/damaged buildings in district";
      case "reports" -> "Stored " + report.storedUnits() + " units; bonuses " + district.bonuses();
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
              + "; manager "
              + district.manager();
    };
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

  private void createCity(Player player, String[] values) {
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
    founding.found(
        player,
        name,
        charter,
        city ->
            player.sendMessage(
                Component.text("Settlement founded: " + city.name(), NamedTextColor.GREEN)),
        failure -> player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED)));
  }

  private void cityInfo(Player player) {
    executeOptional(
        player,
        () -> settlements.city(player.getUniqueId()),
        city ->
            city.name()
                + " | "
                + city.level()
                + " | population "
                + city.population()
                + " | prosperity "
                + city.prosperity()
                + " | civilization "
                + city.civilization());
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
        case "audit" -> {
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
                "treasury actions: status, deposit, withdraw, pay, audit");
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
                    "Frontier Harbor: take a starter contract with /frontier harbor jobs, complete it with /frontier harbor work, then use /frontier city create and /frontier treasury deposit. Wallet="
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
        default ->
            throw new IllegalArgumentException("logistics actions: list, node, connect, ship");
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
                "war actions: list, declare, ceasefire, resume, resolve, end");
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
                                        + Math.round(region.roadIntegrity()))
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
                "kingdom actions: list, create, invite, accept, treaty, treaty-accept, treaties, research, wonder, contribute, wonders, mega, mega-contribute, projects, objectives");
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
                  "admin actions: health, recover, metrics, snapshot, inspect, audit",
                  NamedTextColor.RED));
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

  private void executeOptional(
      Player player,
      java.util.function.Supplier<java.util.Optional<SettlementGateway.CitySnapshot>> work,
      java.util.function.Function<SettlementGateway.CitySnapshot, String> success) {
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
                        player.sendMessage(
                            Component.text(
                                "You do not belong to a settlement.", NamedTextColor.YELLOW));
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

  private static String rootMessage(Throwable failure) {
    Throwable value = failure;
    while (value.getCause() != null) value = value.getCause();
    return value.getMessage() == null ? "Operation failed." : value.getMessage();
  }

  private static void help(CommandSender sender) {
    sender.sendMessage(
        Component.text(
            "/frontier city create|info|invite|accept|role|claim|building|upgrade|policy|transfer|succession|abandon|disband|recover|merge|merge-accept|history",
            NamedTextColor.GOLD));
    sender.sendMessage(
        Component.text(
            "/frontier district list|create|delete|resize|rename|manager-assign|manager-transfer|budget|priority|policy|worker-assign|worker-remove|view",
            NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text(
            "/frontier balance | pay <player> <cents> | treasury status|deposit|withdraw|pay|audit",
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
    sender.sendMessage(
        Component.text("/frontier contracts list|post|accept|deliver", NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text(
            "/frontier war list|declare|ceasefire|resume|resolve|end", NamedTextColor.GRAY));
    sender.sendMessage(Component.text("/frontier repair list|quote|buy", NamedTextColor.GRAY));
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
    if (args.length == 2 && args[0].equalsIgnoreCase("city"))
      return matching(
          List.of(
              "create",
              "info",
              "invite",
              "accept",
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
    if (args.length == 3
        && args[0].equalsIgnoreCase("city")
        && args[1].equalsIgnoreCase("building"))
      return matching(
          Arrays.stream(BuildingType.values())
              .map(type -> type.name().toLowerCase(Locale.ROOT))
              .toList(),
          args[2]);
    if (args.length == 2 && args[0].equalsIgnoreCase("district"))
      return matching(
          List.of(
              "list",
              "create",
              "delete",
              "resize",
              "rename",
              "manager-assign",
              "manager-transfer",
              "budget",
              "priority",
              "policy",
              "worker-assign",
              "worker-remove",
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
    if (args.length == 2 && args[0].equalsIgnoreCase("contracts"))
      return matching(List.of("list", "post", "accept", "deliver"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("war"))
      return matching(List.of("list", "declare", "ceasefire", "resume", "resolve", "end"), args[1]);
    if (args.length == 2 && args[0].equalsIgnoreCase("repair"))
      return matching(List.of("list", "quote", "buy"), args[1]);
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
              "objectives"),
          args[1]);
    return List.of();
  }

  private static List<String> matching(List<String> values, String prefix) {
    String normalized = prefix.toLowerCase(Locale.ROOT);
    return values.stream().filter(value -> value.startsWith(normalized)).toList();
  }
}
