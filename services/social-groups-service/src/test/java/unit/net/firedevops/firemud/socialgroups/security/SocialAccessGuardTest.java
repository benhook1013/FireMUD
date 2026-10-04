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

  private final SocialAccessGuard guard = new SocialAccessGuard();

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void hasAccountAccessRejectsMalformedCurrentAccountClaim() {
    SessionContext.setContext("not-a-number", List.of(), Map.of());

    assertFalse(guard.hasAccountAccess(1L, 42L));
  }

  @Test
  void canonicalCurrentAccountCannotAuthorizeLegacyNumericSelector() {
    SessionContext.setContext(ACCOUNT_UUID, List.of(), Map.of());

    assertFalse(guard.hasAccountAccess(1L, 42L));
  }

  @Test
  void hasAccountAccessPreservesTenantAdminAuthorizationForLegacySelector() {
    SessionContext.setContext(ACCOUNT_UUID, List.of(), Map.of("1", List.of("tenantAdmin")));

    assertTrue(guard.hasAccountAccess(1L, 42L));
  }
}
