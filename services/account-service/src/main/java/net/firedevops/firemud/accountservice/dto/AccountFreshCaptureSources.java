package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountGlobalRoleSourceRepository.FreshEmptySource;

/**
 * Partial fresh-Account capture material from one existing-only Account owner transaction.
 *
 * <p>This composes the existing membership source vector with the fresh Account's immutable
 * explicit-empty global-role birth source. It is source evidence only, not an authorization bundle,
 * credential decision, or admission result.
 */
public record AccountFreshCaptureSources(
    UUID requestedAccountUuid,
    UUID requestedTenantUuid,
    AccountMembershipCaptureSources membershipSources,
    FreshEmptySource freshGlobalRoleSource) {
  public AccountFreshCaptureSources {
    requireCanonicalUuid(requestedAccountUuid, "requested Account UUID");
    requireCanonicalUuid(requestedTenantUuid, "requested tenant UUID");
    Objects.requireNonNull(membershipSources, "existing membership sources are required");
    Objects.requireNonNull(freshGlobalRoleSource, "fresh global-role source is required");

    if (!requestedAccountUuid.equals(membershipSources.requestedAccountUuid())
        || !requestedTenantUuid.equals(membershipSources.requestedTenantUuid())) {
      throw new IllegalArgumentException(
          "Fresh capture membership sources differ from the exact canonical request");
    }

    if (!requestedAccountUuid.equals(freshGlobalRoleSource.accountUuid())
        || freshGlobalRoleSource.accountRowId() <= 0L
        || freshGlobalRoleSource.accountUuidSourceNumericId()
            != freshGlobalRoleSource.accountRowId()
        || !isFreshInsertProvenance(freshGlobalRoleSource.accountUuidProvenance())
        || !freshGlobalRoleSource.globalRoles().isEmpty()
        || freshGlobalRoleSource.globalRoleSourceVersion() <= 0L) {
      throw new IllegalArgumentException(
          "Fresh global-role source differs from its exact fresh Account identity");
    }
  }

  private static void requireCanonicalUuid(UUID value, String field) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
  }

  private static boolean isFreshInsertProvenance(AccountIdentityProvenance provenance) {
    return provenance == AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
        || provenance == AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT;
  }
}
