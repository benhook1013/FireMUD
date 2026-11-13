package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl.ControlUiPrimaryIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Flyway/PostgreSQL owner storage proof only. Primary authentication, canonical authority,
 * signer capture and Redis/TLS/WAITAOF are explicitly test-only doubles. The tenant source is a
 * stipulated external Game Design fixture, not genuine owner provenance. No private proof
 * constructor is bypassed: Coordination creates its receipt through its normal transport path.
 * Opaque envelope fixture bytes prove retention, not AEAD, genuine issuance or physical Redis.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountControlUiIssuancePostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void originalPreparedCandidateCommittedAndLostResponseReadBackExactOwnerBytes() {
    Context c = context();
    Fixture f = new Fixture(c);
    var prepared = f.prepare();
    assertThat(prepared.status).isEqualTo("PREPARED");
    assertThat(prepared.expiresAt).isEqualTo(prepared.issuedAt.plusSeconds(300));
    assertThat(prepared.expiryMillis()).isEqualTo(prepared.expiresAt.toEpochMilli() + 300_000L);
    assertThat(prepared.recoveryExpiry).isEqualTo(prepared.issuedAt.plusSeconds(60));
    var candidate = f.candidate(prepared);
    assertThat(candidate.status).isEqualTo("CANDIDATE");
    Transport transport = new Transport(candidate);
    var pending =
        transport.client.registerPending(
            candidate.tokenHash, candidate.pendingRegistry, candidate.expiryMillis());
    var committed = c.tx(() -> c.operations.commit(candidate, pending));
    assertThat(committed.stored.status).isEqualTo("COMMITTED");

    // Discard the commit response and re-read through a fresh repository instance/transaction.
    var reread =
        c.tx(() -> new AccountControlUiIssuanceRepository(c.dsl).findRequest(prepared.requestId));
    var recovered = c.tx(() -> c.operations.requireCommitted(reread));
    assertOriginal(prepared, recovered.stored);
    assertThat(recovered.tokenHash()).isEqualTo(candidate.tokenHash);
    assertThat(recovered.pendingRegistry()).isEqualTo(candidate.pendingRegistry);
    assertThat(recovered.activeRegistry()).isEqualTo(candidate.activeRegistry);
    assertThat(reread.expiryMillis()).isEqualTo(prepared.expiryMillis());
    var envelope =
        Objects.requireNonNull(
            c.tx(() -> c.operations.envelope(reread)),
            "Original protected response envelope readback required");
    assertThat(envelope.encrypted()).isEqualTo(f.encryptedResponse);
    assertThat(envelope.binding()).isEqualTo(f.ownerBinding);
    assertThat(c.dsl.fetchCount(DSL.table("account_control_ui_issuance_operations"))).isEqualTo(1);
    assertThat(c.dsl.fetchCount(DSL.table("account_control_ui_response_envelopes"))).isEqualTo(1);
  }

  @Test
  void replayDenialsPersistBoundedHistoryWithoutChangingRetainedIssuanceAccountOrOtp() {
    Context c = contextAt("1043");
    Fixture f = new Fixture(c);
    var original = f.prepare(300_000L, AccountControlUiOwnerSourcesFixture.CALLER);
    c.tx(
        () ->
            c.dsl.execute(
                "INSERT INTO account_email_login_challenge (account_id, code_hash, expires_at,"
                    + " resend_available_at, created_at, updated_at) SELECT id,"
                    + " 'test-only-original-otp-hash', CURRENT_TIMESTAMP + INTERVAL '5 minutes',"
                    + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM accounts WHERE"
                    + " account_uuid = ?",
                f.actor));
    var accountBefore =
        Objects.requireNonNull(
                c.dsl.fetchOne("SELECT * FROM accounts WHERE account_uuid = ?", f.actor))
            .intoMap();
    var otpBefore =
        Objects.requireNonNull(c.dsl.fetchOne("SELECT * FROM account_email_login_challenge"))
            .intoMap();
    migrateToLatest(c);
    var primary = mock(AccountServiceImpl.class);
    var cryptography = mock(AccountControlUiResponseCryptography.class);
    when(cryptography.requestDigest(anyString(), any(byte[].class))).thenReturn("a".repeat(64));
    var service = replayService(c, primary, cryptography);
    var request =
        new AccountControlUiIssuanceService.Request(
            original.requestId,
            "owner@example.test",
            "wrong-secret",
            original.tenantId,
            original.callerContextId);
    try (var peer = AccountControlUiOwnerSourcesFixture.withPeer(original.caller)) {
      for (int failure = 1; failure <= 5; failure++) {
        assertThatThrownBy(() -> service.issue(request, mock(CapturedEnvironmentBoundary.class)))
            .isInstanceOf(AccountControlUiIssuanceService.IdempotencyConflictException.class);
        // A fresh transaction/repository observes the denial's committed history.
        assertThat(
                c.<Integer>tx(
                    () ->
                        new AccountControlUiIssuanceRepository(c.dsl)
                            .countReplayFailures(original)))
            .isEqualTo(failure);
      }
      // Even a now-correct secret cannot reach its MAC verifier after budget exhaustion.
      when(cryptography.requestDigest(anyString(), any(byte[].class)))
          .thenReturn(original.requestDigest);
      assertThatThrownBy(() -> service.issue(request, mock(CapturedEnvironmentBoundary.class)))
          .isInstanceOf(AccountControlUiIssuanceService.IdempotencyConflictException.class);
    }
    verify(cryptography, times(5)).requestDigest(anyString(), any(byte[].class));
    verifyNoInteractions(primary);
    assertThat(
            Objects.requireNonNull(
                    c.dsl.fetchOne("SELECT * FROM accounts WHERE account_uuid = ?", f.actor))
                .intoMap())
        .isEqualTo(accountBefore);
    assertThat(
            Objects.requireNonNull(c.dsl.fetchOne("SELECT * FROM account_email_login_challenge"))
                .intoMap())
        .isEqualTo(otpBefore);
    assertOriginal(original, c.tx(() -> c.operations.findRequest(original.requestId)));
    var independent =
        c.tx(
            () ->
                c.operations.prepare(
                    UUID.randomUUID(),
                    original.caller,
                    UUID.randomUUID(),
                    "test-mac1",
                    "c".repeat(64),
                    f.primary,
                    f.authority,
                    f.signer,
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                    300_000L));
    assertThat(c.<Integer>tx(() -> c.operations.countReplayFailures(independent))).isZero();

    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "INSERT INTO account_control_ui_issuance_replay_failures VALUES (?, ?,"
                                + " 1)",
                            independent.operationId,
                            original.requestId)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("exact original request");
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "INSERT INTO account_control_ui_issuance_replay_failures VALUES (?, ?,"
                                + " 6)",
                            original.operationId,
                            original.requestId)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE account_control_ui_issuance_replay_failures SET failure_number"
                                + " = 1")))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                c.tx(
                    () -> c.dsl.execute("DELETE FROM account_control_ui_issuance_replay_failures")))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () -> c.tx(() -> c.dsl.execute("TRUNCATE account_control_ui_issuance_replay_failures")))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
  }

  @Test
  void concurrentReplayGuessesShareOneFiveFailureBudget() throws Exception {
    Context c = context();
    Fixture f = new Fixture(c);
    var original = f.prepare(300_000L, AccountControlUiOwnerSourcesFixture.CALLER);
    var primary = mock(AccountServiceImpl.class);
    var cryptography = mock(AccountControlUiResponseCryptography.class);
    when(cryptography.requestDigest(anyString(), any(byte[].class))).thenReturn("a".repeat(64));
    var service = replayService(c, primary, cryptography);
    var request =
        new AccountControlUiIssuanceService.Request(
            original.requestId,
            "owner@example.test",
            "wrong-secret",
            original.tenantId,
            original.callerContextId);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(8)) {
      var attempts = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int index = 0; index < 8; index++) {
        attempts.add(
            workers.submit(
                () -> {
                  try (var peer = AccountControlUiOwnerSourcesFixture.withPeer(original.caller)) {
                    if (!start.await(10, TimeUnit.SECONDS))
                      throw new AssertionError("Replay start timed out");
                    assertThatThrownBy(
                            () -> service.issue(request, mock(CapturedEnvironmentBoundary.class)))
                        .isInstanceOf(
                            AccountControlUiIssuanceService.IdempotencyConflictException.class);
                  }
                  return null;
                }));
      }
      start.countDown();
      for (var attempt : attempts) attempt.get(15, TimeUnit.SECONDS);
    }
    assertThat(c.<Integer>tx(() -> c.operations.countReplayFailures(original))).isEqualTo(5);
    verify(cryptography, times(5)).requestDigest(anyString(), any(byte[].class));
    verifyNoInteractions(primary);
    assertOriginal(original, c.tx(() -> c.operations.findRequest(original.requestId)));
  }

  private static AccountControlUiIssuanceService replayService(
      Context c, AccountServiceImpl primary, AccountControlUiResponseCryptography cryptography) {
    return new AccountControlUiIssuanceService(
        primary,
        null, // No implicit shared abuse policy or admitting fallback.
        c.operations,
        mock(AccountControlUiAuthority.class),
        mock(DraftAuthorizationFenceRepository.class),
        mock(AccountControlUiSignerOwner.class),
        cryptography,
        mock(AccountControlUiCoordination.class),
        mock(AccountControlUiActorService.class),
        Objects.requireNonNull(c.transaction.getTransactionManager()),
        Clock.systemUTC(),
        300_000L,
        AccountControlUiOwnerSourcesFixture.CALLER);
  }

  @Test
  void explicitlyConfiguredZeroMarginIsPresentOnNewOwnerRows() {
    Context c = context();
    var prepared = new Fixture(c).prepare(0L);

    assertThat(prepared.registryCleanupDeadlineEpochMillis)
        .isEqualTo(prepared.expiresAt.toEpochMilli());
  }

  @Test
  void v126_2PreservesRetainedPreparedRowAndDeniesCurrentUseAndAdvance() {
    Context c = contextAt("126.1");
    Fixture f = new Fixture(c);
    UUID operationId = UUID.randomUUID();
    UUID tokenJti = UUID.randomUUID();
    Instant issuedAt =
        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(10);
    Instant expiresAt = issuedAt.plusSeconds(300);
    OffsetDateTime recoveryExpiresAt =
        OffsetDateTime.ofInstant(issuedAt.plusSeconds(60), ZoneOffset.UTC);
    byte[] claims =
        bytes(
            "{\"accountId\":\""
                + f.actor
                + "\",\"jti\":\""
                + tokenJti
                + "\",\"iat\":"
                + issuedAt.getEpochSecond()
                + ",\"nbf\":"
                + issuedAt.getEpochSecond()
                + ",\"exp\":"
                + expiresAt.getEpochSecond()
                + ",\"tokenGeneration\":1,\"authorityTuple\":{},"
                + "\"membershipVersion\":{},\"issuanceFence\":1}");
    byte[] source = bytes("{\"historicalFixture\":true}");
    byte[] bundle = bytes("{\"authoritySourceVersions\":[],\"bundleRef\":{}}");
    byte[] signerReceipt = bytes("test-only-historical-signer-receipt");
    // Historical V126.1 fixture seeding uses real owner IDs but is not a production issuance path.
    c.tx(
        () ->
            c.dsl.execute(
                "INSERT INTO account_control_ui_issuance_operations (request_id, operation_id,"
                    + " token_jti, account_uuid, tenant_uuid, caller_workload, caller_context_id,"
                    + " request_mac_key_id, request_digest, claims_payload, source_payload,"
                    + " bundle_payload, signer_receipt, issued_at_epoch_second,"
                    + " expires_at_epoch_second, recovery_expires_at, status) VALUES (?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'PREPARED')",
                f.request,
                operationId,
                tokenJti,
                f.actor,
                f.tenant,
                "spiffe://test-only/control-ui",
                UUID.randomUUID(),
                "historical-test",
                "c".repeat(64),
                claims,
                source,
                bundle,
                signerReceipt,
                issuedAt.getEpochSecond(),
                expiresAt.getEpochSecond(),
                recoveryExpiresAt));
    var before =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_control_ui_issuance_operations WHERE request_id = ?",
                f.request),
            "Historically seeded pre-deadline row required");

    migrateToLatest(c);

    var after =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_control_ui_issuance_operations WHERE request_id = ?",
                f.request),
            "Retained row must remain after additive migration");
    assertLegacyFieldsUnchanged(before, after);
    assertThat(after.get("registry_cleanup_deadline_epoch_millis", Long.class)).isNull();

    var retained = c.tx(() -> c.operations.findRequest(f.request));
    assertThat(retained.status).isEqualTo("PREPARED");
    assertThatThrownBy(retained::expiryMillis).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.operations.bindCandidate(
                            retained,
                            "d".repeat(64),
                            bytes("test-only-envelope"),
                            bytes("test-only-binding"),
                            f.signer)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(c.dsl.fetchCount(DSL.table("account_control_ui_response_envelopes"))).isZero();
    var afterDeniedAdvance =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_control_ui_issuance_operations WHERE request_id = ?",
                f.request),
            "Denied advancement must preserve retained row");
    assertLegacyFieldsUnchanged(before, afterDeniedAdvance);
    assertThat(afterDeniedAdvance.get("registry_cleanup_deadline_epoch_millis", Long.class))
        .isNull();
  }

  @Test
  void changedOriginalAndDirectSqlByteOrTimingChangesCannotRewriteRetainedOperation() {
    Context c = context();
    Fixture f = new Fixture(c);
    var prepared = f.prepare();
    var candidate = f.candidate(prepared);
    Transport transport = new Transport(candidate);
    var pending =
        transport.client.registerPending(
            candidate.tokenHash, candidate.pendingRegistry, candidate.expiryMillis());
    c.tx(() -> c.operations.commit(candidate, pending));
    var changedRow =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_control_ui_issuance_operations WHERE request_id = ?",
                prepared.requestId),
            "Original issuance row required before changed-input definition");
    changedRow.set(
        changedRow.field("claims_payload", byte[].class), bytes("test-only-changed-original"));
    var changed = new AccountControlUiIssuanceRepository.Stored(changedRow);
    assertThatThrownBy(() -> c.tx(() -> c.operations.requireCommitted(changed)))
        .isInstanceOf(IllegalStateException.class);
    for (String column :
        List.of("claims_payload", "source_payload", "bundle_payload", "signer_receipt")) {
      assertThatThrownBy(
              () ->
                  c.tx(
                      () ->
                          c.dsl.execute(
                              "UPDATE account_control_ui_issuance_operations SET "
                                  + column
                                  + " = ? WHERE request_id = ?",
                              bytes("test-only-rewrite"),
                              prepared.requestId)))
          .isInstanceOf(DataAccessException.class);
    }
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE account_control_ui_issuance_operations SET recovery_expires_at"
                                + " = recovery_expires_at + INTERVAL '1 second' WHERE request_id ="
                                + " ?",
                            prepared.requestId)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE account_control_ui_response_envelopes"
                                + " SET encrypted_response = ? WHERE operation_id = ?",
                            bytes("test-only-envelope-rewrite"),
                            prepared.operationId)))
        .isInstanceOf(DataAccessException.class);
    // A new row cannot evade the actual PostgreSQL-specific recovery bound either.
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "INSERT INTO account_control_ui_issuance_operations (request_id,"
                                + " operation_id, token_jti, account_uuid, tenant_uuid,"
                                + " caller_workload, caller_context_id, request_mac_key_id,"
                                + " request_digest, claims_payload, source_payload, bundle_payload,"
                                + " signer_receipt, issued_at_epoch_second,"
                                + " expires_at_epoch_second,"
                                + " registry_cleanup_deadline_epoch_millis, recovery_expires_at,"
                                + " status) SELECT ?, ?, ?, account_uuid, tenant_uuid,"
                                + " caller_workload, caller_context_id, request_mac_key_id,"
                                + " request_digest, claims_payload, source_payload, bundle_payload,"
                                + " signer_receipt, issued_at_epoch_second,"
                                + " expires_at_epoch_second,"
                                + " registry_cleanup_deadline_epoch_millis,"
                                + " to_timestamp(issued_at_epoch_second + 61), 'PREPARED' FROM"
                                + " account_control_ui_issuance_operations WHERE request_id = ?",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            prepared.requestId)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("account_control_ui_recovery_expiry_bound");
    assertOriginal(prepared, c.tx(() -> c.operations.findRequest(prepared.requestId)));
  }

  @Test
  void
      durableRevokingDeniesCommittedRecoveryAndUnavailableExternalRevocationIsNotTerminalSuccess() {
    Context c = context();
    Fixture f = new Fixture(c);
    var prepared = f.prepare();
    var candidate = f.candidate(prepared);
    Transport transport = new Transport(candidate);
    var pending =
        transport.client.registerPending(
            candidate.tokenHash, candidate.pendingRegistry, candidate.expiryMillis());
    var committed = c.tx(() -> c.operations.commit(candidate, pending));
    var revoking = c.tx(() -> c.operations.beginRecoveryFailure(committed.stored));
    assertThat(revoking.status).isEqualTo("REVOKING");
    assertThatThrownBy(() -> c.tx(() -> c.operations.requireCommitted(committed.stored)))
        .isInstanceOf(IllegalStateException.class);
    when(transport.commands.get(any(byte[].class)))
        .thenThrow(new IllegalStateException("test-only transport unavailable"));
    assertThatThrownBy(() -> transport.client.revoke(revoking))
        .isInstanceOf(IllegalStateException.class);
    var held = c.tx(() -> c.operations.findRequest(prepared.requestId));
    assertThat(held.status).isEqualTo("REVOKING");
    assertOriginal(prepared, held);
    var revocation =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT revocation_receipt FROM account_control_ui_issuance_operations WHERE"
                    + " request_id = ?",
                prepared.requestId),
            "Durable non-authorizing issuance row must remain present");
    assertThat(revocation.get("revocation_receipt")).isNull();
    assertThat(c.tx(() -> c.operations.beginRecoveryFailure(held)).status).isEqualTo("REVOKING");

    byte[] revoked = AccountControlUiIssuanceRepository.revokedRegistry(held);
    doReturn(held.pendingRegistry, revoked).when(transport.commands).get(any(byte[].class));
    var receipt = transport.client.revoke(held);
    c.tx(
        () -> {
          c.operations.finishRecoveryFailure(held, receipt);
          return null;
        });
    assertThat(c.tx(() -> c.operations.findRequest(prepared.requestId)).status)
        .isEqualTo("REVOKED");
    assertThatThrownBy(() -> c.tx(() -> c.operations.requireCommitted(committed.stored)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static void assertOriginal(
      AccountControlUiIssuanceRepository.Stored original,
      AccountControlUiIssuanceRepository.Stored observed) {
    assertThat(observed.operationId).isEqualTo(original.operationId);
    assertThat(observed.requestId).isEqualTo(original.requestId);
    assertThat(observed.jti).isEqualTo(original.jti);
    assertThat(observed.accountId).isEqualTo(original.accountId);
    assertThat(observed.tenantId).isEqualTo(original.tenantId);
    assertThat(observed.scopeKind).isEqualTo(original.scopeKind);
    assertThat(observed.claims).isEqualTo(original.claims);
    assertThat(observed.sources).isEqualTo(original.sources);
    assertThat(observed.bundle).isEqualTo(original.bundle);
    assertThat(observed.signerReceipt).isEqualTo(original.signerReceipt);
    assertThat(observed.issuedAt).isEqualTo(original.issuedAt);
    assertThat(observed.expiresAt).isEqualTo(original.expiresAt);
    assertThat(observed.recoveryExpiry).isEqualTo(original.recoveryExpiry);
  }

  private static void assertLegacyFieldsUnchanged(
      org.jooq.Record original, org.jooq.Record observed) {
    for (String field :
        List.of(
            "request_id",
            "operation_id",
            "token_jti",
            "account_uuid",
            "tenant_uuid",
            "caller_workload",
            "caller_context_id",
            "request_mac_key_id",
            "request_digest",
            "claims_payload",
            "source_payload",
            "bundle_payload",
            "signer_receipt",
            "issued_at_epoch_second",
            "expires_at_epoch_second",
            "recovery_expires_at",
            "status",
            "token_hash",
            "pending_registry",
            "active_registry",
            "pending_receipt",
            "committed_at",
            "recovery_failed_at",
            "revocation_receipt",
            "created_at")) {
      Object originalValue = original.get(field);
      Object observedValue = observed.get(field);
      if (originalValue instanceof byte[] originalBytes) {
        assertThat((byte[]) observedValue).containsExactly(originalBytes);
      } else {
        assertThat(observedValue).isEqualTo(originalValue);
      }
    }
  }

  private static final class Fixture {
    final Context c;
    final UUID actor, tenant = UUID.randomUUID(), request = UUID.randomUUID();
    final ControlUiPrimaryIdentity primary = mock(ControlUiPrimaryIdentity.class);
    final AccountControlUiAuthority.Snapshot authority =
        mock(AccountControlUiAuthority.Snapshot.class);
    final AccountControlUiSignerOwner.Capture signer =
        mock(AccountControlUiSignerOwner.Capture.class);
    final byte[] encryptedResponse = bytes("test-only-opaque-encrypted-response-double");
    final byte[] ownerBinding = bytes("test-only-exact-envelope-owner-binding");

    Fixture(Context c) {
      this.c = c;
      actor =
          c.tx(
              () -> {
                Account account = new Account();
                String suffix = UUID.randomUUID().toString();
                account.setUsername("control-ui-" + suffix);
                account.setEmail(suffix + "@example.test");
                account.setPasswordHash("test-only-primary-hash");
                return new AccountRepository(c.dsl).save(account).getAccountUuid();
              });
      // Stipulated external creation evidence only; real source/claim triggers still execute.
      c.tx(
          () ->
              c.dsl.execute(
                  "INSERT INTO account_fresh_tenant_identity_associations (schema_version,"
                      + " target_namespace, creation_request_id, operation_id, request_digest,"
                      + " canonical_tenant_id, source_game_row_id, source_game_tenant_key,"
                      + " provenance_kind, evidence_digest) VALUES (1, 'test-control', ?, ?, ?, ?,"
                      + " 1, ?, 'NEW_GAME_ROW', ?)",
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  "sha256:" + "a".repeat(64),
                  tenant,
                  tenant.toString(),
                  "sha256:" + "b".repeat(64)));
      when(primary.accountId()).thenReturn(actor);
      when(authority.actor()).thenReturn(actor);
      when(authority.tenant()).thenReturn(tenant);
      when(authority.scopeKind()).thenReturn(AccountControlUiAuthority.ScopeKind.TENANT);
      when(authority.optionalTenantId()).thenReturn(Optional.of(tenant));
      when(authority.scopedRoles()).thenReturn(Map.of(tenant.toString(), List.of("tenantAdmin")));
      when(authority.authorityTuple())
          .thenReturn(
              Map.of(
                  "issuerAuthGeneration",
                  1L,
                  "accountAuthorityGeneration",
                  1L,
                  "tenantAuthorityGeneration",
                  Map.of(tenant.toString(), 1L),
                  "membershipAuthorityGeneration",
                  Map.of(tenant.toString(), 1L),
                  "privateRealmGrantVersions",
                  List.of()));
      when(authority.membershipVersion()).thenReturn(Map.of(tenant.toString(), 1L));
      when(authority.issuanceFence()).thenReturn(1L);
      when(authority.evidence()).thenReturn(bytes("test-only-source-evidence-double"));
      when(authority.sources())
          .thenReturn(
              List.of(
                  new SourceEvidence(
                      SourceKind.ACCOUNT,
                      actor.toString(),
                      "1",
                      "1",
                      null,
                      null,
                      bytes("test-only-account-source"))));
      when(authority.accountIdentitySource()).thenReturn(Map.of("testOnly", true));
      when(authority.outboxCheckpoints()).thenReturn(List.of());
      when(signer.receipt()).thenReturn(bytes("test-only-signer-receipt-double"));
      when(signer.kid()).thenReturn("test-control-kid");
      when(signer.generation()).thenReturn("1");
    }

    AccountControlUiIssuanceRepository.Stored prepare() {
      return prepare(300_000L);
    }

    AccountControlUiIssuanceRepository.Stored prepare(long sessionSafetyMarginMillis) {
      return prepare(sessionSafetyMarginMillis, "spiffe://test-only/control-ui");
    }

    AccountControlUiIssuanceRepository.Stored prepare(
        long sessionSafetyMarginMillis, String caller) {
      return c.tx(
          () ->
              c.operations.prepare(
                  request,
                  caller,
                  UUID.randomUUID(),
                  "test-mac1",
                  "c".repeat(64),
                  primary,
                  authority,
                  signer,
                  Instant.ofEpochSecond(Instant.now().getEpochSecond()),
                  sessionSafetyMarginMillis));
    }

    AccountControlUiIssuanceRepository.Stored candidate(
        AccountControlUiIssuanceRepository.Stored original) {
      return c.tx(
          () ->
              c.operations.bindCandidate(
                  original, "d".repeat(64), encryptedResponse, ownerBinding, signer));
    }
  }

  /** No Redis process or TLS proof: only actual owner adapter/receipt construction with doubles. */
  private static final class Transport {
    @SuppressWarnings("unchecked")
    final RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);

    @SuppressWarnings("unchecked")
    final StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);

    final AccountControlUiCoordination client;

    Transport(AccountControlUiIssuanceRepository.Stored original) {
      var descriptor = AccountControlUiRegistryContract.descriptor();
      var catalog = mock(RedisScriptCatalog.class);
      when(catalog.require(AccountControlUiRegistryContract.SCRIPT_ID)).thenReturn(descriptor);
      when(connection.isOpen()).thenReturn(true);
      when(connection.getOptions())
          .thenReturn(ClientOptions.builder().autoReconnect(false).build());
      when(connection.sync()).thenReturn(commands);
      when(commands.aclWhoami()).thenReturn("account_coord_app");
      when(commands.scriptLoad(any(byte[].class)))
          .thenAnswer(
              invocation ->
                  AccountControlUiCoordination.digest("SHA-1", invocation.getArgument(0)));
      doReturn(1L)
          .when(commands)
          .evalsha(
              anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
      doReturn(List.of(1L, 1L)).when(commands).dispatch(any(), any(), any());
      when(commands.pexpiretime(any(byte[].class))).thenReturn(original.expiryMillis());
      when(commands.get(any(byte[].class))).thenReturn(original.pendingRegistry);
      client =
          new AccountControlUiCoordination(
              () -> connection, new AcknowledgementRequirements(1, 1, 1000), catalog);
    }
  }

  private static Context context() {
    return contextAt(null);
  }

  private static Context contextAt(String targetVersion) {
    String schema = "control_ui_storage_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var migration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (targetVersion != null) {
      migration.target(org.flywaydb.core.api.MigrationVersion.fromVersion(targetVersion));
    }
    migration.load().migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Context(
        dsl, new AccountControlUiIssuanceRepository(dsl), transaction, source, schema);
  }

  private static void migrateToLatest(Context c) {
    Flyway.configure()
        .dataSource(c.dataSource)
        .schemas(c.schema)
        .defaultSchema(c.schema)
        .placeholders(Map.of("serviceSchema", c.schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private record Context(
      DSLContext dsl,
      AccountControlUiIssuanceRepository operations,
      TransactionTemplate transaction,
      DriverManagerDataSource dataSource,
      String schema) {
    <T> T tx(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
