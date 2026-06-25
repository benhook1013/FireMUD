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
}
