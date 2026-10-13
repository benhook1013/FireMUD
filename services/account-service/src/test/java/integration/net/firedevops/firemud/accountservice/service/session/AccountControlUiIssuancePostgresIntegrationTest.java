package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl.ControlUiPrimaryIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AcknowledgementRequirements;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
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
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void originalPreparedCandidateCommittedAndLostResponseReadBackExactOwnerBytes() {
    Context c = context();
    Fixture f = new Fixture(c);
    var prepared = f.prepare();
    assertThat(prepared.status).isEqualTo("PREPARED");
    assertThat(prepared.expiresAt).isEqualTo(prepared.issuedAt.plusSeconds(300));
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
                            "UPDATE account_control_ui_issuance_operations"
                                + " SET recovery_expires_at = recovery_expires_at + INTERVAL '1 second' WHERE request_id = ?",
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
                            "INSERT INTO account_control_ui_issuance_operations"
                                + " (request_id, operation_id, token_jti, account_uuid, tenant_uuid, caller_workload, caller_context_id,"
                                + " request_mac_key_id, request_digest, claims_payload, source_payload, bundle_payload, signer_receipt,"
                                + " issued_at_epoch_second, expires_at_epoch_second, recovery_expires_at, status)"
                                + " SELECT ?, ?, ?, account_uuid, tenant_uuid, caller_workload, caller_context_id, request_mac_key_id,"
                                + " request_digest, claims_payload, source_payload, bundle_payload, signer_receipt, issued_at_epoch_second,"
                                + " expires_at_epoch_second, to_timestamp(issued_at_epoch_second + 61), 'PREPARED'"
                                + " FROM account_control_ui_issuance_operations WHERE request_id = ?",
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
                "SELECT revocation_receipt FROM account_control_ui_issuance_operations WHERE request_id = ?",
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
    assertThat(observed.claims).isEqualTo(original.claims);
    assertThat(observed.sources).isEqualTo(original.sources);
    assertThat(observed.bundle).isEqualTo(original.bundle);
    assertThat(observed.signerReceipt).isEqualTo(original.signerReceipt);
    assertThat(observed.issuedAt).isEqualTo(original.issuedAt);
    assertThat(observed.expiresAt).isEqualTo(original.expiresAt);
    assertThat(observed.recoveryExpiry).isEqualTo(original.recoveryExpiry);
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
                  "INSERT INTO account_fresh_tenant_identity_associations"
                      + " (schema_version, target_namespace, creation_request_id, operation_id, request_digest,"
                      + " canonical_tenant_id, source_game_row_id, source_game_tenant_key, provenance_kind, evidence_digest)"
                      + " VALUES (1, 'test-control', ?, ?, ?, ?, 1, ?, 'NEW_GAME_ROW', ?)",
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  "sha256:" + "a".repeat(64),
                  tenant,
                  tenant.toString(),
                  "sha256:" + "b".repeat(64)));
      when(primary.accountId()).thenReturn(actor);
      when(authority.actor()).thenReturn(actor);
      when(authority.tenant()).thenReturn(tenant);
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
      return c.tx(
          () ->
              c.operations.prepare(
                  request,
                  "spiffe://test-only/control-ui",
                  UUID.randomUUID(),
                  "test-mac1",
                  "c".repeat(64),
                  primary,
                  authority,
                  signer,
                  Instant.ofEpochSecond(Instant.now().getEpochSecond())));
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
    String schema = "control_ui_storage_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Context(dsl, new AccountControlUiIssuanceRepository(dsl), transaction);
  }

  private record Context(
      DSLContext dsl,
      AccountControlUiIssuanceRepository operations,
      TransactionTemplate transaction) {
    <T> T tx(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
