package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayPublicAdmissionSourceSnapshot;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import net.firedevops.firemud.accountservice.dto.AccountMembershipRoleSourceSnapshot;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayTokenIdentityFenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, read-only-in-purpose SQL source composition for public fresh NON_PAID_DEMO work.
 *
 * <p>The caller must already own a writable Account transaction at REPEATABLE_READ or SERIALIZABLE
 * isolation. The membership reader supplies the first Account lock. This reader does not create a
 * transaction, write state, authenticate a caller or token, validate a signature or token registry,
 * establish Account safety restrictions, World ACTIVE, routing, coordination, grant, or deadline
 * evidence, decide admission, or issue or commit an admission lease. Current SQL token-fence state
 * and Account lifecycle are only captured sources, not complete token authority or admission.
 */
public final class AccountGameplayPublicAdmissionSourceReader {
  private final AccountMembershipRoleSourceReader membershipSources;
  private final AccountRepository accounts;
  private final AccountDemoTenantEntitlementRepository entitlements;
  private final AccountGameplayTokenIdentityFenceRepository tokenFences;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected Account owner sources are private transaction collaborators.")
  public AccountGameplayPublicAdmissionSourceReader(
      AccountMembershipRoleSourceReader membershipSources,
      AccountRepository accounts,
      AccountDemoTenantEntitlementRepository entitlements,
      AccountGameplayTokenIdentityFenceRepository tokenFences) {
    this.membershipSources = Objects.requireNonNull(membershipSources);
    this.accounts = Objects.requireNonNull(accounts);
    this.entitlements = Objects.requireNonNull(entitlements);
    this.tokenFences = Objects.requireNonNull(tokenFences);
  }

  /** Captures exact Account owner sources under the caller's existing serialized transaction. */
  public AccountGameplayPublicAdmissionSourceSnapshot readCurrent(
      UUID accountId, UUID tenantId, TokenIdentity identity) {
    requireUuid(accountId, "account");
    requireUuid(tenantId, "tenant");
    Objects.requireNonNull(identity, "Exact committed token identity is required");
    if (!accountId.equals(identity.accountId())) {
      throw new IllegalArgumentException("Token identity account differs from the exact account");
    }
    requireWritableOwnerTransaction();

    // This owner read establishes the Account-first lock order and the membership snapshot.
    AccountMembershipRoleSourceSnapshot membership =
        membershipSources.readCurrent(accountId, tenantId);
    requireMembership(membership, accountId, tenantId, identity);
    Account account =
        accounts
            .findByAccountUuid(accountId)
            .orElseThrow(AccountGameplayPublicAdmissionSourceReader::accountSourceUnavailable);
    AccountLifecycleState accountLifecycleState =
        requireAccountIdentityAndActiveLifecycle(account, membership, accountId);

    DemoTenantEntitlementSnapshot evaluated = entitlements.readCurrent(tenantId);
    require(evaluated != null, "Current demo entitlement source is unavailable");
    DemoTenantEntitlementSnapshot current = entitlements.revalidate(evaluated);
    require(
        current != null && current.equals(evaluated),
        "Revalidated demo entitlement source is unavailable or changed");
    requireEntitlement(membership, current, tenantId);

    AccountGameplayTokenIdentityFence tokenFence = tokenFences.requireActiveForUpdate(identity);
    require(
        tokenFence != null
            && identity.equals(tokenFence.identity())
            && tokenFence.state() == State.ACTIVE,
        "Exact Account SQL token identity is not active");

    return new AccountGameplayPublicAdmissionSourceSnapshot(
        membership, accountLifecycleState, current, tokenFence);
  }

  private static AccountLifecycleState requireAccountIdentityAndActiveLifecycle(
      Account account, AccountMembershipRoleSourceSnapshot membership, UUID accountId) {
    var member = membership.membership();
    var accountSource = membership.issuerAccountSources().account();
    require(
        accountId.equals(account.getAccountUuid())
            && account.getId() != null
            && account.getId() > 0L
            && account.getId() == member.accountRowId()
            && Objects.equals(account.getId(), accountSource.accountSourceNumericId())
            && account.getAccountUuidProvenance()
                == AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            && account.getAccountUuidProvenance() == member.accountIdentityProvenance()
            && AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
                .name()
                .equals(accountSource.accountUuidProvenance())
            && account.getAccountUuidSourceNumericId() != null
            && account.getAccountUuidSourceNumericId().equals(account.getId())
            && account.getAccountUuidSourceNumericId() == member.accountSourceNumericId(),
        "Current Account lifecycle source is unavailable or inconsistent");
    AccountLifecycleState lifecycleState = account.getLifecycleState();
    require(
        lifecycleState == AccountLifecycleState.ACTIVE,
        "Current Account lifecycle does not allow gameplay admission");
    return lifecycleState;
  }

  private static IllegalStateException accountSourceUnavailable() {
    return new IllegalStateException(
        "Current Account lifecycle source is unavailable or inconsistent");
  }

