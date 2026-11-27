package net.firedevops.firemud.accountservice.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.charactercreate.AccountCharacterCreateOwnerClients.ResolvedSource;
import net.firedevops.firemud.accountservice.charactercreate.AccountCharacterCreateRepository.Protection;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Controlled dependency proof; this does not establish bootstrap JWT or coupled owner authority.
 */
class AccountCharacterCreateServiceTest {
  private static final String NAMESPACE = "character-create-proof";
  private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
  private final AccountCharacterCreateRepository repository =
      mock(AccountCharacterCreateRepository.class);
  private final AccountCharacterCreateOwnerClients owners =
      mock(AccountCharacterCreateOwnerClients.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final AccountCharacterCreatePrincipal principal =
      mock(AccountCharacterCreatePrincipal.class);
  private final AccountCharacterCreateIntent intent =
      AccountCharacterCreateTestActions.intent(
          NAMESPACE, UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(300));
  private final Protection protection =
      new Protection(intent, UUID.randomUUID(), 17L, NOW, new byte[] {1}, new byte[] {2});

  @AfterEach
  void clearContext() {
    SessionContext.clear();
  }

  @Test
  void commitsOriginalIntentAndProtectionReadbackBeforeDelegating() {
    principal();
    when(repository.findOriginal(NAMESPACE, intent.accountId(), intent.createCharacterRequestId()))
        .thenReturn(Optional.empty());
    when(owners.resolvePublicProduction(NAMESPACE, principal, intent.connectScopeId()))
        .thenReturn(new ResolvedSource(intent.actionBytes(), intent.originalSourceReadback()));
    when(repository.retain(any())).thenReturn(intent);
    readyOriginal();

    service().submit(principal, intent.createCharacterRequestId(), intent.connectScopeId());

    var order = inOrder(principal, repository, owners, transactions);
    order.verify(principal).authenticateCurrentAccount();
    order
        .verify(repository)
        .findOriginal(NAMESPACE, intent.accountId(), intent.createCharacterRequestId());
    order.verify(transactions).commit(any());
    order.verify(owners).resolvePublicProduction(NAMESPACE, principal, intent.connectScopeId());
    order.verify(repository).retain(any());
    order.verify(transactions).commit(any());
    order.verify(repository).findOperation(NAMESPACE, intent.operationId());
    order.verify(transactions).commit(any());
    order.verify(owners).requireExactOriginalSource(intent);
    order.verify(principal).authenticateCurrentAccount();
    order.verify(repository).acquire(intent);
    order.verify(transactions).commit(any());
    order.verify(repository).findProtection(intent);
    order.verify(transactions).commit(any());
    order.verify(principal).authenticateCurrentAccount();
    order.verify(owners).delegateProtected(intent, protection);
  }

  @Test
  void revocationDuringOriginalSourceReadCannotAcquireProtection() {
    readyOriginal();
    when(repository.findOriginal(NAMESPACE, intent.accountId(), intent.createCharacterRequestId()))
        .thenReturn(Optional.of(intent));
    var denied = new IllegalStateException("credential revoked");
    when(principal.authenticateCurrentAccount()).thenReturn(intent.accountId()).thenThrow(denied);
    assertThatThrownBy(
            () ->
                service()
                    .submit(principal, intent.createCharacterRequestId(), intent.connectScopeId()))
        .isSameAs(denied);
    verify(owners).requireExactOriginalSource(intent);
    verify(repository, never()).acquire(any());
    verify(owners, never()).delegateProtected(any(), any());
  }

  @Test
  void revocationAfterProtectionCommitCannotDelegateOrEraseProtection() {
    readyOriginal();
    when(repository.findOriginal(NAMESPACE, intent.accountId(), intent.createCharacterRequestId()))
        .thenReturn(Optional.of(intent));
    var denied = new IllegalStateException("credential revoked");
    when(principal.authenticateCurrentAccount())
        .thenReturn(intent.accountId(), intent.accountId())
        .thenThrow(denied);
    assertThatThrownBy(
            () ->
                service()
                    .submit(principal, intent.createCharacterRequestId(), intent.connectScopeId()))
        .isSameAs(denied);
    verify(repository).acquire(intent);
    verify(repository).findProtection(intent);
    verify(owners, never()).delegateProtected(any(), any());
    verify(repository, never()).settle(any(), any());
  }

  @Test
  void invalidOpaqueScopesFailBeforeAnySourceOrStorageAccess() {
    principal();
    for (String scope : List.of("", " \t\n", "s".repeat(2049))) {
      assertThatThrownBy(
              () -> service().submit(principal, intent.createCharacterRequestId(), scope))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(repository, owners, transactions);
  }

  @Test
  void retriesUseOriginalSourceWithoutResolvingNewTarget() {
    principal();
    readyOriginal();
    when(repository.findOriginal(NAMESPACE, intent.accountId(), intent.createCharacterRequestId()))
        .thenReturn(Optional.of(intent));

    service().submit(principal, intent.createCharacterRequestId(), intent.connectScopeId());

    verify(owners, never()).resolvePublicProduction(any(), any(), any());
    verify(repository, never()).retain(any());
    verify(owners).requireExactOriginalSource(intent);
    verify(owners).delegateProtected(intent, protection);
  }

  @Test
  void changedConnectScopeCannotBorrowOriginalIntent() {
    principal();
    when(repository.findOriginal(NAMESPACE, intent.accountId(), intent.createCharacterRequestId()))
        .thenReturn(Optional.of(intent));

    assertThatThrownBy(
            () ->
                service()
                    .submit(principal, intent.createCharacterRequestId(), "changed-issued-scope"))
        .isInstanceOf(StatusRuntimeException.class);

    verifyNoInteractions(owners);
    verify(repository, never()).acquire(any());
  }

  @Test
  void originalSourceReadbackFailureCannotAcquireOrDelegate() {
    principal();
    readyOriginal();
    when(repository.findOriginal(NAMESPACE, intent.accountId(), intent.createCharacterRequestId()))
        .thenReturn(Optional.of(intent));
    doThrow(Status.UNAVAILABLE.asRuntimeException())
        .when(owners)
        .requireExactOriginalSource(intent);

    assertThatThrownBy(
            () ->
                service()
                    .submit(principal, intent.createCharacterRequestId(), intent.connectScopeId()))
        .isInstanceOf(StatusRuntimeException.class);

    verify(repository, never()).acquire(any());
    verify(owners, never()).delegateProtected(any(), any());
  }

  @Test
  void expiryCannotDelegateOrReleaseAnExistingProtection() {
    principal();
    readyOriginal();
    when(repository.findOriginal(NAMESPACE, intent.accountId(), intent.createCharacterRequestId()))
        .thenReturn(Optional.of(intent));
    var service =
        new AccountCharacterCreateService(
            repository,
            owners,
            transactions,
            NAMESPACE,
            Clock.fixed(NOW.plusSeconds(300), ZoneOffset.UTC));

    assertThatThrownBy(
            () ->
                service.submit(
                    principal, intent.createCharacterRequestId(), intent.connectScopeId()))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("expired");

    verify(repository, never()).acquire(any());
    verify(owners, never()).delegateProtected(any(), any());
  }

  @Test
  void missingCapabilityAndGenericSessionContextsCannotReachOwnerSources() {
    assertThatThrownBy(
            () ->
                service().submit(null, intent.createCharacterRequestId(), intent.connectScopeId()))
        .isInstanceOf(StatusRuntimeException.class);
    SessionContext.setContext(intent.accountId().toString(), List.of("player"), Map.of());
    assertThatThrownBy(
            () ->
                service().submit(null, intent.createCharacterRequestId(), intent.connectScopeId()))
        .isInstanceOf(StatusRuntimeException.class);
    SessionContext.setContext(
        intent.accountId().toString(),
        List.of("admin"),
        Map.of(),
        true,
        "game-session-service",
        "controlled-instance");
    assertThatThrownBy(
            () ->
                service().submit(null, intent.createCharacterRequestId(), intent.connectScopeId()))
        .isInstanceOf(StatusRuntimeException.class);
    verifyNoInteractions(repository, owners, transactions);
  }

  @Test
  void failedPrincipalRevalidationCannotReachStorageOrOwnerSources() {
    when(principal.authenticateCurrentAccount())
        .thenThrow(Status.UNAUTHENTICATED.asRuntimeException());

    assertThatThrownBy(
            () ->
                service()
                    .submit(principal, intent.createCharacterRequestId(), intent.connectScopeId()))
        .isInstanceOf(StatusRuntimeException.class);

    verifyNoInteractions(repository, owners, transactions);
  }

  @Test
  void originalAndProtectionReadsRequireExactCreationOwnerPeerAndOriginalBinding() {
    readyOriginal();
    assertThatThrownBy(
            () -> service().readOriginalForOwner(intent.operationId(), intent.mutationDigest()))
        .isInstanceOf(StatusRuntimeException.class);
    peer(
        NAMESPACE,
        "social-groups-service",
        () -> {
          assertThatThrownBy(
                  () ->
                      service().readOriginalForOwner(intent.operationId(), intent.mutationDigest()))
              .isInstanceOf(StatusRuntimeException.class);
          assertThatThrownBy(
                  () ->
                      service()
                          .readProtectionForOwner(
                              intent.operationId(),
                              intent.mutationDigest(),
                              protection.protectionId(),
                              protection.protectionFence()))
              .isInstanceOf(StatusRuntimeException.class);
        });
    for (String workload :
        List.of("entity-management-service", "game-session-service", "world-management-service")) {
      peer(
          "other-namespace",
          workload,
          () -> {
            assertThatThrownBy(
                    () ->
                        service()
                            .readOriginalForOwner(intent.operationId(), intent.mutationDigest()))
                .isInstanceOf(StatusRuntimeException.class);
            assertThatThrownBy(
                    () ->
                        service()
                            .readProtectionForOwner(
                                intent.operationId(),
                                intent.mutationDigest(),
                                protection.protectionId(),
                                protection.protectionFence()))
                .isInstanceOf(StatusRuntimeException.class);
          });
      peer(
          NAMESPACE,
          workload,
          () -> {
            assertThat(
                    service()
                        .readOriginalForOwner(intent.operationId(), intent.mutationDigest())
                        .sameBinding(intent))
                .isTrue();
            assertThat(
                    service()
                        .readProtectionForOwner(
                            intent.operationId(),
                            intent.mutationDigest(),
                            protection.protectionId(),
                            protection.protectionFence())
                        .sameBinding(protection))
                .isTrue();
            assertThatThrownBy(
                    () -> service().readOriginalForOwner(intent.operationId(), "0".repeat(64)))
                .isInstanceOf(StatusRuntimeException.class);
            assertThatThrownBy(
                    () ->
                        service()
                            .readProtectionForOwner(
                                intent.operationId(),
                                intent.mutationDigest(),
                                UUID.randomUUID(),
                                protection.protectionFence()))
                .isInstanceOf(StatusRuntimeException.class);
            assertThatThrownBy(
                    () ->
                        service()
                            .readProtectionForOwner(
                                intent.operationId(),
                                intent.mutationDigest(),
                                protection.protectionId(),
                                protection.protectionFence() + 1L))
                .isInstanceOf(StatusRuntimeException.class);
            principal();
            assertThatThrownBy(
                    () ->
                        service()
                            .readOriginalForOwner(intent.operationId(), intent.mutationDigest()))
                .isInstanceOf(StatusRuntimeException.class);
            SessionContext.setContext(
                null, List.of(), Map.of(), true, workload, "controlled-instance");
            assertThatThrownBy(
                    () ->
                        service()
                            .readProtectionForOwner(
                                intent.operationId(),
                                intent.mutationDigest(),
                                protection.protectionId(),
                                protection.protectionFence()))
                .isInstanceOf(StatusRuntimeException.class);
            SessionContext.clear();
          });
    }
  }

  @Test
  void missingGenuineOwnerClientsAreUnavailableAndNeverAssertAbort() {
    var unavailable = AccountCharacterCreateOwnerClients.unavailable();
    assertThatThrownBy(
            () ->
                unavailable.resolvePublicProduction(NAMESPACE, principal, intent.connectScopeId()))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("UNAVAILABLE");
    assertThatThrownBy(() -> unavailable.requireExactOriginalSource(intent))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("UNAVAILABLE");
    assertThatThrownBy(() -> unavailable.delegateProtected(intent, protection))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("UNAVAILABLE");
  }

  @Test
  void immutableIntentRejectsAccountDigestPrivatePolicyAndSourceRebinding() {
    assertThatThrownBy(
            () ->
                copy(
                    intent.accountId(),
                    "0".repeat(64),
                    intent.actionBytes(),
                    intent.originalSourceReadback()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                copy(
                    UUID.randomUUID(),
                    intent.mutationDigest(),
                    intent.actionBytes(),
                    intent.originalSourceReadback()))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] privateAction =
        new String(intent.actionBytes(), StandardCharsets.UTF_8)
            .replace("\"publicProduction\":true", "\"publicProduction\":false")
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                copy(
                    intent.accountId(),
                    intent.mutationDigest(),
                    privateAction,
                    intent.originalSourceReadback()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            intent.sameBinding(
                copy(
                    intent.accountId(),
                    intent.mutationDigest(),
                    intent.actionBytes(),
                    new byte[] {3})))
        .isFalse();
  }

  private AccountCharacterCreateIntent copy(
      UUID account, String digest, byte[] action, byte[] source) {
    return new AccountCharacterCreateIntent(
        intent.operationId(),
        intent.createCharacterRequestId(),
        intent.connectScopeId(),
        account,
        action,
        digest,
        source);
  }

  private void readyOriginal() {
    when(repository.findOperation(NAMESPACE, intent.operationId())).thenReturn(Optional.of(intent));
    when(repository.acquire(intent)).thenReturn(protection);
    when(repository.findProtection(intent)).thenReturn(Optional.of(protection));
  }

  private AccountCharacterCreateService service() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    return new AccountCharacterCreateService(
        repository, owners, transactions, NAMESPACE, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void principal() {
    when(principal.authenticateCurrentAccount()).thenReturn(intent.accountId());
    SessionContext.setContext(intent.accountId().toString(), List.of("player"), Map.of());
  }

  private static void peer(String namespace, String workload, Runnable action) {
    var identity =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
            .orElseThrow();
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, identity).run(action);
  }
}
