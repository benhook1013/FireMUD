package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.ConnectTokenRequestFingerprint;
import org.junit.jupiter.api.Test;

class ConnectTokenRequestFingerprintTest {
  private static final UUID ACCOUNT = UUID.fromString("7c9b1c20-e15c-4809-b6fb-d2ecc253460b");
  private static final UUID TENANT = UUID.fromString("e16c4af2-c203-4f1a-a0a8-ab1163234e46");

  @Test
  void fixedVersionedCanonicalVector() {
    var lookup = ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "scope", "request");
    assertThat(lookup.requestDigest())
        .isEqualTo("sha256:3cff276388fd178b00aa6b01788178709c1f8d20320e865aad418b15da577123");
    assertThat(lookup.digestVersion()).isEqualTo(1);
    assertThat(lookup.accountId()).isEqualTo(ACCOUNT);
    assertThat(lookup.tenantId()).isEqualTo(TENANT);
    assertThat(lookup.connectScopeId()).isEqualTo("scope");
    assertThat(lookup.requestId()).isEqualTo("request");
  }

  @Test
  void exactInputsDoNotNormalizeOrCollideAcrossFields() {
    var base = ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "scope", "request");
    assertThat(
            ConnectTokenRequestFingerprint.lookup(TENANT, TENANT, "scope", "request")
                .requestDigest())
        .isNotEqualTo(base.requestDigest());
    assertThat(
            ConnectTokenRequestFingerprint.lookup(ACCOUNT, ACCOUNT, "scope", "request")
                .requestDigest())
        .isNotEqualTo(base.requestDigest());
    assertThat(
            ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "Scope", "request")
                .requestDigest())
        .isNotEqualTo(base.requestDigest());
    assertThat(
            ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "scope", " request ")
                .requestDigest())
        .isNotEqualTo(base.requestDigest());
    assertThat(ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "ab", "c").requestDigest())
        .isNotEqualTo(
            ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "a", "bc").requestDigest());
    assertThat(
            ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "scope", "\u00e9")
                .requestDigest())
        .isNotEqualTo(
            ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "scope", "e\u0301")
                .requestDigest());
    assertThat(
            ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "scope", "a=\\\"\n")
                .requestDigest())
        .startsWith("sha256:");
  }

  @Test
  void rejectsInvalidIdentityAndMalformedOrOversizeUtf8() {
    assertThatThrownBy(
            () -> ConnectTokenRequestFingerprint.lookup(null, TENANT, "scope", "request"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ConnectTokenRequestFingerprint.lookup(ACCOUNT, new UUID(0, 0), "scope", "request"))
        .isInstanceOf(IllegalArgumentException.class);
    for (String invalid :
        new String[] {"", " ", "a\0b", "\uD800", "x".repeat(129), "\u00e9".repeat(65)}) {
      assertThatThrownBy(
              () -> ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "scope", invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                ConnectTokenRequestFingerprint.lookup(ACCOUNT, TENANT, "x".repeat(2049), "request"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            ConnectTokenRequestFingerprint.lookup(
                    ACCOUNT, TENANT, "x".repeat(2048), "\u00e9".repeat(64))
                .requestDigest())
        .startsWith("sha256:");
  }
}
