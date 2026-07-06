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
  void hasAccountAccessAcceptsMatchingCurrentAccountClaim() {
    SessionContext.setContext("42", List.of(), Map.of());

    assertTrue(guard.hasAccountAccess(1L, 42L));
  }
}
