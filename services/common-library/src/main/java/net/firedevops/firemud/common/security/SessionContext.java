package net.firedevops.firemud.common.security;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Thread-local storage for JWT claims extracted by {@link AuthTokenInterceptor}. */
public final class SessionContext {
  private SessionContext() {}

  private static final ThreadLocal<ClaimsData> HOLDER = new ThreadLocal<>();

  static void setContext(String accountId, List<String> globalRoles, Map<String, List<String>> scopedRoles) {
    HOLDER.set(new ClaimsData(accountId, globalRoles, scopedRoles));
  }

  static void clear() {
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

  /** Returns the scoped roles for the provided gameId or an empty list. */
  public static List<String> getScopedRoles(String gameId) {
    ClaimsData data = HOLDER.get();
    if (data == null || data.scopedRoles == null) {
      return List.of();
    }
    return data.scopedRoles.getOrDefault(gameId, Collections.emptyList());
  }

  private record ClaimsData(
      String accountId, List<String> globalRoles, Map<String, List<String>> scopedRoles) {}
}
