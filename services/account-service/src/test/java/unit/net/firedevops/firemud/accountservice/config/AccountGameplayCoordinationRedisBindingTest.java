package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisURI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayCoordinationRedisBindingTest {
  private static final String HOST = "redis-coordination.internal";
  private static final String CA_SHA256 = "a".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void strictBindingSelectsOnlyAccountCoordinationIdentityAndVerifiedTlsEndpoint()
      throws Exception {
    var parsed = AccountGameplayCoordinationRedisBinding.parseProtectedBytes(configBytes());

    assertThat(parsed.coordinationHost()).isEqualTo(HOST);
    assertThat(parsed.coordinationPort()).isEqualTo(6379);
    assertThat(parsed.tlsServerName()).isEqualTo(HOST);
    assertThat(parsed.servingCaPath()).isEqualTo(AccountGameplayCoordinationRedisBinding.CA_PATH);
    assertThat(parsed.aclUsername())
        .isEqualTo(AccountGameplayCoordinationRedisBinding.REQUIRED_ACL_IDENTITY);
    assertThat(parsed.credentialPath())
        .isEqualTo(AccountGameplayCoordinationRedisBinding.CREDENTIAL_PATH);
    assertThat(parsed.bindingDigest()).matches("[0-9a-f]{64}");
  }

  @Test
  void rejectsDisabledAmbiguousUnknownMismatchedAndPathSubstitutedBindings() throws Exception {
    String valid = new String(configBytes(), StandardCharsets.UTF_8);
    assertRejected(valid.replace("\"enabled\":true", "\"enabled\":false"));
    assertRejected(
        valid.replace(
            "\"aclUsername\":\"account_coord_app\"", "\"aclUsername\":\"gamesession_coord_app\""));
    assertRejected(
        valid.replace(
            "\"coordinationHost\":\"" + HOST + "\"",
            "\"coordinationHost\":\"other-redis.internal\""));
    assertRejected(
        valid.replace(AccountGameplayCoordinationRedisBinding.CA_PATH.toString(), "/tmp/ca.pem"));
    assertRejected(
        valid.replace(
            AccountGameplayCoordinationRedisBinding.CREDENTIAL_PATH.toString(), "/tmp/password"));
    assertRejected(
        valid.replace("\"bindingDigest\":", "\"unknown\":\"not-accepted\",\"bindingDigest\":"));
    assertRejected(valid.replace("\"enabled\":true,", "\"enabled\":true,\"enabled\":true,"));

    Map<String, Object> wrongDigest = validFields();
    wrongDigest.put("bindingDigest", "f".repeat(64));
    assertRejected(JSON.writeValueAsString(wrongDigest));
  }

  @Test
  void boundedCredentialFileTextRejectsWeakOrWhitespaceMaterial() throws Exception {
    char[] accepted =
        AccountGameplayCoordinationRedisBinding.parsePassword(
            ("A".repeat(48) + "\n").getBytes(StandardCharsets.US_ASCII));
    try {
      assertThat(accepted).hasSize(48);
    } finally {
      java.util.Arrays.fill(accepted, '\0');
    }

    assertThatThrownBy(
            () ->
                AccountGameplayCoordinationRedisBinding.parsePassword(
                    "short-password\n".getBytes(StandardCharsets.US_ASCII)))
        .isInstanceOf(java.io.IOException.class);
    assertThatThrownBy(
            () ->
                AccountGameplayCoordinationRedisBinding.parsePassword(
                    ("secret" + " ".repeat(26)).getBytes(StandardCharsets.US_ASCII)))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  void protectedFileReaderRejectsSymlinksSubstitutedPathsAndUntrustedDirectories(
      @TempDir Path temporaryDirectory) throws Exception {
    Path target = temporaryDirectory.resolve("target");
    Path symlink = temporaryDirectory.resolve("binding.json");
    Files.writeString(target, "not-a-protected-binding");
    Files.createSymbolicLink(symlink, target);

    assertThatThrownBy(
            () ->
                AccountGameplayCoordinationRedisBinding.readProtectedBytesForTest(
                    symlink, temporaryDirectory, 1024, false))
        .isInstanceOf(java.io.IOException.class);
    assertThatThrownBy(
            () ->
                AccountGameplayCoordinationRedisBinding.readProtectedBytesForTest(
                    target, temporaryDirectory, 1024, false))
        .isInstanceOf(java.io.IOException.class);

    Path substitutedDirectory = Files.createDirectory(temporaryDirectory.resolve("substitute"));
    Path substituted = Files.writeString(substitutedDirectory.resolve("binding.json"), "data");
    assertThatThrownBy(
            () ->
                AccountGameplayCoordinationRedisBinding.readProtectedBytesForTest(
                    substituted, temporaryDirectory, 1024, false))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  void clientConfigurationPinsTlsHostAndFiniteNoReconnectOptionsWithoutEmittingCredentials()
      throws Exception {
    var parsed = AccountGameplayCoordinationRedisBinding.parseProtectedBytes(configBytes());
    TrustManagerFactory trustManagers = mock(TrustManagerFactory.class);
    char[] password = "Q".repeat(48).toCharArray();
    ClientConfiguration config;
    try {
      config =
          AccountGameplayCoordinationRedisBinding.buildClientConfiguration(
              parsed,
              password,
              trustManagers,
              (endpoint, options) -> new ClientConfiguration(endpoint, options));
    } finally {
      java.util.Arrays.fill(password, '\0');
    }

    assertThat(config.endpoint().getHost()).isEqualTo(HOST);
    assertThat(config.endpoint().getPort()).isEqualTo(6379);
    assertThat(config.endpoint().isSsl()).isTrue();
    assertThat(config.endpoint().isVerifyPeer()).isTrue();
    assertThat(config.endpoint().isStartTls()).isFalse();
    assertThat(config.endpoint().getTimeout())
        .isEqualTo(AccountGameplayCoordinationRedisBinding.COMMAND_TIMEOUT);
    assertThat(config.options().isAutoReconnect()).isFalse();
    assertThat(config.options().getSocketOptions().getConnectTimeout())
        .isEqualTo(AccountGameplayCoordinationRedisBinding.CONNECT_TIMEOUT);
    assertThat(config.options().getSslOptions()).isNotNull();
    assertThat(config.options().getTimeoutOptions()).isNotNull();
    assertThat(config.toString()).doesNotContain("Q".repeat(48));
  }

  private static byte[] configBytes() throws Exception {
    return JSON.writeValueAsBytes(validFields());
  }

  private static Map<String, Object> validFields() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("version", "account-coordination-redis-binding/v1");
    values.put("enabled", true);
    values.put("coordinationHost", HOST);
    values.put("coordinationPort", 6379);
    values.put("tlsServerName", HOST);
    values.put("servingCaPath", AccountGameplayCoordinationRedisBinding.CA_PATH.toString());
    values.put("servingCaSha256", CA_SHA256);
    values.put("aclUsername", AccountGameplayCoordinationRedisBinding.REQUIRED_ACL_IDENTITY);
    values.put(
        "credentialPath", AccountGameplayCoordinationRedisBinding.CREDENTIAL_PATH.toString());
    values.put(
        "bindingDigest",
        AccountGameplayCoordinationRedisBinding.computeBindingDigest(
            HOST,
            6379,
            HOST,
            AccountGameplayCoordinationRedisBinding.CA_PATH.toString(),
            CA_SHA256,
            AccountGameplayCoordinationRedisBinding.REQUIRED_ACL_IDENTITY,
            AccountGameplayCoordinationRedisBinding.CREDENTIAL_PATH.toString()));
    return values;
  }

  private static void assertRejected(String json) {
    assertThatThrownBy(
            () ->
                AccountGameplayCoordinationRedisBinding.parseProtectedBytes(
                    json.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(RuntimeException.class);
  }

  private record ClientConfiguration(RedisURI endpoint, ClientOptions options) {
    @Override
    public String toString() {
      return "ClientConfiguration[credentials redacted]";
    }
  }
}
