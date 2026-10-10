package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AccountControlUiRegistryReaderTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TOKEN_HASH = "a".repeat(64);

  @Test
  void acceptsOnlyTheExactCanonicalActiveProfileRecord() throws Exception {
    byte[] bytes = canonicalRecord();

    var parsed = AccountControlUiRegistryReader.parseActiveRecord(bytes, TOKEN_HASH);

    assertThat(parsed).containsEntry("tokenHash", TOKEN_HASH).containsEntry("state", "active");
  }

  @Test
  void rejectsUnknownFieldsAndNonCanonicalBytes() throws Exception {
    Map<String, Object> extended = new LinkedHashMap<>(record());
    extended.put("unexpected", true);
    byte[] withUnknown = canonicalBytes(extended);

    assertThatThrownBy(
            () -> AccountControlUiRegistryReader.parseActiveRecord(withUnknown, TOKEN_HASH))
        .isInstanceOf(IllegalStateException.class);

    String canonical = new String(canonicalRecord(), StandardCharsets.UTF_8);
    byte[] nonCanonical = (" " + canonical).getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () -> AccountControlUiRegistryReader.parseActiveRecord(nonCanonical, TOKEN_HASH))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void readsOnlyAfterLiveAclIdentityAndExactExpiryReadback() throws Exception {
    byte[] active = canonicalRecord();
    long deadline = 1_800_000_000_000L;
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    ClientOptions options = mock(ClientOptions.class);
    @SuppressWarnings("unchecked")
    RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(options);
    when(options.isAutoReconnect()).thenReturn(false);
    when(connection.sync()).thenReturn(commands);
    when(commands.aclWhoami()).thenReturn(AccountControlUiRegistryReader.REQUIRED_ACL_IDENTITY);
    when(commands.get(any(byte[].class))).thenReturn(active);
    when(commands.pexpiretime(any(byte[].class))).thenReturn(deadline);

    AccountCoordinationPinnedConnectionProvider provider = () -> connection;
    byte[] observed = new AccountControlUiRegistryReader(provider).readActive(TOKEN_HASH);

    assertThat(observed).isEqualTo(active).isNotSameAs(active);
    verify(commands).aclWhoami();
    verify(commands).pexpiretime(any(byte[].class));
  }

  @Test
  void deniesWhenPhysicalExpiryDiffersFromExactTokenExpiry() throws Exception {
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    ClientOptions options = mock(ClientOptions.class);
    @SuppressWarnings("unchecked")
    RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(options);
    when(options.isAutoReconnect()).thenReturn(false);
    when(connection.sync()).thenReturn(commands);
    when(commands.aclWhoami()).thenReturn(AccountControlUiRegistryReader.REQUIRED_ACL_IDENTITY);
    when(commands.get(any(byte[].class))).thenReturn(canonicalRecord());
    when(commands.pexpiretime(any(byte[].class))).thenReturn(1_800_000_300_000L);

    AccountControlUiRegistryReader reader = new AccountControlUiRegistryReader(() -> connection);

    assertThatThrownBy(() -> reader.readActive(TOKEN_HASH))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void computesExactTokenExpiryWithCheckedArithmetic() {
    assertThat(AccountControlUiRegistryReader.checkedExpiryMillis(1_800_000_000L))
        .isEqualTo(1_800_000_000_000L);
    assertThatThrownBy(() -> AccountControlUiRegistryReader.checkedExpiryMillis(Long.MAX_VALUE))
        .isInstanceOf(IllegalStateException.class);
  }

  private static Map<String, Object> record() {
    Map<String, Object> tuple =
        Map.of(
            "issuerAuthGeneration", 1,
            "accountAuthorityGeneration", 1,
            "tenantAuthorityGeneration", Map.of("20000000-0000-4000-8000-000000000002", 1),
            "membershipAuthorityGeneration", Map.of("20000000-0000-4000-8000-000000000002", 1),
            "privateRealmGrantVersions", List.of());
    return Map.ofEntries(
        Map.entry("schemaVersion", 1),
        Map.entry("registryVersion", 2),
        Map.entry("tokenHash", TOKEN_HASH),
        Map.entry("kid", "key-1"),
        Map.entry("signerGeneration", "1"),
        Map.entry("issuer", "firemud-account-service"),
        Map.entry("profile", "control-ui"),
        Map.entry("type", "control-ui"),
        Map.entry("audience", "control-ui"),
        Map.entry("accountId", "10000000-0000-4000-8000-000000000001"),
        Map.entry("jti", UUID.fromString("30000000-0000-4000-8000-000000000003").toString()),
        Map.entry("iat", 1_799_999_700),
        Map.entry("nbf", 1_799_999_700),
        Map.entry("exp", 1_800_000_000),
        Map.entry("tokenGeneration", 1),
        Map.entry("authorityTuple", tuple),
        Map.entry("membershipVersion", Map.of("20000000-0000-4000-8000-000000000002", 1)),
        Map.entry("issuanceFence", 1),
        Map.entry("operationId", "40000000-0000-4000-8000-000000000004"),
        Map.entry("requestId", "50000000-0000-4000-8000-000000000005"),
        Map.entry("requestDigest", "b".repeat(64)),
        Map.entry("state", "active"),
        Map.entry(
            "authoritySourceVersions", Map.of("ACCOUNT:10000000-0000-4000-8000-000000000001", 1)),
        Map.entry(
            "authEvidenceBundle",
            Map.of(
                "bundleVersion",
                "1",
                "sourceVersion",
                "1",
                "sourceFence",
                "1",
                "linearization",
                "1",
                "canonicalSha256",
                "c".repeat(64))),
        Map.entry("originalSignerReceiptDigest", "d".repeat(64)));
  }

  private static byte[] canonicalRecord() throws Exception {
    return canonicalBytes(record());
  }

  private static byte[] canonicalBytes(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }
}
