package net.firedevops.firemud.common.security;

import io.jsonwebtoken.Claims;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Issues and validates signed gameplay-session attestations for delegated gameplay RPCs. */
public class GameplaySessionAttestationService {
  public static final String GAMEPLAY_SESSION = "GAMEPLAY_SESSION";
  public static final String INTERNAL_PROBE = "INTERNAL_PROBE";

  private final JwtUtil jwtUtil;

  public GameplaySessionAttestationService(JwtUtil jwtUtil) {
    this.jwtUtil = jwtUtil;
  }

  public String issueGameplaySessionAttestation(
      String tenantId,
      String sessionId,
      String accountId,
      String characterId,
      String gameInstanceId,
      String roomInstanceId) {
    requireText(tenantId, "tenantId");
    requireText(sessionId, "sessionId");
    requireText(accountId, "accountId");
    requireText(characterId, "characterId");
    requireText(gameInstanceId, "gameInstanceId");
    return jwtUtil.generateToken(
        "gameplay-session:" + sessionId,
        Map.of(
            "attestationType", GAMEPLAY_SESSION,
            "tenantId", tenantId,
            "sessionId", sessionId,
            "accountId", accountId,
            "characterId", characterId,
            "gameInstanceId", gameInstanceId,
            "roomInstanceId", blankToEmpty(roomInstanceId)));
  }

  public String issueInternalProbeAttestation(
      String tenantId, String gameInstanceId, String roomInstanceId) {
    requireText(tenantId, "tenantId");
    requireText(gameInstanceId, "gameInstanceId");
    requireText(roomInstanceId, "roomInstanceId");
    return jwtUtil.generateToken(
        "gameplay-probe:" + tenantId + ":" + gameInstanceId + ":" + roomInstanceId,
        Map.of(
            "attestationType", INTERNAL_PROBE,
            "tenantId", tenantId,
            "sessionId", "0",
            "accountId", "0",
            "characterId", "0",
            "gameInstanceId", gameInstanceId,
            "roomInstanceId", roomInstanceId));
  }

  public GameplaySessionAttestationClaims requireValid(String token) {
    if (!StringUtils.hasText(token)) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_REQUIRED", "Gameplay session attestation is required");
    }
    Claims claims;
    try {
      claims = jwtUtil.parseToken(token).getPayload();
    } catch (RuntimeException ex) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay session attestation is invalid", ex);
    }
    String attestationType = requireClaim(claims, "attestationType");
    String tenantId = requireClaim(claims, "tenantId");
    String sessionId = requireClaim(claims, "sessionId");
    String accountId = requireClaim(claims, "accountId");
    String characterId = requireClaim(claims, "characterId");
    String gameInstanceId = requireClaim(claims, "gameInstanceId");
    String roomInstanceId = claimText(claims.get("roomInstanceId"));
    if (!GAMEPLAY_SESSION.equals(attestationType) && !INTERNAL_PROBE.equals(attestationType)) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay session attestation type is unsupported");
    }
    return new GameplaySessionAttestationClaims(
        attestationType,
        tenantId,
        sessionId,
        accountId,
        characterId,
        gameInstanceId,
        roomInstanceId);
  }

  public void requireGameplaySessionMatch(
      String token,
      String tenantId,
      String sessionId,
      String accountId,
      String characterId,
      String gameInstanceId,
      String roomInstanceId) {
    GameplaySessionAttestationClaims claims = requireValid(token);
    if (!GAMEPLAY_SESSION.equals(claims.attestationType())) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay session attestation type is unsupported");
    }
    requireEquals(claims.tenantId(), tenantId, "tenantId");
    requireOptionalEquals(claims.sessionId(), sessionId, "sessionId");
    requireOptionalEquals(claims.accountId(), accountId, "accountId");
    requireOptionalEquals(claims.characterId(), characterId, "characterId");
    requireOptionalEquals(claims.gameInstanceId(), gameInstanceId, "gameInstanceId");
    requireOptionalEquals(claims.roomInstanceId(), roomInstanceId, "roomInstanceId");
  }

  public void requireGameplayOrProbeMatch(
      String token, String tenantId, String gameInstanceId, String roomInstanceId) {
    GameplaySessionAttestationClaims claims = requireValid(token);
    requireEquals(claims.tenantId(), tenantId, "tenantId");
    requireEquals(claims.gameInstanceId(), gameInstanceId, "gameInstanceId");
    requireOptionalEquals(claims.roomInstanceId(), roomInstanceId, "roomInstanceId");
  }

  private void requireEquals(String actual, String expected, String fieldName) {
    if (!blankToEmpty(actual).equals(blankToEmpty(expected))) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_MISMATCH",
          "Gameplay session attestation does not match " + fieldName);
    }
  }

  private void requireOptionalEquals(String actual, String expected, String fieldName) {
    if (StringUtils.hasText(expected)) {
      requireEquals(actual, expected, fieldName);
    }
  }

  private String requireClaim(Claims claims, String name) {
    String value = claimText(claims.get(name));
    if (!StringUtils.hasText(value)) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay session attestation is missing " + name);
    }
    return value;
  }

  private String claimText(Object value) {
    return value == null ? "" : value.toString().trim();
  }

  private void requireText(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }

  private String blankToEmpty(String value) {
    return value == null ? "" : value.trim();
  }
}
