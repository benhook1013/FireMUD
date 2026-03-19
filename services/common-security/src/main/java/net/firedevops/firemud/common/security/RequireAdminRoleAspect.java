package net.firedevops.firemud.common.security;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/** Validates that the caller has an admin-level role. */
@Aspect
@Component
public class RequireAdminRoleAspect {
  @Before("@annotation(net.firedevops.firemud.common.security.RequireAdminRole)")
  public void checkRole() {
    List<String> roles = SessionContext.getGlobalRoles();
    boolean allowed = roles.contains("platformAdmin") || roles.contains("moderator");
    if (!allowed) {
      throw new StatusRuntimeException(
          Status.PERMISSION_DENIED.withDescription("Admin role required"));
    }
  }
}
