package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountMembershipRoleSourceSnapshot;
import net.firedevops.firemud.accountservice.dto.AccountMembershipRoleSourceSnapshot.MembershipSource;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, read-only-in-purpose current membership/role SQL source capture.
 *
 * <p>Requires a writable Account transaction for owner locks, with REPEATABLE_READ or SERIALIZABLE
 * isolation. No transaction, network call, enrollment, mutation, actor authority, deadline evidence
 * or admission decision is created here. Actual owner APIs currently support FRESH_GAME_DESIGN
 * tenant provenance only; approved retained tenant mappings cannot be inferred through this reader.
 * Applicable later owner decisions can consume this capture; it supplies no reduction intent.
 */
public final class AccountMembershipRoleSourceReader {
  private final AccountAuthoritySourceEvidenceRepository sources;
  private final AccountAuthorityGenerationRepository generations;
  private final AccountTenantMembershipRepository memberships;
  private final AccountMembershipPairAuthorityRepository pairs;
  private final AccountTenantMembershipRoleSnapshotRepository roles;
  private final AccountAuthorityOutboxRepository outbox;
  private final AccountTenantAuthorityEventRepository tenants;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected Account owner repositories are private transaction collaborators.")
  public AccountMembershipRoleSourceReader(
      AccountAuthoritySourceEvidenceRepository sources,
      AccountAuthorityGenerationRepository generations,
      AccountTenantMembershipRepository memberships,
      AccountMembershipPairAuthorityRepository pairs,
      AccountTenantMembershipRoleSnapshotRepository roles,
      AccountAuthorityOutboxRepository outbox,
      AccountTenantAuthorityEventRepository tenants) {
    this.sources = Objects.requireNonNull(sources);
    this.generations = Objects.requireNonNull(generations);
    this.memberships = Objects.requireNonNull(memberships);
    this.pairs = Objects.requireNonNull(pairs);
    this.roles = Objects.requireNonNull(roles);
    this.outbox = Objects.requireNonNull(outbox);
    this.tenants = Objects.requireNonNull(tenants);
  }