  private static void requireMembership(
      AccountMembershipRoleSourceSnapshot snapshot,
      UUID accountId,
      UUID tenantId,
      TokenIdentity identity) {
    require(snapshot != null, "Current membership source is unavailable");
    var member = snapshot.membership();
    require(
        accountId.equals(member.accountId())
            && tenantId.equals(member.tenantId())
            && "ACTIVE".equals(member.lifecycleState())
            && member.gameplayAdmissionAllowed(),
        "Current Account membership does not allow gameplay admission");
    require(
        snapshot.roles().roles().contains("player")
            && snapshot.roles().accountUuid() != null
            && accountId.equals(snapshot.roles().accountUuid())
            && tenantId.equals(snapshot.roles().tenantUuid())
            && snapshot.roles().membershipId() == member.membershipId()
            && snapshot.roles().snapshotVersion() == member.membershipVersion(),
        "Current Account membership has no exact player role source");
    require(
        accountId.equals(snapshot.issuerAccountSources().issuanceFence().accountId())
            && snapshot
                .currentAuthority()
                .issuanceFence()
                .equals(snapshot.issuerAccountSources().issuanceFence())
            && snapshot.issuerAccountSources().issuanceFence().value() == identity.issuanceFence(),
        "Current Account issuance fence differs from the exact token identity");
    require(
        tenantId.equals(snapshot.tenantSource().tenantId())
            && tenantId.equals(snapshot.pair().tenantUuid())
            && accountId.equals(snapshot.pair().accountUuid())
            && snapshot.pair().provenance().equals(member.tenantProvenance())
            && snapshot.pair().provenance().equals(snapshot.roles().tenantProvenance())
            && tenantId.toString().equals(snapshot.membershipEvent().tenantId())
            && accountId.toString().equals(snapshot.membershipEvent().accountId())
            && snapshot.currentAuthority().tenants().size() == 1
            && snapshot.currentAuthority().memberships().size() == 1,
        "Current Account membership source scope is inconsistent");
    var checkpoint = snapshot.membershipCheckpoint();
    var storedEvent = snapshot.storedMembershipEvent();
    var sourceEvent = snapshot.membershipEvent();
    require(
        checkpoint.outboxStreamKey().equals(storedEvent.outboxStreamKey())
            && checkpoint.outboxSequence() == storedEvent.outboxSequence()
            && checkpoint.sourceEventId().equals(storedEvent.eventId())
            && checkpoint.sourceEventDigest().equals(storedEvent.eventDigest())
            && snapshot.pair().eventSequence() == checkpoint.outboxSequence()
            && snapshot.pair().eventId().equals(checkpoint.sourceEventId())
            && snapshot.pair().eventDigest().equals(checkpoint.sourceEventDigest())
            && sourceEvent.eventId().equals(storedEvent.eventId())
            && sourceEvent.eventDigest().equals(storedEvent.eventDigest())
            && sourceEvent.roles().equals(snapshot.roles().roles())
            && sourceEvent.membershipLifecycleState().equals(member.lifecycleState())
            && sourceEvent.gameplayAdmissionAllowed() == member.gameplayAdmissionAllowed(),
        "Current Account membership event and checkpoint do not match the role snapshot");
  }

  private static void requireEntitlement(
      AccountMembershipRoleSourceSnapshot membership,
      DemoTenantEntitlementSnapshot entitlement,
      UUID tenantId) {
    var tenant = membership.tenantSource();
    var currentTenantAuthority =
        membership.currentAuthority().tenants().stream()
            .filter(state -> AuthorityScope.tenant(tenantId).equals(state.scope()))
            .findFirst()
            .orElse(null);
    require(
        currentTenantAuthority != null
            && tenantId.equals(entitlement.canonicalTenantId())
            && "NEW_GAME_ROW".equals(entitlement.sourceEvidence().provenanceKind())
            && entitlement.sourceEvidence().equals(tenant.sourceEvidence())
            && membership.pair().provenance().kind() == TenantProvenanceKind.FRESH_GAME_DESIGN
            && entitlement
                .sourceEvidence()
                .operationId()
                .equals(membership.pair().provenance().sourceOperationId())
            && entitlement
                .sourceEvidence()
                .evidenceDigest()
                .equals(membership.pair().provenance().digest())
            && entitlement.tenantAuthorityGeneration() == tenant.tenantAuthorityGeneration()
            && entitlement.tenantAuthoritySourceVersion() == tenant.tenantAuthoritySourceVersion()
            && currentTenantAuthority.generation() == tenant.tenantAuthorityGeneration()
            && currentTenantAuthority.sourceVersion() == tenant.tenantAuthoritySourceVersion()
            && entitlement.gameplayAvailable()
            && entitlement.allowNewGameplayBindings(),
        "Current public demo entitlement or fresh Game Design source is unavailable or denied");

    require(
        entitlement.tenantAuthorityOutboxStreamKey().equals(tenant.outboxStreamKey())
            && entitlement.tenantAuthorityOutboxSequence() == tenant.outboxSequence()
            && entitlement.tenantAuthorityEventId().equals(tenant.eventId())
            && entitlement.tenantAuthorityEventDigest().equals(tenant.eventDigest()),
        "Current demo entitlement differs from the tenant authority event checkpoint");
    require(
        entitlement.outboxStreamKey().equals(tenant.tenantBillingStreamKey())
            && entitlement.tenantBillingSequence() == tenant.tenantBillingSequence()
            && entitlement.eventId().equals(tenant.tenantBillingEventId())
            && entitlement.eventDigest().equals(tenant.tenantBillingEventDigest()),
        "Current demo entitlement differs from the exact tenant billing receipt");
  }

  private static void requireWritableOwnerTransaction() {
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    require(
        TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            && (Objects.equals(isolation, TransactionDefinition.ISOLATION_REPEATABLE_READ)
                || Objects.equals(isolation, TransactionDefinition.ISOLATION_SERIALIZABLE)),
        "Writable Account REPEATABLE_READ or SERIALIZABLE transaction required");
  }

  private static void requireUuid(UUID value, String label) {
    if (value == null
        || value.equals(new UUID(0L, 0L))
        || value.version() != 4
        || value.variant() != 2) {
      throw new IllegalArgumentException("Canonical non-nil v4 " + label + " UUID required");
    }
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new IllegalStateException(message);
  }
}
