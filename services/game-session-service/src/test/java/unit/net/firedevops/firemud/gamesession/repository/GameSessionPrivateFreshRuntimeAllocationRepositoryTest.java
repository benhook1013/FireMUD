package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshRuntimeAllocationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshRuntimeAllocationRepository.Request;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionPrivateFreshRuntimeAllocationRepositoryTest {
  @Test
  void requestHasOnlyExpectedSourceIdentityAndRejectsNilOrInvalidBindings() {
    UUID request = UUID.randomUUID();
    UUID preparation = UUID.randomUUID();
    UUID lifecycle = UUID.randomUUID();
    UUID realm = UUID.randomUUID();
    UUID catalog = UUID.randomUUID();
    UUID selection = UUID.randomUUID();
    String digest = "sha256:" + "a".repeat(64);
    assertThat(
            new Request(request, preparation, lifecycle, realm, 1, catalog, digest, selection, 1)
                .requestId())
        .isEqualTo(request);
    assertThatThrownBy(
            () ->
                new Request(
                    new UUID(0, 0),
                    preparation,
                    lifecycle,
                    realm,
                    1,
                    catalog,
                    digest,
                    selection,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Request(
                    request, preparation, lifecycle, realm, 0, catalog, digest, selection, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Request(
                    request, preparation, lifecycle, realm, 1, catalog, digest, selection, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Request(
                    request, preparation, lifecycle, realm, 1, catalog, "unbound", selection, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allocationAndCurrentReadRefuseMissingOwnerTransactionBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    var owner = new GameSessionPrivateFreshRuntimeAllocationRepository(dsl);
    var request =
        new Request(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            "sha256:" + "a".repeat(64),
            UUID.randomUUID(),
            1);
    assertThatThrownBy(() -> owner.allocate(request))
        .hasMessageContaining("writable owner transaction");
    assertThatThrownBy(
            () ->
                owner.readForWorldPreparation(
                    request.preparationRequestId(), request.playtestLifecycleId()))
        .hasMessageContaining("writable owner read");
    verifyNoInteractions(dsl);
  }

  @Test
  void immutableHistoricalReadRefusesAmbientTransactionRatherThanClaimingCommittedReadback() {
    DSLContext dsl = mock(DSLContext.class);
    var owner = new GameSessionPrivateFreshRuntimeAllocationRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> owner.readByRequest(UUID.randomUUID()))
          .hasMessageContaining("committed readback");
      verifyNoInteractions(dsl);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
