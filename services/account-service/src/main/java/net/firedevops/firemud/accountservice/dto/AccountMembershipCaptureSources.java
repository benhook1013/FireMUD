package net.firedevops.firemud.accountservice.dto;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;

/**
 * Immutable source material retained from one owner-local existing-membership read.
 *
 * <p>This value is not authorization. It preserves the exact locked authority rows, their
 * independent source versions and Account fence, alongside the membership projection that carries
 * the corresponding source checkpoints and events. The applicable retained or fresh tenant identity
 * receipt is carried separately from the raw role header for exact source binding.
 */
public record AccountMembershipCaptureSources(
    UUID requestedAccountUuid,
    UUID requestedTenantUuid,
    CompositeSnapshot authoritySnapshot,
    RuntimeMembershipSnapshotDto membershipSnapshot,
    Optional<RoleSnapshot> roleSource,
    Optional<ApprovedAssociation> retainedTenantAssociation,
    Optional<FreshTenantCreationEvidence> freshTenantAssociation) {
  private static final String AUTHORITY_STREAM_PREFIX =
      MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX;

  public AccountMembershipCaptureSources {
    requireCanonicalUuid(requestedAccountUuid, "requested Account UUID");
    requireCanonicalUuid(requestedTenantUuid, "requested tenant UUID");
    Objects.requireNonNull(authoritySnapshot, "locked Account authority snapshot is required");
    Objects.requireNonNull(membershipSnapshot, "existing runtime membership snapshot is required");
    roleSource = Objects.requireNonNull(roleSource, "role source presence is required");
    retainedTenantAssociation =
        Objects.requireNonNull(
            retainedTenantAssociation, "retained tenant association presence is required");
    freshTenantAssociation =
        Objects.requireNonNull(
            freshTenantAssociation, "fresh tenant association presence is required");
    if (retainedTenantAssociation.isPresent() == freshTenantAssociation.isPresent()) {
      throw new IllegalArgumentException(
          "Exactly one retained or fresh tenant association must identify the source");
    }

    String accountId = requestedAccountUuid.toString();
    String tenantId = requestedTenantUuid.toString();
    if (!accountId.equals(membershipSnapshot.requestAccountUuid())
        || !tenantId.equals(membershipSnapshot.requestTenantUuid())
        || !accountId.equals(membershipSnapshot.accountUuid())
        || !tenantId.equals(membershipSnapshot.tenantUuid())) {
      throw new IllegalArgumentException(
          "Account membership sources differ from the exact canonical request");
    }

    ScopeState issuer = authoritySnapshot.issuer();
    ScopeState account = authoritySnapshot.account();
    ScopeState tenant = only(authoritySnapshot.tenants(), "tenant");
    ScopeState membership = only(authoritySnapshot.memberships(), "membership");
    var fence = authoritySnapshot.issuanceFence();
    if (!AuthorityScope.issuer(AccountServiceImpl.ACCOUNT_JWT_ISSUER).equals(issuer.scope())
        || !AuthorityScope.account(requestedAccountUuid).equals(account.scope())
        || !AuthorityScope.tenant(requestedTenantUuid).equals(tenant.scope())
        || !AuthorityScope.membership(requestedAccountUuid, requestedTenantUuid)
            .equals(membership.scope())
        || !requestedAccountUuid.equals(fence.accountId())
        || !fence.equals(account.issuanceFence())
        || !fence.equals(membership.issuanceFence())) {
      throw new IllegalArgumentException(
          "Account authority sources do not retain the exact issuer, account, tenant and pair");
    }

    var tuple = membershipSnapshot.authorityTuple();
    var baseline = membershipSnapshot.membershipBaseline();
    String version = baseline.membershipVersion().get(tenantId);
    if (version == null
        || baseline.membershipVersion().size() != 1
        || !Long.toString(issuer.generation()).equals(tuple.issuerAuthGeneration())
        || !Long.toString(account.generation()).equals(tuple.accountAuthorityGeneration())
        || !Map.of(tenantId, Long.toString(tenant.generation()))
            .equals(tuple.tenantAuthorityGeneration())
        || !Map.of(tenantId, Long.toString(membership.generation()))
            .equals(tuple.membershipAuthorityGeneration())
        || !Long.toString(membership.generation()).equals(baseline.membershipAuthorityGeneration())
        || !Long.toString(fence.value()).equals(membershipSnapshot.issuanceFence())) {
      throw new IllegalArgumentException(
          "Membership projection differs from its exact captured authority generations or fence");
    }

    requireExactCheckpoints(
        requestedAccountUuid,
        requestedTenantUuid,
        membershipSnapshot,
        issuer,
        account,
        tenant,
        membership);
    requireSourceEvents(
        requestedAccountUuid,
        requestedTenantUuid,
        membershipSnapshot,
        issuer,
        account,
        tenant,
        membership);
    VerifiedTenantProvenance retainedProvenance =
        retainedTenantAssociation
            .map(association -> requireRetainedAssociation(requestedTenantUuid, association))
            .orElse(null);
    VerifiedTenantProvenance freshProvenance =
        freshTenantAssociation
            .map(association -> requireFreshAssociation(requestedTenantUuid, association))
            .orElse(null);
    requireRoleSource(
        requestedAccountUuid,
        requestedTenantUuid,
        membershipSnapshot,
        roleSource.orElse(null),
        retainedProvenance,
        freshProvenance,
        version);
  }

  private static void requireExactCheckpoints(
      UUID accountUuid,
      UUID tenantUuid,
      RuntimeMembershipSnapshotDto snapshot,
      ScopeState issuer,
      ScopeState account,
      ScopeState tenant,
      ScopeState membership) {
    Map<String, String> actual = new HashMap<>();
    for (OutboxCheckpointEntry checkpoint : snapshot.outboxCheckpoints()) {
      if (actual.put(checkpoint.outboxStreamKey(), checkpoint.outboxSequence()) != null) {
        throw new IllegalArgumentException("Account source checkpoints must be unique");
      }
    }
    if (!actual
            .keySet()
            .equals(
                new HashSet<>(
                    List.of(
                        issuerStreamKey(),
                        accountStreamKey(accountUuid),
                        tenantStreamKey(tenantUuid),
                        membershipStreamKey(accountUuid, tenantUuid))))
        || !scopeCheckpointMatches(actual.get(issuerStreamKey()), issuer)
        || !scopeCheckpointMatches(actual.get(accountStreamKey(accountUuid)), account)
        || !scopeCheckpointMatches(actual.get(tenantStreamKey(tenantUuid)), tenant)
        || !actual
            .get(membershipStreamKey(accountUuid, tenantUuid))
            .equals(membershipSequence(snapshot))) {
      throw new IllegalArgumentException(
          "Account membership checkpoints differ from their exact source scopes");
    }

    String membershipSequence = membershipSequence(snapshot);
    if ("0".equals(membershipSequence)
        && (membership.generation() != 1L
            || membership.sourceVersion() != 1L
            || !"MISSING".equals(snapshot.membershipBaseline().membershipLifecycleState())
            || snapshot.membershipExists()
            || snapshot.gameplayAdmissionAllowed())) {
      throw new IllegalArgumentException(
          "Sequence-zero membership is not the original non-admitting pair baseline");
    }
  }

  private static void requireSourceEvents(
      UUID accountUuid,
      UUID tenantUuid,
      RuntimeMembershipSnapshotDto snapshot,
      ScopeState issuer,
      ScopeState account,
      ScopeState tenant,
      ScopeState membership) {
    Map<String, OutboxSourceEvidence> events = new HashMap<>();
    for (OutboxSourceEvidence source : snapshot.outboxSourceEvidence()) {
      if (events.put(source.outboxStreamKey(), source) != null) {
        throw new IllegalArgumentException("Account source events must be unique by stream");
      }
    }
    var expectedPositiveStreams =
        snapshot.outboxCheckpoints().stream()
            .filter(checkpoint -> !"0".equals(checkpoint.outboxSequence()))
            .map(OutboxCheckpointEntry::outboxStreamKey)
            .collect(java.util.stream.Collectors.toSet());
    if (!events.keySet().equals(expectedPositiveStreams)) {
      throw new IllegalArgumentException(
          "Account source events differ from the positive source checkpoint inventory");
    }
    requireSourceEventPresence(
        issuerStreamKey(), checkpointSequence(snapshot, issuerStreamKey()), events);
    requireSourceEventPresence(
        accountStreamKey(accountUuid),
        checkpointSequence(snapshot, accountStreamKey(accountUuid)),
        events);
    requireSourceEventPresence(
        tenantStreamKey(tenantUuid),
        checkpointSequence(snapshot, tenantStreamKey(tenantUuid)),
        events);
    String membershipStream = membershipStreamKey(accountUuid, tenantUuid);
    requireSourceEventPresence(
        membershipStream, checkpointSequence(snapshot, membershipStream), events);

    OutboxSourceEvidence issuerEvent = events.get(issuerStreamKey());
    if (issuerEvent != null
        && !Long.toString(issuer.sourceVersion())
            .equals(
                IssuerGenerationAuthorityEventV1Codec.verify(issuerEvent.canonicalEventJson())
                    .sourceVersion())) {
      throw new IllegalArgumentException("Issuer source version differs from its exact event");
    }

    OutboxSourceEvidence accountEvent = events.get(accountStreamKey(accountUuid));
    if (accountEvent != null
        && !Long.toString(account.sourceVersion())
            .equals(sourceVersionForAccountEvent(accountEvent))) {
      throw new IllegalArgumentException("Account source version differs from its exact event");
    }

    OutboxSourceEvidence tenantEvent = events.get(tenantStreamKey(tenantUuid));
    if (tenantEvent != null
        && !Long.toString(tenant.sourceVersion())
            .equals(
                TenantGenerationAuthorityEventV1Codec.verify(tenantEvent.canonicalEventJson())
                    .sourceVersion())) {
      throw new IllegalArgumentException("Tenant source version differs from its exact event");
    }

    OutboxSourceEvidence membershipEvent = events.get(membershipStream);
    if (membershipEvent != null) {
      var event = MembershipAuthorityEventV1Codec.verify(membershipEvent.canonicalEventJson());
      if (!accountUuid.toString().equals(event.accountId())
          || !tenantUuid.toString().equals(event.tenantId())
          || !Long.toString(membership.generation()).equals(event.membershipAuthorityGeneration())
          || snapshot.sourceEvent() == null
          || !event.canonicalJson().equals(snapshot.sourceEvent().canonicalJson())) {
        throw new IllegalArgumentException(
            "Membership source event differs from its exact locked membership scope");
      }
    } else if (snapshot.sourceEvent() != null) {
      throw new IllegalArgumentException("Membership event is missing from its source evidence");
    }
  }

  private static String sourceVersionForAccountEvent(OutboxSourceEvidence source) {
    try {
      return PasswordResetAuthorityEventV1Codec.verify(source.canonicalEventJson()).sourceVersion();
    } catch (IllegalArgumentException resetSchemaMismatch) {
      return AccountLogoutAllAuthorityEventV1Codec.verify(source.canonicalEventJson())
          .sourceVersion();
    }
  }

  private static void requireSourceEventPresence(
      String streamKey, String sequence, Map<String, OutboxSourceEvidence> events) {
    OutboxSourceEvidence event = events.get(streamKey);
    if ("0".equals(sequence) != (event == null)
        || (event != null && !sequence.equals(event.outboxSequence()))) {
      throw new IllegalArgumentException("Account source event differs from its exact checkpoint");
    }
  }

  private static void requireRoleSource(
      UUID accountUuid,
      UUID tenantUuid,
      RuntimeMembershipSnapshotDto snapshot,
      RoleSnapshot roleSource,
      VerifiedTenantProvenance retainedProvenance,
      VerifiedTenantProvenance freshProvenance,
      String membershipVersion) {
    if (!snapshot.membershipExists()) {
      if (roleSource != null || !snapshot.roles().isEmpty()) {
        throw new IllegalArgumentException(
            "An absent membership cannot contain role source evidence or grants");
      }
      return;
    }

    if (roleSource == null
        || roleSource.accountUuid() == null
        || !accountUuid.equals(roleSource.accountUuid())
        || roleSource.accountId() <= 0L
        || roleSource.membershipId() <= 0L
        || !Long.toString(roleSource.snapshotVersion()).equals(membershipVersion)
        || !roleSource.roles().equals(snapshot.roles())) {
      throw new IllegalArgumentException(
          "Current membership roles lack the exact existing role header and identity");
    }

    if (freshProvenance != null) {
      if (roleSource.tenantId() != null
          || !tenantUuid.equals(roleSource.tenantUuid())
          || !freshProvenance.equals(roleSource.tenantProvenance())) {
        throw new IllegalArgumentException(
            "Fresh membership role header differs from its exact UUID source receipt");
      }
      return;
    }

    boolean canonicalTenantUuidPresent = roleSource.tenantUuid() != null;
    boolean canonicalProvenancePresent = roleSource.tenantProvenance() != null;
    if (retainedProvenance == null
        || !Objects.equals(roleSource.tenantId(), retainedProvenance.legacyTenantId())
        || canonicalTenantUuidPresent != canonicalProvenancePresent
        || (canonicalTenantUuidPresent
            && (!tenantUuid.equals(roleSource.tenantUuid())
                || !retainedProvenance.equals(roleSource.tenantProvenance())))) {
      throw new IllegalArgumentException(
          "Current membership roles lack the exact retained tenant association");
    }
  }

  private static VerifiedTenantProvenance requireRetainedAssociation(
      UUID tenantUuid, ApprovedAssociation association) {
    if (association.legacyTenantId() <= 0L
        || association.canonicalTenantId() == null
        || !tenantUuid.equals(association.canonicalTenantId())) {
      throw new IllegalArgumentException(
          "Retained tenant association differs from the exact canonical request");
    }
    return new VerifiedTenantProvenance(
        association.legacyTenantId(),
        TenantProvenanceKind.APPROVED_RETAINED,
        association.operationId(),
        association.manifestDigest());
  }

  private static VerifiedTenantProvenance requireFreshAssociation(
      UUID tenantUuid, FreshTenantCreationEvidence association) {
    if (association.canonicalTenantId() == null
        || !tenantUuid.equals(association.canonicalTenantId())) {
      throw new IllegalArgumentException(
          "Fresh tenant association differs from the exact canonical request");
    }
    return new VerifiedTenantProvenance(
        null,
        TenantProvenanceKind.FRESH_GAME_DESIGN,
        association.operationId(),
        association.evidenceDigest());
  }

  private static String membershipSequence(RuntimeMembershipSnapshotDto snapshot) {
    UUID accountUuid = requireCanonicalSnapshotUuid(snapshot.accountUuid(), "Account UUID");
    UUID tenantUuid = requireCanonicalSnapshotUuid(snapshot.tenantUuid(), "tenant UUID");
    return checkpointSequence(snapshot, membershipStreamKey(accountUuid, tenantUuid));
  }

  private static UUID requireCanonicalSnapshotUuid(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException invalidUuid) {
      throw new IllegalArgumentException(field + " must be a canonical UUID", invalidUuid);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical lowercase UUID");
    }
    return requireCanonicalUuid(parsed, field);
  }

  private static String checkpointSequence(
      RuntimeMembershipSnapshotDto snapshot, String streamKey) {
    return snapshot.outboxCheckpoints().stream()
        .filter(checkpoint -> streamKey.equals(checkpoint.outboxStreamKey()))
        .map(OutboxCheckpointEntry::outboxSequence)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Membership source checkpoint is missing"));
  }

  private static boolean scopeCheckpointMatches(String sequence, ScopeState scope) {
    if (sequence == null) {
      return false;
    }
    if (scope.generation() == 1L && scope.sourceVersion() == 1L) {
      return "0".equals(sequence);
    }
    return !"0".equals(sequence);
  }

  private static String issuerStreamKey() {
    return AUTHORITY_STREAM_PREFIX + "issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER;
  }

  private static String accountStreamKey(UUID accountUuid) {
    return AUTHORITY_STREAM_PREFIX + "account/" + accountUuid;
  }

  private static String tenantStreamKey(UUID tenantUuid) {
    return AUTHORITY_STREAM_PREFIX + "tenant/" + tenantUuid;
  }

  private static String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return AUTHORITY_STREAM_PREFIX + "membership/" + accountUuid + "/" + tenantUuid;
  }

  private static ScopeState only(List<ScopeState> scopes, String label) {
    if (scopes.size() != 1) {
      throw new IllegalArgumentException("Exactly one captured " + label + " scope is required");
    }
    return scopes.getFirst();
  }

  private static UUID requireCanonicalUuid(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(field + " must be a non-nil canonical UUID");
    }
    return value;
  }
}
