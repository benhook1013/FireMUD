package net.firedevops.firemud.common.security;

import io.grpc.Context;
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
  private static final Context.Key<ClaimsData> GRPC_CONTEXT = Context.key("firemud-session");

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
    HOLDER.set(
        toClaimsData(
            accountId, globalRoles, scopedRoles, internalService, serviceName, serviceInstanceId));
  }

  public static void clear() {
    HOLDER.remove();
  }

  /** Returns the accountId claim or {@code null} if not present. */
  public static String getAccountId() {
    ClaimsData data = currentData();
    return data == null ? null : data.accountId;
  }

  /** Returns the globalRoles claim or an empty list if not present. */
  public static List<String> getGlobalRoles() {
    ClaimsData data = currentData();
    return data == null ? List.of() : data.globalRoles;
  }

  /** Returns the scoped roles for the provided tenantId or an empty list. */
  public static List<String> getScopedRoles(String tenantId) {
    ClaimsData data = currentData();
    if (data == null || data.scopedRoles == null) {
      return List.of();
    }
    return data.scopedRoles.getOrDefault(tenantId, Collections.emptyList());
  }

  /** Returns the full scoped-role map or an empty map if not present. */
  public static Map<String, List<String>> getScopedRolesMap() {
    ClaimsData data = currentData();
    return data == null || data.scopedRoles == null ? Map.of() : data.scopedRoles;
  }

  /** Returns whether the current caller is a shared internal service identity. */
  public static boolean isInternalService() {
    ClaimsData data = currentData();
    return data != null && data.internalService;
  }

  /** Returns the current internal service name when present. */
  public static String getServiceName() {
    ClaimsData data = currentData();
    return data == null ? null : data.serviceName;
  }

  /** Returns the current internal service instance id when present. */
  public static String getServiceInstanceId() {
    ClaimsData data = currentData();
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
    return roles.contains("tenantAdmin") || roles.contains("moderator");
  }

  /** Returns whether the current caller carries any non-internal account or role context. */
  public static boolean hasAuthenticatedCallerContext() {
    ClaimsData data = currentData();
    if (data == null) {
      return false;
    }
    if (data.accountId != null && !data.accountId.isBlank()) {
      return true;
    }
    return (data.globalRoles != null && !data.globalRoles.isEmpty())
        || (data.scopedRoles != null && !data.scopedRoles.isEmpty());
  }

  /**
   * Returns the current account id when present.
   *
   * <p>Blank or missing claims return {@code null}. Malformed or non-positive claims fail closed.
   */
  public static Long currentAccountIdOrNull() {
    ClaimsData data = currentData();
    if (data == null || data.accountId == null || data.accountId.isBlank()) {
      return null;
    }
    return JwtClaims.requireLong(data.accountId, "accountId", false);
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
    if (accountId == null || accountId <= 0L) {
      return false;
    }
    try {
      return currentAccountId() == accountId;
    } catch (IllegalArgumentException ex) {
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
    ClaimsData data = currentData();
    if (data == null || data.globalRoles == null) {
      return false;
    }
    return data.globalRoles.contains("platformAdmin") || data.globalRoles.contains("moderator");
  }

  private static long currentAccountId() {
    ClaimsData data = currentData();
    if (data == null || data.accountId == null || data.accountId.isBlank()) {
      throw new IllegalArgumentException("accountId is required");
    }
    return JwtClaims.requireLong(data.accountId, "accountId", false);
  }

  static Context grpcContextWith(SessionClaims claims) {
    return Context.current()
        .withValue(
            GRPC_CONTEXT,
            toClaimsData(
                claims.accountId(),
                claims.globalRoles(),
                claims.scopedRoles(),
                claims.internalService(),
                claims.serviceName(),
                claims.serviceInstanceId()));
  }

  private static ClaimsData currentData() {
    ClaimsData threadLocalData = HOLDER.get();
    if (threadLocalData != null) {
      return threadLocalData;
    }
    return GRPC_CONTEXT.get();
  }

  private static ClaimsData toClaimsData(
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
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    return new ClaimsData(
        accountId,
        immutableGlobals,
        immutableScoped,
        internalService,
        serviceName,
        serviceInstanceId);
  }

  private record ClaimsData(
      String accountId,
      List<String> globalRoles,
      Map<String, List<String>> scopedRoles,
      boolean internalService,
      String serviceName,
      String serviceInstanceId) {}
}
