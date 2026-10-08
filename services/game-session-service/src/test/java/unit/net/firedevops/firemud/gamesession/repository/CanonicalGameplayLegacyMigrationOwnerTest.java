package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import org.junit.jupiter.api.Test;

class CanonicalGameplayLegacyMigrationOwnerTest {
  @Test
  void retainsOriginalAdapterFailureAndSuppressedBlockPersistenceFailure() {
    CanonicalGameplayLegacyMigrationRepository migration =
        mock(CanonicalGameplayLegacyMigrationRepository.class);
    CanonicalGameplayBindingInventoryRepository inventory =
        mock(CanonicalGameplayBindingInventoryRepository.class);
    CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner namespaceIndex =
        mock(CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner.class);
    UUID cohortId = UUID.randomUUID();
    UUID writerFence = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    CanonicalGameplayLegacyMigrationStorageIdentity identity =
        new CanonicalGameplayLegacyMigrationStorageIdentity(
            "cluster-uid",
            "namespace-uid",
            "producer-pod-uid",
            "container-id",
            "node-uid",
            "987654321",
            42L,
            "postgres-volume-uid",
            "redis-run-id",
            "redis-volume-uid");
    CanonicalGameplayLegacyMigrationOperation fencedOperation =
        new CanonicalGameplayLegacyMigrationOperation(
            operationId,
            cohortId,
            writerFence,
            identity,
            CanonicalGameplayLegacyMigrationOperation.State.FENCED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(migration.begin(cohortId, writerFence, identity)).thenReturn(fencedOperation);

    IllegalStateException originalAdapterFailure =
        new IllegalStateException("source snapshot adapter failed after cohort fencing");
    IllegalStateException blockPersistenceFailure =
        new IllegalStateException("BLOCKED state persistence failed");
    when(migration.block(operationId, "OWNER_ADAPTER_AMBIGUOUS"))
        .thenThrow(blockPersistenceFailure);

    CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort =
        new CanonicalGameplayLegacyMigrationOwner.FencedCohort() {
          @Override
          public UUID cohortId() {
            return cohortId;
          }

          @Override
          public UUID legacyWriterFence() {
            return writerFence;
          }

          @Override
          public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
            return identity;
          }

          @Override
          public void requireStillFenced(
              CanonicalGameplayLegacyMigrationStorageIdentity expectedIdentity) {
            assertThat(expectedIdentity).isEqualTo(identity);
          }
        };
    CanonicalGameplayLegacyMigrationOwner owner =
        new CanonicalGameplayLegacyMigrationOwner(
            migration,
            inventory,
            () -> cohort,
            ignored -> {
              throw originalAdapterFailure;
            },
            namespaceIndex);

    assertThatThrownBy(owner::migrate)
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("remains fenced and unresolved")
        .satisfies(
            failure -> {
              assertThat(failure.getCause()).isSameAs(originalAdapterFailure);
              assertThat(originalAdapterFailure.getSuppressed())
                  .containsExactly(blockPersistenceFailure);
            });

    verify(migration).begin(cohortId, writerFence, identity);
    verify(migration).block(operationId, "OWNER_ADAPTER_AMBIGUOUS");
    verifyNoMoreInteractions(migration);
    verifyNoInteractions(inventory, namespaceIndex);
  }
}
