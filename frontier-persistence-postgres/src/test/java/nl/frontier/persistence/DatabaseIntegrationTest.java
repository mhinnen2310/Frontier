package nl.frontier.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import nl.frontier.api.RecoveryCoordinator;
import nl.frontier.city.BuildingSurvey;
import nl.frontier.city.BuildingType;
import nl.frontier.city.BuildingValidationService;
import nl.frontier.city.BuildingValidator;
import nl.frontier.city.ClaimProtectionGateway;
import nl.frontier.city.DistrictApplicationService;
import nl.frontier.city.DistrictType;
import nl.frontier.city.GovernmentRole;
import nl.frontier.city.SettlementDailySimulation;
import nl.frontier.city.SettlementGateway;
import nl.frontier.city.SettlementLevel;
import nl.frontier.city.SettlementLifecycleGateway;
import nl.frontier.city.SettlementLifecycleService;
import nl.frontier.domain.DomainException;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.ChunkPos;
import nl.frontier.economy.ContractGateway;
import nl.frontier.economy.EconomyGateway;
import nl.frontier.economy.FinanceApplicationService;
import nl.frontier.economy.HarborGateway;
import nl.frontier.economy.InfrastructureSurvey;
import nl.frontier.economy.InfrastructureType;
import nl.frontier.economy.InfrastructureValidator;
import nl.frontier.economy.LogisticsGateway;
import nl.frontier.economy.MarketEngine;
import nl.frontier.economy.ProductionGateway;
import nl.frontier.influence.ChunkOwnershipCache;
import nl.frontier.influence.InfluenceSimulationService;
import nl.frontier.influence.TerritoryState;
import nl.frontier.npc.NpcMaterializationGateway;
import nl.frontier.repair.RepairGateway;
import nl.frontier.repair.RepairOrder;
import nl.frontier.warfare.CampaignGateway;
import nl.frontier.warfare.WarCampaign;
import nl.frontier.warfare.WarDamageGateway;
import nl.frontier.world.CivilizationGateway;
import nl.frontier.world.KingdomIntegrationGateway;
import nl.frontier.world.KingdomIntegrationService;
import nl.frontier.world.WorldSimulationGateway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class DatabaseIntegrationTest {
  @Test
  void concurrentMarketMatchersCannotDuplicateLastReservedUnit() throws Exception {
    String url = System.getProperty("frontier.test.database.url");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "integration database not configured");
    try (DatabaseManager database =
        new DatabaseManager(
            new DatabaseManager.Configuration(url, "frontier", "", 4, Duration.ofSeconds(5)))) {
      database.migrate();
      resetData(database);
      JdbcTransactionalStore store = new JdbcTransactionalStore(database.dataSource());
      PostgresSettlementGateway settlements = new PostgresSettlementGateway(store);
      PostgresEconomyGateway economy = new PostgresEconomyGateway(store);
      PostgresLogisticsGateway logistics = new PostgresLogisticsGateway(store);
      UUID world = UUID.randomUUID();
      UUID sellerActor = UUID.randomUUID();
      UUID firstActor = UUID.randomUUID();
      UUID secondActor = UUID.randomUUID();
      SettlementGateway.CitySnapshot seller =
          settlements.create(sellerActor, "RaceSeller-" + shortId(), world, 1, 1, Instant.now());
      SettlementGateway.CitySnapshot first =
          settlements.create(firstActor, "RaceBuyerA-" + shortId(), world, 2, 2, Instant.now());
      SettlementGateway.CitySnapshot second =
          settlements.create(secondActor, "RaceBuyerB-" + shortId(), world, 3, 3, Instant.now());
      LogisticsGateway.RoadNode sellerNode =
          logistics.registerNode(
              seller.id(), sellerActor, world, 16, 64, 16, "WAREHOUSE", Instant.now());
      LogisticsGateway.RoadNode firstNode =
          logistics.registerNode(
              first.id(), firstActor, world, 32, 64, 32, "WAREHOUSE", Instant.now());
      LogisticsGateway.RoadNode secondNode =
          logistics.registerNode(
              second.id(), secondActor, world, 48, 64, 48, "WAREHOUSE", Instant.now());
      logistics.connect(
          seller.id(), sellerActor, sellerNode.id(), firstNode.id(), 10, Instant.now());
      logistics.connect(
          seller.id(), sellerActor, sellerNode.id(), secondNode.id(), 10, Instant.now());
      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE accounts SET balance_minor=2000000 WHERE owner_type='CITY' AND owner_id IN ('"
                + first.id()
                + "','"
                + second.id()
                + "')");
      }
      String commodity = "frontier:race_" + UUID.randomUUID();
      economy.deposit(seller.id(), sellerActor, commodity, 1, Instant.now());
      economy.placeOrder(
          first.id(),
          firstActor,
          MarketEngine.Side.BUY,
          commodity,
          1,
          1_000_000,
          UUID.randomUUID(),
          Instant.now());
      economy.placeOrder(
          second.id(),
          secondActor,
          MarketEngine.Side.BUY,
          commodity,
          1,
          1_000_000,
          UUID.randomUUID(),
          Instant.now());
      economy.placeOrder(
          seller.id(),
          sellerActor,
          MarketEngine.Side.SELL,
          commodity,
          1,
          999_999,
          UUID.randomUUID(),
          Instant.now());
      try (var executor = Executors.newFixedThreadPool(2)) {
        var firstMatch = executor.submit(() -> economy.match(1, Instant.now()));
        var secondMatch = executor.submit(() -> economy.match(1, Instant.now()));
        assertEquals(
            1, firstMatch.get(10, TimeUnit.SECONDS) + secondMatch.get(10, TimeUnit.SECONDS));
      }
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "SELECT count(*),min(quantity),max(quantity) FROM shipment_items WHERE commodity_key=?")) {
        statement.setString(1, commodity);
        try (var result = statement.executeQuery()) {
          result.next();
          assertEquals(1, result.getInt(1));
          assertEquals(1, result.getLong(2));
          assertEquals(1, result.getLong(3));
        }
      }
    }
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void migrationsConstraintsAndRecoveryQueriesWorkOnPostgres() throws Exception {
    String url = System.getProperty("frontier.test.database.url");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "integration database not configured");
    try (DatabaseManager database =
        new DatabaseManager(
            new DatabaseManager.Configuration(url, "frontier", "", 2, Duration.ofSeconds(5)))) {
      database.migrate();
      resetData(database);
      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement();
          var result =
              statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
        result.next();
        assertEquals(26, result.getInt(1));
      }
      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        assertThrows(
            SQLException.class,
            () ->
                statement.executeUpdate(
                    "INSERT INTO accounts(id,owner_type,owner_id,balance_minor) VALUES(gen_random_uuid(),'CITY',gen_random_uuid(),-1)"));
      }
      JdbcTransactionalStore store = new JdbcTransactionalStore(database.dataSource());
      UUID harborWorld = UUID.randomUUID();
      PostgresHarborGateway harbor = new PostgresHarborGateway(store);
      HarborGateway.HarborSnapshot harborSnapshot =
          harbor.bootstrap(harborWorld, 0, 0, Instant.now());
      assertEquals(3, harborSnapshot.openBuyOrders());
      assertEquals(3, harborSnapshot.openSellOrders());
      UUID starter = UUID.randomUUID();
      assertTrue(harbor.onboard(starter, Instant.now()).firstVisit());
      HarborGateway.StarterJob starterJob = harbor.jobs(starter, Instant.now()).getFirst();
      HarborGateway.JobReceipt jobReceipt =
          harbor.completeJob(starter, starterJob.id(), Instant.now());
      assertEquals(starterJob.rewardMinor(), jobReceipt.playerBalanceMinor());
      assertEquals(
          jobReceipt.playerBalanceMinor(),
          harbor.completeJob(starter, starterJob.id(), Instant.now()).playerBalanceMinor());

      PostgresSettlementGateway starterSettlements = new PostgresSettlementGateway(store);
      SettlementGateway.CitySnapshot starterCity =
          starterSettlements.create(
              starter, "Starter-" + shortId(), harborWorld, 5, 5, Instant.now());
      FinanceApplicationService finances =
          new FinanceApplicationService(new PostgresFinanceGateway(store));
      UUID depositKey = UUID.randomUUID();
      finances.deposit(starter, starterCity.id(), 500, depositKey, Instant.now());
      assertEquals(
          10_500,
          finances
              .deposit(starter, starterCity.id(), 500, depositKey, Instant.now())
              .destinationBalanceMinor());
      finances.withdraw(starterCity.id(), starter, 100, UUID.randomUUID(), Instant.now());
      UUID recipient = UUID.randomUUID();
      finances.settlementPay(
          starterCity.id(), starter, recipient, 50, UUID.randomUUID(), Instant.now());
      finances.pay(recipient, starter, 25, UUID.randomUUID(), Instant.now());
      assertEquals(10_350, starterSettlements.treasuryBalance(starterCity.id()));
      assertTrue(finances.audit(starterCity.id(), starter, 20).size() >= 3);
      SettlementLifecycleService lifecycle =
          new SettlementLifecycleService(new PostgresSettlementLifecycleGateway(store));
      UUID founder = UUID.randomUUID();
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "INSERT INTO accounts(id,owner_type,owner_id,balance_minor) VALUES(?,'PLAYER',?,5000)")) {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, founder);
        statement.executeUpdate();
      }
      var foundingReservation = lifecycle.reserve(founder, Instant.now());
      var foundedCity =
          starterSettlements.create(
              founder, "Founded-" + shortId(), harborWorld, 40, 40, Instant.now());
      var core = new SettlementLifecycleGateway.CoreLocation(harborWorld, 648, 64, 648);
      lifecycle.validateCore(core);
      lifecycle.complete(
          foundingReservation.id(),
          foundedCity.id(),
          founder,
          core,
          "We establish a fair and lasting settlement charter.",
          Instant.now());
      assertThrows(DomainException.class, () -> lifecycle.validateCore(core));
      assertEquals(2_500, finances.balance(founder, Instant.now()));
      UUID successor = UUID.randomUUID();
      SettlementGateway.Invitation successorInvite =
          starterSettlements.invite(
              foundedCity.id(),
              founder,
              successor,
              EnumSet.of(GovernmentRole.MAYOR),
              Instant.now().plusSeconds(60),
              Instant.now());
      starterSettlements.acceptInvitation(successorInvite.id(), successor, Instant.now());
      lifecycle.touch(successor, Instant.now());
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "UPDATE settlement_member_activity SET last_active_at=now()-interval '8 days' WHERE city_id=? AND player_id=?")) {
        statement.setObject(1, foundedCity.id());
        statement.setObject(2, founder);
        statement.executeUpdate();
      }
      assertEquals(
          successor, lifecycle.succession(foundedCity.id(), successor, Instant.now()).owner());
      assertEquals(
          founder, lifecycle.transfer(foundedCity.id(), successor, founder, Instant.now()).owner());
      assertTrue(lifecycle.history(foundedCity.id(), founder).size() >= 3);
      UUID cancelledFounder = UUID.randomUUID();
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "INSERT INTO accounts(id,owner_type,owner_id,balance_minor) VALUES(?,'PLAYER',?,3000)")) {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, cancelledFounder);
        statement.executeUpdate();
      }
      var cancelledReservation = lifecycle.reserve(cancelledFounder, Instant.now());
      lifecycle.cancel(cancelledReservation.id(), cancelledFounder, Instant.now());
      assertEquals(3_000, finances.balance(cancelledFounder, Instant.now()));
      assertEquals("RUINS", lifecycle.disband(foundedCity.id(), founder, Instant.now()).status());
      assertEquals(
          "ACTIVE", lifecycle.recoverRuins(foundedCity.id(), founder, Instant.now()).status());
      assertEquals("RUINS", lifecycle.abandon(foundedCity.id(), founder, Instant.now()).status());
      UUID mergeSourceOwner = UUID.randomUUID();
      UUID mergeTargetOwner = UUID.randomUUID();
      var mergeSource =
          starterSettlements.create(
              mergeSourceOwner, "MergeSource-" + shortId(), harborWorld, 50, 50, Instant.now());
      var mergeTarget =
          starterSettlements.create(
              mergeTargetOwner, "MergeTarget-" + shortId(), harborWorld, 60, 60, Instant.now());
      var mergeProposal =
          lifecycle.merge(mergeSource.id(), mergeSourceOwner, mergeTarget.id(), Instant.now());
      assertEquals(
          mergeTarget.id(),
          lifecycle.acceptMerge(mergeProposal.id(), mergeTargetOwner, Instant.now()).city());
      assertEquals(
          mergeTarget.id(), starterSettlements.findByPlayer(mergeSourceOwner).orElseThrow().id());
      RecoveryCoordinator.RecoveryReport report = new PostgresRecoveryCoordinator(store).recover();
      assertTrue(report.outboxEvents() >= 0);
      assertEquals(0, report.leases());
      assertEquals(0, report.consumptions());
      assertEquals(0, report.transfers());

      PostgresSettlementGateway settlements = new PostgresSettlementGateway(store);
      UUID owner = UUID.randomUUID();
      UUID world = UUID.randomUUID();
      SettlementGateway.CitySnapshot city =
          settlements.create(
              owner, "Test-" + owner.toString().substring(0, 8), world, 10, 10, Instant.now());
      DistrictApplicationService districtService =
          new DistrictApplicationService(new PostgresDistrictGateway(store));
      SettlementGateway.Bounds districtBounds =
          new SettlementGateway.Bounds(world, 160, -64, 160, 175, 320, 175);
      var district =
          districtService.create(
              city.id(), owner, "Fields", DistrictType.AGRICULTURAL, districtBounds, Instant.now());
      assertEquals(20, district.bonuses().production());
      assertEquals(1, districtService.list(city.id(), owner).size());
      assertThrows(
          DomainException.class,
          () ->
              districtService.create(
                  city.id(),
                  owner,
                  "Overlap",
                  DistrictType.RESIDENTIAL,
                  districtBounds,
                  Instant.now()));
      UUID firstManager = UUID.randomUUID();
      UUID secondManager = UUID.randomUUID();
      UUID worker = UUID.randomUUID();
      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "INSERT INTO city_members(city_id,player_id,role,joined_at) VALUES('"
                + city.id()
                + "','"
                + firstManager
                + "','CITIZEN',now()),('"
                + city.id()
                + "','"
                + secondManager
                + "','CITIZEN',now())");
        statement.executeUpdate(
            "INSERT INTO workers(id,city_id,profession,skill,state,salary_minor) VALUES('"
                + worker
                + "','"
                + city.id()
                + "','FARMER',50,'IDLE',0)");
      }
      districtService.rename(district.id(), owner, "Harvest Fields", Instant.now());
      districtService.resize(
          district.id(),
          owner,
          new SettlementGateway.Bounds(world, 160, -64, 160, 174, 320, 174),
          Instant.now());
      districtService.manager(district.id(), owner, firstManager, false, Instant.now());
      assertThrows(
          DomainException.class,
          () -> districtService.priority(district.id(), firstManager, 10, Instant.now()));
      districtService.manager(district.id(), owner, secondManager, true, Instant.now());
      districtService.budget(district.id(), owner, 500, Instant.now());
      districtService.priority(district.id(), owner, 80, Instant.now());
      districtService.policy(district.id(), owner, "work", "DAYLIGHT", Instant.now());
      districtService.worker(district.id(), owner, worker, 90, Instant.now());
      BuildingValidationService buildingValidation =
          new BuildingValidationService(
              new PostgresBuildingValidationGateway(store), new BuildingValidator());
      SettlementGateway.Bounds farmBounds =
          new SettlementGateway.Bounds(world, 162, 60, 162, 169, 66, 169);
      var registeredFarm =
          buildingValidation.validateAndRegister(
              city.id(),
              owner,
              BuildingType.FARM,
              farmBounds,
              district.id().toString(),
              new BuildingSurvey(8, 7, 8, 48, 0, 0, 0, 0, 0, 0, 24, 1, 16, 0, 0, 0, Map.of()),
              Instant.now());
      assertEquals("ACTIVE", registeredFarm.state());
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "INSERT INTO district_storage(district_id,commodity_key,quantity,capacity) VALUES(?,'minecraft:wheat',32,64)")) {
        statement.setObject(1, district.id());
        statement.executeUpdate();
      }
      var districtReport = districtService.report(district.id(), owner);
      assertEquals(1, districtReport.workers());
      assertEquals(1, districtReport.buildings());
      assertEquals(32, districtReport.storedUnits());
      assertEquals(500, districtReport.district().budgetMinor());
      assertEquals(80, districtReport.district().priority());
      assertEquals("DAYLIGHT", districtReport.district().policies().get("WORK"));
      assertTrue(districtReport.history().size() >= 8);
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "SELECT status,(SELECT count(*) FROM building_validation_history WHERE building_id=?) FROM city_buildings WHERE id=?")) {
        statement.setObject(1, registeredFarm.id());
        statement.setObject(2, registeredFarm.id());
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals("ACTIVE", result.getString(1));
          assertEquals(4, result.getInt(2));
        }
      }
      districtService.removeWorker(district.id(), owner, worker, Instant.now());
      districtService.delete(district.id(), owner, Instant.now());
      assertTrue(districtService.list(city.id(), owner).isEmpty());
      assertEquals(SettlementLevel.CAMP, city.level());
      assertEquals(
          "INFLUENCED",
          settlements
              .claim(
                  city.id(),
                  owner,
                  world,
                  11,
                  10,
                  4,
                  EnumSet.of(GovernmentRole.MAYOR),
                  Instant.now())
              .state());
      ClaimProtectionGateway.Snapshot protection =
          new PostgresClaimProtectionGateway(store).load(Instant.now());
      assertEquals(
          city.id(), protection.claims().get(new ClaimProtectionGateway.ClaimKey(world, 11, 10)));
      assertTrue(
          protection.members().stream()
              .anyMatch(value -> value.city().equals(city.id()) && value.player().equals(owner)));

      UUID member = UUID.randomUUID();
      SettlementGateway.Invitation invite =
          settlements.invite(
              city.id(),
              owner,
              member,
              EnumSet.of(GovernmentRole.MAYOR),
              Instant.now().plusSeconds(60),
              Instant.now());
      settlements.acceptInvitation(invite.id(), member, Instant.now());
      assertThrows(
          DomainException.class,
          () ->
              settlements.claim(
                  city.id(),
                  member,
                  world,
                  9,
                  10,
                  4,
                  EnumSet.of(GovernmentRole.MAYOR, GovernmentRole.ARCHITECT),
                  Instant.now()));
      settlements.changeRole(
          city.id(),
          owner,
          member,
          GovernmentRole.ARCHITECT,
          EnumSet.of(GovernmentRole.MAYOR),
          Instant.now());
      assertEquals(
          "INFLUENCED",
          settlements
              .claim(
                  city.id(),
                  member,
                  world,
                  9,
                  10,
                  4,
                  EnumSet.of(GovernmentRole.MAYOR, GovernmentRole.ARCHITECT),
                  Instant.now())
              .state());

      settlements.setPolicy(
          city.id(),
          owner,
          "TAX_PROFILE",
          "\"STANDARD\"",
          Instant.now().plusSeconds(3600),
          EnumSet.of(GovernmentRole.MAYOR),
          Instant.now());
      assertThrows(
          DomainException.class,
          () ->
              settlements.setPolicy(
                  city.id(),
                  owner,
                  "TAX_PROFILE",
                  "\"HIGH\"",
                  Instant.now().plusSeconds(7200),
                  EnumSet.of(GovernmentRole.MAYOR),
                  Instant.now()));

      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE cities SET population=10,civilization=20 WHERE id='" + city.id() + "'");
        statement.executeUpdate(
            "UPDATE accounts SET balance_minor=10000 WHERE owner_type='CITY' AND owner_id='"
                + city.id()
                + "'");
      }
      UUID upgradeKey = UUID.randomUUID();
      SettlementGateway.CitySnapshot upgraded =
          settlements.upgrade(
              city.id(),
              owner,
              SettlementLevel.CAMP,
              SettlementLevel.OUTPOST,
              10,
              50,
              20,
              null,
              10_000,
              EnumSet.of(GovernmentRole.MAYOR),
              upgradeKey,
              Instant.now());
      assertEquals(SettlementLevel.OUTPOST, upgraded.level());
      assertEquals(
          SettlementLevel.OUTPOST,
          settlements
              .upgrade(
                  city.id(),
                  owner,
                  SettlementLevel.CAMP,
                  SettlementLevel.OUTPOST,
                  10,
                  50,
                  20,
                  null,
                  10_000,
                  EnumSet.of(GovernmentRole.MAYOR),
                  upgradeKey,
                  Instant.now())
              .level());
      assertEquals(0, settlements.treasuryBalance(city.id()));

      SettlementDailySimulation daily =
          new SettlementDailySimulation(new PostgresSettlementSimulationGateway(store));
      assertTrue(daily.cycle(1_000, Instant.now()).settlements() > 0);
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "SELECT c.population,c.prosperity,a.balance_minor,i.status FROM cities c JOIN accounts a ON a.owner_type='CITY' AND a.owner_id=c.id JOIN maintenance_invoices i ON i.city_id=c.id WHERE c.id=? ORDER BY i.due_at DESC LIMIT 1")) {
        statement.setObject(1, city.id());
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals(9, result.getInt(1));
          assertEquals(45, result.getInt(2));
          assertEquals(500, result.getLong(3));
          assertEquals("OVERDUE", result.getString(4));
        }
      }

      ChunkOwnershipCache cache = new ChunkOwnershipCache();
      InfluenceSimulationService influence =
          new InfluenceSimulationService(new PostgresInfluencePersistence(store), cache, 75, 3);
      influence.rebuildCache();
      InfluenceSimulationService.CycleReport cycle = influence.cycle(1_000, Instant.now());
      assertEquals(true, cycle.settlements() > 0);
      assertEquals(
          TerritoryState.CAPITAL,
          cache.get(new ChunkPos(new WorldId(world), 10, 10)).orElseThrow().state());
      assertEquals(
          TerritoryState.CONTROLLED,
          cache.get(new ChunkPos(new WorldId(world), 11, 10)).orElseThrow().state());

      UUID buyerOwner = UUID.randomUUID();
      UUID sellerOwner = UUID.randomUUID();
      UUID sellerWorld = UUID.randomUUID();
      SettlementGateway.CitySnapshot buyer =
          settlements.create(
              buyerOwner,
              "Buyer-" + buyerOwner.toString().substring(0, 8),
              sellerWorld,
              20,
              20,
              Instant.now());
      SettlementGateway.CitySnapshot seller =
          settlements.create(
              sellerOwner,
              "Seller-" + sellerOwner.toString().substring(0, 8),
              sellerWorld,
              30,
              30,
              Instant.now());
      PostgresLogisticsGateway logistics = new PostgresLogisticsGateway(store);
      LogisticsGateway.RoadNode sellerNode =
          logistics.registerNode(
              seller.id(), sellerOwner, sellerWorld, 488, 64, 488, "WAREHOUSE", Instant.now());
      LogisticsGateway.RoadNode buyerNode =
          logistics.registerNode(
              buyer.id(), buyerOwner, sellerWorld, 328, 64, 328, "WAREHOUSE", Instant.now());
      var physicalEdge =
          new PostgresInfrastructureGateway(store)
              .register(
                  seller.id(),
                  sellerOwner,
                  sellerNode.id(),
                  buyerNode.id(),
                  80,
                  new InfrastructureValidator()
                      .validate(
                          InfrastructureType.ROAD,
                          new InfrastructureSurvey(161, 161, 3, 0, 0, 80, 1, 0, 0)),
                  Instant.now());
      assertEquals(seller.id(), physicalEdge.owner());
      assertEquals(2_700, physicalEdge.capacity());
      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE accounts SET balance_minor=1000 WHERE owner_type='CITY' AND owner_id='"
                + buyer.id()
                + "'");
      }
      PostgresEconomyGateway economy = new PostgresEconomyGateway(store);
      economy.deposit(seller.id(), sellerOwner, "minecraft:iron_ingot", 10, Instant.now());
      EconomyGateway.OrderSnapshot buy =
          economy.placeOrder(
              buyer.id(),
              buyerOwner,
              MarketEngine.Side.BUY,
              "minecraft:iron_ingot",
              10,
              20,
              UUID.randomUUID(),
              Instant.now());
      economy.placeOrder(
          seller.id(),
          sellerOwner,
          MarketEngine.Side.SELL,
          "minecraft:iron_ingot",
          6,
          15,
          UUID.randomUUID(),
          Instant.now());
      assertEquals(1, economy.match(10, Instant.now()));
      Instant caravanNow = Instant.now();
      PostgresCaravanGateway caravanGateway = new PostgresCaravanGateway(store);
      assertEquals(1, caravanGateway.cycle(10, caravanNow).synchronizedShipments());
      LogisticsGateway.Shipment marketCaravan = logistics.shipments(buyer.id()).getFirst();
      assertEquals(0, logistics.cycle(1, caravanNow).delivered());
      assertEquals(1, caravanGateway.cycle(10, caravanNow.plusSeconds(1)).advanced());
      assertEquals(marketCaravan.id(), caravanGateway.presentations(10).getFirst().shipment());
      assertEquals(
          buyerOwner,
          caravanGateway
              .escort(marketCaravan.id(), buyerOwner, caravanNow.plusSeconds(2))
              .escort());
      UUID caravanEntity = UUID.randomUUID();
      caravanGateway.bind(marketCaravan.id(), caravanEntity, caravanNow.plusSeconds(2));
      var damagedCaravan =
          caravanGateway.damage(marketCaravan.id(), sellerOwner, 12, caravanNow.plusSeconds(3));
      assertEquals("PHYSICAL", damagedCaravan.mode());
      assertEquals(91, damagedCaravan.health());
      caravanGateway.unbind(marketCaravan.id(), caravanEntity, caravanNow.plusSeconds(4));
      assertEquals(0, caravanGateway.cycle(10, caravanNow.plusSeconds(14)).advanced());
      assertEquals(0, logistics.cycle(1, caravanNow.plusSeconds(15)).delivered());
      caravanGateway.cycle(10, caravanNow.plusSeconds(16));
      assertEquals(1, logistics.cycle(1, caravanNow.plusSeconds(1_000)).delivered());
      assertEquals(1, caravanGateway.cycle(10, caravanNow.plusSeconds(1_001)).unloaded());
      assertEquals(1, caravanGateway.cycle(10, caravanNow.plusSeconds(1_007)).despawned());
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "SELECT infrastructure_type,integrity,capacity,traffic,importance,owner_city,minimum_width,surface_quality,broken_segments FROM road_edges WHERE id=?")) {
        statement.setObject(1, physicalEdge.id());
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals("ROAD", result.getString(1));
          assertEquals(90, result.getInt(2));
          assertEquals(2_700, result.getLong(3));
          assertEquals(2, result.getLong(4));
          assertEquals(80, result.getInt(5));
          assertEquals(seller.id(), result.getObject(6, UUID.class));
          assertEquals(3, result.getInt(7));
          assertEquals(80, result.getInt(8));
          assertEquals(0, result.getInt(9));
        }
      }
      assertEquals(
          6,
          economy.warehouse(buyer.id(), buyerOwner, Instant.now()).stock().stream()
              .filter(stock -> stock.commodity().equals("minecraft:iron_ingot"))
              .findFirst()
              .orElseThrow()
              .available());
      EconomyGateway.Stock sellerStock =
          economy.warehouse(seller.id(), sellerOwner, Instant.now()).stock().stream()
              .filter(stock -> stock.commodity().equals("minecraft:iron_ingot"))
              .findFirst()
              .orElseThrow();
      assertEquals(4, sellerStock.available());
      assertEquals(0, sellerStock.reserved());
      economy.cancel(buyer.id(), buyerOwner, buy.id(), Instant.now());
      assertEquals(910, settlements.treasuryBalance(buyer.id()));
      assertEquals(10_090, settlements.treasuryBalance(seller.id()));

      SettlementGateway.BuildingSnapshot industry =
          settlements.registerBuilding(
              seller.id(),
              sellerOwner,
              nl.frontier.city.Building.Category.INDUSTRY,
              new SettlementGateway.Bounds(sellerWorld, 480, 50, 480, 495, 80, 495),
              EnumSet.of(GovernmentRole.MAYOR),
              Instant.now());
      economy.deposit(seller.id(), sellerOwner, "minecraft:oak_log", 2, Instant.now());
      PostgresProductionGateway production = new PostgresProductionGateway(store);
      production.hire(seller.id(), sellerOwner, "LUMBERJACK", 100, 20, Instant.now());
      ProductionGateway.ProductionOrder productionOrder =
          production.queue(
              seller.id(),
              sellerOwner,
              industry.id(),
              "frontier:saw_planks",
              2,
              50,
              UUID.randomUUID(),
              Instant.now());
      assertEquals("ACTIVE", productionOrder.status());
      assertEquals(1, production.cycle(1, Instant.now()).completed());
      EconomyGateway.WarehouseSnapshot produced =
          economy.warehouse(seller.id(), sellerOwner, Instant.now());
      EconomyGateway.Stock logs =
          produced.stock().stream()
              .filter(stock -> stock.commodity().equals("minecraft:oak_log"))
              .findFirst()
              .orElseThrow();
      EconomyGateway.Stock planks =
          produced.stock().stream()
              .filter(stock -> stock.commodity().equals("minecraft:oak_planks"))
              .findFirst()
              .orElseThrow();
      assertEquals(0, logs.available());
      assertEquals(0, logs.reserved());
      assertEquals(8, planks.available());

      UUID sellerWarehouse = economy.warehouse(seller.id(), sellerOwner, Instant.now()).id();
      UUID buyerWarehouse = economy.warehouse(buyer.id(), buyerOwner, Instant.now()).id();
      LogisticsGateway.Shipment shipment =
          logistics.createShipment(
              seller.id(),
              sellerOwner,
              sellerWarehouse,
              buyerWarehouse,
              sellerNode.id(),
              buyerNode.id(),
              "minecraft:oak_planks",
              3,
              "CARAVAN",
              100,
              UUID.randomUUID(),
              Instant.now());
      assertEquals("TRAVELING", shipment.status());
      assertEquals(1, logistics.cycle(1, Instant.now().plusSeconds(1_000)).delivered());
      assertEquals(
          3,
          economy.warehouse(buyer.id(), buyerOwner, Instant.now()).stock().stream()
              .filter(stock -> stock.commodity().equals("minecraft:oak_planks"))
              .findFirst()
              .orElseThrow()
              .available());

      PostgresContractGateway contracts = new PostgresContractGateway(store);
      ContractGateway.ContractSnapshot contract =
          contracts.postDelivery(
              buyer.id(),
              buyerOwner,
              buyerWarehouse,
              "minecraft:oak_planks",
              2,
              100,
              Instant.now().plusSeconds(3_600),
              UUID.randomUUID(),
              Instant.now());
      assertEquals("POSTED", contract.status());
      assertEquals(
          "ACCEPTED", contracts.accept(contract.id(), sellerOwner, Instant.now()).status());
      UUID completionKey = UUID.randomUUID();
      assertEquals(
          "PAID",
          contracts.deliver(contract.id(), sellerOwner, completionKey, Instant.now()).status());
      assertEquals(
          "PAID",
          contracts.deliver(contract.id(), sellerOwner, completionKey, Instant.now()).status());
      assertEquals(810, settlements.treasuryBalance(buyer.id()));
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "SELECT balance_minor FROM accounts WHERE owner_type='PLAYER' AND owner_id=?")) {
        statement.setObject(1, sellerOwner);
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals(100, result.getLong(1));
        }
      }

      PostgresNpcMaterializationGateway npcs = new PostgresNpcMaterializationGateway(store);
      NpcMaterializationGateway.Candidate candidate =
          npcs.candidates(java.util.Set.of(sellerOwner), 20).stream()
              .filter(value -> value.city().equals(seller.id()))
              .findFirst()
              .orElseThrow();
      UUID presentation = UUID.randomUUID();
      npcs.bind(candidate.worker(), presentation, Instant.now());
      assertEquals(
          presentation,
          npcs.candidates(java.util.Set.of(sellerOwner), 20).stream()
              .filter(value -> value.worker().equals(candidate.worker()))
              .findFirst()
              .orElseThrow()
              .entity());
      assertTrue(
          npcs.retirements(java.util.Set.of()).stream()
              .anyMatch(binding -> binding.worker().equals(candidate.worker())));
      npcs.unbind(candidate.worker(), presentation, Instant.now());

      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE cities SET population=8 WHERE id='" + seller.id() + "'");
        statement.executeUpdate(
            "UPDATE accounts SET balance_minor=2000 WHERE owner_type='CITY' AND owner_id='"
                + seller.id()
                + "'");
      }
      economy.deposit(seller.id(), sellerOwner, "minecraft:wheat", 2, Instant.now());
      economy.deposit(seller.id(), sellerOwner, "minecraft:bread", 2, Instant.now());
      assertTrue(daily.cycle(1_000, Instant.now().plusSeconds(2 * 86_400L)).settlements() > 0);
      assertEquals(1_020, settlements.treasuryBalance(seller.id()));
      EconomyGateway.WarehouseSnapshot afterDaily =
          economy.warehouse(seller.id(), sellerOwner, Instant.now());
      assertEquals(
          64,
          afterDaily.stock().stream()
              .filter(stock -> stock.commodity().equals("minecraft:wheat"))
              .findFirst()
              .orElseThrow()
              .available());
      assertEquals(
          18,
          afterDaily.stock().stream()
              .filter(stock -> stock.commodity().equals("minecraft:bread"))
              .findFirst()
              .orElseThrow()
              .available());
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "SELECT mood,happiness,experience,state FROM workers WHERE id=?")) {
        statement.setObject(1, candidate.worker());
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals(72, result.getInt(1));
          assertEquals(71, result.getInt(2));
          assertEquals(1, result.getLong(3));
          assertEquals("IDLE", result.getString(4));
        }
      }

      UUID warWorld = UUID.randomUUID();
      UUID attackerOwner = UUID.randomUUID();
      UUID defenderOwner = UUID.randomUUID();
      SettlementGateway.CitySnapshot attacker =
          settlements.create(
              attackerOwner,
              "Attack-" + attackerOwner.toString().substring(0, 8),
              warWorld,
              40,
              40,
              Instant.now());
      SettlementGateway.CitySnapshot defender =
          settlements.create(
              defenderOwner,
              "Defend-" + defenderOwner.toString().substring(0, 8),
              warWorld,
              50,
              50,
              Instant.now());
      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE accounts SET balance_minor=10000 WHERE owner_type='CITY' AND owner_id='"
                + attacker.id()
                + "'");
      }
      PostgresCampaignGateway campaigns = new PostgresCampaignGateway(store);
      Instant declaredAt = Instant.now();
      CampaignGateway.CampaignSnapshot campaign =
          campaigns.declare(
              attacker.id(),
              attackerOwner,
              defender.id(),
              WarCampaign.Type.BORDER,
              new CampaignGateway.ObjectiveSpec(
                  "FORT_BREACH", warWorld, 800, 0, 800, 815, 320, 815, 100, 1),
              5_000,
              Duration.ofSeconds(1),
              Duration.ofSeconds(10),
              UUID.randomUUID(),
              declaredAt);
      assertEquals(WarCampaign.Phase.PREPARATION, campaign.phase());
      assertEquals(5_000, settlements.treasuryBalance(attacker.id()));
      assertEquals(1, campaigns.advanceDue(10, declaredAt.plusSeconds(2)).activated());
      CampaignGateway.WarPolicySnapshot policy =
          campaigns.policySnapshot(declaredAt.plusSeconds(2));
      assertTrue(policy.wars().stream().anyMatch(value -> value.campaign().equals(campaign.id())));
      PostgresWarDamageGateway damage = new PostgresWarDamageGateway(store);
      WarDamageGateway.DamageAttempt attempt =
          new WarDamageGateway.DamageAttempt(
              campaign.id(),
              attackerOwner,
              defender.id(),
              warWorld,
              805,
              64,
              805,
              "minecraft:stone_bricks",
              "minecraft:air",
              "PLAYER_BREAK",
              2,
              0,
              declaredAt.plusSeconds(2));
      WarDamageGateway.Decision damageDecision;
      try (var executor = Executors.newFixedThreadPool(2)) {
        var firstDamage =
            executor.submit(
                () -> damage.authorizeAndJournal(attempt, Duration.ofHours(6), 1_200, 3_000));
        var secondDamage =
            executor.submit(
                () -> damage.authorizeAndJournal(attempt, Duration.ofHours(6), 1_200, 3_000));
        WarDamageGateway.Decision first = firstDamage.get(10, TimeUnit.SECONDS);
        WarDamageGateway.Decision second = secondDamage.get(10, TimeUnit.SECONDS);
        assertEquals(20, first.chargedPoints() + second.chargedPoints());
        assertEquals(1, (first.mutationRequired() ? 1 : 0) + (second.mutationRequired() ? 1 : 0));
        damageDecision = first.mutationRequired() ? first : second;
      }
      assertTrue(damageDecision.allowed());
      assertEquals(20, damageDecision.chargedPoints());
      damage.confirmApplied(damageDecision.damage(), attempt.damagedData(), Instant.now());
      damage.confirmApplied(damageDecision.damage(), attempt.damagedData(), Instant.now());
      assertEquals(
          0,
          damage.authorizeAndJournal(attempt, Duration.ofHours(6), 1_200, 3_000).chargedPoints());
      CampaignGateway.ObjectiveTickReport objectiveTick =
          campaigns.tickObjectives(
              java.util.List.of(
                  new CampaignGateway.Presence(attackerOwner, warWorld, 805, 64, 805, true)),
              100,
              declaredAt.plusSeconds(2));
      assertEquals(1, objectiveTick.completed());
      assertEquals(
          WarCampaign.Phase.CEASEFIRE,
          campaigns.ceasefire(campaign.id(), defenderOwner, declaredAt.plusSeconds(3)).phase());
      assertEquals(
          WarCampaign.Phase.ACTIVE,
          campaigns.resume(campaign.id(), attackerOwner, declaredAt.plusSeconds(4)).phase());
      assertEquals(
          WarCampaign.Phase.RESOLUTION,
          campaigns
              .resolve(campaign.id(), defenderOwner, "SURRENDER", declaredAt.plusSeconds(5))
              .phase());
      assertEquals(
          WarCampaign.Phase.ENDED,
          campaigns
              .end(campaign.id(), defenderOwner, "SURRENDER", declaredAt.plusSeconds(6))
              .phase());

      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE cities SET level=3 WHERE id='" + defender.id() + "'");
        statement.executeUpdate(
            "UPDATE accounts SET balance_minor=1000 WHERE owner_type='CITY' AND owner_id='"
                + defender.id()
                + "'");
      }
      settlements.registerBuilding(
          defender.id(),
          defenderOwner,
          nl.frontier.city.Building.Category.INFRASTRUCTURE,
          new SettlementGateway.Bounds(warWorld, 800, 50, 800, 815, 90, 815),
          EnumSet.of(GovernmentRole.MAYOR),
          Instant.now());
      economy.deposit(defender.id(), defenderOwner, "minecraft:stone_bricks", 1, Instant.now());
      production.hire(defender.id(), defenderOwner, "BUILDER", 80, 25, Instant.now());
      PostgresRepairGateway repairs = new PostgresRepairGateway(store);
      RepairGateway.Quote repairQuote =
          repairs.quote(
              defender.id(),
              defenderOwner,
              campaign.id(),
              RepairOrder.Priority.NORMAL,
              Instant.now());
      assertEquals(1, repairQuote.tasks());
      assertEquals(43, repairQuote.totalCostMinor());
      RepairGateway.RepairSnapshot repair =
          repairs.purchase(
              defender.id(),
              defenderOwner,
              campaign.id(),
              RepairOrder.Priority.NORMAL,
              UUID.randomUUID(),
              Instant.now());
      assertEquals(RepairOrder.Status.RESERVED, repair.status());
      UUID expiredCoordinator = UUID.randomUUID();
      RepairGateway.PreparedTask repairTask =
          repairs
              .leaseReady(expiredCoordinator, 10, Instant.now(), Instant.now().minusSeconds(1))
              .stream()
              .filter(value -> value.order().equals(repair.id()))
              .findFirst()
              .orElseThrow();
      assertTrue(new PostgresRecoveryCoordinator(store).recover().leases() >= 1);
      UUID repairCoordinator = UUID.randomUUID();
      repairTask =
          repairs
              .leaseReady(repairCoordinator, 10, Instant.now(), Instant.now().plusSeconds(60))
              .stream()
              .filter(value -> value.order().equals(repair.id()))
              .findFirst()
              .orElseThrow();
      repairs.commit(repairCoordinator, repairTask.id(), Instant.now());
      repairs.commit(repairCoordinator, repairTask.id(), Instant.now());
      assertEquals(RepairOrder.Status.COMPLETED, repairs.orders(defender.id()).getFirst().status());
      assertEquals(957, settlements.treasuryBalance(defender.id()));
      assertEquals(1, repairs.archiveCompleted(Instant.now().plusSeconds(1), 10, Instant.now()));
      assertEquals(RepairOrder.Status.ARCHIVED, repairs.orders(defender.id()).getFirst().status());

      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE campaigns SET phase='ACTIVE' WHERE id='" + campaign.id() + "'");
        statement.executeUpdate(
            "UPDATE campaign_objectives SET state='ACTIVE' WHERE campaign_id='"
                + campaign.id()
                + "'");
      }
      WarDamageGateway.Decision rebreak =
          damage.authorizeAndJournal(
              new WarDamageGateway.DamageAttempt(
                  campaign.id(),
                  attackerOwner,
                  defender.id(),
                  warWorld,
                  805,
                  64,
                  805,
                  "minecraft:stone_bricks",
                  "minecraft:air",
                  "PLAYER_BREAK",
                  2,
                  0,
                  Instant.now().minusSeconds(20)),
              Duration.ofHours(6),
              1_200,
              3_000);
      assertTrue(rebreak.mutationRequired());
      assertTrue(rebreak.chargedPoints() > 0);
      assertTrue(
          damage.pendingMutations(10).stream()
              .anyMatch(value -> value.damage().equals(rebreak.damage())));
      damage.reject(rebreak.damage(), "TEST_ROLLBACK", Instant.now());
      assertEquals(0, damage.pendingMutations(10).size());

      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE cities SET prosperity=100,version=version+1 WHERE id='" + buyer.id() + "'");
      }
      PostgresWorldSimulationGateway worldSimulation = new PostgresWorldSimulationGateway(store);
      WorldSimulationGateway.CycleReport worldCycle = worldSimulation.cycle(1_000, Instant.now());
      assertTrue(worldCycle.cities() > 0);
      assertTrue(worldSimulation.regions().stream().anyMatch(region -> region.population() >= 0));
      assertTrue(
          worldSimulation.events(false).stream()
              .anyMatch(event -> event.key().equals("TRADE_FAIR")));

      PostgresCivilizationGateway civilization = new PostgresCivilizationGateway(store);
      CivilizationGateway.KingdomSnapshot firstKingdom =
          civilization.createKingdom(
              attacker.id(),
              attackerOwner,
              "First " + attackerOwner.toString().substring(0, 8),
              Instant.now());
      CivilizationGateway.KingdomSnapshot secondKingdom =
          civilization.createKingdom(
              defender.id(),
              defenderOwner,
              "Second " + defenderOwner.toString().substring(0, 8),
              Instant.now());
      CivilizationGateway.Invitation kingdomInvite =
          civilization.inviteCity(
              firstKingdom.id(),
              attackerOwner,
              buyer.id(),
              Instant.now().plusSeconds(3_600),
              Instant.now());
      assertTrue(
          civilization
              .acceptInvitation(kingdomInvite.id(), buyer.id(), buyerOwner, Instant.now())
              .cities()
              .contains(buyer.id()));
      KingdomIntegrationService kingdomIntegration =
          new KingdomIntegrationService(new PostgresKingdomIntegrationGateway(store));
      kingdomIntegration.assignRole(
          firstKingdom.id(),
          attackerOwner,
          buyerOwner,
          KingdomIntegrationGateway.Role.DIPLOMAT,
          Instant.now());
      var governanceVote =
          kingdomIntegration.createVote(
              firstKingdom.id(),
              attackerOwner,
              "SHARED_PROJECT",
              "{\"project\":\"northern_road\"}",
              Instant.now().plusSeconds(3_600),
              Instant.now());
      assertEquals(
          "OPEN",
          kingdomIntegration
              .castVote(governanceVote.id(), attacker.id(), attackerOwner, true, Instant.now())
              .status());
      assertEquals(
          "PASSED",
          kingdomIntegration
              .castVote(governanceVote.id(), buyer.id(), buyerOwner, true, Instant.now())
              .status());
      long kingdomBefore = kingdomIntegration.report(firstKingdom.id()).treasuryMinor();
      var kingdomDeposit =
          kingdomIntegration.deposit(
              firstKingdom.id(),
              attacker.id(),
              attackerOwner,
              100,
              UUID.randomUUID(),
              Instant.now());
      assertEquals(kingdomBefore + 100, kingdomDeposit.kingdomBalanceMinor());
      assertEquals(
          kingdomBefore + 75,
          kingdomIntegration
              .withdraw(
                  firstKingdom.id(),
                  attacker.id(),
                  attackerOwner,
                  25,
                  UUID.randomUUID(),
                  Instant.now())
              .kingdomBalanceMinor());
      kingdomIntegration.setTaxRate(firstKingdom.id(), attackerOwner, 100, Instant.now());
      assertTrue(
          kingdomIntegration.collectTaxes(firstKingdom.id(), LocalDate.now(), Instant.now()).paid()
              >= 1);
      kingdomIntegration.setPolicy(
          firstKingdom.id(), attackerOwner, "PEACEFUL_SECESSION", "DENY", Instant.now());
      assertTrue(
          kingdomIntegration.report(firstKingdom.id()).policies().stream()
              .anyMatch(value -> value.equals("PEACEFUL_SECESSION=DENY")));
      assertEquals(
          "CONTESTED",
          kingdomIntegration
              .requestSecession(firstKingdom.id(), buyer.id(), buyerOwner, Instant.now())
              .status());
      var warApproval =
          kingdomIntegration.approveWar(
              firstKingdom.id(),
              attackerOwner,
              seller.id(),
              "CAMPAIGN",
              Instant.now().plusSeconds(3_600),
              Instant.now());
      assertEquals(seller.id(), warApproval.targetCity());
      CampaignGateway.CampaignSnapshot kingdomCampaign =
          campaigns.declare(
              attacker.id(),
              attackerOwner,
              seller.id(),
              WarCampaign.Type.BORDER,
              new CampaignGateway.ObjectiveSpec(
                  "KINGDOM_BORDER", warWorld, 800, 0, 800, 815, 320, 815, 10, 1),
              100,
              Duration.ofSeconds(1),
              Duration.ofSeconds(10),
              UUID.randomUUID(),
              Instant.now());
      assertEquals(attacker.id(), kingdomCampaign.attacker());
      assertThrows(
          DomainException.class,
          () ->
              campaigns.declare(
                  attacker.id(),
                  attackerOwner,
                  seller.id(),
                  WarCampaign.Type.BORDER,
                  new CampaignGateway.ObjectiveSpec(
                      "REPLAY", warWorld, 800, 0, 800, 815, 320, 815, 10, 1),
                  100,
                  Duration.ofSeconds(1),
                  Duration.ofSeconds(10),
                  UUID.randomUUID(),
                  Instant.now()));
      CivilizationGateway.TreatySnapshot treaty =
          civilization.proposeTreaty(
              firstKingdom.id(),
              attackerOwner,
              secondKingdom.id(),
              "TRADE",
              "{}",
              Instant.now().plusSeconds(86_400),
              Instant.now());
      assertEquals(
          "ACTIVE", civilization.acceptTreaty(treaty.id(), defenderOwner, Instant.now()).status());
      CivilizationGateway.ResearchSnapshot research =
          civilization.startResearch(
              firstKingdom.id(), attackerOwner, "ENGINEERING", "roads_1", 1, Instant.now());
      assertTrue(civilization.cycle(100, Instant.now()).researchCompleted() >= 1);
      assertEquals(
          "COMPLETED",
          civilization.research(firstKingdom.id()).stream()
              .filter(value -> value.id().equals(research.id()))
              .findFirst()
              .orElseThrow()
              .status());
      economy.deposit(attacker.id(), attackerOwner, "minecraft:stone", 3, Instant.now());
      CivilizationGateway.MegaProjectSnapshot mega =
          civilization.startMegaProject(
              firstKingdom.id(),
              attackerOwner,
              "highway_" + UUID.randomUUID(),
              "minecraft:stone",
              3,
              Instant.now());
      assertEquals(
          "COMPLETED",
          civilization
              .contributeMegaProject(
                  mega.id(), attacker.id(), attackerOwner, 3, UUID.randomUUID(), Instant.now())
              .status());
      civilization.cycle(100, Instant.now());
      assertTrue(
          civilization.globalObjectives().stream()
              .anyMatch(value -> value.key().equals("BUILD_WORLD_WONDERS")));
      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE kingdoms SET era='GOLDEN_AGE' WHERE id='" + firstKingdom.id() + "'");
      }
      economy.deposit(attacker.id(), attackerOwner, "minecraft:stone_bricks", 2, Instant.now());
      CivilizationGateway.WonderSnapshot wonder =
          civilization.startWonder(
              firstKingdom.id(),
              attackerOwner,
              "wonder_" + UUID.randomUUID(),
              "minecraft:stone_bricks",
              2,
              Instant.now());
      assertEquals(
          "COMPLETED",
          civilization
              .contributeWonder(
                  wonder.id(), attacker.id(), attackerOwner, 2, UUID.randomUUID(), Instant.now())
              .status());

      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE campaigns SET phase='RESOLUTION',attacker_score=200,defender_score=100 WHERE id='"
                + campaign.id()
                + "'");
      }
      PostgresCampaignOutcomeGateway outcomes = new PostgresCampaignOutcomeGateway(store);
      var conquest =
          outcomes.apply(
              campaign.id(),
              attackerOwner,
              nl.frontier.warfare.CampaignOutcomeGateway.Outcome.CONQUEST,
              0,
              Instant.now());
      assertEquals(attacker.id(), conquest.winner());
      assertTrue(conquest.claims() >= 1);
      assertTrue(conquest.storageUnits() >= 1);
      CampaignGateway.CampaignSnapshot tributeCampaign =
          campaigns.declare(
              seller.id(),
              sellerOwner,
              buyer.id(),
              WarCampaign.Type.BORDER,
              new CampaignGateway.ObjectiveSpec(
                  "TOLL_GATE", sellerWorld, 320, 0, 320, 335, 320, 335, 10, 1),
              100,
              Duration.ofSeconds(1),
              Duration.ofSeconds(10),
              UUID.randomUUID(),
              Instant.now());
      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE campaigns SET phase='RESOLUTION',attacker_score=20,defender_score=0 WHERE id='"
                + tributeCampaign.id()
                + "'");
      }
      assertEquals(
          nl.frontier.warfare.CampaignOutcomeGateway.Outcome.TRIBUTE,
          outcomes
              .apply(
                  tributeCampaign.id(),
                  sellerOwner,
                  nl.frontier.warfare.CampaignOutcomeGateway.Outcome.TRIBUTE,
                  50,
                  Instant.now())
              .outcome());
      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE campaign_tributes SET next_due_at=now()-interval '1 second'");
      }
      assertEquals(1, outcomes.cycleTributes(10, Instant.now()).paid());

      UUID populationOwner = UUID.randomUUID();
      var populationCity =
          settlements.create(
              populationOwner, "Population-" + shortId(), sellerWorld, 70, 70, Instant.now());
      settlements.registerBuilding(
          populationCity.id(),
          populationOwner,
          nl.frontier.city.Building.Category.RESIDENTIAL,
          new SettlementGateway.Bounds(sellerWorld, 1120, 50, 1120, 1135, 80, 1135),
          EnumSet.of(GovernmentRole.MAYOR),
          Instant.now());
      var agingWorker =
          production.hire(populationCity.id(), populationOwner, "FARMER", 80, 20, Instant.now());
      try (var connection = database.dataSource().getConnection();
          var cityStatement =
              connection.prepareStatement(
                  "UPDATE cities SET population=10,prosperity=80 WHERE id=?");
          var workerStatement =
              connection.prepareStatement(
                  "UPDATE workers SET age_days=retirement_age_days-1 WHERE id=?")) {
        cityStatement.setObject(1, populationCity.id());
        cityStatement.executeUpdate();
        workerStatement.setObject(1, agingWorker.id());
        workerStatement.executeUpdate();
      }
      PostgresPopulationGateway population = new PostgresPopulationGateway(store);
      var populationCycle = population.cycle(500, Instant.now());
      assertTrue(populationCycle.settlements() > 0);
      assertEquals(1, populationCycle.retiredWorkers());
      var populationReport = population.report(populationCity.id(), populationOwner);
      assertEquals(14, populationReport.population());
      assertEquals(15, populationReport.housingCapacity());
      assertEquals(1, populationReport.births());
      assertEquals(3, populationReport.immigration());
      assertEquals(
          "RETIRED",
          population.workers(populationCity.id(), populationOwner).getFirst().employment());

      UUID invoicePayer = UUID.randomUUID();
      try (var connection = database.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate(
            "INSERT INTO accounts(id,owner_type,owner_id,balance_minor) VALUES(gen_random_uuid(),'PLAYER','"
                + populationOwner
                + "',5000), (gen_random_uuid(),'PLAYER','"
                + invoicePayer
                + "',500)");
      }
      PostgresCommercialGateway commercial = new PostgresCommercialGateway(store);
      var company =
          commercial.createCompany(
              populationOwner,
              populationCity.id(),
              "Frontier Works",
              1_000,
              UUID.randomUUID(),
              Instant.now());
      assertEquals(1_000, company.balanceMinor());
      var invoice =
          commercial.issueInvoice(
              company.id(),
              populationOwner,
              invoicePayer,
              200,
              Instant.now().plusSeconds(86_400),
              Instant.now());
      assertEquals(
          "PAID",
          commercial
              .payInvoice(invoice.id(), invoicePayer, UUID.randomUUID(), Instant.now())
              .status());
      commercial.issueInvoice(
          company.id(),
          populationOwner,
          invoicePayer,
          100,
          Instant.now().minusSeconds(1),
          Instant.now());
      var loan =
          commercial.borrow(
              company.id(), populationOwner, 1_000, 1_000, UUID.randomUUID(), Instant.now());
      commercial.setBusinessTax(populationCity.id(), populationOwner, 1_000, Instant.now());
      try (var connection = database.dataSource().getConnection();
          var statement =
              connection.prepareStatement(
                  "UPDATE company_loans SET next_interest_at=now()-interval '1 second' WHERE id=?")) {
        statement.setObject(1, loan.id());
        statement.executeUpdate();
      }
      var commercialCycle = commercial.cycle(100, Instant.now());
      assertEquals(1, commercialCycle.interestAccruals());
      assertEquals(1, commercialCycle.taxesCollected());
      assertTrue(commercialCycle.overdueInvoices() >= 1);
      var repaid =
          commercial.repay(loan.id(), populationOwner, 1_100, UUID.randomUUID(), Instant.now());
      assertEquals("PAID", repaid.status());
      var procurement =
          commercial.procure(buyer.id(), buyerOwner, "minecraft:wheat", 2, 10, Instant.now());
      assertEquals(
          "COMPLETED",
          commercial
              .fulfill(procurement.id(), company.id(), populationOwner, 2, Instant.now())
              .status());
      var emergency =
          commercial.emergencyBuy(
              buyer.id(), buyerOwner, "minecraft:wheat", 1, UUID.randomUUID(), Instant.now());
      assertEquals(20, emergency.unitPriceMinor());
      assertTrue(commercial.history(buyer.id(), buyerOwner, 100).size() >= 2);

      ArrayList<PostgresOutboxDispatcher.Event> published = new ArrayList<>();
      PostgresOutboxDispatcher outbox = new PostgresOutboxDispatcher(store, published::add);
      var dispatch = outbox.dispatch(10_000, Instant.now());
      assertTrue(dispatch.published() > 0);
      assertEquals(0, dispatch.remaining());
      assertEquals(dispatch.published(), published.size());
      PostgresAdminDiagnostics diagnostics = new PostgresAdminDiagnostics(store);
      assertTrue(diagnostics.snapshot().counts().get("city") >= 4);
      assertEquals(1, diagnostics.inspect("wonder", wonder.id()).size());
    }
  }

  private static void resetData(DatabaseManager database) throws SQLException {
    try (var connection = database.dataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "DO $$ DECLARE names text; BEGIN SELECT string_agg(format('%I.%I',schemaname,tablename),',') INTO names FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','commodity_definitions','recipes','recipe_inputs','recipe_outputs'); IF names IS NOT NULL THEN EXECUTE 'TRUNCATE TABLE '||names||' CASCADE'; END IF; END $$");
      statement.execute(
          "INSERT INTO global_objectives(id,objective_key,status,progress,target,version) VALUES(gen_random_uuid(),'CONNECT_CAPITALS','ACTIVE',0,1,0),(gen_random_uuid(),'BUILD_WORLD_WONDERS','ACTIVE',0,1,0),(gen_random_uuid(),'SURVIVE_WORLD_CRISIS','ACTIVE',0,1,0),(gen_random_uuid(),'RESTORE_WAR_RUINS','ACTIVE',0,1,0)");
    }
  }
}
