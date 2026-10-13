package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
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
    var source = mock(AccountControlUiAuthority.Snapshot.class);
    when(source.sources()).thenReturn(java.util.List.of());
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
        .prepare(any(), anyString(), any(), anyString(), anyString(), any(), any(), any(), any());
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
            .isInstanceOf(IllegalArgumentException.class);
      }
      verify(fixture.operations, never()).beginRecoveryFailure(any());
      verifyNoInteractions(fixture.registry, fixture.primary, fixture.signers);
    }
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
  void committedRecoveryReturnsTheExactOriginalCredentialWithoutPrimaryAuthenticationOrSigning() {
    Fixture fixture = new Fixture(NOW.plusSeconds(30));
    byte[] exactCredential = "test-only-original-compact-jwt".getBytes(StandardCharsets.US_ASCII);
    String tokenHash = AccountControlUiIssuanceRepository.hash(exactCredential);
    var original = fixture.useOwnerRepository(tokenHash);
    var snapshot = mock(AccountControlUiAuthority.Snapshot.class);
    when(snapshot.evidence()).thenReturn(new byte[] {1});
    when(snapshot.sources()).thenReturn(java.util.List.of());
    when(fixture.authority.capture(fixture.account, fixture.tenant, fixture.environment))
        .thenReturn(snapshot);
    when(fixture.signers.requireOriginal(any(byte[].class)))
        .thenReturn(mock(AccountControlUiSignerOwner.Capture.class));
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

  private static boolean sameOriginalRecord(
      AccountControlUiIssuanceRepository.Stored expected,
      AccountControlUiIssuanceRepository.Stored actual) {
    return actual != null
        && expected.requestId.equals(actual.requestId)
        && expected.operationId.equals(actual.operationId)
        && expected.jti.equals(actual.jti)
        && expected.accountId.equals(actual.accountId)
        && expected.tenantId.equals(actual.tenantId)
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
                operations,
                authority,
                fences,
                signers,
                cryptography,
                registry,
                actors,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
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

    AccountControlUiIssuanceRepository.Stored useOwnerRepository(String tokenHash) {
      var stored = stored("COMMITTED", recoveryExpiry, tokenHash);
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
      doAnswer(call -> stored).when(operations).findRequest(requestId);
      when(cryptography.requestDigest(anyString(), any(byte[].class)))
          .thenReturn("original-request-digest");
      ownerRow = rowFor(stored, tokenHash);
      return stored;
    }

    void recoveryInputs() {
      var snapshot = mock(AccountControlUiAuthority.Snapshot.class);
      when(snapshot.evidence()).thenReturn(new byte[] {1});
      when(snapshot.sources()).thenReturn(java.util.List.of());
      when(authority.capture(account, tenant, environment)).thenReturn(snapshot);
      when(signers.requireOriginal(any(byte[].class)))
          .thenReturn(mock(AccountControlUiSignerOwner.Capture.class));
    }

    AccountControlUiIssuanceRepository.Stored stored(
        String status, Instant expiry, String tokenHash) {
      return stored(status, expiry, tokenHash, ISSUER);
    }

    AccountControlUiIssuanceRepository.Stored stored(
        String status, Instant expiry, String tokenHash, String caller) {
      var values = new java.util.HashMap<String, Object>();
      values.put("request_id", requestId);
      values.put("operation_id", operationId);
      values.put("token_jti", jti);
      values.put("account_uuid", account);
      values.put("tenant_uuid", tenant);
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
      return AccountControlUiAuthority.canonical(
          java.util.Map.ofEntries(
              java.util.Map.entry("purpose", "account-control-ui-original-credential/v1"),
              java.util.Map.entry("operationId", original.operationId.toString()),
              java.util.Map.entry("requestId", original.requestId.toString()),
              java.util.Map.entry("accountId", original.accountId.toString()),
              java.util.Map.entry("tenantId", original.tenantId.toString()),
              java.util.Map.entry("callerWorkload", original.caller),
              java.util.Map.entry("callerContextId", original.callerContextId.toString()),
              java.util.Map.entry("requestMacKeyId", original.requestMacKeyId),
              java.util.Map.entry("requestDigest", original.requestDigest),
              java.util.Map.entry("tokenHash", tokenHash),
              java.util.Map.entry(
                  "claimsDigest", AccountControlUiIssuanceRepository.hash(original.claims)),
              java.util.Map.entry(
                  "bundleDigest", AccountControlUiIssuanceRepository.hash(original.bundle)),
              java.util.Map.entry(
                  "signerReceiptDigest",
                  AccountControlUiIssuanceRepository.hash(original.signerReceipt)),
              java.util.Map.entry("recoveryExpiresAt", original.recoveryExpiry.toString())));
    }

    AccountControlUiIssuanceService.Request request(
        UUID requestTenant, UUID requestContext, String secret) {
      return new AccountControlUiIssuanceService.Request(
          requestId, "creator@example.test", secret, requestTenant, requestContext);
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
