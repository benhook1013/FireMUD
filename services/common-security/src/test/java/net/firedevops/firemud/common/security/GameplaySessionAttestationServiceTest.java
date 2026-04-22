package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    String token = service.issueGameplaySessionAttestation("22", "41", "7", "123", "1", "1021");

    assertDoesNotThrow(
        () -> service.requireGameplaySessionMatch(token, "22", null, null, "123", "1", "1021"));
  }

  @Test
  void requireGameplaySessionMatchStillRejectsProvidedMismatchedDimensions() {
    String token = service.issueGameplaySessionAttestation("22", "41", "7", "123", "1", "1021");

    GameplaySessionAttestationException ex =
        assertThrows(
            GameplaySessionAttestationException.class,
            () -> service.requireGameplaySessionMatch(token, "22", "41", "99", "123", "1", "1021"));

    assertEquals("SESSION_ATTESTATION_MISMATCH", ex.getCode());
    assertEquals("Gameplay session attestation does not match accountId", ex.getMessage());
  }

  @Test
  void requireGameplayOrProbeMatchAcceptsProbeAttestation() {
    String token = service.issueInternalProbeAttestation("22", "1", "1021");

    assertDoesNotThrow(() -> service.requireGameplayOrProbeMatch(token, "22", "1", "1021"));
  }
}
