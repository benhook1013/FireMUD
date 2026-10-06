package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameTenantCreationReservationRepositoryTest {
  private static final String NAMESPACE = "reservation-test";
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String SOURCE_KEY = "fresh-reservation-source";
  private static final String NAME = "Fresh Realm";
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

  @Test
  void reserveRequiresAnOwnerTransactionBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    GameTenantCreationReservationRepository repository =
        new GameTenantCreationReservationRepository(dsl);

    assertThatThrownBy(() -> repository.reserve(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Game Design owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void reserveRejectsReadOnlyOwnerTransactionBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    GameTenantCreationReservationRepository repository =
        new GameTenantCreationReservationRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(() -> repository.reserve(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active writable Game Design owner transaction");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verifyNoInteractions(dsl);
  }

  @Test
  void reservedCreationRejectsReadOnlyOwnerTransactionBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    GameRepository gameRepository = mock(GameRepository.class);
    GameTenantCreationRepository repository = new GameTenantCreationRepository(dsl, gameRepository);
    FreshTenantCreationReservation reservation =
        new FreshTenantCreationReservation(
            1,
            NAMESPACE,
            REQUEST_ID,
            GameTenantCreationDigest.requestDigest(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, null),
            OPERATION_ID,
            TENANT_ID,
            SOURCE_KEY,
            NAME,
            null);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(
              () ->
                  repository.createReservedCandidateWithCreator(
                      reservation,
                      TENANT_ID,
                      UUID.fromString("55555555-5555-4555-8555-555555555555"),
                      "sha256:" + "a".repeat(64)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active writable Game Design owner transaction");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verifyNoInteractions(dsl, gameRepository);
  }

  @Test
  void reservationValueRejectsChangedDigestAndCallerSuppliedNilIdentity() {
    String requestDigest =
        GameTenantCreationDigest.requestDigest(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, null);

    assertThatThrownBy(
            () ->
                new FreshTenantCreationReservation(
                    1,
                    NAMESPACE,
                    REQUEST_ID,
                    "sha256:" + "a".repeat(64),
                    OPERATION_ID,
                    TENANT_ID,
                    SOURCE_KEY,
                    NAME,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match the exact reservation input");
    assertThatThrownBy(
            () ->
                new FreshTenantCreationReservation(
                    1,
                    NAMESPACE,
                    REQUEST_ID,
                    requestDigest,
                    new UUID(0L, 0L),
                    TENANT_ID,
                    SOURCE_KEY,
                    NAME,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operationId must not be nil");
  }

  @Test
  void committedReservationReadRequiresAnIndependentOwnerRead() {
    DSLContext dsl = mock(DSLContext.class);
    GameTenantCreationReservationRepository repository =
        new GameTenantCreationReservationRepository(dsl);
    String requestDigest =
        GameTenantCreationDigest.requestDigest(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, null);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> repository.read(NAMESPACE, REQUEST_ID, requestDigest))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("independent committed-outcome read");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verifyNoInteractions(dsl);
  }
}
