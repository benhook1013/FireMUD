package net.firedevops.firemud.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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
      String roomInstanceId,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String playableStateScope) {
    String normalizedRoomInstanceId = requireOptionalCanonicalRuntimeRoomId(roomInstanceId);
    requireText(tenantId, "tenantId");
    requireText(sessionId, "sessionId");
    requireText(accountId, "accountId");
    requireText(characterId, "characterId");
    requireText(gameInstanceId, "gameInstanceId");
    requireText(worldSlug, "worldSlug");
    requireText(realmSlug, "realmSlug");
    requireText(pointerVersion, "pointerVersion");
    return jwtUtil.generateToken(
        "gameplay-session:" + sessionId,
        Map.ofEntries(
            Map.entry("attestationType", GAMEPLAY_SESSION),
            Map.entry("tenantId", tenantId),
            Map.entry("sessionId", sessionId),
            Map.entry("accountId", accountId),
            Map.entry("characterId", characterId),
            Map.entry("gameInstanceId", gameInstanceId),
            Map.entry("roomInstanceId", normalizedRoomInstanceId),
            Map.entry("worldSlug", blankToEmpty(worldSlug)),
            Map.entry("realmSlug", blankToEmpty(realmSlug)),
            Map.entry("pointerVersion", blankToEmpty(pointerVersion)),
            Map.entry("playableStateScope", blankToEmpty(playableStateScope))));
  }

  public String issueInternalProbeAttestation(
      String tenantId, String gameInstanceId, String roomInstanceId) {
    String normalizedRoomInstanceId =
        RequestIdValidation.requireCanonicalRuntimeRoomId(roomInstanceId, "roomInstanceId");
    requireText(tenantId, "tenantId");
    requireText(gameInstanceId, "gameInstanceId");
    return jwtUtil.generateToken(
        "gameplay-probe:" + tenantId + ":" + gameInstanceId + ":" + normalizedRoomInstanceId,
        Map.ofEntries(
            Map.entry("attestationType", INTERNAL_PROBE),
            Map.entry("tenantId", tenantId),
            Map.entry("sessionId", "0"),
            Map.entry("accountId", "0"),
            Map.entry("characterId", "0"),
            Map.entry("gameInstanceId", gameInstanceId),
            Map.entry("roomInstanceId", normalizedRoomInstanceId),
            Map.entry("worldSlug", ""),
            Map.entry("realmSlug", ""),
            Map.entry("pointerVersion", ""),
            Map.entry("playableStateScope", "")));
  }

  public GameplaySessionAttestationClaims requireValid(String token) {
    if (!StringUtils.hasText(token)) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_REQUIRED", "Gameplay session attestation is required");
    }
    Claims claims;
    try {
      claims = jwtUtil.parseToken(token).getPayload();
    } catch (IllegalArgumentException | JwtException ex) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay session attestation is invalid", ex);
    }
    String attestationType = requireClaim(claims, "attestationType");
    String tenantId = requireClaim(claims, "tenantId");
    String sessionId = requireClaim(claims, "sessionId");
    String accountId = requireClaim(claims, "accountId");
    String characterId = requireClaim(claims, "characterId");
    String gameInstanceId = requireClaim(claims, "gameInstanceId");
    String roomInstanceId =
        requireOptionalCanonicalRuntimeRoomClaim(claimText(claims.get("roomInstanceId")));
    String worldSlug = claimText(claims.get("worldSlug"));
    String realmSlug = claimText(claims.get("realmSlug"));
    String pointerVersion = claimText(claims.get("pointerVersion"));
    String playableStateScope = claimText(claims.get("playableStateScope"));
    if (!GAMEPLAY_SESSION.equals(attestationType) && !INTERNAL_PROBE.equals(attestationType)) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay session attestation type is unsupported");
    }
    requireSubjectMatchesClaims(
        attestationType, claims, tenantId, sessionId, gameInstanceId, roomInstanceId);
    return new GameplaySessionAttestationClaims(
        attestationType,
        tenantId,
        sessionId,
        accountId,
        characterId,
        gameInstanceId,
        roomInstanceId,
        worldSlug,
        realmSlug,
        pointerVersion,
        playableStateScope);
  }

  public void requireAdmittedRoutingBundle(GameplaySessionAttestationClaims claims) {
    requireClaimsType(claims, GAMEPLAY_SESSION);
    requirePresent(claims.worldSlug(), "worldSlug");
    requirePresent(claims.realmSlug(), "realmSlug");
    requirePresent(claims.pointerVersion(), "pointerVersion");
  }

  public void requireGameplaySessionMatch(
      String token,
      String tenantId,
      String sessionId,
      String accountId,
      String characterId,
      String gameInstanceId,
      String roomInstanceId) {
    requireGameplaySessionMatch(
        token,
        tenantId,
        sessionId,
        accountId,
        characterId,
        gameInstanceId,
        roomInstanceId,
        null,
        null,
        null,
        null);
  }

  public void requireGameplaySessionMatch(
      String token,
      String tenantId,
      String sessionId,
      String accountId,
      String characterId,
      String gameInstanceId,
      String roomInstanceId,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String playableStateScope) {
    GameplaySessionAttestationClaims claims = requireValid(token);
    if (!GAMEPLAY_SESSION.equals(claims.attestationType())) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay session attestation type is unsupported");
    }
    requirePositiveId(claims.tenantId(), "tenantId");
    requirePositiveId(claims.sessionId(), "sessionId");
    requirePositiveId(claims.accountId(), "accountId");
    requirePositiveId(claims.characterId(), "characterId");
    requirePositiveId(claims.gameInstanceId(), "gameInstanceId");
    requirePositiveId(claims.pointerVersion(), "pointerVersion");
    requirePositiveEquals(claims.tenantId(), tenantId, "tenantId");
    requireOptionalPositiveEquals(claims.sessionId(), sessionId, "sessionId");
    requireOptionalPositiveEquals(claims.accountId(), accountId, "accountId");
    requireOptionalPositiveEquals(claims.characterId(), characterId, "characterId");
    requireOptionalPositiveEquals(claims.gameInstanceId(), gameInstanceId, "gameInstanceId");
    // roomInstanceId remains a routed room identifier string; keep text equality here.
    requireOptionalEquals(claims.roomInstanceId(), roomInstanceId, "roomInstanceId");
    requireOptionalEquals(claims.worldSlug(), worldSlug, "worldSlug");
    requireOptionalEquals(claims.realmSlug(), realmSlug, "realmSlug");
    requireOptionalPositiveEquals(claims.pointerVersion(), pointerVersion, "pointerVersion");
    requireOptionalEquals(claims.playableStateScope(), playableStateScope, "playableStateScope");
  }

  public void requireGameplayOrProbeMatch(
      String token, String tenantId, String gameInstanceId, String roomInstanceId) {
    GameplaySessionAttestationClaims claims = requireValid(token);
    requirePositiveEquals(claims.tenantId(), tenantId, "tenantId");
    requirePositiveEquals(claims.gameInstanceId(), gameInstanceId, "gameInstanceId");
    // roomInstanceId remains a routed room identifier string; keep text equality for probe tokens.
    requireOptionalEquals(claims.roomInstanceId(), roomInstanceId, "roomInstanceId");
  }

  private void requireEquals(String actual, String expected, String fieldName) {
    if (!actual.equals(expected)) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_MISMATCH",
          "Gameplay session attestation does not match " + fieldName);
    }
  }

  private void requirePositiveEquals(String actual, String expected, String fieldName) {
    long actualValue = requirePositiveId(actual, fieldName);
    long expectedValue = requirePositiveId(expected, fieldName);
    if (actualValue != expectedValue) {
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

  private void requireOptionalPositiveEquals(String actual, String expected, String fieldName) {
    if (StringUtils.hasText(expected)) {
      requirePositiveEquals(actual, expected, fieldName);
    }
  }

  private void requireClaimsType(GameplaySessionAttestationClaims claims, String expectedType) {
    if (claims == null || !expectedType.equals(claims.attestationType())) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay session attestation type is unsupported");
    }
  }

  private void requirePresent(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay session attestation is missing " + fieldName);
    }
  }

  private long requirePositiveId(String value, String fieldName) {
    try {
      return JwtClaims.requireLong(value, fieldName, false);
    } catch (IllegalArgumentException ex) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", ex.getMessage(), ex);
    }
  }

  private String requireClaim(Claims claims, String name) {
    try {
      return JwtClaims.requireClaim(claims, name);
    } catch (IllegalArgumentException ex) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay session attestation is missing " + name, ex);
    }
  }

  private String requireOptionalCanonicalRuntimeRoomId(String roomInstanceId) {
    return StringUtils.hasText(roomInstanceId)
        ? RequestIdValidation.requireCanonicalRuntimeRoomId(roomInstanceId, "roomInstanceId")
        : "";
  }

  private String requireOptionalCanonicalRuntimeRoomClaim(String roomInstanceId) {
    if (!StringUtils.hasText(roomInstanceId)) {
      return roomInstanceId;
    }
    try {
      return RequestIdValidation.requireCanonicalRuntimeRoomId(roomInstanceId, "roomInstanceId");
    } catch (IllegalArgumentException ex) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", ex.getMessage(), ex);
    }
  }

  private void requireSubjectMatchesClaims(
      String attestationType,
      Claims claims,
      String tenantId,
      String sessionId,
      String gameInstanceId,
      String roomInstanceId) {
    if (GAMEPLAY_SESSION.equals(attestationType) && !hasPositiveId(sessionId)) {
      return;
    }
    if (INTERNAL_PROBE.equals(attestationType)
        && (!hasPositiveId(tenantId)
            || !hasPositiveId(gameInstanceId)
            || !StringUtils.hasText(roomInstanceId))) {
      return;
    }
    final String subject;
    try {
      subject = JwtClaims.requireText(claims.getSubject(), "sub");
    } catch (IllegalArgumentException ex) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", ex.getMessage(), ex);
    }
    String expectedSubject =
        switch (attestationType) {
          case GAMEPLAY_SESSION -> "gameplay-session:" + sessionId;
          case INTERNAL_PROBE ->
              "gameplay-probe:" + tenantId + ":" + gameInstanceId + ":" + roomInstanceId;
          default -> throw new IllegalStateException("Unsupported attestation type");
        };
    if (!expectedSubject.equals(subject)) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID",
          "Gameplay session attestation subject does not match claims");
    }
  }

  private boolean hasPositiveId(String value) {
    try {
      JwtClaims.requireLong(value, "id", false);
      return true;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private String claimText(Object value) {
    return JwtClaims.claimText(value);
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
