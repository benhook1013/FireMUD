package net.firedevops.firemud.accountservice.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseSubjectKeys.DigestKey;
import org.junit.jupiter.api.Test;

class AccountCredentialAbuseSubjectKeysTest {
  @Test
  void matchesIndependentlyFramedFullLengthPythonVectors() {
    // Synthetic key bytes 0..31. Reference literals use Python stdlib hmac/hashlib and byte
    // framing,
    // not this Java implementation; no production key or address is represented.
    try (var key = key("test-key")) {
      var keys = new AccountCredentialAbuseSubjectKeys("login-v1", key);
      var source = keys.source("192.0.2.1", 7);
      assertThat(source.redisKey())
          .isEqualTo(
              "ratelimit:account-credential:v1:subject:login-v1:source:rsh-v2-test-key-"
                  + "8df362b0bfc2e35d45ee873dcb7be9e171af1763f7f4bc43cb3ea4a071e0f90d:7");
      assertThat(source.collisionWitness())
          .isEqualTo(
              "wsh-v2-test-key-310198aa1cdb83bf61131551c69b74df8e50acebbbf5163e235a81baad2fd007");
      var candidate = keys.candidate("  PLAYER@example.TEST  ", 7);
      assertThat(candidate.redisKey())
          .isEqualTo(
              "ratelimit:account-credential:v1:subject:login-v1:candidate:rsh-v2-test-key-"
                  + "118218b9279a4dcd1bef286be9872858fa143956f2294d421ebf437ea8e94776:7");
      assertThat(candidate.collisionWitness())
          .isEqualTo(
              "wsh-v2-test-key-14ccd38a948cb02b5a6b042be413ba9c2297ecd31f6a9823f7aff433b6d840cd");
    }
  }

  @Test
  void preservesNetworkAndCandidateEquivalenceWithoutPartitioningByTransportOrMode() {
    try (var key = key("test-key")) {
      var keys = new AccountCredentialAbuseSubjectKeys("login-v1", key);
      assertThat(keys.source("::ffff:192.0.2.1", 7).redisKey())
          .isEqualTo(keys.source("192.0.2.1", 7).redisKey());
      assertThat(keys.source("2001:DB8::1", 7).redisKey())
          .isEqualTo(keys.source("2001:db8:0:0:0:0:0:1", 7).redisKey());
      assertThat(keys.candidate("  PLAYER@example.TEST  ", 7).redisKey())
          .isEqualTo(keys.candidate("player@example.test", 7).redisKey());
    }
  }

  @Test
  void separatesKeysSubjectsScopesKindsAndWindows() {
    try (var key = key("test-key");
        var rotated = key("rotated-key")) {
      var keys = new AccountCredentialAbuseSubjectKeys("login-v1", key);
      String original = keys.source("192.0.2.1", 7).redisKey();
      assertThat(keys.source("192.0.2.2", 7).redisKey()).isNotEqualTo(original);
      assertThat(keys.source("192.0.2.1", 8).redisKey()).isNotEqualTo(original);
      assertThat(keys.candidate("192.0.2.1", 7).redisKey()).isNotEqualTo(original);
      assertThat(
              new AccountCredentialAbuseSubjectKeys("login-v2", key)
                  .source("192.0.2.1", 7)
                  .redisKey())
          .isNotEqualTo(original);
      assertThat(
              new AccountCredentialAbuseSubjectKeys("login-v1", rotated)
                  .source("192.0.2.1", 7)
                  .redisKey())
          .isNotEqualTo(original);
      byte[] changed = material();
      changed[0] ^= 1;
      try (var changedKey = new DigestKey("test-key", changed)) {
        assertThat(
                new AccountCredentialAbuseSubjectKeys("login-v1", changedKey)
                    .source("192.0.2.1", 7)
                    .redisKey())
            .isNotEqualTo(original);
      } finally {
        Arrays.fill(changed, (byte) 0);
      }
    }
  }