  public AccountMembershipRoleSourceSnapshot readCurrent(UUID accountId, UUID tenantId) {
    requireUuid(accountId);
    requireUuid(tenantId);
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    require(
        TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            && (Objects.equals(isolation, TransactionDefinition.ISOLATION_REPEATABLE_READ)
                || Objects.equals(isolation, TransactionDefinition.ISOLATION_SERIALIZABLE)));

    // Both owner source/composite APIs lock Account first, before authority, tenant or pair rows.
    var source =
        sources.readCurrentIssuerAccountSources(
            GameSessionAccountDelegationProfile.ISSUER, accountId);
    var authority =
        generations.readCompositeSnapshot(
            GameSessionAccountDelegationProfile.ISSUER,
            accountId,
            List.of(tenantId),
            List.of(tenantId));
    require(source != null && authority != null);
    AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);
    require(authority.tenants().size() == 1 && authority.memberships().size() == 1);
    var tenantState = authority.tenants().getFirst();
    var memberState = authority.memberships().getFirst();
    require(
        AuthorityScope.issuer(GameSessionAccountDelegationProfile.ISSUER)
                .equals(source.issuer().scope())
            && AuthorityScope.account(accountId).equals(source.account().scope())
            && source.issuer().scope().equals(authority.issuer().scope())
            && source.account().scope().equals(authority.account().scope())
            && AuthorityScope.tenant(tenantId).equals(tenantState.scope())
            && AuthorityScope.membership(accountId, tenantId).equals(memberState.scope())
            && source.issuer().generation() == authority.issuer().generation()
            && source.issuer().sourceVersion() == authority.issuer().sourceVersion()
            && source.account().generation() == authority.account().generation()
            && source.account().sourceVersion() == authority.account().sourceVersion()
            && source.issuanceFence().equals(authority.issuanceFence())
            && accountId.equals(authority.issuanceFence().accountId())
            && authority.issuanceFence().equals(authority.account().issuanceFence())
            && authority.issuanceFence().equals(memberState.issuanceFence())
            && authority.issuer().issuanceFence() == null
            && tenantState.issuanceFence() == null);
    var tenant = tenants.readCurrentByTenant(tenantId);
    require(tenant != null);
    require(
        tenantId.equals(tenant.tenantId())
            && tenantId.equals(tenant.sourceEvidence().canonicalTenantId())
            && tenant.tenantAuthorityGeneration() == tenantState.generation()
            && tenant.tenantAuthoritySourceVersion() == tenantState.sourceVersion());
    var member =
        memberships
            .findFreshMembershipForUpdate(accountId, tenantId)
            .orElseThrow(AccountMembershipRoleSourceReader::unavailable);
    var pair =
        pairs
            .readForUpdate(accountId, tenantId)
            .orElseThrow(AccountMembershipRoleSourceReader::unavailable);
    var provenance = pair.provenance();
    var account = member.getAccount();
    if (account == null) {
      throw unavailable();
    }
    require(
        account.getId() != null
            && account.getId() > 0L
            && member.getId() != null
            && member.getId() > 0L);
    require(
        accountId.equals(pair.accountUuid())
            && tenantId.equals(pair.tenantUuid())
            && pair.membershipExists()
            && pair.eventSequence() > 0L
            && provenance.kind() == TenantProvenanceKind.FRESH_GAME_DESIGN
            && provenance.legacyTenantId() == null
            && member.getTenantId() == null
            && provenance.kind().name().equals(member.getTenantProvenanceKind())
            && provenance.sourceOperationId().equals(tenant.sourceEvidence().operationId())
            && provenance.digest().equals(tenant.sourceEvidence().evidenceDigest())
            && provenance.sourceOperationId().equals(member.getTenantSourceOperationId())
            && provenance.digest().equals(member.getTenantProvenanceDigest())
            && accountId.equals(account.getAccountUuid())
            && tenantId.equals(member.getTenantUuid())
            && AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.equals(
                account.getAccountUuidProvenance())
            && account.getId().equals(account.getAccountUuidSourceNumericId())
            && account.getId().equals(source.account().accountSourceNumericId())
            && member.getMembershipVersion() == pair.membershipVersion()
            && member.getMembershipAuthorityGeneration() == pair.membershipAuthorityGeneration()
            && memberState.generation() == pair.membershipAuthorityGeneration()
            && member.getAuthorityProvenance() != null
            && !member.getAuthorityProvenance().isBlank());
    var role =
        roles
            .findForCanonicalUpdate(
                accountId, tenantId, provenance, member.getId(), member.getMembershipVersion())
            .orElseThrow(AccountMembershipRoleSourceReader::unavailable);
    require(
        role.accountId() == account.getId()
            && Objects.equals(role.tenantId(), member.getTenantId())
            && role.membershipId() == member.getId()
            && role.snapshotVersion() == member.getMembershipVersion()
            && accountId.equals(role.accountUuid())
            && tenantId.equals(role.tenantUuid())
            && provenance.equals(role.tenantProvenance()));
    String stream =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + accountId
            + "/"
            + tenantId;
    var checkpoint =
        outbox.readCheckpoint(stream).orElseThrow(AccountMembershipRoleSourceReader::unavailable);
    var event =
        outbox
            .findEvent(stream, checkpoint.outboxSequence())
            .orElseThrow(AccountMembershipRoleSourceReader::unavailable);
    var verified =
        MembershipAuthorityEventV1Codec.verify(new String(event.payload(), StandardCharsets.UTF_8));
    require(
        stream.equals(checkpoint.outboxStreamKey())
            && stream.equals(event.outboxStreamKey())
            && stream.equals(verified.outboxStreamKey())
            && ("membership/" + accountId + "/" + tenantId).equals(verified.sourceScope())
            && checkpoint.outboxSequence() == pair.eventSequence()
            && event.outboxSequence() == pair.eventSequence()
            && Long.toString(pair.eventSequence()).equals(verified.outboxSequence())
            && pair.eventId().equals(checkpoint.sourceEventId())
            && pair.eventId().equals(event.eventId())
            && pair.eventId().equals(verified.eventId())
            && pair.eventDigest().equals(checkpoint.sourceEventDigest())
            && pair.eventDigest().equals(event.eventDigest())
            && pair.eventDigest().equals(verified.eventDigest())
            && event.requestId().equals(verified.requestId())
            && Arrays.equals(event.payload(), verified.canonicalJsonUtf8())
            && accountId.toString().equals(verified.accountId())
            && tenantId.toString().equals(verified.tenantId())
            && Map.of(tenantId.toString(), Long.toString(pair.membershipVersion()))
                .equals(verified.membershipVersion())
            && Long.toString(pair.membershipAuthorityGeneration())
                .equals(verified.membershipAuthorityGeneration())
            && Objects.equals(member.getLifecycleState(), verified.membershipLifecycleState())
            && member.isGameplayAdmissionAllowed() == verified.gameplayAdmissionAllowed()
            && pair.lastTransitionInvalidated() == verified.callerBoundAuthorityInvalidated()
            && role.roles().equals(verified.roles()));
    // Historical event bytes remain unchanged when independent current sources advance.
    var tuple = verified.authorityTuple();
    require(
        tuple
                .membershipAuthorityGeneration()
                .equals(Map.of(tenantId.toString(), Long.toString(memberState.generation())))
            && tuple
                .tenantAuthorityGeneration()
                .keySet()
                .equals(java.util.Set.of(tenantId.toString())));
    requireNotFuture(tuple.issuerAuthGeneration(), authority.issuer().generation());
    requireNotFuture(tuple.accountAuthorityGeneration(), authority.account().generation());
    requireNotFuture(
        tuple.tenantAuthorityGeneration().get(tenantId.toString()), tenantState.generation());
    requireNotFuture(verified.issuanceFence(), authority.issuanceFence().value());
    // No private-grant source owner is included in this bounded reader.
    require(tuple.privateRealmGrantVersions().isEmpty());
    tuple
        .accountSecurityCutoff()
        .ifPresent(
            cutoff -> {
              requireNotFuture(cutoff.accountAuthorityGeneration(), source.account().generation());
              requireNotFuture(cutoff.outboxSequence(), source.account().checkpoint().sequence());
            });
    tuple
        .tenantBillingCutoff()
        .ifPresent(
            cutoffs -> {
              require(cutoffs.keySet().equals(java.util.Set.of(tenantId.toString())));
              var cutoff = cutoffs.get(tenantId.toString());
              requireNotFuture(cutoff.tenantAuthorityGeneration(), tenantState.generation());
              requireNotFuture(cutoff.tenantBillingSequence(), tenant.tenantBillingSequence());
              requireNotFuture(cutoff.outboxSequence(), tenant.outboxSequence());
            });
    return new AccountMembershipRoleSourceSnapshot(
        source,
        authority,
        tenant,
        pair,
        new MembershipSource(
            member.getId(),
            account.getId(),
            accountId,
            account.getAccountUuidProvenance(),
            account.getAccountUuidSourceNumericId(),
            tenantId,
            provenance,
            member.getLifecycleState(),
            member.isGameplayAdmissionAllowed(),
            member.getMembershipVersion(),
            member.getMembershipAuthorityGeneration(),
            member.getAuthorityProvenance()),
        role,
        checkpoint,
        event,
        verified);
  }

  private static void requireNotFuture(String historical, long current) {
    require(new BigInteger(historical).compareTo(BigInteger.valueOf(current)) <= 0);
  }

  private static void requireUuid(UUID id) {
    if (id == null || id.equals(new UUID(0L, 0L)) || id.version() != 4 || id.variant() != 2) {
      throw new IllegalArgumentException("Canonical non-nil v4 UUID selector is required");
    }
  }

  private static void require(boolean valid) {
    if (!valid) throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Current Account membership/role SQL source is unavailable or inconsistent");
  }
}
