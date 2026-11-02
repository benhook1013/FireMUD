package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ProducerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ReplacementFence;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountSubscriptionIntentRepositoryTest {
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID INSTRUMENT = UUID.randomUUID();
  private static final OwnerFence OWNER = new OwnerFence(ACCOUNT, 1, UUID.randomUUID());
  private static final ProducerEvidence PRODUCER =
      new ProducerEvidence(ACCOUNT, UUID.randomUUID(), "sha256:" + "a".repeat(64));
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountSubscriptionIntentRepository repository =
      new AccountSubscriptionIntentRepository(dsl, new AccountHostingPlanCatalog(List.of()));

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void pendingWritesAndClaimReadsRefuseAbsentOrReadOnlyTransactionsBeforeSql() {
    assertTransactionRefusal();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertTransactionRefusal();
    verifyNoInteractions(dsl);
  }

  private void assertTransactionRefusal() {
    var request = request("basic-hosting", 1);
    assertThatThrownBy(() -> repository.retainPending(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
    assertThatThrownBy(() -> repository.lockPendingForClaim(request.requestId(), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
  }

  @Test
  void exactNormalizedSavedInstrumentInputsAndAdvanceableVersionsAreRequired() {
    assertThatThrownBy(() -> request(" basic-hosting", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("", 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("a".repeat(256), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("basic-hosting", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("basic-hosting", Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OwnerFence(ACCOUNT, 0, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReplacementFence(UUID.randomUUID(), Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProducerEvidence(ACCOUNT, UUID.randomUUID(), "unverified claim"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountSubscriptionCreateRequest(
                    new UUID(0L, 0L),
                    TENANT,
                    OWNER,
                    "basic-hosting",
                    INSTRUMENT,
                    1,
                    PRODUCER,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void invalidReadIdentitiesCannotBecomeSqlLookupsOrProviderMappings() {
    assertThatThrownBy(() -> repository.findCreation(new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.findCurrentByTenant(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.lookupProviderId("customer-or-latest-subscription"))
        .isInstanceOf(IllegalArgumentException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> repository.lockPendingForClaim(UUID.randomUUID(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl);
  }

  private static AccountSubscriptionCreateRequest request(String plan, long instrumentVersion) {
    return new AccountSubscriptionCreateRequest(
        UUID.randomUUID(), TENANT, OWNER, plan, INSTRUMENT, instrumentVersion, PRODUCER, null);
  }
}
