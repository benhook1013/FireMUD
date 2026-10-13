package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseWireCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owner/storage/transaction doubles only; fabricated carriers prove no current source authority.
 */
public class AccountGameplayAdmissionAbortOwnerTest {
  private final AccountGameplayAdmissionLeaseRepository repository =
      mock(AccountGameplayAdmissionLeaseRepository.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final TransactionStatus status = mock(TransactionStatus.class);
  private final AccountGameplayAdmissionAbortOwner owner =
      new AccountGameplayAdmissionAbortOwner(repository, manager, "test");

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void missingWrongAndCrossNamespacePeersNeverReachStorage() {
    assertThatThrownBy(() -> owner.abort(request()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            f ->
                assertThat(Status.fromThrowable(f).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    for (String uri :
        List.of(
            "spiffe://firemud/ns/test/sa/account-service",
            "spiffe://firemud/ns/other/sa/game-session-service")) {
      assertThatThrownBy(() -> peer(uri).call(() -> owner.abort(request())))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              f ->
                  assertThat(Status.fromThrowable(f).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    }
    verifyNoInteractions(repository, manager);
  }

  @Test
  void rejectsAmbientTransactionBeforeOwnedTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> peer().call(() -> owner.abort(request())))
        .isInstanceOf(StatusRuntimeException.class);
    verifyNoInteractions(repository, manager);
  }

  @Test
  void rejectsAmbientSynchronizationBeforeOwnedTransaction() {
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> peer().call(() -> owner.abort(request())))
        .isInstanceOf(StatusRuntimeException.class);
    verifyNoInteractions(repository, manager);
  }

  @Test
  void pendingAbortAndLostResponseRetryRetainOneCleanupAndOriginalExpiredEvidence()
      throws Exception {
    var original = fixture();
    var stored =
        new AtomicReference<>(
            new AccountGameplayAdmissionLeaseOperation(original, State.PENDING, null, null));
    when(manager.getTransaction(any())).thenReturn(status);
    when(repository.readExact(any())).thenAnswer(invocation -> Optional.of(stored.get()));
    when(repository.recordAborted(eq(original), eq(null), any()))
        .thenAnswer(
            invocation -> {
              var value =
                  new AccountGameplayAdmissionLeaseOperation(
                      original, State.ABORTED, null, invocation.getArgument(2));
              stored.set(value);
              return value;
            });
    var first = peer().call(() -> owner.abort(request()));
    var retry = peer().call(() -> owner.abort(request()));
    assertThat(retry).isSameAs(first);
    assertThat(first.evidence()).isEqualTo(original);
    assertThat(first.bindingDecisionId()).isNull();
    assertThat(first.orphanCleanupId().version()).isEqualTo(4);
    verify(repository).recordAborted(eq(original), eq(null), eq(first.orphanCleanupId()));
    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(manager, org.mockito.Mockito.times(2)).getTransaction(definition.capture());
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().isReadOnly()).isFalse();
    verify(manager, org.mockito.Mockito.times(2)).commit(status);
  }

  @Test
  void suppliedDecisionIsOnlyCorrelationAndChangedPresenceOrValueConflicts() throws Exception {
    UUID decision = UUID.randomUUID();
    var aborted =
        new AccountGameplayAdmissionLeaseOperation(
            fixture(), State.ABORTED, decision, UUID.randomUUID());
    when(manager.getTransaction(any())).thenReturn(status);
    when(repository.readExact(any())).thenReturn(Optional.of(aborted));
    var exact = request().toBuilder().setBindingDecisionId(decision.toString()).build();
    assertThat(peer().call(() -> owner.abort(exact))).isSameAs(aborted);
    for (var changed :
        List.of(
            request(),
            request().toBuilder().setBindingDecisionId(UUID.randomUUID().toString()).build())) {
      assertThatThrownBy(() -> peer().call(() -> owner.abort(changed)))
          .isInstanceOf(IllegalStateException.class);
    }
    verify(repository, never()).recordAborted(any(), any(), any());
    verify(manager, org.mockito.Mockito.times(2)).rollback(status);
  }

  @Test
  void committedAbsentAndChangedStoredEvidenceDenyAndRollBack() {
    when(manager.getTransaction(any())).thenReturn(status);
    when(repository.readExact(any()))
        .thenReturn(
            Optional.of(
                new AccountGameplayAdmissionLeaseOperation(
                    fixture(), State.COMMITTED, UUID.randomUUID(), null)))
        .thenReturn(Optional.empty())
        .thenThrow(new AccountGameplayAdmissionLeaseRepository.IdentityConflictException());
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatThrownBy(() -> peer().call(() -> owner.abort(request())))
          .isInstanceOf(IllegalStateException.class);
    }
    verify(repository, never()).recordAborted(any(), any(), any());
    verify(manager, org.mockito.Mockito.times(3)).rollback(status);
  }

