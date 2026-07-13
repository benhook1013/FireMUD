package net.firedevops.firemud.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

public record SessionClaims(
    String accountId,
    List<String> globalRoles,
    Map<String, List<String>> scopedRoles,
    boolean internalService,
    String serviceName,
    String serviceInstanceId) {

  public SessionClaims {
    globalRoles = globalRoles == null ? List.of() : List.copyOf(globalRoles);
    scopedRoles =
        scopedRoles == null
            ? Map.of()
            : scopedRoles.entrySet().stream()
                .collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
  }

  public static SessionClaims fromJwt(Jws<Claims> jwt) {
    Claims payload = jwt.getPayload();
    return new SessionClaims(
        JwtClaims.claimText(payload.get("accountId")),
        extractGlobalRoles(payload.get("globalRoles")),
        extractScopedRoles(payload.get("scopedRoles")),
        Boolean.TRUE.equals(payload.get("internalService", Boolean.class)),
        JwtClaims.claimText(payload.get("serviceName")),
        JwtClaims.claimText(payload.get("serviceInstanceId")));
  }

  public void applyToSession() {
    SessionContext.setContext(
        accountId, globalRoles, scopedRoles, internalService, serviceName, serviceInstanceId);
  }

  @Override
  public List<String> globalRoles() {
    return List.copyOf(globalRoles);
  }

  @Override
  public Map<String, List<String>> scopedRoles() {
    return Map.copyOf(scopedRoles);
  }

  public boolean hasPrivilegedRole() {
    if (globalRoles.contains("platformAdmin") || globalRoles.contains("moderator")) {
      return true;
    }
    for (List<String> roles : scopedRoles.values()) {
      if (roles.contains("tenantAdmin") || roles.contains("moderator")) {
        return true;
      }
    }
    return false;
  }

  public boolean hasGameplayElevatedRole(String tenantId) {
    if (containsAnyRoleIgnoreCase(globalRoles, "platformAdmin", "moderator", "god")) {
      return true;
    }
    return StringUtils.hasText(tenantId)
        && containsAnyRoleIgnoreCase(scopedRoles.get(tenantId), "tenantAdmin", "moderator", "god");
  }

  /** Returns whether a global or requested tenant-scoped role matches a gameplay role. */
  public boolean hasGameplayRole(String tenantId, String... expectedRoles) {
    if (containsAnyRoleIgnoreCase(globalRoles, expectedRoles)) {
      return true;
    }
    return StringUtils.hasText(tenantId)
        && containsAnyRoleIgnoreCase(scopedRoles.get(tenantId), expectedRoles);
  }

  private static List<String> extractGlobalRoles(Object rawGlobalRoles) {
    if (rawGlobalRoles == null) {
      return List.of();
    }
    if (!(rawGlobalRoles instanceof List<?> roles)) {
      throw new IllegalArgumentException("Malformed claim: globalRoles");
    }
    return normalizeRoleValues(roles);
  }

  private static Map<String, List<String>> extractScopedRoles(Object rawScopedRoles) {
    if (rawScopedRoles == null) {
      return Map.of();
    }
    if (!(rawScopedRoles instanceof Map<?, ?> scopedRolesMap)) {
      throw new IllegalArgumentException("Malformed claim: scopedRoles");
    }
    Map<String, List<String>> scopedRoles = new HashMap<>();
    for (Map.Entry<?, ?> entry : scopedRolesMap.entrySet()) {
      String tenantKey = JwtClaims.requireText(entry.getKey(), "scopedRoles");
      if (!StringUtils.hasText(tenantKey)) {
        throw new IllegalArgumentException("Malformed claim: scopedRoles");
      }
      if (!(entry.getValue() instanceof List<?> roles)) {
        throw new IllegalArgumentException("Malformed claim: scopedRoles");
      }
      scopedRoles.put(tenantKey, normalizeRoleValues(roles));
    }
    return scopedRoles;
  }

  private static List<String> normalizeRoleValues(List<?> values) {
    List<String> normalizedRoles = new ArrayList<>();
    for (Object role : values) {
      String value = JwtClaims.claimText(role);
      if (StringUtils.hasText(value)) {
        normalizedRoles.add(value);
      }
    }
    return normalizedRoles;
  }

  private static boolean containsAnyRoleIgnoreCase(
      Iterable<String> roles, String... expectedRoles) {
    if (roles == null) {
      return false;
    }
    for (String role : roles) {
      if (!StringUtils.hasText(role)) {
        continue;
      }
      for (String expectedRole : expectedRoles) {
        if (role.equalsIgnoreCase(expectedRole)) {
          return true;
        }
      }
    }
    return false;
  }
}
