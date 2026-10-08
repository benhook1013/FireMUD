package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Non-bean Game Session owner flow. There is intentionally no default cohort, legacy-source, or
 * Redis-index adapter: without all three trusted implementations this package-private operation
 * cannot be composed or invoked. The current Redis-index adapter supports only a fully inventoried
 * empty cohort; it does not serialize or rebuild active gameplay session records.
 */
final class CanonicalGameplayLegacyMigrationOwner {
  private final CanonicalGameplayLegacyMigrationRepository migration;
  private final CanonicalGameplayBindingInventoryRepository inventory;
  private final ProtectedCohortProvider cohortProvider;
  private final CompleteLegacySource legacySource;
  private final NamespaceIndexOwner namespaceIndex;

  CanonicalGameplayLegacyMigrationOwner(
      CanonicalGameplayLegacyMigrationRepository migration,
      CanonicalGameplayBindingInventoryRepository inventory,
      ProtectedCohortProvider cohortProvider,
      CompleteLegacySource legacySource,
      NamespaceIndexOwner namespaceIndex) {
    this.migration = Objects.requireNonNull(migration, "migration");
    this.inventory = Objects.requireNonNull(inventory, "inventory");
    this.cohortProvider = Objects.requireNonNull(cohortProvider, "cohortProvider");
    this.legacySource = Objects.requireNonNull(legacySource, "legacySource");
    this.namespaceIndex = Objects.requireNonNull(namespaceIndex, "namespaceIndex");
  }

  /**
   * Replays one fenced cohort through durable snapshot, exact replacement, readback, and V31
   * publication. Admission remains fenced; this owner has no fence-release or activation method.
   */
  CanonicalGameplayLegacyMigrationOperation migrate() {
    requireNoAmbientTransaction();
    FencedCohort cohort =
        Objects.requireNonNull(
            cohortProvider.requireProtectedDisposableCohort(),
            "protected disposable cohort provider returned no cohort");
    CanonicalGameplayLegacyMigrationStorageIdentity identity =
        Objects.requireNonNull(cohort.storageIdentity(), "cohort storage identity");
    cohort.requireStillFenced(identity);
    CanonicalGameplayLegacyMigrationOperation operation =
        migration.begin(cohort.cohortId(), cohort.legacyWriterFence(), identity);
    try {
      cohort.requireStillFenced(identity);
      if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.VERIFIED
          || operation.state() == CanonicalGameplayLegacyMigrationOperation.State.BLOCKED) {
        return operation;
      }

      CanonicalGameplayLegacyMigrationSourceSnapshot source;
      CanonicalGameplayBindingInventorySnapshot canonical;
      if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.FENCED
          || operation.state() == CanonicalGameplayLegacyMigrationOperation.State.SNAPSHOTTED) {
        cohort.requireStillFenced(identity);
        source =
            Objects.requireNonNull(
                legacySource.captureEveryKnownFamily(cohort),
                "legacy source adapter returned no complete structural inventory");
        cohort.requireStillFenced(identity);
      } else {
        // The exact sanitized pre-mutation source inventory is durable. Re-scanning after a
        // successful replacement would compare against the intentionally removed old keys.
        source = migration.readSourceSnapshot(operation.operationId());
      }

      cohort.requireStillFenced(identity);
      canonical = inventory.readSnapshot();
      cohort.requireStillFenced(identity);
      operation = migration.captureSnapshots(operation.operationId(), source, canonical);
      cohort.requireStillFenced(identity);
      if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.BLOCKED) {
        return operation;
      }

      if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.SNAPSHOTTED) {
        cohort.requireStillFenced(identity);
        namespaceIndex.rebuildExact(cohort, source, canonical);
        cohort.requireStillFenced(identity);
        operation = migration.markRebuilt(operation.operationId());
      }

      if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.REBUILT
          || operation.state()
              == CanonicalGameplayLegacyMigrationOperation.State.READBACK_VERIFIED) {
        canonical = inventory.readSnapshot();
        cohort.requireStillFenced(identity);
        CanonicalGameplayLegacyMigrationReadback readback =
            Objects.requireNonNull(
                namespaceIndex.readBackExact(cohort, canonical),
                "namespace index owner returned no exact readback");
        cohort.requireStillFenced(identity);
        operation = migration.recordReadback(operation.operationId(), canonical, readback);
      }

      if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.BLOCKED) {
        return operation;
      }
      if (operation.state() != CanonicalGameplayLegacyMigrationOperation.State.READBACK_VERIFIED) {
        throw new IllegalStateException(
            "Migration owner did not reach exact readback verification");
      }
      cohort.requireStillFenced(identity);
      return migration.publishVerified(operation.operationId(), cohort);
    } catch (RuntimeException ambiguous) {
      if (operation.state() != CanonicalGameplayLegacyMigrationOperation.State.VERIFIED
          && operation.state() != CanonicalGameplayLegacyMigrationOperation.State.BLOCKED) {
        try {
          migration.block(operation.operationId(), "OWNER_ADAPTER_AMBIGUOUS");
        } catch (RuntimeException persistFailure) {
          ambiguous.addSuppressed(persistFailure);
        }
      }
      throw new CanonicalGameplayBindingInventoryConflictException(
          "Legacy migration remains fenced and unresolved; retain the same cohort and operation",
          ambiguous);
    }
  }

  /** Existing authenticated factory must provide the actual cluster/storage identity and fence. */
  interface ProtectedCohortProvider {
    FencedCohort requireProtectedDisposableCohort();
  }

  /**
   * Live retained-fence handle. Implementations must re-observe the exact Kubernetes/PG/Redis
   * identities and all-writer/admission exclusions on every call; no release operation is exposed.
   */
  interface FencedCohort {
    UUID cohortId();

    UUID legacyWriterFence();

    CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity();

    void requireStillFenced(CanonicalGameplayLegacyMigrationStorageIdentity expectedIdentity);
  }

  /** Reads every actual legacy key family, decodes only through the trusted GS serializer owner. */
  interface CompleteLegacySource {
    CanonicalGameplayLegacyMigrationSourceSnapshot captureEveryKnownFamily(FencedCohort cohort);
  }

  /** Exact namespace reconciliation and full Redis readback owner contract. */
  interface NamespaceIndexOwner {
    /**
     * The current owner admits only a completely enumerated empty legacy snapshot and a complete
     * durable snapshot with no bindings, transitions, reservations, or repair obligations. Its
     * fresh-cohort implementation proves the target session/index namespace is physically empty
     * with bounded repeated scans and performs no writes. Only the separately owned canonical
     * issuer-generation projection family is excluded from this namespace result and remains
     * subject to its independent owner gate. Any retained session/index key, unknown key, nonempty
     * legacy family, or nonempty canonical row remains blocked and untouched. This is not an
     * active-row rebuild implementation.
     */
    void rebuildExact(
        FencedCohort cohort,
        CanonicalGameplayLegacyMigrationSourceSnapshot legacySnapshot,
        CanonicalGameplayBindingInventorySnapshot canonicalSnapshot);

    /**
     * Independently re-inventories legacy families and all target Redis families under the same
     * cohort fence. The current empty-only implementation returns empty rows only after complete
     * repeated physical scans confirm no retained keys; counts or caller-provided digests are not
     * accepted.
     */
    CanonicalGameplayLegacyMigrationReadback readBackExact(
        FencedCohort cohort, CanonicalGameplayBindingInventorySnapshot canonicalSnapshot);
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new CanonicalGameplayBindingInventoryConflictException(
          "Legacy migration owner refuses to join an ambient Spring transaction");
    }
  }
}
