package unit.net.firedevops.firemud.socialgroups.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SocialAccessGuardTest {
  private static final String ACCOUNT_UUID = "c41744c9-285e-4ed0-9fb4-0f0acb7a0123";
  private static final String OTHER_ACCOUNT_UUID = "d52755da-396f-4fd1-80c5-1f1bcb8b1234";

  private final SocialAccessGuard guard = new SocialAccessGuard();

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void hasAccountAccessRejectsMalformedCurrentAccountClaim() {
    SessionContext.setContext("not-a-number", List.of(), Map.of());

    assertFalse(guard.hasAccountAccess(1L, ACCOUNT_UUID));
  }

  @Test
  void canonicalCurrentAccountAuthorizesExactUuidSelector() {
    SessionContext.setContext(ACCOUNT_UUID, List.of(), Map.of());

    assertTrue(guard.hasAccountAccess(1L, ACCOUNT_UUID));
    assertFalse(guard.hasAccountAccess(1L, OTHER_ACCOUNT_UUID));
  }

  @Test
  void hasAccountAccessPreservesTenantAdminAuthorizationForUuidSelector() {
    SessionContext.setContext(ACCOUNT_UUID, List.of(), Map.of("1", List.of("tenantAdmin")));

    assertTrue(guard.hasAccountAccess(1L, OTHER_ACCOUNT_UUID));
  }

  @Test
  void hasAccountAccessRejectsMalformedAccountSelector() {
    SessionContext.setContext(ACCOUNT_UUID, List.of(), Map.of("1", List.of("tenantAdmin")));

    assertFalse(guard.hasAccountAccess(1L, "00000000-0000-0000-0000-000000000000"));
    assertFalse(guard.hasAccountAccess(1L, "42"));
  }
}
