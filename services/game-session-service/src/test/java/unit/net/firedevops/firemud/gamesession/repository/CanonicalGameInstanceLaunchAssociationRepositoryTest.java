package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalGameInstanceLaunchAssociationRepositoryTest {
  private static final String NAMESPACE = "launch-owner";

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void captureRequiresAWritableOwnerTransactionBeforeReadingAnyRuntimeRow() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
    var tenant = freshTenant();

    assertThatThrownBy(() -> repository.capture(tenant, 92L, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void committedReadCannotBeComposedInsideAnotherOwnerTransaction() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.read("launch-request-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed owner read");

    verifyNoInteractions(dsl);
  }

  @Test
  void originalStartSessionOwnerReadAlsoRequiresOneCommittedOwnerSnapshot() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
    var selector =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            NAMESPACE,
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            "world",
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            "launch-request-1",
            "descriptor-1",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.readOriginalStartSessionAssociation(selector))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed owner read");

    verifyNoInteractions(dsl);
  }

  private static FreshGameSessionTenantAssociation freshTenant() {
    RuntimeTenantIdentityEvidence source =
        new RuntimeTenantIdentityEvidence(
            1,
            NAMESPACE,
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            1L,
            "source-game-tenant-key",
            "NEW_GAME_ROW");
    return new FreshGameSessionTenantAssociation(
        UUID.fromString("33333333-3333-4333-8333-333333333333"), 701L, source);
  }
}
