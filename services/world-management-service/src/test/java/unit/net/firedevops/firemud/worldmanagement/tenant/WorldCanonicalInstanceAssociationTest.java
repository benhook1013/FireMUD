package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.Connection;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInstanceAssociationTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final WorldCompleteLaunchBindingRepository launchBindings =
      mock(WorldCompleteLaunchBindingRepository.class);
  private final WorldAuthoredSourceIntakeRepository sourceIntakes =
      mock(WorldAuthoredSourceIntakeRepository.class);
  private final WorldAuthoredVersionIdentityRepository versionIdentities =
      mock(WorldAuthoredVersionIdentityRepository.class);
  private final WorldCanonicalInstanceAssociationRepository repository =
      new WorldCanonicalInstanceAssociationRepository(
          dsl, launchBindings, sourceIntakes, versionIdentities);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void ownerWriteRequiresAnActiveTransactionBeforeAnyRepositoryAccess() {
    assertThatThrownBy(() -> repository.retainClaimInOwnerTransaction(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active World owner transaction");

    verifyNoInteractions(dsl, launchBindings, sourceIntakes, versionIdentities);
  }

  @Test
  void ownerWriteRejectsReadOnlyAndNonReadCommittedTransactionsBeforeOwnerAccess() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_READ_COMMITTED);

    assertThatThrownBy(() -> repository.retainClaimInOwnerTransaction(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");

    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_SERIALIZABLE);

    assertThatThrownBy(() -> repository.retainClaimInOwnerTransaction(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ COMMITTED isolation");

    verifyNoInteractions(dsl, launchBindings, sourceIntakes, versionIdentities);
  }

  @Test
  void committedOwnerReadMustRunOutsideAnyTransactionAndRequiresNonNilIdentity() {
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.readOwnerAssociation(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("independent committed owner read");

    TransactionSynchronizationManager.clear();
    assertThatThrownBy(() -> repository.readOwnerAssociation(new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");

    verifyNoInteractions(dsl, launchBindings, sourceIntakes, versionIdentities);
  }

  @Test
  void canonicalIdentityRequiresExplicitPublicProductionClassification() {
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceAssociation.CanonicalIdentity(
                    UUID.randomUUID(),
                    "firemud",
                    UUID.randomUUID(),
                    "fixture-world",
                    UUID.randomUUID(),
                    "SHARED",
                    false,
                    "synthetic-control-request"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("explicit public-production evidence");
  }
}
