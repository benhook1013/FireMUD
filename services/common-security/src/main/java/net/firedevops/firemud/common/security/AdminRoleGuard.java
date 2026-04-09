package net.firedevops.firemud.common.security;

import java.util.List;

/** Shared admin-role guard for gRPC surfaces that return application errors in-band. */
public final class AdminRoleGuard {
  private AdminRoleGuard() {}

  public static void requireAdminRole() {
    List<String> roles = SessionContext.getGlobalRoles();
    if (!roles.contains("platformAdmin") && !roles.contains("moderator")) {
      throw new AdminAuthorizationException("Admin role required");
    }
  }
}
