package unit.net.firedevops.firemud.accountservice.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.ActualClaimMaps;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.AuthorityMapShape;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.ClaimFieldPresence;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.ControlUiShape;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.DeploymentCeilings;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.NonTenantDelegationShape;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.PlayerBootstrapShape;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.PrivateRealmGrantShape;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.PrivateRealmTarget;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.ProfileLimits;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.TenantBoundDelegationShape;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AccountTokenProfileCatalogTest {
  private static final UUID TENANT_A = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID TENANT_B = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID TENANT_C = UUID.fromString("00000000-0000-4000-8000-000000000003");
  private static final UUID TENANT_D = UUID.fromString("00000000-0000-4000-8000-000000000004");
  private static final UUID LIFECYCLE_A = UUID.fromString("00000000-0000-4000-8000-000000000011");
  private static final String LARGE_GRANT_VERSION = "900719925474099312345678901234567890";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DeploymentCeilings DEPLOYMENT =
      new DeploymentCeilings(64_000, 48_000, 64_000, 3_600);

  @Test
  void requiresExplicitVersionCompleteProfileSetAndExactAudiences() {
    Map<String, ProfileLimits> limits = validLimits();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccountTokenProfileCatalog("account-token-profile-catalog/v2", limits, DEPLOYMENT));

    Map<String, ProfileLimits> missing = validLimits();
    missing.remove(AccountTokenProfileCatalog.PLAYER_BOOTSTRAP);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccountTokenProfileCatalog(
                AccountTokenProfileCatalog.VERSION, missing, DEPLOYMENT));

    Map<String, ProfileLimits> forbiddenProfile = validLimits();
    forbiddenProfile.put("gameplay-connect", forbiddenLimits("gameplay-connect"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccountTokenProfileCatalog(
                AccountTokenProfileCatalog.VERSION, forbiddenProfile, DEPLOYMENT));
    forbiddenProfile.remove("gameplay-connect");
    forbiddenProfile.put("gateway-context", forbiddenLimits("gateway-context"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccountTokenProfileCatalog(
                AccountTokenProfileCatalog.VERSION, forbiddenProfile, DEPLOYMENT));

    Map<String, ProfileLimits> wrongAudience = validLimits();
    wrongAudience.put(
        AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
        limits("gateway", 600, 1, 1, 1, 1, 1, 16_000, 12_000, 32_000, Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccountTokenProfileCatalog(
                AccountTokenProfileCatalog.VERSION, wrongAudience, DEPLOYMENT));
  }

  @Test
  void rejectsMissingZeroNegativeAndInconsistentFiniteLimits() {
    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.CONTROL_UI,
            limits("control-ui", 0, 2, 2, 2, 2, 0, 16_000, 12_000, 32_000, Optional.of(4))));
    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.CONTROL_UI,
            limits("control-ui", 900, -1, 2, 2, 2, 0, 16_000, 12_000, 32_000, Optional.of(4))));
    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.CONTROL_UI,
            limits("control-ui", 900, 5, 2, 2, 2, 0, 16_000, 12_000, 32_000, Optional.of(4))));
    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.CONTROL_UI,
            limits("control-ui", 900, 2, 2, 2, 2, 0, 16_000, 12_000, 32_000, Optional.empty())));
    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.CONTROL_UI,
            limits("control-ui", 900, 2, 2, 2, 2, 0, 16_000, 12_000, 32_000, Optional.of(0))));
    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.CONTROL_UI,
            limits("control-ui", 900, 2, 2, 2, 2, 0, 0, 12_000, 32_000, Optional.of(4))));
    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.CONTROL_UI,
            limits("control-ui", -1, 2, 2, 2, 2, 0, 16_000, 12_000, 32_000, Optional.of(4))));
    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.CONTROL_UI,
            limits("control-ui", 900, 2, 2, 2, 2, 0, 16_000, 12_000, -1, Optional.of(4))));
    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.CONTROL_UI,
            limits("control-ui", 900, 2, 2, 2, 2, 0, 16_000, -1, 32_000, Optional.of(4))));

    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.PLAYER_BOOTSTRAP,
            limits(
                "player-bootstrap", 300, 0, 1, 0, 0, 0, 8_000, 12_000, 16_000, Optional.empty())));
    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
            limits(
                "account-service", 600, 2, 1, 1, 1, 1, 16_000, 12_000, 32_000, Optional.empty())));

    assertInvalidCatalog(validLimits(), new DeploymentCeilings(11_999, 48_000, 64_000, 3_600));
    assertInvalidCatalog(validLimits(), new DeploymentCeilings(64_000, 15_999, 64_000, 3_600));
    assertInvalidCatalog(validLimits(), new DeploymentCeilings(64_000, 48_000, 31_999, 3_600));
    assertInvalidCatalog(validLimits(), new DeploymentCeilings(64_000, 48_000, 64_000, 899));

    assertInvalidCatalog(
        replace(
            validLimits(),
            AccountTokenProfileCatalog.CONTROL_UI,
            limits("control-ui", 900, 2, 2, 2, 2, 0, 33_000, 12_000, 32_000, Optional.of(4))));
  }

  @Test
  void keepsCatalogAndTypedShapeInputsDefensivelyImmutableAndRedactsDiagnostics() {
    Map<String, ProfileLimits> mutableProfiles = validLimits();
    AccountTokenProfileCatalog catalog =
        new AccountTokenProfileCatalog(
            AccountTokenProfileCatalog.VERSION, mutableProfiles, DEPLOYMENT);
    mutableProfiles.clear();

    assertEquals(3, catalog.profiles().size());
    assertThrows(
        UnsupportedOperationException.class,
        () -> catalog.profiles().put("control-ui", uiLimits()));
    assertEquals(DEPLOYMENT, catalog.deploymentCeilings());

    List<PrivateRealmGrantShape> mutableGrants = new ArrayList<>();
    PrivateRealmGrantShape grant = grant(TENANT_A, LIFECYCLE_A, LARGE_GRANT_VERSION);
    mutableGrants.add(grant);
    AuthorityMapShape authority =
        new AuthorityMapShape(
            Set.of(TENANT_A), Set.of(TENANT_A), Set.of(TENANT_A), Set.of(), mutableGrants);
    mutableGrants.clear();

    assertEquals(Set.of(TENANT_A), authority.tenantAuthorityKeys());
    assertEquals(List.of(grant), authority.privateRealmGrants());
    assertThrows(
        UnsupportedOperationException.class, () -> authority.tenantAuthorityKeys().add(TENANT_B));
    assertThrows(
        UnsupportedOperationException.class, () -> authority.privateRealmGrants().add(grant));

    ControlUiShape originalUiShape = controlUiShape();
    Set<UUID> mutableTenants = new HashSet<>(originalUiShape.scopedTenantKeys());
    ControlUiShape copiedUiShape =
        new ControlUiShape(
            originalUiShape.claims(),
            originalUiShape.authority(),
            mutableTenants,
            originalUiShape.scopedRoleKeys(),
            originalUiShape.tenantAuthorityRouteKeys(),
            originalUiShape.requiredCallerMembershipKeys(),
            originalUiShape.billingSafeTenantKeys(),
            originalUiShape.applicableBillingCutoffKeys());
    mutableTenants.clear();

    assertEquals(originalUiShape.scopedTenantKeys(), copiedUiShape.scopedTenantKeys());
    assertThrows(
        UnsupportedOperationException.class, () -> copiedUiShape.scopedTenantKeys().add(TENANT_C));
    assertThrows(
        UnsupportedOperationException.class, () -> copiedUiShape.scopedRoleKeys().add(TENANT_C));
    assertThrows(
        UnsupportedOperationException.class,
        () -> copiedUiShape.tenantAuthorityRouteKeys().add(TENANT_C));
    assertThrows(
        UnsupportedOperationException.class,
        () -> copiedUiShape.requiredCallerMembershipKeys().add(TENANT_C));
    assertThrows(
        UnsupportedOperationException.class,
        () -> copiedUiShape.billingSafeTenantKeys().add(TENANT_C));
    assertThrows(
        UnsupportedOperationException.class,
        () -> copiedUiShape.applicableBillingCutoffKeys().add(TENANT_C));

    Set<UUID> mutableNonTenantRoles = new HashSet<>();
    NonTenantDelegationShape copiedNonTenantShape =
        new NonTenantDelegationShape(
            delegationClaims(false, false), emptyAuthority(), mutableNonTenantRoles);
    mutableNonTenantRoles.add(TENANT_B);
    assertTrue(copiedNonTenantShape.scopedRoleKeys().isEmpty());
    assertThrows(
        UnsupportedOperationException.class,
        () -> copiedNonTenantShape.scopedRoleKeys().add(TENANT_B));

    String marker = "eyJhbGciOiJub25lIn0.secret.signature";
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                catalogWithUiPostSignLimits(5, 7)
                    .validatePostSignCandidate(
                        AccountTokenProfileCatalog.CONTROL_UI, marker, "{}"));
    assertFalse(failure.getMessage().contains(marker));
    assertFalse(catalog.toString().contains(marker));
    assertTrue(grant.toString().contains("redacted"));
    assertFalse(grant.toString().contains(LARGE_GRANT_VERSION));
  }

  @Test
  void checksCheckedUtcEpochSecondLifetimeAtExactAndInvalidBoundaries() {
    AccountTokenProfileCatalog catalog = catalog();

    assertEquals(
        900, catalog.validateTokenLifetime(AccountTokenProfileCatalog.CONTROL_UI, 1_000, 1_900));
    assertThrows(
        IllegalArgumentException.class,
        () -> catalog.validateTokenLifetime(AccountTokenProfileCatalog.CONTROL_UI, 1_000, 1_901));
    assertThrows(
        IllegalArgumentException.class,
        () -> catalog.validateTokenLifetime(AccountTokenProfileCatalog.CONTROL_UI, 1_000, 1_000));
    assertThrows(
        IllegalArgumentException.class,
        () -> catalog.validateTokenLifetime(AccountTokenProfileCatalog.CONTROL_UI, 1_001, 1_000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validateTokenLifetime(
                AccountTokenProfileCatalog.CONTROL_UI, Long.MIN_VALUE, Long.MAX_VALUE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validateTokenLifetime(
                AccountTokenProfileCatalog.CONTROL_UI, Long.MAX_VALUE, Long.MAX_VALUE));
  }

  @Test
  void validatesControlUiIndependentMapsAndBillingSafeTenantRelationship() {
    AccountTokenProfileCatalog catalog = catalog();
    ControlUiShape valid = controlUiShape();
    validatePreSign(catalog, AccountTokenProfileCatalog.CONTROL_UI, valid, tupleJson(valid));

    AuthorityMapShape wrongTenantAuthority =
        authority(
            Set.of(TENANT_A, TENANT_B),
            Set.of(TENANT_A, TENANT_B),
            Set.of(TENANT_A, TENANT_B),
            Set.of(TENANT_A, TENANT_B),
            List.of());
    ControlUiShape extraTargetTenant =
        new ControlUiShape(
            controlUiClaims(true),
            wrongTenantAuthority,
            Set.of(TENANT_A, TENANT_B),
            Set.of(TENANT_A),
            Set.of(TENANT_A),
            Set.of(TENANT_A, TENANT_B),
            Set.of(TENANT_B),
            Set.of(TENANT_A, TENANT_B));
    assertInvalidShape(catalog, AccountTokenProfileCatalog.CONTROL_UI, extraTargetTenant);

    AuthorityMapShape missingBillingSafeMembershipVersion =
        authority(
            Set.of(TENANT_A),
            Set.of(TENANT_A, TENANT_B),
            Set.of(TENANT_A),
            Set.of(TENANT_A, TENANT_B),
            List.of());
    ControlUiShape missingVersion =
        new ControlUiShape(
            controlUiClaims(true),
            missingBillingSafeMembershipVersion,
            Set.of(TENANT_A, TENANT_B),
            Set.of(TENANT_A),
            Set.of(TENANT_A),
            Set.of(TENANT_A, TENANT_B),
            Set.of(TENANT_B),
            Set.of(TENANT_A, TENANT_B));
    assertInvalidShape(catalog, AccountTokenProfileCatalog.CONTROL_UI, missingVersion);

    ControlUiShape misclassifiedBillingSafe =
        new ControlUiShape(
            controlUiClaims(true),
            valid.authority(),
            valid.scopedTenantKeys(),
            valid.scopedRoleKeys(),
            Set.of(TENANT_A, TENANT_B),
            valid.requiredCallerMembershipKeys(),
            valid.billingSafeTenantKeys(),
            valid.applicableBillingCutoffKeys());
    assertInvalidShape(catalog, AccountTokenProfileCatalog.CONTROL_UI, misclassifiedBillingSafe);

    ControlUiShape missingCutoffObject =
        new ControlUiShape(
            controlUiClaims(false),
            valid.authority(),
            valid.scopedTenantKeys(),
            valid.scopedRoleKeys(),
            valid.tenantAuthorityRouteKeys(),
            valid.requiredCallerMembershipKeys(),
            valid.billingSafeTenantKeys(),
            valid.applicableBillingCutoffKeys());
    assertInvalidShape(catalog, AccountTokenProfileCatalog.CONTROL_UI, missingCutoffObject);

    ControlUiShape wrongAudience =
        new ControlUiShape(
            claims("player-bootstrap", true, true, false),
            valid.authority(),
            valid.scopedTenantKeys(),
            valid.scopedRoleKeys(),
            valid.tenantAuthorityRouteKeys(),
            valid.requiredCallerMembershipKeys(),
            valid.billingSafeTenantKeys(),
            valid.applicableBillingCutoffKeys());
    assertInvalidShape(catalog, AccountTokenProfileCatalog.CONTROL_UI, wrongAudience);

    ControlUiShape missingRequiredTupleMap =
        new ControlUiShape(
            claimsWithoutMembershipVersion(),
            valid.authority(),
            valid.scopedTenantKeys(),
            valid.scopedRoleKeys(),
            valid.tenantAuthorityRouteKeys(),
            valid.requiredCallerMembershipKeys(),
            valid.billingSafeTenantKeys(),
            valid.applicableBillingCutoffKeys());
    assertInvalidShape(catalog, AccountTokenProfileCatalog.CONTROL_UI, missingRequiredTupleMap);

    ControlUiShape overSharedScope =
        new ControlUiShape(
            controlUiClaims(false),
            authority(
                Set.of(TENANT_A, TENANT_B, TENANT_C, uuid(4), uuid(5)),
                Set.of(TENANT_A, TENANT_B, TENANT_C, uuid(4), uuid(5)),
                Set.of(TENANT_A, TENANT_B, TENANT_C, uuid(4), uuid(5)),
                Set.of(),
                List.of()),
            Set.of(TENANT_A, TENANT_B, TENANT_C, uuid(4), uuid(5)),
            Set.of(TENANT_A, TENANT_B, TENANT_C, uuid(4), uuid(5)),
            Set.of(TENANT_A, TENANT_B, TENANT_C, uuid(4), uuid(5)),
            Set.of(TENANT_A, TENANT_B, TENANT_C, uuid(4), uuid(5)),
            Set.of(),
            Set.of());
    assertInvalidShape(catalog, AccountTokenProfileCatalog.CONTROL_UI, overSharedScope);
  }

  @Test
  void acceptsEachControlUiFieldAtItsExactFiniteCardinalityAndRejectsOneOver() {
    AccountTokenProfileCatalog catalog = catalog();
    ControlUiShape exactBoundary = controlUiShapeAtFiniteLimits();
    validatePreSign(
        catalog, AccountTokenProfileCatalog.CONTROL_UI, exactBoundary, tupleJson(exactBoundary));

    assertInvalidShape(
        catalogWithUiFieldLimits(2, 4, 4, 3), AccountTokenProfileCatalog.CONTROL_UI, exactBoundary);
    assertInvalidShape(
        catalogWithUiFieldLimits(3, 3, 4, 3), AccountTokenProfileCatalog.CONTROL_UI, exactBoundary);
    assertInvalidShape(
        catalogWithUiFieldLimits(3, 4, 3, 3), AccountTokenProfileCatalog.CONTROL_UI, exactBoundary);
    assertInvalidShape(
        catalogWithUiFieldLimits(3, 4, 4, 2), AccountTokenProfileCatalog.CONTROL_UI, exactBoundary);
  }

  @Test
  void acceptsOptionalSingleAccountSecurityCutoffWithoutTreatingItAsTokenScope() {
    AccountTokenProfileCatalog catalog = catalog();
    ControlUiShape shape = controlUiShape();
    ControlUiShape withAccountCutoff =
        new ControlUiShape(
            controlUiClaims(true, true),
            shape.authority(),
            shape.scopedTenantKeys(),
            shape.scopedRoleKeys(),
            shape.tenantAuthorityRouteKeys(),
            shape.requiredCallerMembershipKeys(),
            shape.billingSafeTenantKeys(),
            shape.applicableBillingCutoffKeys());
    validatePreSign(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        withAccountCutoff,
        tupleJson(withAccountCutoff));
  }

  @Test
  void enforcesEmptyBootstrapAndNonTenantDelegationShapes() {
    AccountTokenProfileCatalog catalog = catalog();
    PlayerBootstrapShape bootstrap =
        new PlayerBootstrapShape(bootstrapClaims(), emptyAuthority(), Set.of());
    validatePreSign(
        catalog, AccountTokenProfileCatalog.PLAYER_BOOTSTRAP, bootstrap, tupleJson(bootstrap));

    AuthorityMapShape bootstrapWithTenant =
        authority(Set.of(TENANT_A), Set.of(), Set.of(), Set.of(), List.of());
    PlayerBootstrapShape wrongBootstrap =
        new PlayerBootstrapShape(bootstrapClaims(), bootstrapWithTenant, Set.of());
    assertInvalidShape(catalog, AccountTokenProfileCatalog.PLAYER_BOOTSTRAP, wrongBootstrap);

    NonTenantDelegationShape nonTenant =
        new NonTenantDelegationShape(delegationClaims(false, false), emptyAuthority(), Set.of());
    validatePreSign(
        catalog,
        AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
        nonTenant,
        tupleJson(nonTenant));

    NonTenantDelegationShape nonTenantWithRole =
        new NonTenantDelegationShape(
            delegationClaims(true, false), emptyAuthority(), Set.of(TENANT_A));
    assertInvalidShape(
        catalog, AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION, nonTenantWithRole);
  }

  @Test
  void enforcesTenantDelegationExactMapsOptionalCutoffAndLifecycleBoundGrant() {
    AccountTokenProfileCatalog catalog = catalog();
    TenantBoundDelegationShape publicDelegation =
        tenantDelegation(true, Optional.empty(), List.of());
    validatePreSign(
        catalog,
        AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
        publicDelegation,
        tupleJson(publicDelegation));

    TenantBoundDelegationShape privateDelegation =
        tenantDelegation(
            false,
            Optional.of(new PrivateRealmTarget(TENANT_A, "emberfall", "preview", LIFECYCLE_A)),
            List.of(grant(TENANT_A, LIFECYCLE_A, LARGE_GRANT_VERSION)));
    validatePreSign(
        catalog,
        AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
        privateDelegation,
        tupleJson(privateDelegation));

    TenantBoundDelegationShape missingGrant =
        tenantDelegation(
            false,
            Optional.of(new PrivateRealmTarget(TENANT_A, "emberfall", "preview", LIFECYCLE_A)),
            List.of());
    assertInvalidShape(
        catalog, AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION, missingGrant);

    TenantBoundDelegationShape wrongLifecycle =
        tenantDelegation(
            false,
            Optional.of(new PrivateRealmTarget(TENANT_A, "emberfall", "preview", LIFECYCLE_A)),
            List.of(grant(TENANT_A, uuid(99), "7")));
    assertInvalidShape(
        catalog, AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION, wrongLifecycle);

    TenantBoundDelegationShape otherTenantGrantAndTarget =
        new TenantBoundDelegationShape(
            delegationClaims(true, true),
            authority(
                Set.of(TENANT_A),
                Set.of(TENANT_A),
                Set.of(TENANT_A),
                Set.of(TENANT_A),
                List.of(grant(TENANT_B, LIFECYCLE_A, "7"))),
            TENANT_A,
            Set.of(TENANT_A),
            false,
            Optional.of(new PrivateRealmTarget(TENANT_B, "emberfall", "preview", LIFECYCLE_A)));
    assertInvalidShape(
        catalog,
        AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
        otherTenantGrantAndTarget);

    TenantBoundDelegationShape wrongCutoffKey =
        new TenantBoundDelegationShape(
            delegationClaims(true, true),
            authority(
                Set.of(TENANT_A), Set.of(TENANT_A), Set.of(TENANT_A), Set.of(TENANT_B), List.of()),
            TENANT_A,
            Set.of(TENANT_A),
            true,
            Optional.empty());
    assertInvalidShape(
        catalog, AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION, wrongCutoffKey);

    assertThrows(IllegalArgumentException.class, () -> grant(TENANT_A, LIFECYCLE_A, "01"));
    assertThrows(IllegalArgumentException.class, () -> grant(TENANT_A, LIFECYCLE_A, "0"));
  }

  @Test
  void rejectsNonCanonicalAndOverLimitAuthorityTupleBeforeSigning() {
    ControlUiShape shape = controlUiShape();
    String canonicalTuple = tupleJson(shape);
    long tupleBytes = canonicalTuple.getBytes(StandardCharsets.UTF_8).length;
    AccountTokenProfileCatalog exact = catalogWithUiTupleLimit(tupleBytes);
    validatePreSign(exact, AccountTokenProfileCatalog.CONTROL_UI, shape, canonicalTuple);

    AccountTokenProfileCatalog oneByteSmaller = catalogWithUiTupleLimit(tupleBytes - 1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            oneByteSmaller.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_900,
                canonicalTuple));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog()
                .validatePreSignCandidate(
                    AccountTokenProfileCatalog.CONTROL_UI,
                    shape,
                    actualClaimMaps(shape),
                    1_000,
                    1_900,
                    "{\"tenantAuthorityGeneration\":{},\"accountAuthorityGeneration\":\"1\"}"));
  }

  @Test
  void bindsSerializedAuthorityTupleMapsAndGrantsToTheSuppliedTypedShape() {
    AccountTokenProfileCatalog catalog = catalog();
    ControlUiShape shape = controlUiShape();
    validatePreSign(catalog, AccountTokenProfileCatalog.CONTROL_UI, shape, tupleJson(shape));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_900,
                tupleJson(
                    shape,
                    Map.of(
                        "tenantAuthorityGeneration",
                        generationMap(Set.of(TENANT_A, TENANT_B, TENANT_C, TENANT_D))))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_900,
                tupleJson(
                    shape,
                    Map.of("membershipAuthorityGeneration", generationMap(Set.of(TENANT_A))))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_900,
                tupleJson(shape, Map.of("tenantBillingCutoff", cutoffMap(Set.of(TENANT_A))))));

    ControlUiShape withAccountCutoff =
        new ControlUiShape(
            controlUiClaims(true, true),
            shape.authority(),
            shape.scopedTenantKeys(),
            shape.scopedRoleKeys(),
            shape.tenantAuthorityRouteKeys(),
            shape.requiredCallerMembershipKeys(),
            shape.billingSafeTenantKeys(),
            shape.applicableBillingCutoffKeys());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                withAccountCutoff,
                actualClaimMaps(withAccountCutoff),
                1_000,
                1_900,
                tupleJsonWithoutField(withAccountCutoff, "accountSecurityCutoff")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_900,
                tupleJson(
                    shape,
                    Map.of(
                        "accountSecurityCutoff",
                        Map.of(
                            "accountAuthorityGeneration",
                            "1",
                            "outboxSequence",
                            "1",
                            "outboxStreamKey",
                            "account")))));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_900,
                tupleJsonWithoutField(shape, "tenantAuthorityGeneration")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_900,
                tupleJson(shape, Collections.singletonMap("tenantAuthorityGeneration", null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_900,
                tupleJson(shape, Map.of("membershipAuthorityGeneration", "not-a-map"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_900,
                tupleJson(shape, Collections.singletonMap("privateRealmGrantVersions", null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_900,
                tupleJson(shape, Map.of("privateRealmGrantVersions", Map.of()))));

    TenantBoundDelegationShape privateShape =
        tenantDelegation(
            false,
            Optional.of(new PrivateRealmTarget(TENANT_A, "emberfall", "preview", LIFECYCLE_A)),
            List.of(grant(TENANT_A, LIFECYCLE_A, LARGE_GRANT_VERSION)));
    String differentGrant =
        tupleJson(
            privateShape,
            Map.of(
                "privateRealmGrantVersions",
                grantList(List.of(grant(TENANT_A, LIFECYCLE_A, "7")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
                privateShape,
                actualClaimMaps(privateShape),
                1_000,
                1_600,
                differentGrant));
    String differentGrantIdentity =
        tupleJson(
            privateShape,
            Map.of(
                "privateRealmGrantVersions",
                grantList(List.of(grant(TENANT_B, LIFECYCLE_A, LARGE_GRANT_VERSION)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
                privateShape,
                actualClaimMaps(privateShape),
                1_000,
                1_600,
                differentGrantIdentity));
  }

  @Test
  void bindsActualMembershipVersionAndScopedRoleValuesToTypedScopeWithoutRounding() {
    AccountTokenProfileCatalog catalog = catalog();
    ControlUiShape shape = controlUiShape();
    BigInteger largeVersion = new BigInteger("900719925474099312345678901234567890");
    Map<String, BigInteger> membershipVersion = new LinkedHashMap<>();
    membershipVersion.put(TENANT_A.toString(), largeVersion);
    membershipVersion.put(TENANT_B.toString(), BigInteger.ONE);
    List<String> rolesForTenantA = new ArrayList<>(List.of("tenantAdmin", "designer"));
    Map<String, List<String>> scopedRoles = new LinkedHashMap<>();
    scopedRoles.put(TENANT_A.toString(), rolesForTenantA);

    ActualClaimMaps copied =
        new ActualClaimMaps(Optional.of(membershipVersion), Optional.of(scopedRoles));
    membershipVersion.clear();
    rolesForTenantA.clear();
    scopedRoles.clear();

    assertEquals(largeVersion, copied.membershipVersion().orElseThrow().get(TENANT_A.toString()));
    assertEquals(
        List.of("tenantAdmin", "designer"),
        copied.scopedRoles().orElseThrow().get(TENANT_A.toString()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> copied.membershipVersion().orElseThrow().put(TENANT_C.toString(), BigInteger.ONE));
    assertThrows(
        UnsupportedOperationException.class,
        () -> copied.scopedRoles().orElseThrow().get(TENANT_A.toString()).add("player"));

    catalog.validatePreSignCandidate(
        AccountTokenProfileCatalog.CONTROL_UI, shape, copied, 1_000, 1_900, tupleJson(shape));
  }

  @Test
  void rejectsActualMembershipVersionAndScopedRoleScopeOrValueMismatches() {
    AccountTokenProfileCatalog catalog = catalog();
    ControlUiShape shape = controlUiShape();
    ActualClaimMaps valid = actualClaimMaps(shape);

    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            Optional.of(Map.of(TENANT_A.toString(), BigInteger.ONE)), valid.scopedRoles()));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            Optional.of(
                Map.of(
                    TENANT_A.toString(), BigInteger.ONE,
                    TENANT_B.toString(), BigInteger.ONE,
                    TENANT_C.toString(), BigInteger.ONE)),
            valid.scopedRoles()));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            Optional.of(
                Map.of(TENANT_C.toString(), BigInteger.ONE, TENANT_B.toString(), BigInteger.ONE)),
            valid.scopedRoles()));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            Optional.of(
                Map.of(
                    UUID.fromString("abcdef00-0000-4000-8000-000000000001")
                        .toString()
                        .toUpperCase(),
                    BigInteger.ONE,
                    TENANT_B.toString(),
                    BigInteger.ONE)),
            valid.scopedRoles()));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            Optional.of(
                Map.of(
                    TENANT_A.toString(), BigInteger.ZERO,
                    TENANT_B.toString(), BigInteger.ONE)),
            valid.scopedRoles()));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            Optional.of(
                Map.of(
                    TENANT_A.toString(),
                    BigInteger.valueOf(-1),
                    TENANT_B.toString(),
                    BigInteger.ONE)),
            valid.scopedRoles()));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            valid.membershipVersion(),
            Optional.of(Map.of(TENANT_B.toString(), List.of("designer")))));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            valid.membershipVersion(),
            Optional.of(
                Map.of(
                    TENANT_A.toString(), List.of("designer"),
                    TENANT_B.toString(), List.of("moderator")))));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            valid.membershipVersion(),
            Optional.of(Map.of(TENANT_A.toString(), List.of("billingAdmin")))));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            valid.membershipVersion(),
            Optional.of(Map.of(TENANT_A.toString(), List.of("designer", "designer")))));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        shape,
        new ActualClaimMaps(
            valid.membershipVersion(), Optional.of(Map.of(TENANT_A.toString(), List.of()))));
  }

  @Test
  void preservesRequiredEmptyScopedRolesAndOptionalDelegationOmissionSeparately() {
    AccountTokenProfileCatalog catalog = catalog();
    ControlUiShape emptyControlUi =
        new ControlUiShape(
            controlUiClaims(false),
            emptyAuthority(),
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of());
    ActualClaimMaps presentEmpty =
        new ActualClaimMaps(Optional.of(Map.of()), Optional.of(Map.of()));
    catalog.validatePreSignCandidate(
        AccountTokenProfileCatalog.CONTROL_UI,
        emptyControlUi,
        presentEmpty,
        1_000,
        1_900,
        tupleJson(emptyControlUi));

    TenantBoundDelegationShape omittedScopedRoles =
        new TenantBoundDelegationShape(
            delegationClaims(false, false),
            authority(Set.of(TENANT_A), Set.of(TENANT_A), Set.of(TENANT_A), Set.of(), List.of()),
            TENANT_A,
            Set.of(),
            true,
            Optional.empty());
    catalog.validatePreSignCandidate(
        AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
        omittedScopedRoles,
        new ActualClaimMaps(
            Optional.of(Map.of(TENANT_A.toString(), BigInteger.ONE)), Optional.empty()),
        1_000,
        1_600,
        tupleJson(omittedScopedRoles));

    TenantBoundDelegationShape emptyScopedRoles =
        new TenantBoundDelegationShape(
            delegationClaims(true, false),
            omittedScopedRoles.authority(),
            TENANT_A,
            Set.of(),
            true,
            Optional.empty());
    catalog.validatePreSignCandidate(
        AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
        emptyScopedRoles,
        new ActualClaimMaps(
            Optional.of(Map.of(TENANT_A.toString(), BigInteger.ONE)), Optional.of(Map.of())),
        1_000,
        1_600,
        tupleJson(emptyScopedRoles));

    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        emptyControlUi,
        new ActualClaimMaps(Optional.empty(), Optional.of(Map.of())));
    assertInvalidClaimMaps(
        catalog,
        AccountTokenProfileCatalog.CONTROL_UI,
        emptyControlUi,
        new ActualClaimMaps(Optional.of(Map.of()), Optional.empty()));
  }

  @Test
  void checksPostSignCompactJwtAndCanonicalRegistryRecordUtf8ExactBoundaries() {
    String compactJwt = "YQ.Yg.Yw";
    String canonicalRecord = "{\"a\":1}";
    AccountTokenProfileCatalog exact = catalogWithUiPostSignLimits(8, 7);

    exact.validatePostSignCandidate(
        AccountTokenProfileCatalog.CONTROL_UI, compactJwt, canonicalRecord);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            exact.validatePostSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI, "YWE.Yg.Yw", canonicalRecord));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            exact.validatePostSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI, compactJwt, "{\"a\":10}"));

    String unicodeRecord = "{\"x\":\"é\"}";
    int unicodeUtf8Length = unicodeRecord.getBytes(StandardCharsets.UTF_8).length;
    AccountTokenProfileCatalog unicodeBoundary = catalogWithUiPostSignLimits(8, unicodeUtf8Length);
    unicodeBoundary.validatePostSignCandidate(
        AccountTokenProfileCatalog.CONTROL_UI, compactJwt, unicodeRecord);
    assertTrue(unicodeUtf8Length > unicodeRecord.length());
  }

  @Test
  void rejectsInvalidCompactJwtAndNonCanonicalRegistryRecordWithoutEchoingJwt() {
    AccountTokenProfileCatalog catalog = catalog();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePostSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI, "YQ.Yg", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePostSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI, "YQ.Yg.é", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePostSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI, "YQ.Yg.Yw", "{ \"a\":1}"));

    String compactJwt = "aGVhZGVy.c2VjcmV0.c2lnbmF0dXJl";
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                catalogWithUiPostSignLimits(8, 7)
                    .validatePostSignCandidate(
                        AccountTokenProfileCatalog.CONTROL_UI, compactJwt, "{\"a\":1}"));
    assertFalse(failure.getMessage().contains(compactJwt));
  }

  @Test
  void rejectsMalformedRawAndEscapedSurrogatesBeforeUtf8ByteMeasurement() {
    AccountTokenProfileCatalog catalog = catalog();
    String compactJwt = "YQ.Yg.Yw";
    String rawHighSurrogate = new String(new char[] {(char) 0xD800});
    String rawLowSurrogate = new String(new char[] {(char) 0xDC00});
    String decomposedAccent = new String(new char[] {'e', (char) 0x0301});

    catalog.validatePostSignCandidate(
        AccountTokenProfileCatalog.CONTROL_UI,
        compactJwt,
        "{\"value\":\"" + decomposedAccent + "\"}");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePostSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                compactJwt,
                "{\"value\":\"" + rawHighSurrogate + "\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePostSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI,
                compactJwt,
                "{\"value\":\"" + rawLowSurrogate + "\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePostSignCandidate(
                AccountTokenProfileCatalog.CONTROL_UI, compactJwt, "{\"value\":\"\\ud800\"}"));
  }

  private static void validatePreSign(
      AccountTokenProfileCatalog catalog,
      String profileId,
      AccountTokenProfileCatalog.ProfileShape shape,
      String tupleJson) {
    catalog.validatePreSignCandidate(
        profileId, shape, actualClaimMaps(shape), 1_000, 1_000 + lifetime(profileId), tupleJson);
  }

  private static ActualClaimMaps actualClaimMaps(AccountTokenProfileCatalog.ProfileShape shape) {
    Optional<Map<String, BigInteger>> membershipVersion = Optional.empty();
    if (shape.claims().membershipVersionPresent()) {
      Map<String, BigInteger> values = new TreeMap<>();
      shape
          .authority()
          .membershipVersionKeys()
          .forEach(tenantId -> values.put(tenantId.toString(), BigInteger.ONE));
      membershipVersion = Optional.of(values);
    }

    Optional<Map<String, List<String>>> scopedRoles = Optional.empty();
    if (shape.claims().scopedRolesPresent()) {
      Map<String, List<String>> values = new TreeMap<>();
      shape
          .scopedRoleKeys()
          .forEach(tenantId -> values.put(tenantId.toString(), List.of("moderator")));
      scopedRoles = Optional.of(values);
    }
    return new ActualClaimMaps(membershipVersion, scopedRoles);
  }

  private static void assertInvalidClaimMaps(
      AccountTokenProfileCatalog catalog,
      String profileId,
      AccountTokenProfileCatalog.ProfileShape shape,
      ActualClaimMaps actualClaims) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                profileId,
                shape,
                actualClaims,
                1_000,
                1_000 + lifetime(profileId),
                tupleJson(shape)));
  }

  private static long lifetime(String profileId) {
    return switch (profileId) {
      case AccountTokenProfileCatalog.CONTROL_UI -> 900;
      case AccountTokenProfileCatalog.PLAYER_BOOTSTRAP -> 300;
      case AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION -> 600;
      default -> throw new IllegalArgumentException("unsupported test profile");
    };
  }

  private static AccountTokenProfileCatalog catalog() {
    return new AccountTokenProfileCatalog(
        AccountTokenProfileCatalog.VERSION, validLimits(), DEPLOYMENT);
  }

  private static AccountTokenProfileCatalog catalogWithUiTupleLimit(long tupleLimit) {
    Map<String, ProfileLimits> profiles = validLimits();
    ProfileLimits current = profiles.get(AccountTokenProfileCatalog.CONTROL_UI);
    profiles.put(
        AccountTokenProfileCatalog.CONTROL_UI,
        limits(
            current.audience(),
            current.maxTokenLifetimeSeconds(),
            current.maxTenantAuthorityEntries(),
            current.maxMembershipAuthorityEntries(),
            current.maxMembershipVersionEntries(),
            current.maxTenantBillingCutoffEntries(),
            current.maxPrivateRealmGrantEntries(),
            tupleLimit,
            current.maxCompactJwtBytes(),
            current.maxRegistryRecordBytes(),
            current.maxControlUiTenantScopes()));
    return new AccountTokenProfileCatalog(AccountTokenProfileCatalog.VERSION, profiles, DEPLOYMENT);
  }

  private static AccountTokenProfileCatalog catalogWithUiFieldLimits(
      int tenantAuthority, int membershipAuthority, int membershipVersion, int billingCutoff) {
    Map<String, ProfileLimits> profiles = validLimits();
    ProfileLimits current = profiles.get(AccountTokenProfileCatalog.CONTROL_UI);
    profiles.put(
        AccountTokenProfileCatalog.CONTROL_UI,
        limits(
            current.audience(),
            current.maxTokenLifetimeSeconds(),
            tenantAuthority,
            membershipAuthority,
            membershipVersion,
            billingCutoff,
            current.maxPrivateRealmGrantEntries(),
            current.maxAuthorityTupleBytes(),
            current.maxCompactJwtBytes(),
            current.maxRegistryRecordBytes(),
            current.maxControlUiTenantScopes()));
    return new AccountTokenProfileCatalog(AccountTokenProfileCatalog.VERSION, profiles, DEPLOYMENT);
  }

  private static AccountTokenProfileCatalog catalogWithUiPostSignLimits(
      long compactLimit, long recordLimit) {
    Map<String, ProfileLimits> profiles = validLimits();
    ProfileLimits current = profiles.get(AccountTokenProfileCatalog.CONTROL_UI);
    profiles.put(
        AccountTokenProfileCatalog.CONTROL_UI,
        limits(
            current.audience(),
            current.maxTokenLifetimeSeconds(),
            current.maxTenantAuthorityEntries(),
            current.maxMembershipAuthorityEntries(),
            current.maxMembershipVersionEntries(),
            current.maxTenantBillingCutoffEntries(),
            current.maxPrivateRealmGrantEntries(),
            Math.min(current.maxAuthorityTupleBytes(), recordLimit),
            compactLimit,
            recordLimit,
            current.maxControlUiTenantScopes()));
    return new AccountTokenProfileCatalog(
        AccountTokenProfileCatalog.VERSION,
        profiles,
        new DeploymentCeilings(
            Math.max(compactLimit, 12_000),
            DEPLOYMENT.maxCoordinationAuthorityTupleBytes(),
            DEPLOYMENT.maxCoordinationRegistryRecordBytes(),
            DEPLOYMENT.maxRegistryTokenLifetimeSeconds()));
  }

  private static Map<String, ProfileLimits> validLimits() {
    Map<String, ProfileLimits> profiles = new LinkedHashMap<>();
    profiles.put(AccountTokenProfileCatalog.CONTROL_UI, uiLimits());
    profiles.put(
        AccountTokenProfileCatalog.PLAYER_BOOTSTRAP,
        limits("player-bootstrap", 300, 0, 0, 0, 0, 0, 8_000, 12_000, 16_000, Optional.empty()));
    profiles.put(
        AccountTokenProfileCatalog.GAME_SESSION_ACCOUNT_DELEGATION,
        limits("account-service", 600, 1, 1, 1, 1, 1, 16_000, 12_000, 32_000, Optional.empty()));
    return profiles;
  }

  private static ProfileLimits uiLimits() {
    return limits("control-ui", 900, 3, 4, 4, 3, 0, 16_000, 12_000, 32_000, Optional.of(4));
  }

  private static ProfileLimits forbiddenLimits(String audience) {
    return limits(audience, 10, 0, 0, 0, 0, 0, 1, 1, 1, Optional.empty());
  }

  private static ProfileLimits limits(
      String audience,
      long lifetime,
      int tenantAuthority,
      int membershipAuthority,
      int membershipVersion,
      int billingCutoff,
      int grants,
      long tupleBytes,
      long compactJwtBytes,
      long recordBytes,
      Optional<Integer> controlUiScopes) {
    return new ProfileLimits(
        audience,
        lifetime,
        tenantAuthority,
        membershipAuthority,
        membershipVersion,
        billingCutoff,
        grants,
        tupleBytes,
        compactJwtBytes,
        recordBytes,
        controlUiScopes);
  }

  private static Map<String, ProfileLimits> replace(
      Map<String, ProfileLimits> profiles, String profileId, ProfileLimits limits) {
    profiles.put(profileId, limits);
    return profiles;
  }

  private static void assertInvalidCatalog(Map<String, ProfileLimits> profiles) {
    assertInvalidCatalog(profiles, DEPLOYMENT);
  }

  private static void assertInvalidCatalog(
      Map<String, ProfileLimits> profiles, DeploymentCeilings ceilings) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccountTokenProfileCatalog(AccountTokenProfileCatalog.VERSION, profiles, ceilings));
  }

  private static void assertInvalidShape(
      AccountTokenProfileCatalog catalog,
      String profileId,
      AccountTokenProfileCatalog.ProfileShape shape) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            catalog.validatePreSignCandidate(
                profileId,
                shape,
                actualClaimMaps(shape),
                1_000,
                1_000 + lifetime(profileId),
                tupleJson(shape)));
  }

  private static ControlUiShape controlUiShape() {
    return new ControlUiShape(
        controlUiClaims(true),
        authority(
            Set.of(TENANT_A),
            Set.of(TENANT_A, TENANT_B),
            Set.of(TENANT_A, TENANT_B),
            Set.of(TENANT_A, TENANT_B),
            List.of()),
        Set.of(TENANT_A, TENANT_B),
        Set.of(TENANT_A),
        Set.of(TENANT_A),
        Set.of(TENANT_A, TENANT_B),
        Set.of(TENANT_B),
        Set.of(TENANT_A, TENANT_B));
  }

  private static ControlUiShape controlUiShapeAtFiniteLimits() {
    return new ControlUiShape(
        controlUiClaims(true),
        authority(
            Set.of(TENANT_A, TENANT_B, TENANT_C),
            Set.of(TENANT_A, TENANT_B, TENANT_C, TENANT_D),
            Set.of(TENANT_A, TENANT_B, TENANT_C, TENANT_D),
            Set.of(TENANT_A, TENANT_B, TENANT_C),
            List.of()),
        Set.of(TENANT_A, TENANT_B, TENANT_C, TENANT_D),
        Set.of(TENANT_A, TENANT_B, TENANT_C),
        Set.of(TENANT_A, TENANT_B, TENANT_C),
        Set.of(TENANT_A, TENANT_B, TENANT_C, TENANT_D),
        Set.of(TENANT_D),
        Set.of(TENANT_A, TENANT_B, TENANT_C));
  }

  private static TenantBoundDelegationShape tenantDelegation(
      boolean publicProduction,
      Optional<PrivateRealmTarget> target,
      List<PrivateRealmGrantShape> grants) {
    return new TenantBoundDelegationShape(
        delegationClaims(true, !publicProduction && !grants.isEmpty()),
        authority(
            Set.of(TENANT_A),
            Set.of(TENANT_A),
            Set.of(TENANT_A),
            !publicProduction && !grants.isEmpty() ? Set.of(TENANT_A) : Set.of(),
            grants),
        TENANT_A,
        Set.of(TENANT_A),
        publicProduction,
        target);
  }

  private static PrivateRealmGrantShape grant(UUID tenantId, UUID lifecycleId, String version) {
    return new PrivateRealmGrantShape(tenantId, "emberfall", "preview", lifecycleId, version);
  }

  private static AuthorityMapShape authority(
      Set<UUID> tenantAuthority,
      Set<UUID> membershipAuthority,
      Set<UUID> membershipVersion,
      Set<UUID> billingCutoff,
      List<PrivateRealmGrantShape> grants) {
    return new AuthorityMapShape(
        tenantAuthority, membershipAuthority, membershipVersion, billingCutoff, grants);
  }

  private static AuthorityMapShape emptyAuthority() {
    return authority(Set.of(), Set.of(), Set.of(), Set.of(), List.of());
  }

  private static ClaimFieldPresence controlUiClaims(boolean cutoffPresent) {
    return controlUiClaims(cutoffPresent, false);
  }

  private static ClaimFieldPresence controlUiClaims(
      boolean cutoffPresent, boolean accountCutoffPresent) {
    return claims("control-ui", true, cutoffPresent, accountCutoffPresent);
  }

  private static ClaimFieldPresence bootstrapClaims() {
    return claims("player-bootstrap", true, false, false);
  }

  private static ClaimFieldPresence delegationClaims(
      boolean scopedRolesPresent, boolean cutoffPresent) {
    return claims("account-service", scopedRolesPresent, cutoffPresent, false);
  }

  private static ClaimFieldPresence claims(
      String audience,
      boolean scopedRolesPresent,
      boolean tenantCutoffPresent,
      boolean accountCutoff) {
    return new ClaimFieldPresence(
        true,
        true,
        true,
        true,
        audience,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        scopedRolesPresent,
        tenantCutoffPresent,
        accountCutoff);
  }

  private static ClaimFieldPresence claimsWithoutMembershipVersion() {
    return new ClaimFieldPresence(
        true,
        true,
        true,
        true,
        "control-ui",
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        false,
        true,
        true,
        true,
        true,
        true,
        false);
  }

  private static String tupleJson(AccountTokenProfileCatalog.ProfileShape shape) {
    return canonicalTupleJson(tupleMap(shape));
  }

  private static String tupleJson(
      AccountTokenProfileCatalog.ProfileShape shape, Map<String, Object> overrides) {
    Map<String, Object> tuple = tupleMap(shape);
    tuple.putAll(overrides);
    return canonicalTupleJson(tuple);
  }

  private static String tupleJsonWithoutField(
      AccountTokenProfileCatalog.ProfileShape shape, String field) {
    Map<String, Object> tuple = tupleMap(shape);
    tuple.remove(field);
    return canonicalTupleJson(tuple);
  }

  private static Map<String, Object> tupleMap(AccountTokenProfileCatalog.ProfileShape shape) {
    AuthorityMapShape authority = shape.authority();
    Map<String, Object> tuple = new TreeMap<>();
    tuple.put("accountAuthorityGeneration", "1");
    tuple.put("issuerAuthGeneration", "1");
    tuple.put("membershipAuthorityGeneration", generationMap(authority.membershipAuthorityKeys()));
    tuple.put("privateRealmGrantVersions", grantList(authority.privateRealmGrants()));
    tuple.put("tenantAuthorityGeneration", generationMap(authority.tenantAuthorityKeys()));
    if (!authority.tenantBillingCutoffKeys().isEmpty()) {
      tuple.put("tenantBillingCutoff", cutoffMap(authority.tenantBillingCutoffKeys()));
    }
    if (shape.claims().accountSecurityCutoffPresent()) {
      tuple.put(
          "accountSecurityCutoff",
          Map.of(
              "accountAuthorityGeneration",
              "1",
              "outboxSequence",
              "1",
              "outboxStreamKey",
              "account"));
    }
    return tuple;
  }

  private static String canonicalTupleJson(Map<String, Object> tuple) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(tuple)),
          StandardCharsets.UTF_8);
    } catch (Exception exception) {
      throw new IllegalStateException("test tuple could not be canonicalized", exception);
    }
  }

  private static Map<String, String> generationMap(Set<UUID> tenantIds) {
    Map<String, String> map = new TreeMap<>();
    tenantIds.forEach(tenantId -> map.put(tenantId.toString(), "1"));
    return map;
  }

  private static List<Map<String, String>> grantList(List<PrivateRealmGrantShape> grants) {
    List<Map<String, String>> values = new ArrayList<>();
    for (PrivateRealmGrantShape grant : grants) {
      Map<String, String> value = new TreeMap<>();
      value.put("grantVersion", grant.grantVersionDecimal());
      value.put("playtestLifecycleId", grant.playtestLifecycleId().toString());
      value.put("realmSlug", grant.realmSlug());
      value.put("tenantId", grant.tenantId().toString());
      value.put("worldSlug", grant.worldSlug());
      values.add(value);
    }
    return values;
  }

  private static Map<String, Object> cutoffMap(Set<UUID> tenantIds) {
    Map<String, Object> values = new TreeMap<>();
    tenantIds.forEach(
        tenantId ->
            values.put(
                tenantId.toString(),
                Map.of(
                    "outboxSequence", "1",
                    "outboxStreamKey", "tenant",
                    "tenantAuthorityGeneration", "1",
                    "tenantBillingSequence", "1")));
    return values;
  }

  private static UUID uuid(long suffix) {
    return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", suffix));
  }
}
