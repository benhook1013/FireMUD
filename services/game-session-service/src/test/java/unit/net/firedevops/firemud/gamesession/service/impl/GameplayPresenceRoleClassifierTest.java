package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class GameplayPresenceRoleClassifierTest {
  private static final JwtUtil JWT_UTIL = new JwtUtil("mysecretkey123456789012345678901", 30_000L);

  @Test
  void classifyRoleReturnsPlayerWhenThereIsNoAccountGrantEvidence() {
    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 102L, "player@example.com", 202L, "Ben", 7L, "R-1", null),
            JWT_UTIL,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleReturnsPlayerWithoutParsingAStoredJwt() {
    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(
                1L, 22L, 102L, "player@example.com", 202L, "Ben", 7L, "R-1", "boom-token"),
            null,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleDoesNotTreatScopedModeratorClaimAsGameplayGrant() {
    String jwt =
        JWT_UTIL.generateToken(
            "202",
            java.util.Map.of(
                "accountId",
                "202",
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("moderator"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleReturnsPlayerForGlobalRolesWithoutTenantGrant() {
    String jwt =
        JWT_UTIL.generateToken(
            "202",
            java.util.Map.of(
                "accountId",
                "202",
                "globalRoles",
                java.util.List.of("platformAdmin", "support", "billingAdmin", "god", "moderator")));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleKeepsGlobalControlPlaneRolesAsPlayerAfterJoin() {
    String jwt =
        JWT_UTIL.generateToken(
            "202",
            java.util.Map.of(
                "accountId",
                "202",
                "globalRoles",
                java.util.List.of("platformAdmin", "support", "billingAdmin"),
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("player"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleDoesNotTreatTenantAdminClaimAsGameplayGrant() {
    String jwt =
        JWT_UTIL.generateToken(
            "202",
            java.util.Map.of(
                "accountId",
                "202",
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("tenantAdmin"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleDoesNotElevateFromTenantRoleClaimsAlone() {
    String jwt =
        JWT_UTIL.generateToken(
            "202",
            java.util.Map.of(
                "accountId",
                "202",
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("moderator", "tenantAdmin", "god"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleDoesNotTreatScopedGodClaimAsGameplayGrant() {
    String jwt =
        JWT_UTIL.generateToken(
            "202",
            java.util.Map.of(
                "accountId",
                "202",
                "scopedRoles",
                java.util.Map.of("22", java.util.List.of("god"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleDoesNotUseAClaimScopedToAnotherTenant() {
    String jwt =
        JWT_UTIL.generateToken(
            "202",
            java.util.Map.of(
                "accountId",
                "202",
                "scopedRoles",
                java.util.Map.of("23", java.util.List.of("tenantAdmin", "moderator"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleDoesNotElevateForSameAndOtherTenantClaimsWithoutGrantEvidence() {
    String jwt =
        JWT_UTIL.generateToken(
            "202",
            java.util.Map.of(
                "accountId",
                "202",
                "scopedRoles",
                java.util.Map.of(
                    "22", java.util.List.of("moderator"),
                    "23", java.util.List.of("god"))));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            JWT_UTIL,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }

  @Test
  void classifyRoleDoesNotUseMalformedRoleClaimsAsAuthority() {
    String jwt =
        JWT_UTIL.generateToken("202", java.util.Map.of("accountId", "202", "scopedRoles", "bad"));

    GameplayPresenceRole role =
        GameplayPresenceRoleClassifier.classifyRole(
            new SessionContext(1L, 22L, 202L, "player@example.com", 202L, "Ben", 7L, "R-1", jwt),
            null,
            null);

    assertEquals(GameplayPresenceRole.PLAYER, role);
  }
}
