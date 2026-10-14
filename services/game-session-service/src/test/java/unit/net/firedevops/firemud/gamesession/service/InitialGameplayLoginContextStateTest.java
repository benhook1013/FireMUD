package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult.NonSecretEvidence;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Codec/source proof with controlled verified-result doubles; no authentication or physical Redis.
 */
class InitialGameplayLoginContextStateTest {
  private static final UUID ACCOUNT = UUID.fromString("2bba6eb7-4874-47e1-af9d-caa9e2d5ef72");
  private static final InitialGameplayLoginAttemptCoordinates ORIGINAL =
      new InitialGameplayLoginAttemptCoordinates(
          UUID.randomUUID(), UUID.randomUUID(), Long.MAX_VALUE);

  @Test
  void roundTripsEveryVariantAndPreservesAllNonsecretEvidenceWithoutPrivateTokenAccess()
      throws Exception {
    var result = mock(CanonicalGameplayLoginResult.class);
    var evidence = evidence(ORIGINAL);
    when(result.evidence()).thenReturn(evidence);
    for (var value :
        List.of(
            InitialGameplayLoginContextState.pending(ORIGINAL),
            InitialGameplayLoginContextState.installed(ORIGINAL, result),
            InitialGameplayLoginContextState.closed(ORIGINAL))) {
      var decoded = InitialGameplayLoginContextState.decode(value.encode());
      assertThat(decoded.coordinates()).isEqualTo(ORIGINAL);
      assertThat(decoded.state()).isEqualTo(value.state());
      assertThat(decoded.evidence()).isEqualTo(value.evidence());
      assertThat(decoded.encode()).containsExactly(value.encode());
      assertThat(new String(value.encode(), StandardCharsets.UTF_8))
          .doesNotContain("compactJwt", "jwt", "password", "retirement");
    }
    org.mockito.Mockito.verify(result, org.mockito.Mockito.never()).compactJwtBytes();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknown",
        "duplicate",
        "trailing",
        "version",
        "attempt",
        "uuid",
        "state",
        "evidence",
        "noncanonical"
      })
  void rejectsMalformedAndOpenPayloads(String kind) {
    String valid =
        new String(
            InitialGameplayLoginContextState.pending(ORIGINAL).encode(), StandardCharsets.UTF_8);
    String malformed =
        switch (kind) {
          case "unknown" -> valid.replace("{", "{\"jwt\":\"private\",");
          case "duplicate" -> valid.replace("{", "{\"schemaVersion\":1,");
          case "trailing" -> valid + "{}";
          case "version" -> valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
          case "attempt" -> valid.replace(Long.toString(Long.MAX_VALUE), "9223372036854775808");
          case "uuid" ->
              valid.replace(
                  ORIGINAL.callerContextId().toString(),
                  ORIGINAL.callerContextId().toString().toUpperCase(java.util.Locale.ROOT));
          case "state" -> valid.replace("PENDING", "OPEN");
          case "evidence" -> valid.replace("{", "{\"accountEvidence\":{},");
          case "noncanonical" -> " " + valid;
          default -> throw new AssertionError(kind);
        };
    assertThatThrownBy(
            () ->
                InitialGameplayLoginContextState.decode(malformed.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Initial LOGIN context is invalid");
  }

  @Test
  void rejectsInvalidUtf8AndOversizedPayloads() {
    assertThatThrownBy(
            () -> InitialGameplayLoginContextState.decode(new byte[] {(byte) 0xc0, (byte) 0xaf}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                InitialGameplayLoginContextState.decode(
                    new byte[InitialGameplayLoginContextState.MAX_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void installedEvidenceCannotChangeTheOriginalRequestOrContext() throws Exception {
    var other =
        new InitialGameplayLoginAttemptCoordinates(
            UUID.randomUUID(), ORIGINAL.callerContextId(), 1);
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence()).thenReturn(evidence(other));
    assertThatThrownBy(() -> InitialGameplayLoginContextState.installed(ORIGINAL, result))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void installedDecodeRejectsChangedCheckpointScopeAndUnknownEvidence() throws Exception {
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence()).thenReturn(evidence(ORIGINAL));
    String encoded =
        new String(
            InitialGameplayLoginContextState.installed(ORIGINAL, result).encode(),
            StandardCharsets.UTF_8);
    String changed =
        encoded.replace(
            "account:auth-authority:v1:account/" + ACCOUNT,
            "account:auth-authority:v1:account/" + UUID.randomUUID());
    assertThatThrownBy(
            () -> InitialGameplayLoginContextState.decode(changed.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    String extra =
        encoded.replace("\"accountEvidence\":{", "\"accountEvidence\":{\"compactJwt\":\"secret\",");
    assertThatThrownBy(
            () -> InitialGameplayLoginContextState.decode(extra.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static NonSecretEvidence evidence(InitialGameplayLoginAttemptCoordinates coordinates)
      throws Exception {
    var streams =
        List.of(
            "account:auth-authority:v1:account/" + ACCOUNT,
            "account:auth-authority:v1:issuer/" + GameSessionAccountDelegationProfile.ISSUER);
    return new NonSecretEvidence(
        ACCOUNT,
        coordinates.requestId(),
        coordinates.callerContextId(),
        UUID.randomUUID(),
        "a".repeat(64),
        GameSessionAccountDelegationProfile.PROFILE,
        1000,
        1000,
        1200,
        "1",
        "7",
        canonical(GameSessionAccountDelegationProfile.authorityTuple(1, 1)),
        canonical(
            streams.stream()
                .map(stream -> Map.of("outboxStreamKey", stream, "outboxSequence", "0"))
                .toList()),
        canonical(List.of()));
  }

  private static ByteString canonical(Object value) throws Exception {
    return ByteString.copyFrom(
        Rfc8785CanonicalJson.canonicalizeUtf8(
            JsonMapper.builder().build().writeValueAsString(value)));
  }
}
