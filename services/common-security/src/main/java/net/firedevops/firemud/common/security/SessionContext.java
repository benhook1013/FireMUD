package net.firedevops.firemud.common.security;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Thread-local storage for JWT claims extracted by {@link AuthTokenInterceptor}. */
public final class SessionContext {
  private SessionContext() {}

  private static final ThreadLocal<ClaimsData> HOLDER = new ThreadLocal<>();

  public static void setContext(
      String accountId, List<String> globalRoles, Map<String, List<String>> scopedRoles) {
    List<String> immutableGlobals = globalRoles == null ? List.of() : List.copyOf(globalRoles);
    Map<String, List<String>> immutableScoped =
        scopedRoles == null
            ? Map.of()
            : scopedRoles.entrySet().stream()
                .collect(
                    Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    HOLDER.set(new ClaimsData(accountId, immutableGlobals, immutableScoped));
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

  private record ClaimsData(
      String accountId, List<String> globalRoles, Map<String, List<String>> scopedRoles) {}
}
