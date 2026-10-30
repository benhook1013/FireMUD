package unit.net.firedevops.firemud.socialgroups.security;

import static net.firedevops.firemud.socialgroups.test.AccountUuidFixtures.accountUuid;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SocialAccessGuardTest {
  private final SocialAccessGuard guard = new SocialAccessGuard();

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "42",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "10000000-0000-4000-8000-00000000000A",
        " 10000000-0000-4000-8000-000000000042"
      })
  void hasAccountAccessRejectsMalformedCurrentAccountClaimEvenWithTenantRoles(String claim) {
    SessionContext.setContext(claim, List.of("platformAdmin"), Map.of("1", List.of("tenantAdmin")));
    assertFalse(guard.hasAccountAccess(1L, accountUuid(42L)));
  }

  @Test
  void hasAccountAccessAcceptsMatchingCanonicalCurrentAccountClaim() {
    SessionContext.setContext(accountUuid(42L), List.of(), Map.of());
    assertTrue(guard.hasAccountAccess(1L, accountUuid(42L)));
  }

  @Test
  void hasAccountAccessRejectsAnotherAccountDespiteTenantAndGlobalRoles() {
    SessionContext.setContext(
        accountUuid(42L), List.of("platformAdmin"), Map.of("1", List.of("tenantAdmin")));
    assertFalse(guard.hasAccountAccess(1L, accountUuid(43L)));
  }

  @Test
  void hasAccountAccessRejectsMalformedRequestedAccountAndAbsentCaller() {
    assertFalse(guard.hasAccountAccess(1L, accountUuid(42L)));
    SessionContext.setContext(accountUuid(42L), List.of(), Map.of());
    assertFalse(guard.hasAccountAccess(1L, "42"));
    assertFalse(guard.hasAccountAccess(0L, accountUuid(42L)));
  }

  @Test
  void hasGuildOwnerAccessAcceptsMatchingOrdinaryAccountUuidWithoutTenantRole() {
    SessionContext.setContext(accountUuid(42L), List.of(), Map.of());

    assertTrue(guard.hasGuildOwnerAccess(1L, accountUuid(42L)));
  }

  @Test
  void hasGuildOwnerAccessRetainsTenantPrivilegeForAnotherCanonicalOwner() {
    SessionContext.setContext(accountUuid(42L), List.of(), Map.of("1", List.of("tenantAdmin")));

    assertTrue(guard.hasGuildOwnerAccess(1L, accountUuid(43L)));
    assertFalse(guard.hasGuildOwnerAccess(2L, accountUuid(43L)));
  }

  @Test
  void hasGuildOwnerAccessRejectsOtherOwnerWithoutTenantPrivilege() {
    SessionContext.setContext(accountUuid(42L), List.of(), Map.of());

    assertFalse(guard.hasGuildOwnerAccess(1L, accountUuid(43L)));
    assertFalse(guard.hasGuildOwnerAccess(0L, accountUuid(42L)));
    assertFalse(guard.hasGuildOwnerAccess(1L, "42"));
  }
}