  @Test
  void failedWriteRollsBackWithoutReturningAnAbort() {
    when(manager.getTransaction(any())).thenReturn(status);
    when(repository.readExact(any()))
        .thenReturn(
            Optional.of(
                new AccountGameplayAdmissionLeaseOperation(fixture(), State.PENDING, null, null)));
    when(repository.recordAborted(any(), any(), any()))
        .thenThrow(new IllegalStateException("write failed"));
    assertThatThrownBy(() -> peer().call(() -> owner.abort(request())))
        .isInstanceOf(IllegalStateException.class);
    verify(manager).rollback(status);
    verify(manager, never()).commit(status);
  }

  @Test
  void mismatchedCarrierNamespaceAndMalformedDecisionFailBeforeSql() {
    var carrier = new LinkedHashMap<>(fixture().carrier());
    carrier.put("targetNamespace", "other");
    carrier.put("callerWorkload", "spiffe://firemud/ns/other/sa/game-session-service");
    var wrongCaller =
        request().toBuilder()
            .setLease(
                AccountGameplayAdmissionLeaseWireCodec.encodeReference(
                    AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier)))
            .build();
    assertThatThrownBy(() -> peer().call(() -> owner.abort(wrongCaller)))
        .isInstanceOf(StatusRuntimeException.class);
    assertThatThrownBy(
            () ->
                peer()
                    .call(
                        () -> owner.abort(request().toBuilder().setBindingDecisionId("").build())))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository, manager);
  }

  public static AbortGameplayAdmissionLeaseRequest request() {
    return AbortGameplayAdmissionLeaseRequest.newBuilder()
        .setLease(AccountGameplayAdmissionLeaseWireCodec.encodeReference(fixture()))
        .build();
  }

  private static Context peer() {
    return peer("spiffe://firemud/ns/test/sa/game-session-service");
  }

  private static Context peer(String uri) {
    return Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow());
  }

  /** Deliberately fabricated expired storage carrier, not issuer or current authority evidence. */
  public static AccountGameplayAdmissionLeaseEvidence fixture() {
    String account = "11111111-1111-4111-8111-111111111111";
    String tenant = "22222222-2222-4222-8222-222222222222";
    String other = "33333333-3333-4333-8333-333333333333";
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", "test");
    value.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    value.put("requestId", other);
    value.put("leaseId", tenant);
    value.put("leaseFence", "1");
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String key :
        List.of(
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) scope.put(key, other);
    scope.put("accountId", account);
    scope.put("tenantId", tenant);
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateScope", "SHARED");
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      scope.put(key, "1");
    value.put("bindingScope", scope);
    value.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(tenant, "1"),
            "membershipAuthorityGeneration",
            Map.of(tenant, "1"),
            "privateRealmGrantVersions",
            List.of()));
    value.put("issuanceFence", "1");
    value.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(tenant, "1"),
            "membershipAuthorityGeneration",
            "1"));
    value.put(
        "outboxCheckpoints",
        List.of(
                "account/" + account,
                "issuer/firemud-account-service",
                "membership/" + account + "/" + tenant,
                "tenant/" + tenant)
            .stream()
            .map(
                suffix ->
                    Map.of(
                        "outboxStreamKey",
                        "account:auth-authority:v1:" + suffix,
                        "outboxSequence",
                        suffix.startsWith("account/") || suffix.startsWith("issuer/") ? "0" : "1"))
            .toList());
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("accountId", account);
    for (String key : List.of("operationId", "issuanceRequestId", "tokenJti"))
      token.put(key, other);
    token.put("tokenSHA256", "a".repeat(64));
    token.put("tokenProfile", "game-session-account-delegation");
    for (String key : List.of("tokenGeneration", "issuanceFence", "tokenIdentityFence"))
      token.put(key, "1");
    token.put("issuedAt", "999");
    token.put("notBefore", "999");
    token.put("expiresAt", "1300");
    value.put("tokenIdentityEvidence", token);
    value.put("evaluatedAt", "1000000");
    value.put("expiresAt", "1015000");
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(value);
  }
}
