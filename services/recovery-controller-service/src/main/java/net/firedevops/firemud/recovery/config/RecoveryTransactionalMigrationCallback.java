package net.firedevops.firemud.recovery.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.internal.info.MigrationInfoImpl;

/** Requires the pinned Flyway executor to run each Recovery migration transactionally. */
final class RecoveryTransactionalMigrationCallback extends BaseCallback {
  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_EACH_MIGRATE;
  }

  @Override
  public void handle(Event event, Context context) {
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
}
