package net.firedevops.firemud.common.security;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Thread-local storage for JWT claims extracted by {@link AuthTokenInterceptor}. */
public final class SessionContext {
  private SessionContext() {}

  private static final ThreadLocal<ClaimsData> HOLDER = new ThreadLocal<>();

  public static void setContext(
      String accountId, List<String> globalRoles, Map<String, List<String>> scopedRoles) {
    setContext(accountId, globalRoles, scopedRoles, false, null, null);
  }

  public static void setContext(
      String accountId,
      List<String> globalRoles,
      Map<String, List<String>> scopedRoles,
      boolean internalService,
      String serviceName,
      String serviceInstanceId) {
    List<String> immutableGlobals = globalRoles == null ? List.of() : List.copyOf(globalRoles);
    Map<String, List<String>> immutableScoped =
        scopedRoles == null
            ? Map.of()
            : scopedRoles.entrySet().stream()
                .collect(
                    Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    HOLDER.set(
        new ClaimsData(
            accountId,
            immutableGlobals,
            immutableScoped,
            internalService,
            serviceName,
            serviceInstanceId));
  }

  public static void clear() {
    HOLDER.remove();
  }

  /** Returns the accountId claim or {@code null} if not present. */
  public static String getAccountId() {
    ClaimsData data = HOLDER.get();
    return data == null ? null : data.accountId;
  }

  /** Returns the globalRoles claim or an empty list if not present. */
  public static List<String> getGlobalRoles() {
    ClaimsData data = HOLDER.get();
    return data == null ? List.of() : data.globalRoles;
  }

  /** Returns the scoped roles for the provided tenantId or an empty list. */
  public static List<String> getScopedRoles(String tenantId) {
    ClaimsData data = HOLDER.get();
    if (data == null || data.scopedRoles == null) {
      return List.of();
    }
    return data.scopedRoles.getOrDefault(tenantId, Collections.emptyList());
  }

  /** Returns the full scoped-role map or an empty map if not present. */
  public static Map<String, List<String>> getScopedRolesMap() {
    ClaimsData data = HOLDER.get();
    return data == null || data.scopedRoles == null ? Map.of() : data.scopedRoles;
  }

  /** Returns whether the current caller is a shared internal service identity. */
  public static boolean isInternalService() {
    ClaimsData data = HOLDER.get();
    return data != null && data.internalService;
  }

  /** Returns the current internal service name when present. */
  public static String getServiceName() {
    ClaimsData data = HOLDER.get();
    return data == null ? null : data.serviceName;
  }

  /** Returns the current internal service instance id when present. */
  public static String getServiceInstanceId() {
    ClaimsData data = HOLDER.get();
    return data == null ? null : data.serviceInstanceId;
  }

  /** Returns whether the current caller can act on the provided tenant. */
  public static boolean hasTenantAccess(Long tenantId) {
    if (tenantId == null) {
      return false;
    }
    if (hasGlobalTenantAccess()) {
      return true;
    }
    List<String> roles = getScopedRoles(String.valueOf(tenantId));
    return roles.contains("tenantAdmin") || roles.contains("admin") || roles.contains("moderator");
  }

  /** Throws 403 when the current caller cannot act on the provided tenant. */
  public static void requireTenantAccess(Long tenantId) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    if (!hasTenantAccess(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant access required");
    }
  }

  /** Returns whether the current caller matches the provided account id. */
  public static boolean isCurrentAccount(Long accountId) {
    if (accountId == null) {
      return false;
    }
    ClaimsData data = HOLDER.get();
    if (data == null || data.accountId == null || data.accountId.isBlank()) {
      return false;
    }
    try {
      return Long.parseLong(data.accountId) == accountId;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  /** Returns whether the current caller can act on the provided tenant-owned account surface. */
  public static boolean hasAccountAccess(Long tenantId, Long accountId) {
    return isCurrentAccount(accountId) || hasTenantAccess(tenantId);
  }

  /** Throws 403 when the current caller cannot act on the provided account surface. */
  public static void requireAccountAccess(Long tenantId, Long accountId) {
    if (!hasAccountAccess(tenantId, accountId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account access required");
    }
  }

  /** Returns whether the current caller carries a global privileged role. */
  public static boolean hasGlobalPrivilegedRole() {
    return hasGlobalTenantAccess();
  }

  /** Throws 403 when the current caller lacks a global privileged role. */
  public static void requireGlobalPrivilegedRole() {
    if (!hasGlobalPrivilegedRole()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Privileged role required");
    }
  }

  private static boolean hasGlobalTenantAccess() {
    ClaimsData data = HOLDER.get();
    if (data == null || data.globalRoles == null) {
      return false;
    }
    return data.globalRoles.contains("platformAdmin") || data.globalRoles.contains("moderator");
  }

  private record ClaimsData(
      String accountId,
      List<String> globalRoles,
      Map<String, List<String>> scopedRoles,
      boolean internalService,
      String serviceName,
      String serviceInstanceId) {}
}
