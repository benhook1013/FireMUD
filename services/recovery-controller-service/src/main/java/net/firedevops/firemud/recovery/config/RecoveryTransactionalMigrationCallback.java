package net.firedevops.firemud.recovery.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.internal.info.MigrationInfoImpl;

/**
 * Bounds Recovery migration locks and requires the pinned Flyway executor to remain transactional.
 */
final class RecoveryTransactionalMigrationCallback extends BaseCallback {
  private static final String MIGRATION_LOCK_TIMEOUT = "5s";

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_EACH_MIGRATE;
  }

  @Override
  public void handle(Event event, Context context) {
    requireTransactionalExecution(context);
    setMigrationLockTimeout(context.getConnection());
  }

  private static void requireTransactionalExecution(Context context) {
    var configuration = context.getConfiguration();
    // A mixed group can make even a transactional migration run outside a transaction.
    if (!configuration.isExecuteInTransaction() || configuration.isMixed()) {
      throw new FlywayException(
          "Recovery requires transactional Flyway execution without mixed mode");
    }
    // Flyway's public MigrationInfo does not expose its resolved executor's transaction decision.
    if (!(context.getMigrationInfo() instanceof MigrationInfoImpl migration)
        || !migration.canExecuteInTransaction()) {
      throw new FlywayException("Recovery migration does not support transactional execution");
    }
  }

  private static void setMigrationLockTimeout(Connection connection) {
    try {
      if (connection.getAutoCommit()) {
        throw new FlywayException("Recovery migration connection is not in a transaction");
      }
      // PostgreSQL restores the session setting when Flyway commits or rolls back.
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT set_config('lock_timeout', ?, true)")) {
        statement.setString(1, MIGRATION_LOCK_TIMEOUT);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) {
            throw new SQLException("PostgreSQL did not apply the lock timeout");
          }
        }
      }
    } catch (SQLException error) {
      throw new FlywayException("Unable to set the Recovery Flyway lock timeout", error);
    }
  }
}
