package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberAcknowledgement;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberRepairOperation;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit, unregistered composition of the binding-local SQL and Redis repair phases. It returns
 * only the SQL owner's stored acknowledgement and does not perform or imply final admission.
 */
public final class CanonicalGameplayAccountMemberRepairOwner {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final CanonicalGameplayBindingInventoryRepository inventory;
  private final CanonicalGameplayAccountMemberRepairRedisOwner redis;

  /** Builds concrete owners from the configured Game Session database and Redis clients. */
  public CanonicalGameplayAccountMemberRepairOwner(
      DSLContext dsl, RedisClient redisClient, RedisScriptCatalog catalog) {
    this.inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    this.redis = new CanonicalGameplayAccountMemberRepairRedisOwner(redisClient, catalog);
  }

  /**
   * Reuses the original SQL-allocated tuple on every retry: begin in SQL, project/read in Redis,
   * then let SQL independently reload and accept that actual readback.
   */
  public CanonicalGameplayAccountMemberAcknowledgement repair(UUID transitionId) {
    Objects.requireNonNull(transitionId, "transitionId required");
    if (transitionId.equals(NIL_UUID)) {
      throw new IllegalArgumentException("transitionId must not be nil");
    }

    requireNoAmbientTransaction();
    CanonicalGameplayAccountMemberRepairOperation original =
        inventory.beginAccountMemberRepair(transitionId);
    requireNoAmbientTransaction();

    CanonicalGameplayAccountMemberRepairRedisOwner.Readback actual = redis.projectAndRead(original);
    requireNoAmbientTransaction();
    if (!original.equals(actual.operation()) || !original.member().equals(actual.member())) {
      throw new IllegalStateException(
          "Redis readback does not match the original repair operation");
    }

    // The repository performs its own transaction and validates the complete stored tuple,
    // current obligation, account-control epoch, and exact actual readback before accepting.
    return inventory.acknowledgeAccountMemberRepair(original, actual);
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Binding-local account member repair phases cannot share an ambient transaction");
    }
  }
}
