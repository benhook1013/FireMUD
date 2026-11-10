package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.RetainedSubscriptionEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.RetainedSubscriptionEvidenceRepository;
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Local containment only; canonical positive producer and admission remain separate obligations.
 */
class LegacySubscriptionContainmentTest {
  @Mock private AccountRepository accounts;
  @Mock private AccountJoinOperationRepository joins;
  @Mock private RetainedSubscriptionEvidenceRepository retained;
  @Mock private AccountAuthorityOutboxRepository outbox;
  @Mock private AccountPasswordResetOperationRepository resets;
  @Mock private AccountLogoutAllOperationRepository logouts;
  @Mock private AccountSecurityStateOperationRepository security;
  @Mock private PlatformTransactionManager transactions;
  @InjectMocks private AccountServiceImpl service;
  private AutoCloseable mocks;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
  }

  @AfterEach
  void tearDown() throws Exception {
    mocks.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"active", "trialing", "past_due", "grace", "canceled"})
  void retainedStatusAndCountersCannotProduceAPositiveServiceOrRuntimeRpc(String status) {
    when(retained.findHistoryByAccountId(2L)).thenReturn(List.of(evidence(status, null)));

    AuthenticationException denied =
        assertThrows(
            AuthenticationException.class,
            () -> service.getTenantEntitlementsForRuntime(7L, "retained-is-not-authority"));
    assertThat(denied.getCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");

    AccountGrpcService rpc = new AccountGrpcService(null, service, null, "test");
    AtomicReference<GetTenantEntitlementsForRuntimeResponse> response = new AtomicReference<>();
    var observer =
        new StreamObserver<GetTenantEntitlementsForRuntimeResponse>() {
          @Override
          public void onNext(GetTenantEntitlementsForRuntimeResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable failure) {
            throw new AssertionError(
                "Expected the existing response-level unavailable outcome", failure);
          }

          @Override
          public void onCompleted() {}
        };
    for (String workload : List.of("game-session-service", "world-management-service")) {
      response.set(null);
      Context context =
          Context.ROOT.withValue(
              GrpcPeerIdentity.CONTEXT_KEY,
              new GrpcPeerIdentity("spiffe://firemud/ns/test/sa/" + workload, "test", workload));
      context.run(
          () ->
              rpc.getTenantEntitlementsForRuntime(
                  GetTenantEntitlementsForRuntimeRequest.newBuilder()
                      .setTenantId("7")
                      .setRequestId("retained-is-not-authority")
                      .build(),
                  observer));
      assertThat(response.get()).isNotNull();
      assertThat(response.get().getError().getCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
      assertThat(response.get().getGameplayAvailable()).isFalse();
      assertThat(response.get().getAllowPublicJoin()).isFalse();
      assertThat(response.get().getEntitlementVersion()).isZero();
      assertThat(response.get().getTenantBillingSequence()).isZero();
      assertThat(response.get().getEvaluatedAt()).isEmpty();
    }
    verifyNoInteractions(
        retained, accounts, joins, outbox, resets, logouts, security, transactions);
  }

  @ParameterizedTest
  @CsvSource({
    "active,false,ACCOUNT_DELETE_ACTIVE_BILLING_OWNER",
    "canceled,false,ACCOUNT_DELETE_ACTIVE_BILLING_OWNER",
    "canceled,true,ACCOUNT_DELETE_UNAVAILABLE",
    "expired,true,ACCOUNT_DELETE_UNAVAILABLE"
  })
  void deletionStillReadsHistoricalBlockersAndNeverGainsErasureAuthority(
      String status, boolean ended, String expectedCode) {
    Account account = new Account();
    account.setId(2L);
    when(accounts.findById(2L)).thenReturn(Optional.of(account));
    when(retained.findHistoryByAccountId(2L))
        .thenReturn(List.of(evidence(status, ended ? LocalDateTime.of(2025, 1, 2, 3, 4) : null)));

    AccountLifecycleException denied =
        assertThrows(AccountLifecycleException.class, () -> service.deleteAccount(2L));

    assertThat(denied.getCode()).isEqualTo(expectedCode);
    verify(retained).findHistoryByAccountId(2L);
    verify(joins).hasRetainedOperation(2L);
    verifyNoInteractions(outbox, resets, logouts, security, transactions);
  }

  @Test
  void retainedJoinReceiptKeepsItsEarlierDeletionDenialBeforeHistoricalBillingReads() {
    Account account = new Account();
    account.setId(2L);
    when(accounts.findById(2L)).thenReturn(Optional.of(account));
    when(joins.hasRetainedOperation(2L)).thenReturn(true);

    AccountLifecycleException denied =
        assertThrows(AccountLifecycleException.class, () -> service.deleteAccount(2L));

    assertThat(denied.getCode()).isEqualTo("ACCOUNT_DELETE_JOIN_RECEIPT_RETAINED");
    verifyNoInteractions(retained, outbox, resets, logouts, security, transactions);
  }

  @Test
  void historicalOwnerHasNoGenericMutationOrCurrentTenantAuthorityApi() {
    assertThat(RetainedSubscriptionEvidence.class.isRecord()).isTrue();
    assertThat(
            Arrays.stream(RetainedSubscriptionEvidenceRepository.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName())
                .toList())
        .containsExactly("findHistoryByAccountId");
  }

  private static RetainedSubscriptionEvidence evidence(String status, LocalDateTime ended) {
    return new RetainedSubscriptionEvidence(
        91L, 2L, "unqualified", status, null, ended, 7L, 19L, UUID.randomUUID());
  }
}
