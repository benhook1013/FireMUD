package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;

/**
 * Immutable Account SQL sources captured for public fresh-admission owner work.
 *
 * <p>The values are valid only within the transaction that captured them. The token fence is
 * persisted SQL state; it does not establish caller authentication, token cryptographic validity,
 * registry state, or gameplay admission by itself.
 */
public record AccountGameplayPublicAdmissionSourceSnapshot(
    AccountMembershipRoleSourceSnapshot membershipSource,
    DemoTenantEntitlementSnapshot entitlementSource,
    AccountGameplayTokenIdentityFence tokenIdentityFence) {
  public AccountGameplayPublicAdmissionSourceSnapshot {
    Objects.requireNonNull(membershipSource, "Current membership source is required");
    Objects.requireNonNull(entitlementSource, "Current demo entitlement source is required");
    Objects.requireNonNull(tokenIdentityFence, "Current SQL token fence is required");
  }
}
