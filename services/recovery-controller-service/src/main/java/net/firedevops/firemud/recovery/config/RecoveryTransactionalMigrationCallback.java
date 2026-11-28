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
  private final ThreadLocal<String> previousLockTimeout = new ThreadLocal<>();

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_EACH_MIGRATE
        || event == Event.AFTER_EACH_MIGRATE
        || event == Event.AFTER_EACH_MIGRATE_ERROR;
  }

  @Override
  public void handle(Event event, Context context) {
    if (event == Event.BEFORE_EACH_MIGRATE) {
      requireTransactionalExecution(context);
      setMigrationLockTimeout(context.getConnection());
    } else {
      restoreLockTimeout(context.getConnection(), event == Event.AFTER_EACH_MIGRATE_ERROR);
    }
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

  private void setMigrationLockTimeout(Connection connection) {
    if (previousLockTimeout.get() != null) {
      throw new FlywayException("Recovery Flyway migration lock timeout is already active");
    }
    String previous;
    try (var statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SHOW lock_timeout")) {
      if (!result.next()) {
        throw new SQLException("PostgreSQL did not return the current lock timeout");
      }
      previous = result.getString(1);
    } catch (SQLException error) {
      throw new FlywayException("Unable to read the Recovery Flyway lock timeout", error);
    }

    try {
      setLockTimeout(connection, MIGRATION_LOCK_TIMEOUT);
      previousLockTimeout.set(previous);
    } catch (SQLException error) {
      try {
        setLockTimeout(connection, previous);
      } catch (SQLException restoreError) {
        error.addSuppressed(restoreError);
        abort(connection, error);
      }
      throw new FlywayException("Unable to set the Recovery Flyway lock timeout", error);
    }
  }

  private void restoreLockTimeout(Connection connection, boolean tolerateMissingValue) {
    String previous = previousLockTimeout.get();
    if (previous == null) {
      if (tolerateMissingValue) {
        return;
      }
      throw new FlywayException("Recovery Flyway migration lock timeout was not captured");
    }
    try {
      setLockTimeout(connection, previous);
    } catch (SQLException error) {
      abort(connection, error);
      throw new FlywayException(
          "Unable to restore the Recovery Flyway lock timeout; the connection was aborted", error);
    } finally {
      previousLockTimeout.remove();
    }
  }

  private static void setLockTimeout(Connection connection, String value) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('lock_timeout', ?, false)")) {
      statement.setString(1, value);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("PostgreSQL did not apply the lock timeout");
        }
      }
    }
  }

  private static void abort(Connection connection, SQLException failure) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException abortError) {
      failure.addSuppressed(abortError);
    }
  }
}
