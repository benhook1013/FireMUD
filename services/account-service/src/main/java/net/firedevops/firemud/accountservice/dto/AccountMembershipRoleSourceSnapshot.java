package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;

/**
 * Immutable Account SQL source capture, including historical membership event and current owners.
 *
 * <p>This is not authentication, authorization, signed recipient evidence, an ACTIVE proof, a
 * mutation, or positive no-applicable-deadline evidence. In particular, INACTIVE and invalidated
 * history may be captured without authorizing admission. Its validity is limited to the enclosing
 * owner transaction; later mutation must revalidate the exact sources under its own owner locks.
 */
public record AccountMembershipRoleSourceSnapshot(
    IssuerAccountSourceSnapshot issuerAccountSources,
    CompositeSnapshot currentAuthority,
    TenantAuthorityEventV1Codec.Event tenantSource,
    PairAuthority pair,
    MembershipSource membership,
    RoleSnapshot roles,
    Checkpoint membershipCheckpoint,
    Event storedMembershipEvent,
    MembershipEvent membershipEvent) {
  public AccountMembershipRoleSourceSnapshot {
    Objects.requireNonNull(issuerAccountSources);
    Objects.requireNonNull(currentAuthority);
    Objects.requireNonNull(tenantSource);
    Objects.requireNonNull(pair);
    Objects.requireNonNull(membership);
    Objects.requireNonNull(roles);
    Objects.requireNonNull(membershipCheckpoint);
    Objects.requireNonNull(storedMembershipEvent);
    Objects.requireNonNull(membershipEvent);
  }

  /** Copies every membership identity/state field instead of retaining a mutable storage entity. */
  public record MembershipSource(
      long membershipId,
      long accountRowId,
      UUID accountId,
      AccountIdentityProvenance accountIdentityProvenance,
      long accountSourceNumericId,
      UUID tenantId,
      VerifiedTenantProvenance tenantProvenance,
      String lifecycleState,
      boolean gameplayAdmissionAllowed,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String authorityProvenance) {
    public MembershipSource {
      Objects.requireNonNull(accountId);
      Objects.requireNonNull(accountIdentityProvenance);
      Objects.requireNonNull(tenantId);
      Objects.requireNonNull(tenantProvenance);
      Objects.requireNonNull(lifecycleState);
      Objects.requireNonNull(authorityProvenance);
    }
  }
}
