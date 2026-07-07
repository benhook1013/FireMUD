package net.firedevops.firemud.common.security;

import io.jsonwebtoken.Claims;
import org.springframework.util.StringUtils;

/** Shared helpers for canonical JWT claim extraction and normalization. */
public final class JwtClaims {
  private JwtClaims() {}

  public static String requireClaim(Claims claims, String claimName) {
    return requireText(claims != null ? claims.get(claimName) : null, claimName);
  }

  public static String requireText(Object value, String claimName) {
    String text = claimText(value);
    if (!StringUtils.hasText(text) || "null".equalsIgnoreCase(text)) {
      throw new IllegalArgumentException("Missing claim: " + claimName);
    }
    return text;
  }

  public static long requireLong(Object value, String claimName, boolean allowZero) {
    String text = requireText(value, claimName);
    long parsed;
    try {
      parsed = Long.parseLong(text);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("Malformed claim: " + claimName);
    }
    if (allowZero ? parsed < 0 : parsed <= 0) {
      throw new IllegalArgumentException("Invalid claim: " + claimName);
    }
    return parsed;
  }

  public static long requireSignedActorAccountId(Claims claims, String mismatchMessage) {
    long subjectAccountId = requireLong(claims.getSubject(), "sub", false);
    long claimedAccountId = requireLong(claims.get("accountId"), "accountId", false);
    if (subjectAccountId != claimedAccountId) {
      throw new IllegalArgumentException(mismatchMessage);
    }
    return claimedAccountId;
  }

  public static SignedGameplayRoutingClaims requireSignedGameplayRoutingClaims(
      Claims claims, String accountMismatchMessage) {
    return new SignedGameplayRoutingClaims(
        requireSignedActorAccountId(claims, accountMismatchMessage),
        requireLong(claims.get("tenantId"), "tenantId", false),
        requireText(claims.get("worldSlug"), "worldSlug"),
        requireText(claims.get("realmSlug"), "realmSlug"),
        requireLong(claims.get("gameInstanceId"), "gameInstanceId", false),
        requireLong(claims.get("pointerVersion"), "pointerVersion", false));
  }

  public static String claimText(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Iterable<?> iterable) {
      return firstNonBlank(iterable);
    }
    if (value.getClass().isArray() && value instanceof Object[] values) {
      return firstNonBlank(java.util.List.of(values));
    }
    return value.toString().trim();
  }

  private static String firstNonBlank(Iterable<?> values) {
    for (Object value : values) {
      if (value == null) {
        continue;
      }
      String text = value.toString().trim();
      if (!text.isBlank()) {
        return text;
      }
    }
    return "";
  }

  public record SignedGameplayRoutingClaims(
      long accountId,
      long tenantId,
      String worldSlug,
      String realmSlug,
      long gameInstanceId,
      long pointerVersion) {}
}
