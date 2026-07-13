package nl.frontier.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.economy.CommercialGateway;

public final class PostgresCommercialGateway implements CommercialGateway {
  private static final Set<String> COMPANY_ROLES = Set.of("OWNER", "DIRECTOR");
  private static final Set<String> GOVERNMENT_ROLES = Set.of("MAYOR", "TREASURER");
  private static final UUID HARBOR_CITY =
      UUID.nameUUIDFromBytes("frontier:harbor:city".getBytes(StandardCharsets.UTF_8));
  private final TransactionalStore store;

  public PostgresCommercialGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public Company createCompany(
      UUID actor, UUID city, String name, long capital, UUID key, Instant now) {
    return store.inTransaction(
        connection -> {
          Company existing = companyByKey(connection, key);
          if (existing != null) return existing;
          requireCityMember(connection, city, actor);
          UUID playerAccount = account(connection, "PLAYER", actor, true);
          long playerBalance = balance(connection, playerAccount);
          if (playerBalance < capital) throw new DomainException("insufficient wallet capital");
          UUID company = UUID.randomUUID();
          UUID companyAccount = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO accounts(id,owner_type,owner_id,balance_minor) VALUES(?,'COMPANY',?,?)",
              companyAccount,
              company,
              capital);
          update(
              connection,
              "UPDATE accounts SET balance_minor=balance_minor-?,version=version+1 WHERE id=?",
              capital,
              playerAccount);
          update(
              connection,
              "INSERT INTO companies(id,city_id,name,founder_id,account_id,status,created_at,idempotency_key) VALUES(?,?,?,?,?,'ACTIVE',?,?)",
              company,
              city,
              name,
              actor,
              companyAccount,
              now,
              key);
          update(
              connection,
              "INSERT INTO company_members(company_id,player_id,role,shares,joined_at) VALUES(?,?,'OWNER',10000,?)",
              company,
              actor,
              now);
          ledger(
              connection,
              playerAccount,
              actor,
              -capital,
              playerBalance - capital,
              company,
              key,
              "COMPANY_CAPITAL",
              now);
          ledger(
              connection,
              companyAccount,
              actor,
              capital,
              capital,
              company,
              UUID.nameUUIDFromBytes((key + ":company").getBytes(StandardCharsets.UTF_8)),
              "COMPANY_CAPITAL",
              now);
          return company(connection, company);
        });
  }

  @Override
  public List<Company> companies(UUID actor) {
    return store.inTransaction(
        connection -> {
          List<UUID> ids = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT company_id FROM company_members WHERE player_id=? ORDER BY joined_at")) {
            statement.setObject(1, actor);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) ids.add(result.getObject(1, UUID.class));
            }
          }
          List<Company> values = new ArrayList<>();
          for (UUID id : ids) values.add(company(connection, id));
          return List.copyOf(values);
        });
  }

  @Override
  public Invoice issueInvoice(
      UUID company, UUID actor, UUID target, long amount, Instant dueAt, Instant now) {
    return store.inTransaction(
        connection -> {
          requireCompanyRole(connection, company, actor);
          account(connection, "PLAYER", target, false);
          UUID invoice = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO commercial_invoices(id,company_id,target_player,amount_minor,status,issued_at,due_at) VALUES(?,?,?,?,'ISSUED',?,?)",
              invoice,
              company,
              target,
              amount,
              now,
              dueAt);
          history(
              connection,
              companyCity(connection, company),
              "INVOICE_ISSUED",
              invoice,
              "{\"amount\":" + amount + "}",
              now);
          return invoice(connection, invoice);
        });
  }

  @Override
  public Invoice payInvoice(UUID invoice, UUID payer, UUID key, Instant now) {
    return store.inTransaction(
        connection -> {
          Invoice value = invoice(connection, invoice);
          if (value.status().equals("PAID")) return value;
          if (!value.targetPlayer().equals(payer))
            throw new DomainException("invoice belongs to another player");
          UUID payerAccount = account(connection, "PLAYER", payer, true);
          UUID companyAccount = companyAccount(connection, value.company());
          long payerBalance = balance(connection, payerAccount);
          long companyBalance = balance(connection, companyAccount);
          if (payerBalance < value.amountMinor())
            throw new DomainException("insufficient wallet balance");
          transfer(connection, payerAccount, companyAccount, value.amountMinor());
          update(
              connection,
              "UPDATE commercial_invoices SET status='PAID',paid_at=?,payment_key=?,version=version+1 WHERE id=? AND status<>'PAID'",
              now,
              key,
              invoice);
          ledger(
              connection,
              payerAccount,
              payer,
              -value.amountMinor(),
              payerBalance - value.amountMinor(),
              invoice,
              key,
              "INVOICE_PAYMENT",
              now);
          ledger(
              connection,
              companyAccount,
              payer,
              value.amountMinor(),
              companyBalance + value.amountMinor(),
              invoice,
              UUID.nameUUIDFromBytes((key + ":recipient").getBytes(StandardCharsets.UTF_8)),
              "INVOICE_PAYMENT",
              now);
          history(
              connection,
              companyCity(connection, value.company()),
              "INVOICE_PAID",
              invoice,
              "{}",
              now);
          return invoice(connection, invoice);
        });
  }

  @Override
  public Loan borrow(
      UUID company, UUID actor, long principal, int interestBasisPoints, UUID key, Instant now) {
    return store.inTransaction(
        connection -> {
          Loan existing = loanByKey(connection, key);
          if (existing != null) return existing;
          requireCompanyRole(connection, company, actor);
          UUID city = companyCity(connection, company);
          UUID treasury = account(connection, "CITY", city, true);
          UUID business = companyAccount(connection, company);
          long treasuryBalance = balance(connection, treasury);
          long companyBalance = balance(connection, business);
          if (treasuryBalance < principal) throw new DomainException("settlement cannot fund loan");
          transfer(connection, treasury, business, principal);
          UUID loan = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO company_loans(id,company_id,lender_city,principal_minor,outstanding_minor,annual_interest_bps,status,next_interest_at,idempotency_key,created_at) VALUES(?,?,?,?,?,?,'ACTIVE',?,?,?)",
              loan,
              company,
              city,
              principal,
              principal,
              interestBasisPoints,
              now.plusSeconds(86_400),
              key,
              now);
          ledger(
              connection,
              treasury,
              actor,
              -principal,
              treasuryBalance - principal,
              loan,
              key,
              "COMPANY_LOAN",
              now);
          ledger(
              connection,
              business,
              actor,
              principal,
              companyBalance + principal,
              loan,
              UUID.nameUUIDFromBytes((key + ":borrower").getBytes(StandardCharsets.UTF_8)),
              "COMPANY_LOAN",
              now);
          history(connection, city, "LOAN_CREATED", loan, "{\"principal\":" + principal + "}", now);
          return loan(connection, loan);
        });
  }

  @Override
  public Loan repay(UUID loanId, UUID actor, long amount, UUID key, Instant now) {
    return store.inTransaction(
        connection -> {
          if (scalar(
                  connection,
                  "SELECT count(*) FROM ledger_entries WHERE idempotency_key=? AND reference_id=?",
                  key,
                  loanId)
              > 0) return loan(connection, loanId);
          Loan loan = loan(connection, loanId);
          requireCompanyRole(connection, loan.company(), actor);
          long due = Math.addExact(loan.outstandingMinor(), loan.accruedInterestMinor());
          long payment = Math.min(amount, due);
          UUID business = companyAccount(connection, loan.company());
          UUID treasury =
              account(connection, "CITY", companyCity(connection, loan.company()), true);
          long businessBalance = balance(connection, business);
          long treasuryBalance = balance(connection, treasury);
          if (businessBalance < payment) throw new DomainException("company cannot fund repayment");
          transfer(connection, business, treasury, payment);
          long interestPaid = Math.min(payment, loan.accruedInterestMinor());
          long principalPaid = payment - interestPaid;
          long outstanding = loan.outstandingMinor() - principalPaid;
          long accrued = loan.accruedInterestMinor() - interestPaid;
          update(
              connection,
              "UPDATE company_loans SET outstanding_minor=?,accrued_interest_minor=?,status=CASE WHEN ?=0 AND ?=0 THEN 'PAID' ELSE 'ACTIVE' END,version=version+1 WHERE id=?",
              outstanding,
              accrued,
              outstanding,
              accrued,
              loanId);
          ledger(
              connection,
              business,
              actor,
              -payment,
              businessBalance - payment,
              loanId,
              key,
              "LOAN_REPAYMENT",
              now);
          ledger(
              connection,
              treasury,
              actor,
              payment,
              treasuryBalance + payment,
              loanId,
              UUID.nameUUIDFromBytes((key + ":lender").getBytes(StandardCharsets.UTF_8)),
              "LOAN_REPAYMENT",
              now);
          return loan(connection, loanId);
        });
  }

  @Override
  public TaxRule setBusinessTax(UUID city, UUID actor, int basisPoints, Instant now) {
    return store.inTransaction(
        connection -> {
          requireGovernment(connection, city, actor);
          update(
              connection,
              "INSERT INTO business_tax_rules(city_id,basis_points,changed_by,changed_at) VALUES(?,?,?,?) ON CONFLICT(city_id) DO UPDATE SET basis_points=excluded.basis_points,changed_by=excluded.changed_by,changed_at=excluded.changed_at,version=business_tax_rules.version+1",
              city,
              basisPoints,
              actor,
              now);
          return new TaxRule(city, basisPoints, now);
        });
  }

  @Override
  public Procurement procure(
      UUID city, UUID actor, String commodity, long quantity, long maximumPrice, Instant now) {
    return store.inTransaction(
        connection -> {
          requireGovernment(connection, city, actor);
          UUID id = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO government_procurements(id,city_id,commodity_key,quantity,maximum_unit_price_minor,status,created_by,created_at) VALUES(?,?,?,?,?,'OPEN',?,?)",
              id,
              city,
              commodity,
              quantity,
              maximumPrice,
              actor,
              now);
          history(
              connection,
              city,
              "PROCUREMENT_POSTED",
              id,
              "{\"commodity\":\"" + commodity + "\"}",
              now);
          return procurement(connection, id);
        });
  }

  @Override
  public Procurement fulfill(
      UUID procurementId, UUID company, UUID actor, long requested, Instant now) {
    return store.inTransaction(
        connection -> {
          requireCompanyRole(connection, company, actor);
          Procurement procurement = procurement(connection, procurementId);
          if (!procurement.status().equals("OPEN") && !procurement.status().equals("PARTIAL"))
            return procurement;
          long quantity = Math.min(requested, procurement.quantity() - procurement.fulfilled());
          UUID source = warehouse(connection, companyCity(connection, company));
          UUID destination = warehouse(connection, procurement.city());
          lockStock(connection, source, procurement.commodity());
          lockStock(connection, destination, procurement.commodity());
          if (stock(connection, source, procurement.commodity()) < quantity)
            throw new DomainException("company settlement lacks procurement stock");
          long total = Math.multiplyExact(quantity, procurement.maximumUnitPriceMinor());
          UUID treasury = account(connection, "CITY", procurement.city(), true);
          UUID business = companyAccount(connection, company);
          long treasuryBalance = balance(connection, treasury);
          long businessBalance = balance(connection, business);
          if (treasuryBalance < total)
            throw new DomainException("government procurement is underfunded");
          changeStock(connection, source, procurement.commodity(), -quantity);
          changeStock(connection, destination, procurement.commodity(), quantity);
          transfer(connection, treasury, business, total);
          long fulfilled = procurement.fulfilled() + quantity;
          update(
              connection,
              "UPDATE government_procurements SET fulfilled_quantity=?,status=CASE WHEN ?=quantity THEN 'COMPLETED' ELSE 'PARTIAL' END,version=version+1 WHERE id=?",
              fulfilled,
              fulfilled,
              procurementId);
          history(
              connection,
              procurement.city(),
              "PROCUREMENT_FULFILLED",
              procurementId,
              "{\"quantity\":" + quantity + "}",
              now);
          return procurement(connection, procurementId);
        });
  }

  @Override
  public EmergencyPurchase emergencyBuy(
      UUID city, UUID actor, String commodity, long quantity, UUID key, Instant now) {
    return store.inTransaction(
        connection -> {
          EmergencyPurchase existing = emergencyByKey(connection, key);
          if (existing != null) return existing;
          requireGovernment(connection, city, actor);
          long unitPrice = Math.multiplyExact(referencePrice(connection, city, commodity), 2);
          long total = Math.multiplyExact(unitPrice, quantity);
          UUID treasury = account(connection, "CITY", city, true);
          UUID harbor = account(connection, "CITY", HARBOR_CITY, true);
          long treasuryBalance = balance(connection, treasury);
          long harborBalance = balance(connection, harbor);
          if (treasuryBalance < total)
            throw new DomainException("treasury cannot fund emergency purchase");
          UUID destination = warehouse(connection, city);
          lockStock(connection, destination, commodity);
          changeStock(connection, destination, commodity, quantity);
          transfer(connection, treasury, harbor, total);
          UUID purchase = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO emergency_purchases(id,city_id,commodity_key,quantity,unit_price_minor,total_minor,idempotency_key,purchased_at) VALUES(?,?,?,?,?,?,?,?)",
              purchase,
              city,
              commodity,
              quantity,
              unitPrice,
              total,
              key,
              now);
          ledger(
              connection,
              treasury,
              actor,
              -total,
              treasuryBalance - total,
              purchase,
              key,
              "EMERGENCY_PURCHASE",
              now);
          ledger(
              connection,
              harbor,
              actor,
              total,
              harborBalance + total,
              purchase,
              UUID.nameUUIDFromBytes((key + ":harbor").getBytes(StandardCharsets.UTF_8)),
              "EMERGENCY_PURCHASE",
              now);
          return new EmergencyPurchase(purchase, city, commodity, quantity, unitPrice, total);
        });
  }

  @Override
  public CycleReport cycle(int limit, Instant now) {
    return store.inTransaction(
        connection -> {
          int overdue =
              updateCount(
                  connection,
                  "UPDATE commercial_invoices SET status='OVERDUE',version=version+1 WHERE status='ISSUED' AND due_at<?",
                  now);
          int interest = 0;
          List<UUID> loans =
              ids(
                  connection,
                  "SELECT id FROM company_loans WHERE status='ACTIVE' AND next_interest_at<=? ORDER BY next_interest_at LIMIT ? FOR UPDATE SKIP LOCKED",
                  now,
                  limit);
          for (UUID id : loans) {
            Loan loan = loan(connection, id);
            long accrued =
                Math.max(
                    0,
                    (loan.outstandingMinor() * loan.annualInterestBasisPoints() + 3_649_999)
                        / 3_650_000);
            update(
                connection,
                "UPDATE company_loans SET accrued_interest_minor=accrued_interest_minor+?,next_interest_at=?,version=version+1 WHERE id=?",
                accrued,
                now.plusSeconds(86_400),
                id);
            interest++;
          }
          int taxes = 0;
          LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
          List<UUID> companies =
              ids(
                  connection,
                  "SELECT c.id FROM companies c JOIN business_tax_rules r ON r.city_id=c.city_id WHERE c.status='ACTIVE' ORDER BY c.id LIMIT ? FOR UPDATE OF c SKIP LOCKED",
                  limit);
          for (UUID company : companies) {
            UUID city = companyCity(connection, company);
            if (scalar(
                    connection,
                    "SELECT count(*) FROM business_tax_assessments WHERE company_id=? AND cycle_date=?",
                    company,
                    java.sql.Date.valueOf(date))
                > 0) continue;
            int rate =
                (int)
                    scalar(
                        connection,
                        "SELECT basis_points FROM business_tax_rules WHERE city_id=?",
                        city);
            UUID business = companyAccount(connection, company);
            UUID treasury = account(connection, "CITY", city, true);
            long companyBalance = balance(connection, business);
            long amount = companyBalance * rate / 10_000;
            String status = companyBalance >= amount ? "PAID" : "DUE";
            if (status.equals("PAID") && amount > 0) {
              transfer(connection, business, treasury, amount);
              taxes++;
            }
            update(
                connection,
                "INSERT INTO business_tax_assessments(id,city_id,company_id,amount_minor,status,assessed_at,paid_at,cycle_date) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                city,
                company,
                amount,
                status,
                now,
                status.equals("PAID") ? now : null,
                java.sql.Date.valueOf(date));
          }
          return new CycleReport(interest, taxes, overdue);
        });
  }

  @Override
  public List<HistoryEntry> history(UUID city, UUID actor, int limit) {
    return store.inTransaction(
        connection -> {
          requireCityMember(connection, city, actor);
          List<HistoryEntry> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT event_type,aggregate_id,details::text,occurred_at FROM commercial_history WHERE city_id=? UNION ALL SELECT 'TRADE',id,jsonb_build_object('commodity',commodity_key,'quantity',quantity,'unitPrice',unit_price_minor)::text,occurred_at FROM trade_history WHERE buyer_city=? OR seller_city=? UNION ALL SELECT 'PRICE',id,jsonb_build_object('commodity',commodity_key,'unitPrice',unit_price_minor,'quantity',quantity)::text,occurred_at FROM price_history WHERE settlement_id=? ORDER BY occurred_at DESC LIMIT ?")) {
            statement.setObject(1, city);
            statement.setObject(2, city);
            statement.setObject(3, city);
            statement.setObject(4, city);
            statement.setInt(5, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new HistoryEntry(
                        result.getString(1),
                        result.getObject(2, UUID.class),
                        result.getString(3),
                        result.getTimestamp(4).toInstant()));
            }
          }
          return List.copyOf(values);
        });
  }

  private static Company companyByKey(Connection c, UUID key) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT id FROM companies WHERE idempotency_key=?")) {
      s.setObject(1, key);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? company(c, r.getObject(1, UUID.class)) : null;
      }
    }
  }

  private static Company company(Connection c, UUID id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT c.id,c.city_id,c.name,c.founder_id,a.balance_minor,c.status FROM companies c JOIN accounts a ON a.id=c.account_id WHERE c.id=?")) {
      s.setObject(1, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("company not found");
        return new Company(
            id,
            r.getObject(2, UUID.class),
            r.getString(3),
            r.getObject(4, UUID.class),
            r.getLong(5),
            r.getString(6));
      }
    }
  }

  private static Invoice invoice(Connection c, UUID id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id,company_id,target_player,amount_minor,status,due_at,paid_at FROM commercial_invoices WHERE id=? FOR UPDATE")) {
      s.setObject(1, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("invoice not found");
        Timestamp paid = r.getTimestamp(7);
        return new Invoice(
            id,
            r.getObject(2, UUID.class),
            r.getObject(3, UUID.class),
            r.getLong(4),
            r.getString(5),
            r.getTimestamp(6).toInstant(),
            paid == null ? null : paid.toInstant());
      }
    }
  }

  private static Loan loanByKey(Connection c, UUID key) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT id FROM company_loans WHERE idempotency_key=?")) {
      s.setObject(1, key);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? loan(c, r.getObject(1, UUID.class)) : null;
      }
    }
  }

  private static Loan loan(Connection c, UUID id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id,company_id,principal_minor,outstanding_minor,annual_interest_bps,accrued_interest_minor,status FROM company_loans WHERE id=? FOR UPDATE")) {
      s.setObject(1, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("loan not found");
        return new Loan(
            id,
            r.getObject(2, UUID.class),
            r.getLong(3),
            r.getLong(4),
            r.getInt(5),
            r.getLong(6),
            r.getString(7));
      }
    }
  }

  private static Procurement procurement(Connection c, UUID id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id,city_id,commodity_key,quantity,fulfilled_quantity,maximum_unit_price_minor,status FROM government_procurements WHERE id=? FOR UPDATE")) {
      s.setObject(1, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("procurement not found");
        return new Procurement(
            id,
            r.getObject(2, UUID.class),
            r.getString(3),
            r.getLong(4),
            r.getLong(5),
            r.getLong(6),
            r.getString(7));
      }
    }
  }

  private static EmergencyPurchase emergencyByKey(Connection c, UUID key) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id,city_id,commodity_key,quantity,unit_price_minor,total_minor FROM emergency_purchases WHERE idempotency_key=?")) {
      s.setObject(1, key);
      try (ResultSet r = s.executeQuery()) {
        return r.next()
            ? new EmergencyPurchase(
                r.getObject(1, UUID.class),
                r.getObject(2, UUID.class),
                r.getString(3),
                r.getLong(4),
                r.getLong(5),
                r.getLong(6))
            : null;
      }
    }
  }

  private static UUID companyCity(Connection c, UUID company) throws SQLException {
    return uuid(c, "SELECT city_id FROM companies WHERE id=?", company);
  }

  private static UUID companyAccount(Connection c, UUID company) throws SQLException {
    return uuid(c, "SELECT account_id FROM companies WHERE id=?", company);
  }

  private static UUID warehouse(Connection c, UUID city) throws SQLException {
    return uuid(
        c, "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' FOR UPDATE", city);
  }

  private static UUID account(Connection c, String type, UUID owner, boolean lock)
      throws SQLException {
    if (type.equals("PLAYER"))
      update(
          c,
          "INSERT INTO accounts(id,owner_type,owner_id,balance_minor) VALUES(?,'PLAYER',?,0) ON CONFLICT(owner_type,owner_id) DO NOTHING",
          UUID.randomUUID(),
          owner);
    return uuid(
        c,
        "SELECT id FROM accounts WHERE owner_type=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""),
        type,
        owner);
  }

  private static UUID uuid(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, values);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("required economy record missing");
        return r.getObject(1, UUID.class);
      }
    }
  }

  private static long balance(Connection c, UUID account) throws SQLException {
    return scalar(c, "SELECT balance_minor FROM accounts WHERE id=? FOR UPDATE", account);
  }

  private static void transfer(Connection c, UUID from, UUID to, long amount) throws SQLException {
    update(
        c,
        "UPDATE accounts SET balance_minor=balance_minor-?,version=version+1 WHERE id=? AND balance_minor>=?",
        amount,
        from,
        amount);
    update(
        c,
        "UPDATE accounts SET balance_minor=balance_minor+?,version=version+1 WHERE id=?",
        amount,
        to);
  }

  private static void lockStock(Connection c, UUID warehouse, String commodity)
      throws SQLException {
    update(
        c,
        "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity) VALUES(?,?,0,0) ON CONFLICT DO NOTHING",
        warehouse,
        commodity);
    scalar(
        c,
        "SELECT available_quantity FROM warehouse_stock WHERE warehouse_id=? AND commodity_key=? FOR UPDATE",
        warehouse,
        commodity);
  }

  private static long stock(Connection c, UUID warehouse, String commodity) throws SQLException {
    return scalar(
        c,
        "SELECT available_quantity FROM warehouse_stock WHERE warehouse_id=? AND commodity_key=?",
        warehouse,
        commodity);
  }

  private static void changeStock(Connection c, UUID warehouse, String commodity, long delta)
      throws SQLException {
    if (updateCount(
            c,
            "UPDATE warehouse_stock SET available_quantity=available_quantity+?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity+?>=0",
            delta,
            warehouse,
            commodity,
            delta)
        != 1) throw new DomainException("warehouse stock invariant violated");
  }

  private static long referencePrice(Connection c, UUID city, String commodity)
      throws SQLException {
    long price =
        scalar(
            c,
            "SELECT coalesce((SELECT percentile_cont(0.5) WITHIN GROUP(ORDER BY unit_price_minor)::bigint FROM price_history WHERE settlement_id=? AND commodity_key=? AND occurred_at>=now()-interval '30 days'),0)",
            city,
            commodity);
    if (price > 0) return price;
    return switch (commodity) {
      case "minecraft:wheat" -> 10;
      case "minecraft:bread" -> 25;
      case "minecraft:oak_log" -> 30;
      case "minecraft:stone" -> 20;
      case "minecraft:iron_ingot" -> 100;
      default -> 100;
    };
  }

  private static void requireCompanyRole(Connection c, UUID company, UUID actor)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT role FROM company_members WHERE company_id=? AND player_id=?")) {
      s.setObject(1, company);
      s.setObject(2, actor);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next() || !COMPANY_ROLES.contains(r.getString(1)))
          throw new DomainException("company role cannot perform action");
      }
    }
  }

  private static void requireGovernment(Connection c, UUID city, UUID actor) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT role FROM city_members WHERE city_id=? AND player_id=?")) {
      s.setObject(1, city);
      s.setObject(2, actor);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next() || !GOVERNMENT_ROLES.contains(r.getString(1)))
          throw new DomainException("settlement role cannot perform economy action");
      }
    }
  }

  private static void requireCityMember(Connection c, UUID city, UUID actor) throws SQLException {
    if (scalar(c, "SELECT count(*) FROM city_members WHERE city_id=? AND player_id=?", city, actor)
        != 1) throw new DomainException("not a settlement member");
  }

  private static void ledger(
      Connection c,
      UUID account,
      UUID actor,
      long amount,
      long after,
      UUID reference,
      UUID key,
      String type,
      Instant now)
      throws SQLException {
    update(
        c,
        "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at,description) VALUES(?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        account,
        actor,
        type,
        amount,
        after,
        reference,
        key,
        now,
        "complete economy transaction");
  }

  private static void history(
      Connection c, UUID city, String event, UUID aggregate, String details, Instant now)
      throws SQLException {
    update(
        c,
        "INSERT INTO commercial_history(id,city_id,event_type,aggregate_id,details,occurred_at) VALUES(?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        city,
        event,
        aggregate,
        details,
        now);
  }

  private static List<UUID> ids(Connection c, String sql, Object... values) throws SQLException {
    List<UUID> ids = new ArrayList<>();
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, values);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) ids.add(r.getObject(1, UUID.class));
      }
    }
    return ids;
  }

  private static long scalar(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, values);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("required value missing");
        return r.getLong(1);
      }
    }
  }

  private static void update(Connection c, String sql, Object... values) throws SQLException {
    updateCount(c, sql, values);
  }

  private static int updateCount(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, values);
      return s.executeUpdate();
    }
  }

  private static void bind(PreparedStatement s, Object... values) throws SQLException {
    for (int i = 0; i < values.length; i++) {
      Object v = values[i];
      if (v instanceof Instant instant) s.setTimestamp(i + 1, Timestamp.from(instant));
      else s.setObject(i + 1, v);
    }
  }
}
