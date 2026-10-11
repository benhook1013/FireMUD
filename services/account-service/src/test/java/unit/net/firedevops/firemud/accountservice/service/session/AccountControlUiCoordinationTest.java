package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit Redis/TLS/ACL/WAITAOF service doubles, not physical Redis or mTLS proof. */
class AccountControlUiCoordinationTest {
  private static final String HASH = "a".repeat(64);
  private static final long EXPIRY = 1791467100000L;

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactRetryMetadataDeclaresPhysicalReassertionAsMutatingSuccess() {
    var descriptor = AccountControlUiRegistryContract.descriptor();
    assertThat(descriptor.outcomes())
        .contains(
            new RedisScriptDescriptor.OutcomeSpec(
                "EXACT_REASSERTED",
                RedisScriptDescriptor.OutcomeCategory.SUCCESS,
                RedisScriptDescriptor.MutationEffect.MUTATING));
  }

  @Test
  void registryDescriptorDigestMatchesClasspathLuaSource() throws Exception {
    var descriptor = AccountControlUiRegistryContract.descriptor();
    var resource =
        AccountControlUiCoordination.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath());
    assertThat(resource).isNotNull();

    try (resource) {
      var sourceDigest =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(resource.readAllBytes()));
      assertThat(sourceDigest).isEqualTo(descriptor.sha256());
    }
  }

  @Test
  void registryLuaRejectsNonNumericExpAndDeadlineBeforeExpiryArithmetic() throws Exception {
    var descriptor = AccountControlUiRegistryContract.descriptor();
    var resource =
        AccountControlUiCoordination.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath());
    assertThat(resource).isNotNull();

    try (resource) {
      String source = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
      int expNumericGuard = source.indexOf("or type(nextRecord.exp) ~= 'number'");
      int cleanupNumericGuard =
          source.indexOf("or type(nextRecord.registryCleanupDeadlineEpochMillis) ~= 'number'");
      int cleanupDeadlineArgumentMatch =
          source.indexOf("or nextRecord.registryCleanupDeadlineEpochMillis ~= deadline");
      int expiryMath =
          source.indexOf(
              "or nextRecord.registryCleanupDeadlineEpochMillis < nextRecord.exp * 1000");
      assertThat(expNumericGuard).isGreaterThanOrEqualTo(0);
      assertThat(cleanupNumericGuard).isGreaterThan(expNumericGuard);
      assertThat(cleanupDeadlineArgumentMatch).isGreaterThan(cleanupNumericGuard);
      assertThat(expiryMath).isGreaterThan(cleanupDeadlineArgumentMatch);
    }
  }

  @Test
  void exactRetryStillRequiresSameConnectionWriteThenWaitAofThenExactReadback() throws Exception {
    Fixture f = new Fixture();
    byte[] pending = record("pending");
    doReturn(0L)
        .when(f.commands)
        .evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
    when(f.commands.get(any(byte[].class))).thenReturn(pending);
    var receipt = f.client.registerPending(HASH, pending, EXPIRY);
    assertThat(receipt.tokenHash).isEqualTo(HASH);
    assertThat(receipt.recordDigest).isEqualTo(AccountControlUiIssuanceRepository.hash(pending));
    var order = inOrder(f.commands);
    order.verify(f.commands).aclWhoami();
    order.verify(f.commands).scriptLoad(any(byte[].class));
    order
        .verify(f.commands)
        .evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
    order.verify(f.commands).dispatch(any(), any(), any());
    order.verify(f.commands).pexpiretime(any(byte[].class));
    order.verify(f.commands).get(any(byte[].class));
  }

  @Test
  void activeReadRequiresOriginalPhysicalExpiryAndNeverRepairs() throws Exception {
    Fixture f = new Fixture();
    when(f.commands.get(any(byte[].class))).thenReturn(record("active"));
    assertThat(f.client.readActive(HASH)).isEqualTo(record("active"));
    for (Long invalid : new Long[] {null, -1L, -2L, EXPIRY + 1}) {
      when(f.commands.pexpiretime(any(byte[].class))).thenReturn(invalid);
      assertThatThrownBy(() -> f.client.readActive(HASH)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void recoveryRevocationKeepsOriginalFieldsAndRequiresDurableExactReadback() throws Exception {
    for (String prior : List.of("pending", "active", "revoked")) {
      Fixture f = new Fixture();
      var original = revoking();
      byte[] revoked = AccountControlUiIssuanceRepository.revokedRegistry(original);
      var fields = AccountControlUiIssuanceRepository.object(revoked);
      assertThat(fields.get("state")).isEqualTo("revoked");
      assertThat(fields.get("registryVersion")).isEqualTo(3);
      assertThat(((Number) fields.get("exp")).longValue()).isEqualTo(EXPIRY / 1000);
      when(f.commands.get(any(byte[].class))).thenReturn(record(prior)).thenReturn(revoked);
      if (prior.equals("revoked")) {
        doReturn(0L)
            .when(f.commands)
            .evalsha(
                anyString(),
                eq(ScriptOutputType.INTEGER),
                any(byte[][].class),
                any(byte[][].class));
      }
      var receipt = f.client.revoke(original);
      assertThat(receipt.recordDigest).isEqualTo(AccountControlUiIssuanceRepository.hash(revoked));
      assertThat(receipt.expiryMillis).isEqualTo(EXPIRY);
      var order = inOrder(f.commands);
      order.verify(f.commands).get(any(byte[].class));
      order.verify(f.commands).pexpiretime(any(byte[].class));
      order.verify(f.commands).scriptLoad(any(byte[].class));
      order
          .verify(f.commands)
          .evalsha(
              anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
      order.verify(f.commands).dispatch(any(), any(), any());
      order.verify(f.commands).pexpiretime(any(byte[].class));
      order.verify(f.commands).get(any(byte[].class));
    }
  }

  @Test
  void missingChangedExpiredOrUnavailableRevocationNeverCountsAsSuccess() throws Exception {
    for (byte[] observed : new byte[][] {null, record("other")}) {
      Fixture f = new Fixture();
      when(f.commands.get(any(byte[].class))).thenReturn(observed);
      assertThatThrownBy(() -> f.client.revoke(revoking()))
          .isInstanceOf(IllegalStateException.class);
    }
    Fixture f = new Fixture();
    when(f.commands.get(any(byte[].class))).thenReturn(record("active"));
    when(f.commands.pexpiretime(any(byte[].class))).thenReturn(-2L);
    assertThatThrownBy(() -> f.client.revoke(revoking())).isInstanceOf(IllegalStateException.class);
    when(f.commands.pexpiretime(any(byte[].class))).thenReturn(EXPIRY);
    doReturn(-4L)
        .when(f.commands)
        .evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
    assertThatThrownBy(() -> f.client.revoke(revoking())).isInstanceOf(IllegalStateException.class);
  }

  private static AccountControlUiIssuanceRepository.Stored revoking() {
    // Explicit owner-record double only; not a committed producer or physical owner transaction.
    Record row = mock(Record.class);
    when(row.get("status", String.class)).thenReturn("REVOKING");
    when(row.get("token_hash", String.class)).thenReturn(HASH);
    when(row.get("pending_registry", byte[].class)).thenReturn(record("pending"));
    when(row.get("active_registry", byte[].class)).thenReturn(record("active"));
    when(row.get("issued_at_epoch_second", Long.class)).thenReturn(EXPIRY / 1000 - 300);
    when(row.get("expires_at_epoch_second", Long.class)).thenReturn(EXPIRY / 1000);
    when(row.get("registry_cleanup_deadline_epoch_millis", Long.class)).thenReturn(EXPIRY);
    when(row.get("recovery_expires_at", OffsetDateTime.class))
        .thenReturn(
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(EXPIRY - 240000), ZoneOffset.UTC));
    return new AccountControlUiIssuanceRepository.Stored(row);
  }

  @Test
  void wrongRoleMissingRecordReconnectSqlAndAcknowledgementShortfallDeny() throws Exception {
    Fixture f = new Fixture();
    when(f.commands.aclWhoami()).thenReturn("game_session_coord_app");
    assertThatThrownBy(() -> f.client.readActive(HASH)).isInstanceOf(IllegalStateException.class);
    when(f.commands.aclWhoami()).thenReturn("account_coord_app");
    when(f.commands.get(any(byte[].class))).thenReturn(null);
    assertThatThrownBy(() -> f.client.readActive(HASH)).isInstanceOf(IllegalStateException.class);
    when(f.connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(true).build());
    assertThatThrownBy(() -> f.client.readActive(HASH)).isInstanceOf(IllegalStateException.class);
    when(f.connection.getOptions())
        .thenReturn(ClientOptions.builder().autoReconnect(false).build());
    doReturn(List.of(1L, 0L)).when(f.commands).dispatch(any(), any(), any());
    assertThatThrownBy(() -> f.client.registerPending(HASH, record("pending"), EXPIRY))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> f.client.readActive(HASH))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SQL locks");
  }

  private static byte[] record(String state) {
    // A service-double observation only, never an authenticated actor or canonical owner bundle.
    return AccountControlUiAuthority.canonical(
        Map.ofEntries(
            Map.entry("schemaVersion", 1),
            Map.entry(
                "registryVersion", state.equals("active") ? 2 : state.equals("revoked") ? 3 : 1),
            Map.entry("tokenHash", HASH),
            Map.entry("kid", "key-1"),
            Map.entry("signerGeneration", "1"),
            Map.entry("issuer", "firemud-account-service"),
            Map.entry("profile", "control-ui"),
            Map.entry("type", "control-ui"),
            Map.entry("audience", "control-ui"),
            Map.entry("accountId", "10000000-0000-4000-8000-000000000001"),
            Map.entry("jti", "30000000-0000-4000-8000-000000000003"),
            Map.entry("iat", EXPIRY / 1000 - 300),
            Map.entry("nbf", EXPIRY / 1000 - 300),
            Map.entry("exp", EXPIRY / 1000),
            Map.entry("tokenGeneration", 1),
            Map.entry("authorityTuple", Map.of("accountAuthorityGeneration", 1)),
            Map.entry("membershipVersion", Map.of("20000000-0000-4000-8000-000000000002", 1)),
            Map.entry("issuanceFence", 1),
            Map.entry("operationId", "40000000-0000-4000-8000-000000000004"),
            Map.entry("requestId", "50000000-0000-4000-8000-000000000005"),
            Map.entry("requestDigest", "b".repeat(64)),
            Map.entry("registryCleanupDeadlineEpochMillis", EXPIRY),
            Map.entry("state", state),
            Map.entry(
                "authoritySourceVersions",
                Map.of("ACCOUNT:10000000-0000-4000-8000-000000000001", 1)),
            Map.entry("authEvidenceBundle", Map.of("bundleVersion", "1")),
            Map.entry("originalSignerReceiptDigest", "c".repeat(64))));
  }

  private static final class Fixture {
    @SuppressWarnings("unchecked")
    final StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);

    @SuppressWarnings("unchecked")
    final RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);

    final RedisScriptCatalog catalog = mock(RedisScriptCatalog.class);
    final AccountControlUiCoordination client;

    Fixture() throws Exception {
      var descriptor = AccountControlUiRegistryContract.descriptor();
      when(connection.isOpen()).thenReturn(true);
      when(connection.getOptions())
          .thenReturn(ClientOptions.builder().autoReconnect(false).build());
      when(connection.sync()).thenReturn(commands);
      when(commands.aclWhoami()).thenReturn("account_coord_app");
      when(catalog.require(AccountControlUiRegistryContract.SCRIPT_ID)).thenReturn(descriptor);
      when(commands.scriptLoad(any(byte[].class)))
          .thenAnswer(
              invocation ->
                  AccountControlUiCoordination.digest("SHA-1", invocation.getArgument(0)));
      doReturn(1L)
          .when(commands)
          .evalsha(
              anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
      doReturn(List.of(1L, 1L)).when(commands).dispatch(any(), any(), any());
      when(commands.pexpiretime(any(byte[].class))).thenReturn(EXPIRY);
      client =
          new AccountControlUiCoordination(
              () -> connection, new AcknowledgementRequirements(1, 1, 1000), catalog);
    }
  }
}