  @Test
  void doesNotExposeRawSubjectsInKeysWitnessesDiagnosticsOrErrors() {
    try (var key = key("test-key")) {
      var keys = new AccountCredentialAbuseSubjectKeys("login-v1", key);
      var source = keys.source("192.0.2.1", 7);
      var candidate = keys.candidate("player@example.test", 7);
      assertThat(
              List.of(
                  source.redisKey(),
                  source.collisionWitness(),
                  candidate.redisKey(),
                  candidate.collisionWitness()))
          .allSatisfy(
              value -> assertThat(value).doesNotContain("192.0.2.1", "player@example.test"));
      assertThat(keys.toString()).isEqualTo("AccountCredentialAbuseSubjectKeys[redacted]");
      assertThat(source.toString()).isEqualTo("AccountCredentialAbuseSubject[redacted]");
      assertThat(key.toString()).isEqualTo("AccountCredentialAbuseDigestKey[redacted]");
      assertThatThrownBy(() -> keys.source("private-host.example", 7))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Invalid credential-abuse subject input");
    }
  }

  @Test
  void rejectsAmbiguousAddressAndIdentifierShapesBeforeMapping() {
    try (var key = key("test-key")) {
      var keys = new AccountCredentialAbuseSubjectKeys("login-v1", key);
      for (String malformed :
          List.of(
              "localhost",
              "192.0.2.1:443",
              "[2001:db8::1]",
              "fe80::1%eth0",
              " 192.0.2.1",
              "192.0.2.1 ",
              "")) {
        assertThatThrownBy(() -> keys.source(malformed, 7))
            .isInstanceOf(IllegalArgumentException.class);
      }
      assertThatThrownBy(() -> new AccountCredentialAbuseSubjectKeys("tenant:invented", key))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> keys.candidate("player\n@example.test", 7))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> keys.candidate("player\ud800@example.test", 7))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> keys.source("192.0.2.1", -1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> keys.pressure(Long.MAX_VALUE))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void keepsCoarsePressureSeparateAndRefusesDestroyedDigestMaterial() {
    var key = key("test-key");
    try {
      var keys = new AccountCredentialAbuseSubjectKeys("login-v1", key);
      assertThat(keys.pressure(7))
          .isEqualTo("ratelimit:account-credential:v1:pressure:login-v1:test-key:7");
      key.close();
      assertThatThrownBy(() -> keys.candidate("player@example.test", 7))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Credential-abuse key is unavailable");
      assertThatThrownBy(() -> keys.pressure(7))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Credential-abuse key is unavailable");
    } finally {
      key.close();
    }
  }

  @Test
  void ownsItsKeyCopyAndClearsOnlyThatCopyOnClose() {
    byte[] callerOwned = material();
    try (var key = new DigestKey("test-key", callerOwned);
        var reference = key("test-key")) {
      var keys = new AccountCredentialAbuseSubjectKeys("login-v1", key);
      var referenceKeys = new AccountCredentialAbuseSubjectKeys("login-v1", reference);
      callerOwned[0] ^= 1;
      assertThat(keys.candidate("player@example.test", 7).redisKey())
          .isEqualTo(referenceKeys.candidate("player@example.test", 7).redisKey());
      byte[] retained = callerOwned.clone();
      key.close();
      key.close();
      assertThat(callerOwned).containsExactly(retained);
      assertThatThrownBy(() -> keys.source("192.0.2.1", 7))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      Arrays.fill(callerOwned, (byte) 0);
    }
  }

  @Test
  void validatesEveryNonSubjectBoundaryWithoutDefaultKeysOrFallbackSubjects() {
    assertThatThrownBy(() -> new DigestKey("test-key", new byte[31]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DigestKey("test-key", null))
        .isInstanceOf(IllegalArgumentException.class);
    for (String invalid : List.of("", "UPPER", "a:b", "k".repeat(65))) {
      assertThatThrownBy(() -> key(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    try (var key = key("test-key")) {
      var keys = new AccountCredentialAbuseSubjectKeys("login-v1", key);
      assertThatThrownBy(() -> keys.source(null, 0)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> keys.candidate(null, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> keys.candidate("  ", 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> keys.candidate("a".repeat(255), 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> keys.candidate("player@example.test", Long.MAX_VALUE))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static DigestKey key(String id) {
    byte[] material = material();
    try {
      return new DigestKey(id, material);
    } finally {
      Arrays.fill(material, (byte) 0);
    }
  }

  private static byte[] material() {
    byte[] material = new byte[32];
    for (int index = 0; index < material.length; index++) material[index] = (byte) index;
    return material;
  }
}
