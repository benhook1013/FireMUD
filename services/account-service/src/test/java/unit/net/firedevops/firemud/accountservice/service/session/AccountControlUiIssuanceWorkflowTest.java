package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseRefusal;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.SourceContext;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Exercises the real workflow orchestration with explicitly synthetic owner, storage, signer,
 * registry, transaction, and peer doubles. These definitions prove call ordering and denial only;
 * they do not prove real Account authority, SQL durability, signer custody, mTLS, or Redis state.
 */
class AccountControlUiIssuanceWorkflowTest {
  private static final Instant NOW = Instant.parse("2026-10-08T11:00:00Z");
  private static final String ISSUER = "spiffe://firemud/ns/firemud/sa/logging-admin-service";
  private static final String OTHER = "spiffe://firemud/ns/firemud/sa/game-design-service";

  @AfterEach
  void clearContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void primaryDenialCommitsAccountingBeforeThrowingAndNeverStartsIssuancePreparation() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    var source = fixture.tenantSource();
    when(fixture.authority.captureInitial(fixture.tenant, fixture.environment)).thenReturn(source);
    var denial = new AuthenticationException("AUTH_INVALID_CREDENTIALS", "Invalid credentials");
    when(fixture.primary.authenticateControlUiPrimaryIdentity(
            "creator@example.test", "test-only-secret"))
        .thenThrow(denial);
    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(
              () ->
                  fixture
                      .service()
                      .issue(
                          fixture.request(fixture.tenant, fixture.context, "test-only-secret"),
                          fixture.environment))
          .isInstanceOf(AuthenticationException.class)
          .hasMessage(denial.getMessage())
          .satisfies(
              error ->
                  assertThat(((AuthenticationException) error).getCode())
                      .isEqualTo(denial.getCode()));
    }
    var order = inOrder(fixture.primary, fixture.transactions);
    order
        .verify(fixture.primary)
        .authenticateControlUiPrimaryIdentity("creator@example.test", "test-only-secret");
    order.verify(fixture.transactions).commit(any());
    verify(fixture.transactions, never()).rollback(any());
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    verifyNoInteractions(fixture.signers, fixture.cryptography, fixture.registry, fixture.actors);
    verify(fixture.operations, never())
        .prepare(
            any(),
            anyString(),
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyLong());
  }

  @Test
  void missingOrWrongProtectedPeerDeniesBeforeOwnerLookup() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    var request = fixture.request(fixture.tenant, fixture.context, "test-only-secret");

    assertThatThrownBy(() -> fixture.service().issue(request, fixture.environment))
        .isInstanceOf(IllegalStateException.class);
    try (var ignored = fixture.withPeer(OTHER)) {
      assertThatThrownBy(() -> fixture.service().issue(request, fixture.environment))
          .isInstanceOf(IllegalStateException.class);
    }

    verifyNoInteractions(
        fixture.operations,
        fixture.authority,
        fixture.fences,
        fixture.primary,
        fixture.signers,
        fixture.registry,
        fixture.cryptography,
        fixture.actors);
  }

  @Test
  void changedDigestTenantOrCallerContextConflictsWithoutTerminalizingOriginal() {
    for (int changed = 0; changed < 5; changed++) {
      Fixture fixture = new Fixture(NOW.plusSeconds(30));
      fixture.existing(changed == 4 ? OTHER : ISSUER);
      var request =
          switch (changed) {
            case 0 -> fixture.request(fixture.tenant, fixture.context, "different-secret");
            case 1 -> fixture.request(UUID.randomUUID(), fixture.context, "test-only-secret");
            case 2 -> fixture.request(fixture.tenant, UUID.randomUUID(), "test-only-secret");
            default -> fixture.request(fixture.tenant, fixture.context, "test-only-secret");
          };
      if (changed == 0 || changed == 3) {
        when(fixture.cryptography.requestDigest(anyString(), any(byte[].class)))
            .thenReturn("changed-request-digest");
      }
      try (var ignored = fixture.withPeer(ISSUER)) {
        assertThatThrownBy(() -> fixture.service().issue(request, fixture.environment))
            .isInstanceOf(AccountControlUiIssuanceService.IdempotencyConflictException.class);
      }
      verify(fixture.operations, never()).beginRecoveryFailure(any());
      if (changed == 1 || changed == 2 || changed == 4) {
        verify(fixture.operations, never()).lockReplayRequest(any());
        verify(fixture.operations, never()).recordReplayFailure(any());
      }
      verifyNoInteractions(fixture.registry, fixture.primary, fixture.signers);
    }
  }

  @Test
  void replayCredentialMismatchCommitsFailureBeforeReportingConflictWithoutPrimaryAuthentication() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    var original = fixture.existing();
    AtomicReference<byte[]> replayMacInput = new AtomicReference<>();
    when(fixture.cryptography.requestDigest(anyString(), any(byte[].class)))
        .thenAnswer(
            call -> {
              replayMacInput.set(call.getArgument(1, byte[].class).clone());
              return "changed-request-digest";
            });

    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(
              () ->
                  fixture
                      .service()
                      .issue(
                          fixture.request(fixture.tenant, fixture.context, "wrong-secret"),
                          fixture.environment))
          .isInstanceOf(AccountControlUiIssuanceService.IdempotencyConflictException.class);
    }

    assertThat(AccountControlUiIssuanceRepository.object(replayMacInput.get()))
        .containsEntry("secret", "wrong-secret");
    var order = inOrder(fixture.operations, fixture.cryptography, fixture.transactions);
    order.verify(fixture.operations).lockReplayRequest(original.requestId);
    order.verify(fixture.operations).countReplayFailures(original);
    order.verify(fixture.cryptography).requestDigest(anyString(), any(byte[].class));
    order.verify(fixture.operations).recordReplayFailure(original);
    order.verify(fixture.transactions).commit(any());
    verify(fixture.transactions, never()).rollback(any());
    verifyNoInteractions(
        fixture.primary,
        fixture.authority,
        fixture.fences,
        fixture.signers,
        fixture.registry,
        fixture.actors);
    verify(fixture.operations, never()).beginRecoveryFailure(any());
  }

  @Test
  void exhaustedReplayBudgetDeniesBeforeCredentialMacOrAccountSourceReads() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    var original = fixture.accountExisting();
    when(fixture.operations.countReplayFailures(original)).thenReturn(5);

    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(() -> fixture.service().issueAccount(fixture.accountRequest()))
          .isInstanceOf(AccountControlUiIssuanceService.IdempotencyConflictException.class);
    }

    verify(fixture.operations).lockReplayRequest(original.requestId);
    verify(fixture.operations, never()).recordReplayFailure(any());
    verify(fixture.operations, never()).beginRecoveryFailure(any());
    verifyNoInteractions(
        fixture.cryptography,
        fixture.primary,
        fixture.authority,
        fixture.fences,
        fixture.signers,
        fixture.registry,
        fixture.actors);
  }

  @Test
  void unavailableReplayCustodyRollsBackWithoutCountingACredentialFailure() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    fixture.accountExisting();
    when(fixture.cryptography.requestDigest(anyString(), any(byte[].class)))
        .thenThrow(new IllegalStateException("custody unavailable"));
    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(() -> fixture.service().issueAccount(fixture.accountRequest()))
          .isInstanceOf(IllegalStateException.class);
    }
    verify(fixture.operations, never()).recordReplayFailure(any());
    verify(fixture.transactions).rollback(any());
    verify(fixture.transactions, never()).commit(any());
    verifyNoInteractions(fixture.primary, fixture.authority, fixture.registry);
  }

  @Test
  void currentSourceUnavailabilityDeniesWithoutMarkingOriginalTerminal() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    var original = fixture.existing();
    when(fixture.authority.capture(original.accountId, original.tenantId, fixture.environment))
        .thenThrow(new IllegalStateException("test-only source unavailable"));

    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(
              () ->
                  fixture
                      .service()
                      .issue(
                          fixture.request(fixture.tenant, fixture.context, "test-only-secret"),
                          fixture.environment))
          .isInstanceOf(IllegalStateException.class);
    }

    verify(fixture.operations, never()).beginRecoveryFailure(any());
    verifyNoInteractions(fixture.registry, fixture.primary);
  }

  @Test
  void changedOriginalSourceDeniesReplayWithoutRecapturingOrTerminalizing() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    var original = fixture.existing();
    var changed = fixture.tenantSource();
    when(changed.evidence()).thenReturn(new byte[] {99});
    when(fixture.authority.capture(original.accountId, original.tenantId, fixture.environment))
        .thenReturn(changed);

    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(
              () ->
                  fixture
                      .service()
                      .issue(
                          fixture.request(fixture.tenant, fixture.context, "test-only-secret"),
                          fixture.environment))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Control-ui original issuance/current recovery unavailable");
    }
    verify(fixture.operations, never()).beginRecoveryFailure(any());
    verify(fixture.authority, never()).captureInitial(any(), any());
    verifyNoInteractions(fixture.registry, fixture.primary, fixture.signers);
  }

  @Test
  void uncertainPreparedCommitNeverReachesSigningOrRegistry() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    fixture.allowControlledAttempt();
    var source = fixture.accountSource();
    var authenticated = mock(AccountServiceImpl.ControlUiPrimaryIdentity.class);
    when(authenticated.accountId()).thenReturn(fixture.account);
    when(fixture.primary.authenticateControlUiPrimaryIdentity(
            anyString(), anyString(), any(Runnable.class)))
        .thenAnswer(
            call -> {
              call.getArgument(2, Runnable.class).run();
              return authenticated;
            });
    when(fixture.authority.captureAccount(fixture.account)).thenReturn(source);
    when(fixture.signers.captureCurrent())
        .thenReturn(mock(AccountControlUiSignerOwner.Capture.class));
    when(fixture.cryptography.currentKeyId()).thenReturn("test-only-mac-key");
    when(fixture.cryptography.requestDigest(anyString(), any(byte[].class)))
        .thenReturn("original-request-digest");
    var prepared = fixture.storedAccount("PREPARED", fixture.recoveryExpiry, null);
    var preparedWritten = new java.util.concurrent.atomic.AtomicBoolean();
    when(fixture.operations.prepare(
            any(),
            anyString(),
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyLong()))
        .thenAnswer(
            call -> {
              preparedWritten.set(true);
              return prepared;
            });
    var uncertain = new IllegalStateException("test-only PREPARED commit acknowledgement lost");
    doAnswer(
            call -> {
              TransactionSynchronizationManager.clear();
              if (preparedWritten.get()) throw uncertain;
              return null;
            })
        .when(fixture.transactions)
        .commit(any());

    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(() -> fixture.service().issueAccount(fixture.accountRequest()))
          .isSameAs(uncertain);
    }
    assertThat(preparedWritten).isTrue();
    verify(fixture.signers, never()).sign(any(), any(), any());
    verify(fixture.operations, never()).bindCandidate(any(), anyString(), any(), any(), any());
    verify(fixture.operations, never()).beginRecoveryFailure(any());
    verifyNoInteractions(fixture.registry, fixture.actors);
  }

  @Test
  void committedRecoveryReturnsTheExactOriginalCredentialWithoutPrimaryAuthenticationOrSigning() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    byte[] exactCredential = "test-only-original-compact-jwt".getBytes(StandardCharsets.US_ASCII);
    String tokenHash = AccountControlUiIssuanceRepository.hash(exactCredential);
    var original = fixture.useOwnerRepository(tokenHash);
    fixture.recoveryInputs();
    var binding = fixture.envelopeBinding(original, tokenHash);
    var envelope = new AccountControlUiIssuanceRepository.Envelope(new byte[] {9}, binding);
    doAnswer(call -> envelope).when(fixture.operations).envelope(any());
    byte[] expectedCredential = exactCredential.clone();
    when(fixture.cryptography.open(any(byte[].class), any(byte[].class), any(Instant.class)))
        .thenReturn(exactCredential);
    var actor = mock(AccountControlUiActorService.AuthenticatedActor.class);
    when(actor.accountId()).thenReturn(fixture.account);
    when(actor.issuanceOperationId()).thenReturn(fixture.operationId);
    when(fixture.actors.authenticate(
            "test-only-original-compact-jwt", fixture.tenant, fixture.environment))
        .thenReturn(actor);

    try (var ignored = fixture.withPeer(ISSUER)) {
      try (var issued =
          fixture
              .service()
              .issue(
                  fixture.request(fixture.tenant, fixture.context, "test-only-secret"),
                  fixture.environment)) {
        org.assertj.core.api.Assertions.assertThat(issued.compactBytes())
            .containsExactly(expectedCredential);
        org.assertj.core.api.Assertions.assertThat(issued.expiresAt())
            .isEqualTo(NOW.plusSeconds(300));
      }
    }
    org.assertj.core.api.Assertions.assertThat(exactCredential).containsOnly((byte) 0);

    verify(fixture.registry).activate(any(AccountControlUiIssuanceRepository.Committed.class));
    verify(fixture.primary, never()).authenticateControlUiPrimaryIdentity(anyString(), anyString());
    verify(fixture.signers, never()).sign(any(), any(), any());
  }

  @Test
  void accountGlobalIssuanceUsesAccountSourcesAndReturnsItsSingleOriginalCredential()
      throws Exception {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    fixture.allowControlledAttempt();
    var source = fixture.accountSource();
    byte[] expectedCredential = "test-only-account-token".getBytes(StandardCharsets.US_ASCII);
    byte[] expectedReturnedCredential = expectedCredential.clone();
    String tokenHash = AccountControlUiIssuanceRepository.hash(expectedCredential);
    var spec =
        new AccountControlUiSigningSpec(
            fixture.operationId, fixture.requestId, fixture.jti, source, NOW, NOW.plusSeconds(300));
    var prepared =
        fixture.storedAccount(
            "PREPARED", fixture.recoveryExpiry, null, spec.claims(), source.evidence());
    var candidate =
        fixture.storedAccount(
            "CANDIDATE", fixture.recoveryExpiry, tokenHash, spec.claims(), source.evidence());
    var committedStored =
        fixture.storedAccount(
            "COMMITTED", fixture.recoveryExpiry, tokenHash, spec.claims(), source.evidence());
    var committed = committed(committedStored);
    var signer = mock(AccountControlUiSignerOwner.Capture.class);
    var signed = signedObservation(spec.claims());
    var actor = mock(AccountControlUiActorService.AccountAuthenticatedActor.class);
    when(actor.accountId()).thenReturn(fixture.account);
    when(actor.issuanceOperationId()).thenReturn(fixture.operationId);
    var authenticated = mock(AccountServiceImpl.ControlUiPrimaryIdentity.class);
    when(authenticated.accountId()).thenReturn(fixture.account);
    AtomicReference<byte[]> requestBytes = new AtomicReference<>();
    when(fixture.primary.authenticateControlUiPrimaryIdentity(
            eq("creator@example.test"), eq("test-only-secret"), any(Runnable.class)))
        .thenAnswer(
            call -> {
              call.getArgument(2, Runnable.class).run();
              return authenticated;
            });
    when(fixture.authority.captureAccount(fixture.account)).thenReturn(source);
    when(fixture.signers.captureCurrent()).thenReturn(signer);
    when(fixture.signers.requireOriginal(any(byte[].class))).thenReturn(signer);
    when(fixture.cryptography.currentKeyId()).thenReturn("test-only-mac-key");
    when(fixture.cryptography.requestDigest(anyString(), any(byte[].class)))
        .thenAnswer(
            call -> {
              requestBytes.set(call.getArgument(1, byte[].class).clone());
              return "account-request-digest";
            });
    when(fixture.cryptography.seal(any(byte[].class), any(byte[].class), any(Instant.class)))
        .thenReturn(new byte[] {8});
    when(fixture.cryptography.open(any(byte[].class), any(byte[].class), any(Instant.class)))
        .thenReturn(expectedCredential);
    when(fixture.actors.verifySigned("test-only-account-token")).thenReturn(signed);
    when(fixture.actors.authenticateAccount("test-only-account-token")).thenReturn(actor);
    when(fixture.operations.findRequest(fixture.requestId))
        .thenReturn(null, null, prepared, candidate, candidate, committedStored);
    when(fixture.operations.prepare(
            any(),
            anyString(),
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyLong()))
        .thenReturn(prepared);
    when(fixture.operations.commit(any(), any())).thenReturn(committed);
    when(fixture.operations.envelope(any()))
        .thenReturn(
            new AccountControlUiIssuanceRepository.Envelope(
                new byte[] {9}, fixture.envelopeBinding(committedStored, tokenHash)));
    when(fixture.registry.registerPending(anyString(), any(byte[].class), anyLong()))
        .thenReturn(mock(AccountControlUiCoordination.Receipt.class));
    org.mockito.Mockito.doAnswer(
            call -> {
              AccountControlUiSigningSpec actual = call.getArgument(1);
              assertThat(actual.claims()).isEqualTo(spec.claims());
              java.util.function.BiConsumer<String, byte[]> consumer = call.getArgument(2);
              consumer.accept(tokenHash, expectedCredential);
              return "test-only-sign-result";
            })
        .when(fixture.signers)
        .sign(any(), any(), any());

    ArgumentCaptor<byte[]> envelopeBindingCaptor = ArgumentCaptor.forClass(byte[].class);
    try (var ignored = fixture.withPeer(ISSUER)) {
      try (var issued = fixture.service().issueAccount(fixture.accountRequest())) {
        assertThat(issued.compactBytes()).containsExactly(expectedReturnedCredential);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(issued.recoveryExpiresAt()).isEqualTo(fixture.recoveryExpiry);
      }
    }
    assertThat(expectedCredential).containsOnly((byte) 0);

    verify(fixture.cryptography)
        .requestDigest(org.mockito.ArgumentMatchers.eq("test-only-mac-key"), any(byte[].class));
    verify(fixture.cryptography)
        .seal(
            any(byte[].class),
            envelopeBindingCaptor.capture(),
            org.mockito.ArgumentMatchers.eq(fixture.recoveryExpiry));
    Map<String, Object> requestFields =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readValue(requestBytes.get(), new tools.jackson.core.type.TypeReference<>() {});
    assertThat(requestFields)
        .containsEntry("authorizationScope", "ACCOUNT")
        .doesNotContainKey("canonicalTenantId");
    assertThat(requestFields)
        .doesNotContainKeys(
            "tenantId",
            "environmentId",
            "clusterId",
            "namespace",
            "canonicalClientAddress",
            "transportClass",
            "sourceContext");
    Map<String, Object> envelopeFields =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readValue(
                envelopeBindingCaptor.getValue(), new tools.jackson.core.type.TypeReference<>() {});
    assertThat(envelopeFields)
        .containsEntry("authorizationScope", "ACCOUNT")
        .doesNotContainKey("tenantId");
    var order = org.mockito.Mockito.inOrder(fixture.primary, fixture.authority);
    order
        .verify(fixture.primary)
        .authenticateControlUiPrimaryIdentity(
            eq("creator@example.test"), eq("test-only-secret"), any(Runnable.class));
    order.verify(fixture.authority, org.mockito.Mockito.times(5)).captureAccount(fixture.account);
    verify(fixture.authority, never()).captureInitial(any(), any());
    verify(fixture.authority, never()).capture(any(), any(), any());
    verify(fixture.fences, org.mockito.Mockito.atLeast(2))
        .lockProducerSourcesNowait(source.sources());
    verify(fixture.actors).authenticateAccount("test-only-account-token");
    verify(fixture.actors, never()).authenticate(anyString(), any(), any());
    verify(fixture.operations)
        .prepare(
            fixture.requestId,
            ISSUER,
            fixture.context,
            "test-only-mac-key",
            "account-request-digest",
            authenticated,
            source,
            signer,
            NOW,
            300_000L);
    verify(fixture.registry).activate(committed);
  }

  @Test
  void accountSignInRequiresSourceBeforeAnyPrimaryOrIssuanceWork() {
    Fixture fixture = new Fixture(NOW);
    assertThatThrownBy(
            () ->
                fixture
                    .service()
                    .issueAccount(
                        new AccountControlUiIssuanceService.AccountSignInRequest(
                            fixture.requestId,
                            "creator@example.test",
                            "test-only-secret",
                            fixture.context,
                            null)))
        .isInstanceOf(IllegalArgumentException.class);
    org.mockito.Mockito.verifyNoInteractions(fixture.primary, fixture.operations, fixture.signers);
  }

  @Test
  void accountGlobalReplayReturnsOriginalCredentialAndCrossScopeRetriesConflict() {
    Fixture replay = new Fixture(NOW.plusSeconds(30));
    byte[] exactCredential = "test-only-account-original".getBytes(StandardCharsets.US_ASCII);
    byte[] expectedCredential = exactCredential.clone();
    String tokenHash = AccountControlUiIssuanceRepository.hash(exactCredential);
    var original = replay.useAccountOwnerRepository(tokenHash);
    replay.accountRecoveryInputs();
    doAnswer(call -> 4).when(replay.operations).countReplayFailures(any());
    var binding = replay.envelopeBinding(original, tokenHash);
    var envelope = new AccountControlUiIssuanceRepository.Envelope(new byte[] {9}, binding);
    doAnswer(call -> envelope).when(replay.operations).envelope(any());
    when(replay.cryptography.open(any(byte[].class), any(byte[].class), any(Instant.class)))
        .thenReturn(exactCredential);
    var actor = mock(AccountControlUiActorService.AccountAuthenticatedActor.class);
    when(actor.accountId()).thenReturn(replay.account);
    when(actor.issuanceOperationId()).thenReturn(replay.operationId);
    when(replay.actors.authenticateAccount("test-only-account-original")).thenReturn(actor);

    var retry = replay.accountRequest();
    var changedSourceRetry =
        new AccountControlUiIssuanceService.AccountSignInRequest(
            retry.requestId(),
            retry.email(),
            retry.secret(),
            retry.callerContextId(),
            new SourceContext("198.51.100.20"));
    try (var ignored = replay.withPeer(ISSUER)) {
      try (var issued = replay.service().issueAccount(changedSourceRetry)) {
        assertThat(issued.compactBytes()).containsExactly(expectedCredential);
      }
    }
    verify(replay.authority, org.mockito.Mockito.atLeastOnce()).captureAccount(replay.account);
    verify(replay.actors).authenticateAccount("test-only-account-original");
    verify(replay.operations, never()).recordReplayFailure(any());
    verify(replay.primary, never())
        .authenticateControlUiPrimaryIdentity(anyString(), anyString(), any(Runnable.class));
    verify(replay.signers, never()).sign(any(), any(), any());
    assertThat(original.scopeKind()).isEqualTo(AccountControlUiAuthority.ScopeKind.ACCOUNT);
    assertThat(original.optionalTenantId()).isEmpty();

    for (boolean originalWasAccount : java.util.List.of(false, true)) {
      Fixture conflict = new Fixture(NOW.plusSeconds(30));
      if (originalWasAccount) {
        conflict.accountExisting();
      } else {
        conflict.existing();
      }
      try (var ignored = conflict.withPeer(ISSUER)) {
        if (originalWasAccount) {
          assertThatThrownBy(
                  () ->
                      conflict
                          .service()
                          .issue(
                              conflict.request(
                                  conflict.tenant, conflict.context, "test-only-secret"),
                              conflict.environment))
              .isInstanceOf(AccountControlUiIssuanceService.IdempotencyConflictException.class);
        } else {
          assertThatThrownBy(() -> conflict.service().issueAccount(conflict.accountRequest()))
              .isInstanceOf(AccountControlUiIssuanceService.IdempotencyConflictException.class);
        }
      }
      verify(conflict.operations, never()).beginRecoveryFailure(any());
      verifyNoInteractions(
          conflict.authority, conflict.primary, conflict.signers, conflict.registry);
    }
  }

  @Test
  void unavailableActiveRegistryReadbackDeniesWithoutTerminalizingCommittedOriginal() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    byte[] credential = "test-only-token".getBytes(StandardCharsets.US_ASCII);
    String tokenHash = AccountControlUiIssuanceRepository.hash(credential);
    var original = fixture.useOwnerRepository(tokenHash);
    fixture.recoveryInputs();
    var envelope =
        new AccountControlUiIssuanceRepository.Envelope(
            new byte[] {9}, fixture.envelopeBinding(original, tokenHash));
    doAnswer(call -> envelope).when(fixture.operations).envelope(any());
    when(fixture.cryptography.open(any(byte[].class), any(byte[].class), any(Instant.class)))
        .thenReturn(credential);
    when(fixture.actors.authenticate(anyString(), any(), any()))
        .thenThrow(new IllegalStateException("test-only active registry unavailable"));

    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(
              () ->
                  fixture
                      .service()
                      .issue(
                          fixture.request(fixture.tenant, fixture.context, "test-only-secret"),
                          fixture.environment))
          .isInstanceOf(IllegalStateException.class);
    }

    verify(fixture.operations, never()).beginRecoveryFailure(any());
    verify(fixture.registry).activate(any(AccountControlUiIssuanceRepository.Committed.class));
    org.assertj.core.api.Assertions.assertThat(original.status).isEqualTo("COMMITTED");
  }

  @Test
  void missingOriginalEnvelopeCreatesRevokingIntentAndDoesNotClaimRevocationOnRegistryFailure() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    var original = fixture.useOwnerRepository("unused-token-hash");
    fixture.recoveryInputs();
    doAnswer(call -> null).when(fixture.operations).envelope(any());
    var revoking = fixture.stored("REVOKING", original.recoveryExpiry, original.tokenHash);
    doAnswer(call -> revoking)
        .when(fixture.operations)
        .beginRecoveryFailure(argThat(actual -> sameOriginalRecord(original, actual)));
    when(fixture.registry.revoke(revoking))
        .thenThrow(new IllegalStateException("test-only Coordination unavailable"));

    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(
              () ->
                  fixture
                      .service()
                      .issue(
                          fixture.request(fixture.tenant, fixture.context, "test-only-secret"),
                          fixture.environment))
          .isInstanceOf(IllegalStateException.class);
    }

    ArgumentCaptor<AccountControlUiIssuanceRepository.Stored> originalCaptor =
        ArgumentCaptor.forClass(AccountControlUiIssuanceRepository.Stored.class);
    verify(fixture.operations).beginRecoveryFailure(originalCaptor.capture());
    assertThat(sameOriginalRecord(original, originalCaptor.getValue())).isTrue();
    verify(fixture.registry)
        .revoke(
            argThat(
                actual ->
                    "REVOKING".equals(actual.status)
                        && original.tokenHash.equals(actual.tokenHash)
                        && original.operationId.equals(actual.operationId)));
    verify(fixture.operations, never()).finishRecoveryFailure(any(), any());
  }

  @Test
  void expiredOriginalPersistsRevokingIntentBeforeUnavailableRegistryAndNeverReturnsCredential() {
    Fixture fixture = new Fixture(NOW.minusSeconds(1));
    var original = fixture.existing();
    var revoking = fixture.stored("REVOKING", original.recoveryExpiry, original.tokenHash);
    when(fixture.operations.beginRecoveryFailure(original)).thenReturn(revoking);
    when(fixture.registry.revoke(revoking))
        .thenThrow(new IllegalStateException("test-only Coordination unavailable"));

    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(
              () ->
                  fixture
                      .service()
                      .issue(
                          fixture.request(fixture.tenant, fixture.context, "test-only-secret"),
                          fixture.environment))
          .isInstanceOf(IllegalStateException.class);
    }

    verify(fixture.operations).beginRecoveryFailure(original);
    verify(fixture.registry).revoke(revoking);
    verify(fixture.operations, never()).finishRecoveryFailure(any(), any());
    verifyNoInteractions(fixture.primary, fixture.signers);
    verify(fixture.cryptography, never())
        .open(any(byte[].class), any(byte[].class), any(Instant.class));
    verify(fixture.cryptography, never())
        .seal(any(byte[].class), any(byte[].class), any(Instant.class));
  }

  @Test
  void expiredOriginalReturnsPermanentDenialOnlyAfterRevocationIsRetained() {
    Fixture fixture = new Fixture(NOW.minusSeconds(1));
    var original = fixture.existing();
    var revoking = fixture.stored("REVOKING", original.recoveryExpiry, original.tokenHash);
    var receipt = mock(AccountControlUiCoordination.Receipt.class);
    when(fixture.operations.beginRecoveryFailure(original)).thenReturn(revoking);
    when(fixture.registry.revoke(revoking)).thenReturn(receipt);

    try (var ignored = fixture.withPeer(ISSUER)) {
      assertThatThrownBy(
              () ->
                  fixture
                      .service()
                      .issue(
                          fixture.request(fixture.tenant, fixture.context, "test-only-secret"),
                          fixture.environment))
          .isInstanceOf(AccountControlUiIssuanceService.PermanentRecoveryDenialException.class);
    }

    var order = inOrder(fixture.operations, fixture.registry);
    order.verify(fixture.operations).beginRecoveryFailure(original);
    order.verify(fixture.registry).revoke(revoking);
    order.verify(fixture.operations).finishRecoveryFailure(revoking, receipt);
    verifyNoInteractions(fixture.primary, fixture.signers);
    verify(fixture.cryptography, never())
        .open(any(byte[].class), any(byte[].class), any(Instant.class));
  }

  private static AccountControlUiIssuanceRepository.Committed committed(
      AccountControlUiIssuanceRepository.Stored stored) throws Exception {
    var constructor =
        AccountControlUiIssuanceRepository.Committed.class.getDeclaredConstructor(
            AccountControlUiIssuanceRepository.Stored.class);
    constructor.setAccessible(true);
    return constructor.newInstance(stored);
  }

  private static AccountControlUiActorService.SignedObservation signedObservation(
      Map<String, Object> claims) throws Exception {
    var verified =
        new net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims(
            "test-control-ui", "control-ui", "control-ui", "test-only-kid", claims);
    var constructor =
        AccountControlUiActorService.SignedObservation.class.getDeclaredConstructor(
            net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims
                .class,
            net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity.class);
    constructor.setAccessible(true);
    return constructor.newInstance(verified, null);
  }

  private static boolean sameOriginalRecord(
      AccountControlUiIssuanceRepository.Stored expected,
      AccountControlUiIssuanceRepository.Stored actual) {
    return actual != null
        && expected.requestId.equals(actual.requestId)
        && expected.operationId.equals(actual.operationId)
        && expected.jti.equals(actual.jti)
        && expected.accountId.equals(actual.accountId)
        && expected.scopeKind() == actual.scopeKind()
        && expected.optionalTenantId().equals(actual.optionalTenantId())
        && expected.callerContextId.equals(actual.callerContextId)
        && expected.caller.equals(actual.caller)
        && expected.requestMacKeyId.equals(actual.requestMacKeyId)
        && expected.requestDigest.equals(actual.requestDigest)
        && expected.status.equals(actual.status)
        && expected.tokenHash.equals(actual.tokenHash)
        && java.util.Arrays.equals(expected.claims, actual.claims)
        && java.util.Arrays.equals(expected.sources, actual.sources)
        && java.util.Arrays.equals(expected.bundle, actual.bundle)
        && java.util.Arrays.equals(expected.signerReceipt, actual.signerReceipt)
        && java.util.Arrays.equals(expected.pendingRegistry, actual.pendingRegistry)
        && java.util.Arrays.equals(expected.activeRegistry, actual.activeRegistry)
        && expected.issuedAt.equals(actual.issuedAt)
        && expected.expiresAt.equals(actual.expiresAt)
        && expected.recoveryExpiry.equals(actual.recoveryExpiry);
  }

  @org.junit.jupiter.api.Test
  void accountOnlyCompositionRefusesTenantIssuanceBeforePrimaryAuthentication() {
    Fixture f = new Fixture(NOW.plusSeconds(600));
    var accountOnly =
        new net.firedevops.firemud.accountservice.authordraft.AccountControlUiAccountAuthority(
            mock(net.firedevops.firemud.accountservice.repository.AccountRepository.class),
            mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountAuthoritySourceEvidenceRepository.class),
            mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountPlatformRestrictionOperationRepository.class),
            mock(org.jooq.DSLContext.class));
    var issuer =
        new AccountControlUiIssuanceService(
            f.primary,
            null, // No implicit shared abuse policy or admitting fallback.
            f.operations,
            accountOnly,
            f.fences,
            f.signers,
            f.cryptography,
            f.registry,
            f.actors,
            f.transactions,
            Clock.fixed(NOW, ZoneOffset.UTC),
            300_000L,
            ISSUER);
    try (var ignored = f.withPeer(ISSUER)) {
      assertThatThrownBy(
              () ->
                  issuer.issue(
                      new AccountControlUiIssuanceService.Request(
                          f.requestId,
                          "owner@example.test",
                          "controlled-secret",
                          f.tenant,
                          f.context),
                      f.environment))
          .isInstanceOf(IllegalStateException.class);
    }
    org.mockito.Mockito.verifyNoInteractions(f.primary, f.operations, f.signers, f.registry);
  }

  @Test
  void newAccountAttemptWithoutPolicyRefusesBeforePrimaryAuthentication() {
    var f = new Fixture(NOW.plusSeconds(30));
    try (var ignored = f.withPeer(ISSUER)) {
      assertThatThrownBy(() -> f.service().issueAccount(f.accountRequest()))
          .isInstanceOf(AccountCredentialAbuseRefusal.class)
          .hasMessage("AUTH_ABUSE_CONTROL_UNAVAILABLE");
    }
    verifyNoInteractions(f.primary, f.signers, f.registry, f.authority);
  }

  @Test
  void remoteAdmissionAndFailureAccountingRunOutsideSqlAfterCredentialDenialCommits() {
    var f = new Fixture(NOW.plusSeconds(30));
    f.allowControlledAttempt();
    when(f.primary.authenticateControlUiPrimaryIdentity(
            anyString(), anyString(), any(Runnable.class)))
        .thenAnswer(
            call -> {
              call.getArgument(2, Runnable.class).run();
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              throw new AuthenticationException("AUTH_INVALID_CREDENTIALS", "Invalid credentials");
            });
    try (var ignored = f.withPeer(ISSUER)) {
      assertThatThrownBy(() -> f.service().issueAccount(f.accountRequest()))
          .isInstanceOf(AuthenticationException.class);
    }
    var ordered = inOrder(f.operations, f.transactions, f.abuse, f.primary);
    ordered.verify(f.operations).findRequest(f.requestId);
    ordered.verify(f.transactions).commit(any());
    ordered.verify(f.abuse).admit(any(), eq("creator@example.test"));
    ordered.verify(f.operations).lockReplayRequest(f.requestId);
    ordered.verify(f.operations).findRequest(f.requestId);
    ordered
        .verify(f.primary)
        .authenticateControlUiPrimaryIdentity(anyString(), anyString(), any(Runnable.class));
    ordered.verify(f.transactions).commit(any());
    ordered.verify(f.abuse).recordFailure(any());
    verify(f.transactions, never()).rollback(any());
    verifyNoInteractions(f.signers, f.registry);
  }

  @Test
  void failedSharedAccountingReturnsUnavailableRatherThanCredentialDenial() {
    var f = new Fixture(NOW.plusSeconds(30));
    f.allowControlledAttempt();
    when(f.primary.authenticateControlUiPrimaryIdentity(
            anyString(), anyString(), any(Runnable.class)))
        .thenAnswer(
            call -> {
              call.getArgument(2, Runnable.class).run();
              throw new AuthenticationException("AUTH_INVALID_CREDENTIALS", "Invalid credentials");
            });
    when(f.abuse.recordFailure(any()))
        .thenReturn(
            new AccountCredentialAbuseService.Decision(
                AccountCredentialAbuseService.Outcome.UNAVAILABLE, Duration.ofSeconds(2), null));
    try (var ignored = f.withPeer(ISSUER)) {
      assertThatThrownBy(() -> f.service().issueAccount(f.accountRequest()))
          .isInstanceOf(AccountCredentialAbuseRefusal.class)
          .hasMessage("AUTH_ABUSE_CONTROL_UNAVAILABLE");
    }
    verify(f.transactions, never()).rollback(any());
  }

  @Test
  void throttledCandidateNeverReachesAccountLookupOrPrimaryAuthentication() {
    var f = new Fixture(NOW.plusSeconds(30));
    f.allowControlledAttempt();
    when(f.abuse.admit(any(), anyString()))
        .thenReturn(
            new AccountCredentialAbuseService.Decision(
                AccountCredentialAbuseService.Outcome.RETRY_LATER, Duration.ofMillis(1501), null));
    try (var ignored = f.withPeer(ISSUER)) {
      assertThatThrownBy(() -> f.service().issueAccount(f.accountRequest()))
          .isInstanceOf(AccountCredentialAbuseRefusal.class)
          .satisfies(
              error ->
                  assertThat(((AccountCredentialAbuseRefusal) error).retryAfterMillis())
                      .isEqualTo(1501));
    }
    verifyNoInteractions(f.primary, f.authority, f.signers, f.registry);
    verify(f.abuse, never()).recordFailure(any());
  }

  @Test
  void concurrentReplayWinnerIsRecheckedBeforePrimaryAndDoesNotSpendNewFailureBudget() {
    var f = new Fixture(NOW.plusSeconds(30));
    f.allowControlledAttempt();
    when(f.abuse.admit(any(), anyString()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var original = f.accountExisting();
              when(f.operations.countReplayFailures(original)).thenReturn(5);
              return new AccountCredentialAbuseService.Decision(
                  AccountCredentialAbuseService.Outcome.ADMITTED,
                  null,
                  mock(AccountCredentialAbuseService.Permit.class));
            });
    try (var ignored = f.withPeer(ISSUER)) {
      assertThatThrownBy(() -> f.service().issueAccount(f.accountRequest()))
          .isInstanceOf(AccountControlUiIssuanceService.IdempotencyConflictException.class);
    }
    verifyNoInteractions(f.primary, f.signers, f.registry);
    verify(f.abuse, never()).recordFailure(any());
    verify(f.operations, never()).recordReplayFailure(any());
  }

  @Test
  void expiredAdmissionBeforePrimaryOwnerLocksNeverConsumesPrimaryCredential() {
    var f = new Fixture(NOW.plusSeconds(30));
    f.allowControlledAttempt();
    when(f.abuse.isUsable(any())).thenReturn(false);
    try (var ignored = f.withPeer(ISSUER)) {
      assertThatThrownBy(() -> f.service().issueAccount(f.accountRequest()))
          .isInstanceOf(AccountCredentialAbuseRefusal.class);
    }
    verifyNoInteractions(f.primary, f.authority, f.signers, f.registry);
    verify(f.abuse).isUsable(any());
    verify(f.abuse, never()).recordFailure(any());
  }

  @Test
  void admissionExpiredDuringPrimaryOwnerLocksRefusesBeforeCredentialVerification() {
    var f = new Fixture(NOW.plusSeconds(30));
    f.allowControlledAttempt();
    when(f.abuse.isUsable(any())).thenReturn(true, false);
    when(f.primary.authenticateControlUiPrimaryIdentity(
            anyString(), anyString(), any(Runnable.class)))
        .thenAnswer(
            call -> {
              call.getArgument(2, Runnable.class).run();
              return null;
            });
    try (var ignored = f.withPeer(ISSUER)) {
      assertThatThrownBy(() -> f.service().issueAccount(f.accountRequest()))
          .isInstanceOf(AccountCredentialAbuseRefusal.class);
    }
    verify(f.primary)
        .authenticateControlUiPrimaryIdentity(anyString(), anyString(), any(Runnable.class));
    verify(f.abuse, org.mockito.Mockito.times(2)).isUsable(any());
    verify(f.abuse, never()).recordFailure(any());
    verifyNoInteractions(f.authority, f.signers, f.registry);
  }

  private static final class Fixture {
    final UUID requestId = UUID.randomUUID();
    final UUID operationId = UUID.randomUUID();
    final UUID jti = UUID.randomUUID();
    final UUID account = UUID.randomUUID();
    final UUID tenant = UUID.randomUUID();
    final UUID context = UUID.randomUUID();
    final AccountServiceImpl primary = mock(AccountServiceImpl.class);
    AccountControlUiIssuanceRepository operations = mock(AccountControlUiIssuanceRepository.class);
    final AccountControlUiAuthority authority = mock(AccountControlUiAuthority.class);
    final DraftAuthorizationFenceRepository fences = mock(DraftAuthorizationFenceRepository.class);
    final AccountControlUiSignerOwner signers = mock(AccountControlUiSignerOwner.class);
    final AccountControlUiResponseCryptography cryptography =
        mock(AccountControlUiResponseCryptography.class);
    final AccountControlUiCoordination registry = mock(AccountControlUiCoordination.class);
    final AccountControlUiActorService actors = mock(AccountControlUiActorService.class);
    final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    AccountControlUiIssuanceService service;
    AccountCredentialAbuseService abuse;

    void allowControlledAttempt() {
      abuse = mock(AccountCredentialAbuseService.class);
      when(abuse.isUsable(any())).thenReturn(true);
      when(abuse.admit(any(), anyString()))
          .thenAnswer(
              call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return new AccountCredentialAbuseService.Decision(
                    AccountCredentialAbuseService.Outcome.ADMITTED,
                    null,
                    mock(AccountCredentialAbuseService.Permit.class));
              });
      when(abuse.recordFailure(any()))
          .thenAnswer(
              call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return new AccountCredentialAbuseService.Decision(
                    AccountCredentialAbuseService.Outcome.FAILURE_RECORDED, null, null);
              });
    }

    Record ownerRow;

    Fixture(Instant recoveryExpiry) {
      when(transactions.getTransaction(any(TransactionDefinition.class)))
          .thenAnswer(
              call -> {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                return new SimpleTransactionStatus();
              });
      doAnswer(
              call -> {
                TransactionSynchronizationManager.clear();
                return null;
              })
          .when(transactions)
          .commit(any());
      doAnswer(
              call -> {
                TransactionSynchronizationManager.clear();
                return null;
              })
          .when(transactions)
          .rollback(any());
      this.recoveryExpiry = recoveryExpiry;
    }

    AccountControlUiIssuanceService service() {
      if (service == null) {
        service =
            new AccountControlUiIssuanceService(
                primary,
                abuse,
                operations,
                authority,
                fences,
                signers,
                cryptography,
                registry,
                actors,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
                300_000L,
                ISSUER);
      }
      return service;
    }

    final Instant recoveryExpiry;

    AccountControlUiIssuanceRepository.Stored existing() {
      return existing(ISSUER);
    }

    AccountControlUiIssuanceRepository.Stored existing(String caller) {
      var stored = stored("COMMITTED", recoveryExpiry, "original-token-hash", caller);
      when(operations.findRequest(requestId)).thenReturn(stored);
      when(cryptography.requestDigest(anyString(), any(byte[].class)))
          .thenReturn("original-request-digest");
      return stored;
    }

    AccountControlUiIssuanceRepository.Stored accountExisting() {
      var stored = storedAccount("COMMITTED", recoveryExpiry, "original-account-token-hash");
      when(operations.findRequest(requestId)).thenReturn(stored);
      when(cryptography.requestDigest(anyString(), any(byte[].class)))
          .thenReturn("original-request-digest");
      return stored;
    }

    AccountControlUiIssuanceRepository.Stored useOwnerRepository(String tokenHash) {
      return useOwnerRepository(tokenHash, AccountControlUiAuthority.ScopeKind.TENANT, tenant);
    }

    AccountControlUiIssuanceRepository.Stored useAccountOwnerRepository(String tokenHash) {
      return useOwnerRepository(tokenHash, AccountControlUiAuthority.ScopeKind.ACCOUNT, null);
    }

    private AccountControlUiIssuanceRepository.Stored useOwnerRepository(
        String tokenHash, AccountControlUiAuthority.ScopeKind scopeKind, UUID tenantId) {
      var stored = stored("COMMITTED", recoveryExpiry, tokenHash, ISSUER, scopeKind, tenantId);
      var dsl = mock(DSLContext.class);
      var connection = mock(java.sql.Connection.class);
      try {
        when(connection.getTransactionIsolation())
            .thenReturn(java.sql.Connection.TRANSACTION_READ_COMMITTED);
      } catch (java.sql.SQLException failure) {
        throw new AssertionError(failure);
      }
      doAnswer(
              call -> {
                call.<org.jooq.ConnectionRunnable>getArgument(0).run(connection);
                return null;
              })
          .when(dsl)
          .connection(any(org.jooq.ConnectionRunnable.class));
      when(dsl.fetchOne(anyString(), any(Object[].class))).thenAnswer(call -> ownerRow);
      operations = Mockito.spy(new AccountControlUiIssuanceRepository(dsl));
      doAnswer(call -> 0).when(operations).countReplayFailures(any());
      doAnswer(call -> stored).when(operations).findRequest(requestId);
      when(cryptography.requestDigest(anyString(), any(byte[].class)))
          .thenReturn("original-request-digest");
      ownerRow = rowFor(stored, tokenHash);
      return stored;
    }

    void recoveryInputs() {
      var snapshot = tenantSource();
      var signerCapture = mock(AccountControlUiSignerOwner.Capture.class);
      when(authority.capture(account, tenant, environment)).thenReturn(snapshot);
      when(signers.requireOriginal(any(byte[].class))).thenReturn(signerCapture);
    }

    AccountControlUiAuthority.Snapshot tenantSource() {
      var snapshot = mock(AccountControlUiAuthority.Snapshot.class);
      when(snapshot.actor()).thenReturn(account);
      when(snapshot.scopeKind()).thenReturn(AccountControlUiAuthority.ScopeKind.TENANT);
      when(snapshot.optionalTenantId()).thenReturn(Optional.of(tenant));
      when(snapshot.authorityTuple())
          .thenReturn(
              Map.of(
                  "issuerAuthGeneration",
                  1L,
                  "accountAuthorityGeneration",
                  2L,
                  "tenantAuthorityGeneration",
                  Map.of(tenant.toString(), 3L),
                  "membershipAuthorityGeneration",
                  Map.of(tenant.toString(), 4L),
                  "privateRealmGrantVersions",
                  List.of()));
      when(snapshot.membershipVersion()).thenReturn(Map.of(tenant.toString(), 5L));
      when(snapshot.scopedRoles()).thenReturn(Map.of(tenant.toString(), List.of("tenantAdmin")));
      when(snapshot.globalRoles()).thenReturn(List.of());
      when(snapshot.issuanceFence()).thenReturn(6L);
      when(snapshot.evidence()).thenReturn(new byte[] {1});
      when(snapshot.sources()).thenReturn(java.util.List.of());
      when(snapshot.accountIdentitySource()).thenReturn(Map.of("accountId", account.toString()));
      when(snapshot.outboxCheckpoints()).thenReturn(List.of());
      return snapshot;
    }

    void accountRecoveryInputs() {
      AccountControlUiAuthority.Snapshot snapshot = accountSource();
      var signerCapture = mock(AccountControlUiSignerOwner.Capture.class);
      when(authority.captureAccount(account)).thenReturn(snapshot);
      when(signers.requireOriginal(any(byte[].class))).thenReturn(signerCapture);
    }

    AccountControlUiAuthority.Snapshot accountSource() {
      var snapshot = mock(AccountControlUiAuthority.Snapshot.class);
      when(snapshot.actor()).thenReturn(account);
      when(snapshot.scopeKind()).thenReturn(AccountControlUiAuthority.ScopeKind.ACCOUNT);
      when(snapshot.optionalTenantId()).thenReturn(Optional.empty());
      when(snapshot.authorityTuple())
          .thenReturn(
              Map.of(
                  "issuerAuthGeneration",
                  1L,
                  "accountAuthorityGeneration",
                  2L,
                  "tenantAuthorityGeneration",
                  Map.of(),
                  "membershipAuthorityGeneration",
                  Map.of(),
                  "privateRealmGrantVersions",
                  List.of()));
      when(snapshot.membershipVersion()).thenReturn(Map.of());
      when(snapshot.scopedRoles()).thenReturn(Map.of());
      when(snapshot.globalRoles()).thenReturn(List.of("platformAdmin"));
      when(snapshot.issuanceFence()).thenReturn(3L);
      when(snapshot.evidence()).thenReturn(new byte[] {1});
      when(snapshot.sources()).thenReturn(List.of());
      when(snapshot.accountIdentitySource()).thenReturn(Map.of("accountId", account.toString()));
      when(snapshot.outboxCheckpoints()).thenReturn(List.of());
      return snapshot;
    }

    AccountControlUiIssuanceRepository.Stored stored(
        String status, Instant expiry, String tokenHash) {
      return stored(status, expiry, tokenHash, ISSUER);
    }

    AccountControlUiIssuanceRepository.Stored stored(
        String status, Instant expiry, String tokenHash, String caller) {
      return stored(
          status, expiry, tokenHash, caller, AccountControlUiAuthority.ScopeKind.TENANT, tenant);
    }

    AccountControlUiIssuanceRepository.Stored storedAccount(
        String status, Instant expiry, String tokenHash) {
      return stored(
          status, expiry, tokenHash, ISSUER, AccountControlUiAuthority.ScopeKind.ACCOUNT, null);
    }

    AccountControlUiIssuanceRepository.Stored storedAccount(
        String status,
        Instant expiry,
        String tokenHash,
        Map<String, Object> claims,
        byte[] source) {
      var values = new java.util.HashMap<String, Object>();
      values.put("request_id", requestId);
      values.put("operation_id", operationId);
      values.put("token_jti", jti);
      values.put("account_uuid", account);
      values.put("tenant_uuid", null);
      values.put("authorization_scope", AccountControlUiAuthority.ScopeKind.ACCOUNT.name());
      values.put("caller_context_id", context);
      values.put("caller_workload", ISSUER);
      values.put("request_mac_key_id", "test-only-mac-key");
      values.put("request_digest", "account-request-digest");
      values.put("status", status);
      values.put("token_hash", tokenHash);
      values.put("claims_payload", AccountControlUiAuthority.canonical(claims));
      values.put("source_payload", source.clone());
      values.put("bundle_payload", new byte[] {2});
      values.put("signer_receipt", new byte[] {3});
      values.put("pending_registry", new byte[] {4});
      values.put("active_registry", new byte[] {5});
      values.put("issued_at_epoch_second", NOW.getEpochSecond());
      values.put("expires_at_epoch_second", NOW.plusSeconds(300).getEpochSecond());
      values.put("registry_cleanup_deadline_epoch_millis", NOW.plusSeconds(600).toEpochMilli());
      values.put("recovery_expires_at", OffsetDateTime.ofInstant(expiry, ZoneOffset.UTC));
      return new AccountControlUiIssuanceRepository.Stored(rowForValues(values));
    }

    private AccountControlUiIssuanceRepository.Stored stored(
        String status,
        Instant expiry,
        String tokenHash,
        String caller,
        AccountControlUiAuthority.ScopeKind scopeKind,
        UUID tenantId) {
      var values = new java.util.HashMap<String, Object>();
      values.put("request_id", requestId);
      values.put("operation_id", operationId);
      values.put("token_jti", jti);
      values.put("account_uuid", account);
      values.put("tenant_uuid", tenantId);
      values.put("authorization_scope", scopeKind.name());
      values.put("caller_context_id", context);
      values.put("caller_workload", caller);
      values.put("request_mac_key_id", "test-only-mac-key");
      values.put("request_digest", "original-request-digest");
      values.put("status", status);
      values.put("token_hash", tokenHash);
      values.put(
          "claims_payload",
          AccountControlUiAuthority.canonical(java.util.Map.of("synthetic", true)));
      values.put("source_payload", new byte[] {1});
      values.put("bundle_payload", new byte[] {2});
      values.put("signer_receipt", new byte[] {3});
      values.put("pending_registry", new byte[] {4});
      values.put("active_registry", new byte[] {5});
      values.put("issued_at_epoch_second", NOW.getEpochSecond());
      values.put("expires_at_epoch_second", NOW.plusSeconds(300).getEpochSecond());
      values.put("registry_cleanup_deadline_epoch_millis", NOW.plusSeconds(600).toEpochMilli());
      values.put("recovery_expires_at", OffsetDateTime.ofInstant(expiry, ZoneOffset.UTC));
      Record row = rowForValues(values);
      return new AccountControlUiIssuanceRepository.Stored(row);
    }

    Record rowFor(AccountControlUiIssuanceRepository.Stored stored, String tokenHash) {
      var values = new java.util.HashMap<String, Object>();
      values.put("request_id", stored.requestId);
      values.put("operation_id", stored.operationId);
      values.put("token_jti", stored.jti);
      values.put("account_uuid", stored.accountId);
      values.put("tenant_uuid", stored.tenantId);
      values.put("authorization_scope", stored.scopeKind().name());
      values.put("caller_context_id", stored.callerContextId);
      values.put("caller_workload", stored.caller);
      values.put("request_mac_key_id", stored.requestMacKeyId);
      values.put("request_digest", stored.requestDigest);
      values.put("status", stored.status);
      values.put("token_hash", tokenHash);
      values.put("claims_payload", stored.claims);
      values.put("source_payload", stored.sources);
      values.put("bundle_payload", stored.bundle);
      values.put("signer_receipt", stored.signerReceipt);
      values.put("pending_registry", stored.pendingRegistry);
      values.put("active_registry", stored.activeRegistry);
      values.put("issued_at_epoch_second", stored.issuedAt.getEpochSecond());
      values.put("expires_at_epoch_second", stored.expiresAt.getEpochSecond());
      values.put("registry_cleanup_deadline_epoch_millis", stored.expiryMillis());
      values.put(
          "recovery_expires_at", OffsetDateTime.ofInstant(stored.recoveryExpiry, ZoneOffset.UTC));
      return rowForValues(values);
    }

    Record rowForValues(java.util.Map<String, Object> values) {
      Record row = mock(Record.class);
      when(row.get(anyString(), any(Class.class)))
          .thenAnswer(call -> values.get(call.getArgument(0)));
      return row;
    }

    byte[] envelopeBinding(AccountControlUiIssuanceRepository.Stored original, String tokenHash) {
      Map<String, Object> binding = new java.util.LinkedHashMap<>();
      binding.put("purpose", "account-control-ui-original-credential/v1");
      binding.put("operationId", original.operationId.toString());
      binding.put("requestId", original.requestId.toString());
      binding.put("accountId", original.accountId.toString());
      if (original.scopeKind() == AccountControlUiAuthority.ScopeKind.TENANT) {
        binding.put("tenantId", original.tenantId().toString());
      } else {
        binding.put("authorizationScope", AccountControlUiAuthority.ScopeKind.ACCOUNT.name());
      }
      binding.put("callerWorkload", original.caller);
      binding.put("callerContextId", original.callerContextId.toString());
      binding.put("requestMacKeyId", original.requestMacKeyId);
      binding.put("requestDigest", original.requestDigest);
      binding.put("tokenHash", tokenHash);
      binding.put("claimsDigest", AccountControlUiIssuanceRepository.hash(original.claims));
      binding.put("bundleDigest", AccountControlUiIssuanceRepository.hash(original.bundle));
      binding.put(
          "signerReceiptDigest", AccountControlUiIssuanceRepository.hash(original.signerReceipt));
      binding.put("recoveryExpiresAt", original.recoveryExpiry.toString());
      return AccountControlUiAuthority.canonical(binding);
    }

    AccountControlUiIssuanceService.Request request(
        UUID requestTenant, UUID requestContext, String secret) {
      return new AccountControlUiIssuanceService.Request(
          requestId, "creator@example.test", secret, requestTenant, requestContext);
    }

    AccountControlUiIssuanceService.AccountSignInRequest accountRequest() {
      return new AccountControlUiIssuanceService.AccountSignInRequest(
          requestId,
          "creator@example.test",
          "test-only-secret",
          context,
          new SourceContext("192.0.2.10"));
    }

    PeerScope withPeer(String uri) {
      var identity = GrpcPeerIdentity.parseUri(uri).orElseThrow();
      var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, identity);
      return new PeerScope(context.attach(), context);
    }
  }

  private record PeerScope(Context previous, Context attached) implements AutoCloseable {
    @Override
    public void close() {
      attached.detach(previous);
    }
  }
}
