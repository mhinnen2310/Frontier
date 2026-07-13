package nl.frontier.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import nl.frontier.api.RecoveryCoordinator;
import nl.frontier.api.TransactionalStore;

public final class PostgresRecoveryCoordinator implements RecoveryCoordinator {
  private final TransactionalStore store;

  public PostgresRecoveryCoordinator(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public RecoveryReport recover() {
    return store.inTransaction(
        connection -> {
          int outbox =
              count(
                  connection.prepareStatement(
                      "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"));
          int leases =
              count(
                  connection.prepareStatement(
                      "SELECT (SELECT count(*) FROM work_packages WHERE status IN ('ISSUED','ACTIVE') AND expires_at < now()) + (SELECT count(*) FROM worker_activity_tasks WHERE status IN ('TRAVELLING','WORKING') AND lease_expires_at < now())"));
          int consumptions =
              count(
                  connection.prepareStatement(
                      "SELECT count(*) FROM material_consumptions WHERE status = 'PREPARED'"));
          int transfers =
              count(
                  connection.prepareStatement(
                      "SELECT count(*) FROM construction_transfers WHERE status IN ('LOADING','IN_TRANSIT')"));
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE workers w SET current_activity_id=NULL,state=CASE WHEN w.state IN ('TRAVELLING','WORKING') THEN 'IDLE' ELSE w.state END,version=w.version+1 FROM worker_activity_tasks a WHERE a.id=w.current_activity_id AND a.status IN ('TRAVELLING','WORKING') AND a.lease_expires_at<now()")) {
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE worker_activity_tasks SET status='QUEUED',simulation_mode='PENDING',path_state='PENDING',lease_owner=NULL,lease_expires_at=NULL,available_at=now(),attempts=attempts+1,last_error='STARTUP_RECOVERY',updated_at=now(),version=version+1 WHERE status IN ('TRAVELLING','WORKING') AND lease_expires_at<now()")) {
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE workers w SET state='IDLE',task_id=NULL,lease_expires_at=NULL,version=w.version+1 FROM work_packages p WHERE p.worker_id=w.id AND p.status IN ('ISSUED','ACTIVE') AND p.expires_at<now()")) {
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_tasks t SET lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=t.version+1 FROM work_packages p WHERE p.repair_task_id=t.id AND p.status IN ('ISSUED','ACTIVE') AND p.expires_at<now()")) {
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE work_packages SET status='EXPIRED',version=version+1 WHERE status IN ('ISSUED','ACTIVE') AND expires_at<now()")) {
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE construction_transfers SET status=CASE WHEN status='LOADING' THEN 'PLANNED' ELSE 'WAITING_ROUTE' END,version=version+1 WHERE status IN ('LOADING','IN_TRANSIT')")) {
            statement.executeUpdate();
          }
          return new RecoveryReport(outbox, leases, consumptions, transfers);
        });
  }

  private static int count(PreparedStatement statement) throws SQLException {
    try (statement;
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    }
  }
}
