package net.firedevops.firemud.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        payload.get("accountId", String.class),
        extractGlobalRoles(payload),
        extractScopedRoles(payload),
        Boolean.TRUE.equals(payload.get("internalService", Boolean.class)),
        payload.get("serviceName", String.class),
        payload.get("serviceInstanceId", String.class));
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

  private static List<String> extractGlobalRoles(Claims payload) {
    List<?> rawGlobalRoles = payload.get("globalRoles", List.class);
    return rawGlobalRoles == null
        ? List.of()
        : rawGlobalRoles.stream().map(String::valueOf).toList();
  }

  private static Map<String, List<String>> extractScopedRoles(Claims payload) {
    Map<?, ?> rawScopedRoles = payload.get("scopedRoles", Map.class);
    Map<String, List<String>> scopedRoles = new HashMap<>();
    if (rawScopedRoles == null) {
      return scopedRoles;
    }
    for (Map.Entry<?, ?> entry : rawScopedRoles.entrySet()) {
      List<?> rolesRaw = entry.getValue() instanceof List<?> list ? list : List.of();
      scopedRoles.put(
          String.valueOf(entry.getKey()), rolesRaw.stream().map(String::valueOf).toList());
    }
    return scopedRoles;
  }
}
