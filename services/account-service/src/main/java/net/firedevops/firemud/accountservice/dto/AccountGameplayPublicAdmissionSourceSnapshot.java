package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;

/**
 * Immutable Account SQL sources captured for public fresh-admission owner work.
 *
 * <p>The values are valid only within the transaction that captured them. The token fence is
 * persisted SQL state and the lifecycle enum is copied from the exact Account row. Neither carries
 * caller authentication, token cryptographic validity, the separate safety-restriction projections,
 * registry state, or gameplay admission by itself.
 */
public record AccountGameplayPublicAdmissionSourceSnapshot(
    AccountMembershipRoleSourceSnapshot membershipSource,
    AccountLifecycleState accountLifecycleState,
    DemoTenantEntitlementSnapshot entitlementSource,
    AccountGameplayTokenIdentityFence tokenIdentityFence) {
  public AccountGameplayPublicAdmissionSourceSnapshot {
    Objects.requireNonNull(membershipSource, "Current membership source is required");
    Objects.requireNonNull(accountLifecycleState, "Current Account lifecycle source is required");
    Objects.requireNonNull(entitlementSource, "Current demo entitlement source is required");
    Objects.requireNonNull(tokenIdentityFence, "Current SQL token fence is required");
  }
}
