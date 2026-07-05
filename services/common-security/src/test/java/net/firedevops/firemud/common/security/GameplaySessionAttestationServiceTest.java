package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GameplaySessionAttestationServiceTest {

  private final JwtUtil jwtUtil;
  private final GameplaySessionAttestationService service;

  GameplaySessionAttestationServiceTest() {
    jwtUtil = new JwtUtil("test-secret-123456789012345678901234567890", 3_600_000L);
    service = new GameplaySessionAttestationService(jwtUtil);
  }

  @Test
  void requireGameplaySessionMatchAllowsOmittedOptionalDimensions() {
    String token =
        service.issueGameplaySessionAttestation(
            "22", "41", "7", "123", "1", "1021", "demo", "production", "17", "SHARED");

    assertDoesNotThrow(
        () -> service.requireGameplaySessionMatch(token, "22", null, null, "123", "1", "1021"));
  }

  @Test
  void requireGameplaySessionMatchStillRejectsProvidedMismatchedDimensions() {
    String token =
        service.issueGameplaySessionAttestation(
            "22", "41", "7", "123", "1", "1021", "demo", "production", "17", "SHARED");

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () -> service.requireGameplaySessionMatch(token, "22", "41", "99", "123", "1", "1021"));

    assertEquals("SESSION_ATTESTATION_MISMATCH", ex.getCode());
    assertEquals("Gameplay session attestation does not match accountId", ex.getMessage());
  }

  @Test
  void requireGameplaySessionMatchAllowsAlphanumericRoomInstanceId() {
    String token =
        service.issueGameplaySessionAttestation(
            "22", "41", "7", "123", "1", "R-1021", "demo", "production", "17", "SHARED");

    assertDoesNotThrow(
        () -> service.requireGameplaySessionMatch(token, "22", "41", "7", "123", "1", "R-1021"));
  }

  @Test
  void requireGameplaySessionMatchRejectsMismatchedAlphanumericRoomInstanceId() {
    String token =
        service.issueGameplaySessionAttestation(
            "22", "41", "7", "123", "1", "R-1021", "demo", "production", "17", "SHARED");

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () ->
                service.requireGameplaySessionMatch(token, "22", "41", "7", "123", "1", "R-2045"));

    assertEquals("SESSION_ATTESTATION_MISMATCH", ex.getCode());
    assertEquals("Gameplay session attestation does not match roomInstanceId", ex.getMessage());
  }

  @Test
  void requireGameplaySessionMatchRejectsMismatchedAttestedRoutingScope() {
    String token =
        service.issueGameplaySessionAttestation(
            "22", "41", "7", "123", "1", "1021", "demo", "production", "17", "SHARED");

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () ->
                service.requireGameplaySessionMatch(
                    token,
                    "22",
                    "41",
                    "7",
                    "123",
                    "1",
                    "1021",
                    "demo",
                    "production",
                    "17",
                    "ISOLATED"));

    assertEquals("SESSION_ATTESTATION_MISMATCH", ex.getCode());
    assertEquals("Gameplay session attestation does not match playableStateScope", ex.getMessage());
  }

  @Test
  void requireGameplaySessionMatchRejectsZeroTenantIdClaim() {
    String token =
        service.issueGameplaySessionAttestation(
            "0", "41", "7", "123", "1", "1021", "demo", "production", "17", "SHARED");

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () -> service.requireGameplaySessionMatch(token, "0", "41", "7", "123", "1", "1021"));

    assertEquals("SESSION_ATTESTATION_INVALID", ex.getCode());
    assertEquals("Invalid claim: tenantId", ex.getMessage());
  }

  @Test
  void requireGameplaySessionMatchRejectsZeroPointerVersionClaim() {
    String token =
        service.issueGameplaySessionAttestation(
            "22", "41", "7", "123", "1", "1021", "demo", "production", "0", "SHARED");

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () -> service.requireGameplaySessionMatch(token, "22", "41", "7", "123", "1", "1021"));

    assertEquals("SESSION_ATTESTATION_INVALID", ex.getCode());
    assertEquals("Invalid claim: pointerVersion", ex.getMessage());
  }

  @Test
  void requireGameplaySessionMatchRejectsNonNumericClaimId() {
    String token =
        jwtUtil.generateToken(
            "gameplay-session:41",
            Map.ofEntries(
                Map.entry("attestationType", "GAMEPLAY_SESSION"),
                Map.entry("tenantId", "22"),
                Map.entry("sessionId", "41"),
                Map.entry("accountId", "7"),
                Map.entry("characterId", "abc"),
                Map.entry("gameInstanceId", "1"),
                Map.entry("roomInstanceId", "1021"),
                Map.entry("worldSlug", "demo"),
                Map.entry("realmSlug", "production"),
                Map.entry("pointerVersion", "17"),
                Map.entry("playableStateScope", "SHARED")));

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () -> service.requireGameplaySessionMatch(token, "22", "41", "7", "123", "1", "1021"));

    assertEquals("SESSION_ATTESTATION_INVALID", ex.getCode());
    assertEquals("Malformed claim: characterId", ex.getMessage());
  }

  @Test
  void requireGameplaySessionMatchRejectsNonPositiveExpectedAccountId() {
    String token =
        service.issueGameplaySessionAttestation(
            "22", "41", "7", "123", "1", "1021", "demo", "production", "17", "SHARED");

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () -> service.requireGameplaySessionMatch(token, "22", null, "0", "123", "1", "1021"));

    assertEquals("SESSION_ATTESTATION_INVALID", ex.getCode());
    assertEquals("Invalid claim: accountId", ex.getMessage());
  }

  @Test
  void requireGameplayOrProbeMatchAcceptsProbeAttestation() {
    String token = service.issueInternalProbeAttestation("22", "1", "1021");

    assertDoesNotThrow(() -> service.requireGameplayOrProbeMatch(token, "22", "1", "1021"));
  }

  @Test
  void requireGameplayOrProbeMatchRejectsMalformedTenantId() {
    String token =
        jwtUtil.generateToken(
            "gameplay-probe:22:1:1021",
            Map.ofEntries(
                Map.entry("attestationType", "INTERNAL_PROBE"),
                Map.entry("tenantId", "abc"),
                Map.entry("sessionId", "0"),
                Map.entry("accountId", "0"),
                Map.entry("characterId", "0"),
                Map.entry("gameInstanceId", "1"),
                Map.entry("roomInstanceId", "1021"),
                Map.entry("worldSlug", ""),
                Map.entry("realmSlug", ""),
                Map.entry("pointerVersion", ""),
                Map.entry("playableStateScope", "")));

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () -> service.requireGameplayOrProbeMatch(token, "22", "1", "1021"));

    assertEquals("SESSION_ATTESTATION_INVALID", ex.getCode());
    assertEquals("Malformed claim: tenantId", ex.getMessage());
  }

  @Test
  void requireGameplayOrProbeMatchRejectsNonPositiveExpectedGameInstanceId() {
    String token = service.issueInternalProbeAttestation("22", "1", "1021");

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () -> service.requireGameplayOrProbeMatch(token, "22", "0", "1021"));

    assertEquals("SESSION_ATTESTATION_INVALID", ex.getCode());
    assertEquals("Invalid claim: gameInstanceId", ex.getMessage());
  }

  @Test
  void requireGameplayOrProbeMatchAcceptsAlphanumericRoomInstanceId() {
    String token = service.issueInternalProbeAttestation("22", "1", "R-1021");

    assertDoesNotThrow(() -> service.requireGameplayOrProbeMatch(token, "22", "1", "R-1021"));
  }

  @Test
  void requireGameplayOrProbeMatchRejectsMismatchedRoomInstanceId() {
    String token = service.issueInternalProbeAttestation("22", "1", "R-1021");

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () -> service.requireGameplayOrProbeMatch(token, "22", "1", "abc"));

    assertEquals("SESSION_ATTESTATION_MISMATCH", ex.getCode());
    assertEquals("Gameplay session attestation does not match roomInstanceId", ex.getMessage());
  }

  @Test
  void requireAdmittedRoutingBundleAcceptsGameplayAttestationWithRoutingClaims() {
    GameplaySessionAttestationClaims claims =
        service.requireValid(
            service.issueGameplaySessionAttestation(
                "22", "41", "7", "123", "1", "1021", "demo", "production", "17", "SHARED"));

    assertDoesNotThrow(() -> service.requireAdmittedRoutingBundle(claims));
  }

  @Test
  void requireAdmittedRoutingBundleRejectsGameplayAttestationMissingPointerVersion() {
    GameplaySessionAttestationClaims claims =
        service.requireValid(
            service.issueGameplaySessionAttestation(
                "22", "41", "7", "123", "1", "1021", "demo", "production", null, "SHARED"));

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () -> service.requireAdmittedRoutingBundle(claims));

    assertEquals("SESSION_ATTESTATION_INVALID", ex.getCode());
    assertEquals("Gameplay session attestation is missing pointerVersion", ex.getMessage());
  }

  @Test
  void requireValidUsesCanonicalIterableClaimParsing() {
    String token =
        jwtUtil.generateToken(
            "gameplay-session:41",
            Map.ofEntries(
                Map.entry("attestationType", "GAMEPLAY_SESSION"),
                Map.entry("tenantId", List.of(" ", "22")),
                Map.entry("sessionId", "41"),
                Map.entry("accountId", "7"),
                Map.entry("characterId", "123"),
                Map.entry("gameInstanceId", "1"),
                Map.entry("roomInstanceId", List.of("", "1021")),
                Map.entry("worldSlug", List.of(" ", "demo")),
                Map.entry("realmSlug", "production"),
                Map.entry("pointerVersion", "17"),
                Map.entry("playableStateScope", "SHARED")));

    GameplaySessionAttestationClaims claims = service.requireValid(token);

    assertEquals("22", claims.tenantId());
    assertEquals("1021", claims.roomInstanceId());
    assertEquals("demo", claims.worldSlug());
  }
}
