package net.firedevops.firemud.common.security;

/** Shared admin-role guard for gRPC surfaces that return application errors in-band. */
public final class AdminRoleGuard {
  private AdminRoleGuard() {}

  public static void requireAdminRole() {
    if (!SessionContext.hasGlobalPrivilegedRole()) {
      throw new AdminAuthorizationException("Admin role required");
    }
  }
}
