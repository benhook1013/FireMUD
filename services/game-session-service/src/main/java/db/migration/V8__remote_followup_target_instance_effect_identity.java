package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public final class V8__remote_followup_target_instance_effect_identity extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    try (Statement statement = context.getConnection().createStatement()) {
      statement.execute(
          """
          CREATE UNIQUE INDEX CONCURRENTLY idx_remote_followup_target_instance_region_epoch_effect
              ON remote_followup (
                  tenant_id,
                  target_game_instance_id,
                  target_region_id,
                  target_region_epoch,
                  effect_key
              )
          """);
      statement.execute(
          "DROP INDEX CONCURRENTLY IF EXISTS idx_remote_followup_target_region_epoch_effect");
    }
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }
}
