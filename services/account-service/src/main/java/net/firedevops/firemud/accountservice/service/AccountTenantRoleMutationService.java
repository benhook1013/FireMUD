package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleMutationDigest;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.Action;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.AuditEvidence;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.OperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.Request;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.PositiveMembershipSnapshot;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.TenantRoleEventEvidence;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicitly unwired Account owner-source writer for retained, currently admitting EXPLICIT_JOIN
 * members. Its actor UUID selects an Account row; this service does not authenticate a caller.
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Injected Account repositories and producer are internal transaction collaborators.")
public class AccountTenantRoleMutationService {
  private static final JsonMapper AUDIT_JSON = JsonMapper.builder().build();

  private final AccountRepository accountRepository;
  private final AccountTenantIdentityResolver tenantIdentityResolver;
  private final AccountAuthorityGenerationRepository authorityGenerationRepository;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;
  private final AccountMembershipPairAuthorityRepository pairAuthorityRepository;
  private final AccountTenantRoleOperationRepository operationRepository;
  private final AccountAuditOutboxRepository auditOutboxRepository;
  private final AccountMembershipAuthorityEventProducer eventProducer;
  private final DraftAuthorizationFenceRepository draftFenceRepository;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The constructor validates injected internal collaborators; it neither publishes this "
              + "nor invokes overridable methods.")
  public AccountTenantRoleMutationService(
      AccountRepository accountRepository,
      AccountTenantIdentityResolver tenantIdentityResolver,
      AccountAuthorityGenerationRepository authorityGenerationRepository,
      AccountTenantMembershipRepository membershipRepository,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository,
      AccountMembershipPairAuthorityRepository pairAuthorityRepository,
      AccountTenantRoleOperationRepository operationRepository,
      AccountAuditOutboxRepository auditOutboxRepository,
      AccountMembershipAuthorityEventProducer eventProducer,
      DSLContext dsl) {
    this.accountRepository = Objects.requireNonNull(accountRepository);
    this.tenantIdentityResolver = Objects.requireNonNull(tenantIdentityResolver);
    this.authorityGenerationRepository = Objects.requireNonNull(authorityGenerationRepository);
    this.membershipRepository = Objects.requireNonNull(membershipRepository);
    this.roleSnapshotRepository = Objects.requireNonNull(roleSnapshotRepository);
    this.pairAuthorityRepository = Objects.requireNonNull(pairAuthorityRepository);
    this.operationRepository = Objects.requireNonNull(operationRepository);
    this.auditOutboxRepository = Objects.requireNonNull(auditOutboxRepository);
    this.eventProducer = Objects.requireNonNull(eventProducer);
    this.draftFenceRepository = new DraftAuthorizationFenceRepository(dsl);
  }

  /**
   * Applies one role-source mutation, or commits only its original pending source intent when Draft
   * participants have not settled. A pending result has no member, audit or authorization evidence.
   * No transport or authenticated actor proof is accepted here; public ingress remains denied.
   */
  @Transactional
  public OperationEvidence mutate(Request request) {
    requireWritableOwnerTransaction();
    Objects.requireNonNull(request, "Tenant-role mutation request is required");

    List<UUID> orderedAccountUuids =
        new LinkedHashSet<>(List.of(request.actorAccountUuid(), request.targetAccountUuid()))
            .stream().sorted(Comparator.comparing(UUID::toString)).toList();
    Map<UUID, Account> lockedAccounts = new LinkedHashMap<>();
    for (UUID accountUuid : orderedAccountUuids) {
      Account account =
          accountRepository
              .findByAccountUuidForUpdate(accountUuid)
              .orElseThrow(() -> new IllegalStateException("Tenant-role Account row is absent"));
      requireAccountIdentity(account, accountUuid);
      lockedAccounts.put(accountUuid, account);
    }

    AccountTenantRoleOperationRepository.Claim claim = operationRepository.claim(request);
    if (!claim.claimed()) {
      OperationEvidence replay = claim.replay().orElseThrow();
      if (!replay.pending()) {
        verifyExactReplay(replay);
        return replay;
      }
    }
    SourceChange originalChange =
        operationRepository.findSourceChangeForUpdate(request).orElse(null);

    // The tenant generation row is the shared-source serialization point for same-tenant writes.
    for (UUID accountUuid : orderedAccountUuids) {
      authorityGenerationRepository.readCompositeSnapshot(
          AccountServiceImpl.ACCOUNT_JWT_ISSUER,
          accountUuid,
          List.of(request.tenantUuid()),
          List.of());
    }
    ApprovedAssociation association = tenantIdentityResolver.resolve(request.tenantUuid());
    VerifiedTenantProvenance expectedProvenance =
        new VerifiedTenantProvenance(
            association.legacyTenantId(),
            TenantProvenanceKind.APPROVED_RETAINED,
            association.operationId(),
            association.manifestDigest());

    Map<UUID, PositiveMembershipSnapshot> current = new LinkedHashMap<>();
    for (UUID accountUuid : orderedAccountUuids) {
      Account account = lockedAccounts.get(accountUuid);
      PositiveMembershipSnapshot snapshot =
          eventProducer.readCurrentPairBoundPositiveMembershipSnapshot(
              account.getId(), association.legacyTenantId());
      if (!accountUuid.toString().equals(snapshot.accountId())
          || !request.tenantUuid().toString().equals(snapshot.tenantId())) {
        throw new IllegalStateException("Tenant-role current membership identity differs");
      }
      current.put(accountUuid, snapshot);
    }
    PositiveMembershipSnapshot actorSnapshot = current.get(request.actorAccountUuid());
    PositiveMembershipSnapshot targetSnapshot = current.get(request.targetAccountUuid());
    requireExpectedVersion(request.expectedActorMembershipVersion(), actorSnapshot, "actor");
    requireExpectedVersion(request.expectedTargetMembershipVersion(), targetSnapshot, "target");
    if (!actorSnapshot.roles().contains("tenantAdmin")) {
      throw new IllegalStateException(
          "Current retained Account actor is not a tenant administrator");
    }

    Map<UUID, RoleChange> changes = roleChanges(request, actorSnapshot, targetSnapshot);
    List<RoleChange> orderedChanges =
        changes.values().stream()
            .sorted(Comparator.comparing(change -> change.accountUuid().toString()))
            .toList();

    List<PreparedChange> preparedChanges = new ArrayList<>();
    for (RoleChange change : orderedChanges) {
      AccountTenantMembership membership =
          membershipRepository
              .findCanonicalMembershipForUpdate(change.accountUuid(), request.tenantUuid())
              .orElseThrow(
                  () ->
                      new IllegalStateException("Current canonical Account membership is absent"));
      requireRetainedExplicitMembership(membership, expectedProvenance, change.current());
      PairAuthority pair =
          pairAuthorityRepository
              .readForUpdate(change.accountUuid(), request.tenantUuid())
              .orElseThrow(
                  () -> new IllegalStateException("Current membership pair authority is absent"));
      requireCurrentPair(pair, membership, change.current(), expectedProvenance);

      RoleSnapshot priorRoles =
          roleSnapshotRepository
              .findForCanonicalUpdate(
                  change.accountUuid(),
                  request.tenantUuid(),
                  expectedProvenance,
                  membership.getId(),
                  membership.getMembershipVersion())
              .orElseThrow(
                  () -> new IllegalStateException("Current canonical role snapshot is absent"));
      if (!priorRoles.roles().equals(change.current().roles())) {
        throw new IllegalStateException("Tenant-role current role source differs from its event");
      }

      CompositeSnapshot authority =
          authorityGenerationRepository.readCompositeSnapshot(
              AccountServiceImpl.ACCOUNT_JWT_ISSUER,
              change.accountUuid(),
              List.of(request.tenantUuid()),
              List.of(request.tenantUuid()));
      ScopeState memberState = authority.memberships().getFirst();
      if (!AuthorityScope.membership(change.accountUuid(), request.tenantUuid())
              .equals(memberState.scope())
          || memberState.generation() != membership.getMembershipAuthorityGeneration()
          || authority.issuanceFence() == null
          || !authority.issuanceFence().accountId().equals(change.accountUuid())
          || authority.issuanceFence().value()
              != Long.parseLong(change.current().issuanceFence())) {
        throw new IllegalStateException(
            "Tenant-role authority generation differs from current source");
      }
      if (change.invalidated()) {
        increment(authority.issuanceFence().value(), "issuance fence");
        increment(authority.issuanceFence().sourceVersion(), "issuance fence source version");
        increment(memberState.generation(), "membership authority generation");
        increment(memberState.sourceVersion(), "membership authority source version");
      }
      increment(membership.getMembershipVersion(), "membership version");
      preparedChanges.add(new PreparedChange(change, membership, pair, authority));
    }

    // Actual Account source rows remain locked through capture, ordering and any source commit.
    // An exact pending retry revalidates against, but never replaces, its original stored vector.
    SourceChange currentChange = sourceChange(request, preparedChanges);
    SourceChange sourceChange = originalChange == null ? currentChange : originalChange;
    if (!Arrays.equals(sourceChange.canonicalBytes(), currentChange.canonicalBytes())) {
      throw new AccountTenantRoleOperationRepository.OperationConflictException(
          "Tenant-role current source differs from its original pending capture");
    }
    boolean settled = draftFenceRepository.requestSourceChange(sourceChange);
    if (originalChange == null) {
      operationRepository.captureSourceChange(request, sourceChange);
    }
    if (!settled || !draftFenceRepository.sourceMutationPermitted(sourceChange)) {
      // Returning normally is essential: V57's WAITING revocation and the immutable role request
      // must survive a retry/process loss rather than rolling back with a thrown denial.
      return operationRepository.findForUpdate(request.requestId()).orElseThrow();
    }

    List<AccountTenantRoleMutationDigest.MemberResult> memberResults = new ArrayList<>();
    for (PreparedChange prepared : preparedChanges) {
      RoleChange change = prepared.change();
      AccountTenantMembership membership = prepared.membership();
      PairAuthority pair = prepared.pair();
      CompositeSnapshot authority = prepared.authority();
      ScopeState memberState = authority.memberships().getFirst();
      if (change.invalidated()) {
        memberState = authorityGenerationRepository.advance(memberState, authority.issuanceFence());
      }

      long nextVersion = increment(membership.getMembershipVersion(), "membership version");
      membership.setMembershipVersion(nextVersion);
      membership.setMembershipAuthorityGeneration(memberState.generation());
      AccountTenantMembership persisted =
          membershipRepository.saveCanonical(
              membership, change.accountUuid(), request.tenantUuid(), expectedProvenance);
      RoleSnapshot persistedRoles =
          roleSnapshotRepository.replaceCanonical(
              persisted,
              change.accountUuid(),
              request.tenantUuid(),
              expectedProvenance,
              nextVersion,
              change.roles());

      TenantRoleEventEvidence event =
          eventProducer.appendTenantRoleEvent(
              request, persisted, persistedRoles, pair, change.invalidated());
      PairAuthority advanced =
          pairAuthorityRepository.commitTransition(
              pair,
              new PairTransition(
                  true,
                  event.checkpoint().outboxSequence(),
                  event.event().eventId(),
                  event.event().eventDigest(),
                  change.invalidated()));
      if (advanced.membershipVersion() != nextVersion
          || advanced.membershipAuthorityGeneration() != memberState.generation()
          || advanced.lastEventSequence() != event.checkpoint().outboxSequence()) {
        throw new IllegalStateException("Tenant-role pair transition readback differs");
      }
      memberResults.add(
          new AccountTenantRoleMutationDigest.MemberResult(
              change.accountUuid(),
              request.tenantUuid(),
              persisted.getMembershipVersion(),
              persisted.getMembershipAuthorityGeneration(),
              event.checkpoint().outboxSequence(),
              event.event().requestId(),
              event.event().eventId(),
              event.event().eventDigest(),
              change.invalidated(),
              event.event().payload()));
    }

    AccountAuditEnvelope auditEnvelope = appendAudit(request, memberResults);
    AuditEvidence auditEvidence =
        new AuditEvidence(
            auditEnvelope.auditEventId(),
            auditEnvelope.eventType(),
            auditEnvelope.occurredAt(),
            auditEnvelope.payloadDigest(),
            auditEnvelope.payload().getBytes(StandardCharsets.UTF_8));
    draftFenceRepository.markSourceCommitted(sourceChange);
    OperationEvidence completed =
        operationRepository.complete(request, auditEvidence, memberResults);

    for (RoleChange change : orderedChanges) {
      PositiveMembershipSnapshot exact =
          eventProducer.readCurrentPairBoundPositiveMembershipSnapshot(
              lockedAccounts.get(change.accountUuid()).getId(), association.legacyTenantId());
      AccountTenantRoleMutationDigest.MemberResult result =
          memberResults.stream()
              .filter(member -> member.accountUuid().equals(change.accountUuid()))
              .findFirst()
              .orElseThrow();
      long expectedFence =
          change.invalidated()
              ? increment(Long.parseLong(change.current().issuanceFence()), "issuance fence")
              : Long.parseLong(change.current().issuanceFence());
      if (!Objects.equals(
              exact.membershipVersion().get(request.tenantUuid().toString()),
              Long.toString(result.membershipVersion()))
          || !Objects.equals(
              exact.membershipAuthorityGeneration(),
              Long.toString(result.membershipAuthorityGeneration()))
          || !exact.authorityEvent().outboxSequence().equals(Long.toString(result.eventSequence()))
          || !exact.authorityEvent().requestId().equals(result.eventRequestId())
          || !exact.authorityEvent().eventId().equals(result.eventId())
          || !exact.authorityEvent().eventDigest().equals(result.eventDigest())
          || exact.authorityEvent().callerBoundAuthorityInvalidated()
              != result.callerBoundAuthorityInvalidated()
          || !exact.issuanceFence().equals(Long.toString(expectedFence))
          || !exact.roles().equals(change.roles())) {
        throw new IllegalStateException("Tenant-role positive current-source readback differs");
      }
    }
    return completed;
  }

  private SourceChange sourceChange(Request request, List<PreparedChange> preparedChanges) {
    List<SourceEvidence> sources = new ArrayList<>();
    for (PreparedChange prepared : preparedChanges) {
      RoleChange change = prepared.change();
      CompositeSnapshot authority = prepared.authority();
      ScopeState membership = authority.memberships().getFirst();
      var event = change.current().authorityEvent();
      sources.add(
          new SourceEvidence(
              SourceKind.MEMBERSHIP,
              change.accountUuid() + "/" + request.tenantUuid(),
              Long.toString(membership.generation()),
              Long.toString(membership.sourceVersion()),
              event.outboxStreamKey(),
              event.outboxSequence(),
              event.canonicalJsonUtf8()));
      if (change.invalidated()) {
        // Membership invalidation also changes this Account's issuance fence. ACCOUNT participation
        // therefore uses the actual independent Account source state and its actual checkpoint,
        // with both issuance-fence counters bound separately in the evidence; no counter aliasing.
        ScopeState account = authority.account();
        String stream =
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + change.accountUuid();
        var checkpoint =
            change.current().outboxCheckpoints().stream()
                .filter(candidate -> stream.equals(candidate.outboxStreamKey()))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalStateException("Account source checkpoint is absent"));
        var sourceEvent =
            change.current().outboxSourceEvidence().stream()
                .filter(candidate -> stream.equals(candidate.outboxStreamKey()))
                .findFirst();
        if ("0".equals(checkpoint.outboxSequence()) == sourceEvent.isPresent()
            || (sourceEvent.isPresent()
                && !checkpoint
                    .outboxSequence()
                    .equals(sourceEvent.orElseThrow().outboxSequence()))) {
          throw new IllegalStateException("Account source event and checkpoint differ");
        }
        byte[] evidence =
            AUDIT_JSON
                .writeValueAsString(
                    new AccountFenceCapture(
                        "account-tenant-role-issuance-source/v1",
                        change.accountUuid().toString(),
                        Long.toString(account.generation()),
                        Long.toString(account.sourceVersion()),
                        Long.toString(authority.issuanceFence().value()),
                        Long.toString(authority.issuanceFence().sourceVersion()),
                        stream,
                        checkpoint.outboxSequence(),
                        sourceEvent.map(candidate -> candidate.canonicalEventJson()).orElse(null)))
                .getBytes(StandardCharsets.UTF_8);
        sources.add(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                change.accountUuid().toString(),
                Long.toString(account.generation()),
                Long.toString(account.sourceVersion()),
                stream,
                checkpoint.outboxSequence(),
                evidence));
      }
    }
    return new SourceChange(request.requestId(), sources, request.payload());
  }

  private Map<UUID, RoleChange> roleChanges(
      Request request, PositiveMembershipSnapshot actor, PositiveMembershipSnapshot target) {
    Map<UUID, RoleChange> changes = new LinkedHashMap<>();
    if (request.action() == Action.TRANSFER_TENANT_ADMIN) {
      if (!actor.roles().contains("tenantAdmin") || target.roles().contains("tenantAdmin")) {
        throw new IllegalStateException("Tenant-admin transfer source or target role is invalid");
      }
      changes.put(
          request.actorAccountUuid(),
          new RoleChange(
              request.actorAccountUuid(), actor, without(actor.roles(), "tenantAdmin"), true));
      changes.put(
          request.targetAccountUuid(),
          new RoleChange(
              request.targetAccountUuid(), target, with(target.roles(), "tenantAdmin"), false));
      return changes;
    }

    boolean grant = request.action() == Action.GRANT_DESIGNER;
    if (target.roles().contains("designer") == grant) {
      throw new IllegalStateException("Tenant designer role request does not change current roles");
    }
    changes.put(
        request.targetAccountUuid(),
        new RoleChange(
            request.targetAccountUuid(),
            target,
            grant ? with(target.roles(), "designer") : without(target.roles(), "designer"),
            !grant));
    if (request.targetAccountUuid().equals(request.actorAccountUuid()) && !actor.equals(target)) {
      throw new IllegalStateException("Self-targeted role source snapshots differ");
    }
    return changes;
  }

  private AccountAuditEnvelope appendAudit(
      Request request, List<AccountTenantRoleMutationDigest.MemberResult> members) {
    List<AuditMember> auditMembers =
        members.stream()
            .sorted()
            .map(
                member ->
                    new AuditMember(
                        member.accountUuid().toString(),
                        Long.toString(member.membershipVersion()),
                        Long.toString(member.membershipAuthorityGeneration()),
                        Long.toString(member.eventSequence()),
                        member.eventId(),
                        member.eventDigest(),
                        member.callerBoundAuthorityInvalidated()))
            .toList();
    final String payload;
    try {
      payload =
          AUDIT_JSON.writeValueAsString(
              new AuditPayload(
                  "account-tenant-role-audit/v1",
                  request.requestId().toString(),
                  request.actorAccountUuid().toString(),
                  request.tenantUuid().toString(),
                  request.targetAccountUuid().toString(),
                  request.action().name(),
                  auditMembers));
    } catch (RuntimeException failure) {
      throw new IllegalStateException("Tenant-role audit serialization failed", failure);
    }
    UUID auditEventId =
        UUID.nameUUIDFromBytes(
            ("account-tenant-role-audit/v1:" + request.requestId())
                .getBytes(StandardCharsets.UTF_8));
    return auditOutboxRepository.appendCanonicalTenant(
        auditEventId, request.tenantUuid().toString(), "ACCOUNT_TENANT_ROLE_CHANGED", payload);
  }

  private void verifyExactReplay(OperationEvidence operation) {
    if (!"COMMITTED".equals(operation.status()) || operation.audit() == null) {
      throw new IllegalStateException("Tenant-role exact retry lacks committed immutable evidence");
    }
    operationRepository
        .findSourceChangeForUpdate(operation.request())
        .ifPresent(
            change -> {
              if (!"SOURCE_COMMITTED"
                  .equals(draftFenceRepository.readSourceChange(change).status())) {
                throw new IllegalStateException("Tenant-role committed source readback differs");
              }
            });
    AuditEvidence audit = operation.audit();
    auditOutboxRepository
        .findCanonicalTenantRoleEnvelopeForUpdate(
            audit.auditEventId(),
            operation.request().tenantUuid(),
            audit.occurredAt(),
            audit.payloadDigest(),
            new String(audit.payload(), StandardCharsets.UTF_8))
        .orElseThrow(() -> new IllegalStateException("Tenant-role retry audit envelope is absent"));
    for (AccountTenantRoleMutationDigest.MemberResult member : operation.members()) {
      var exact = eventProducer.readExactTenantRoleEvent(operation.request(), member);
      if (exact.outboxSequence() != member.eventSequence()
          || !exact.eventId().equals(member.eventId())
          || !exact.eventDigest().equals(member.eventDigest())
          || !java.util.Arrays.equals(exact.payload(), member.eventPayload())) {
        throw new IllegalStateException(
            "Tenant-role retry event differs from original immutable bytes");
      }
    }
  }

  private void requireRetainedExplicitMembership(
      AccountTenantMembership membership,
      VerifiedTenantProvenance expectedProvenance,
      PositiveMembershipSnapshot current) {
    VerifiedTenantProvenance stored =
        new VerifiedTenantProvenance(
            membership.getTenantId(),
            TenantProvenanceKind.valueOf(membership.getTenantProvenanceKind()),
            membership.getTenantSourceOperationId(),
            membership.getTenantProvenanceDigest());
    if (!stored.equals(expectedProvenance)
        || stored.kind() != TenantProvenanceKind.APPROVED_RETAINED
        || !"ACTIVE".equals(membership.getLifecycleState())
        || !membership.isGameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())
        || membership.getMembershipVersion()
            != Long.parseLong(
                current.membershipVersion().get(membership.getTenantUuid().toString()))
        || membership.getMembershipAuthorityGeneration()
            != Long.parseLong(current.membershipAuthorityGeneration())) {
      throw new IllegalStateException(
          "Tenant-role mutation requires the exact retained active explicit-join membership");
    }
  }

  private static void requireCurrentPair(
      PairAuthority pair,
      AccountTenantMembership membership,
      PositiveMembershipSnapshot current,
      VerifiedTenantProvenance provenance) {
    var event = current.authorityEvent();
    if (!pair.membershipExists()
        || !pair.provenance().equals(provenance)
        || pair.membershipVersion() != membership.getMembershipVersion()
        || pair.membershipAuthorityGeneration() != membership.getMembershipAuthorityGeneration()
        || pair.lastEventSequence() != Long.parseLong(event.outboxSequence())
        || !pair.lastEventId().equals(event.eventId())
        || !pair.lastEventDigest().equals(event.eventDigest())) {
      throw new IllegalStateException(
          "Current tenant-role pair authority differs from membership evidence");
    }
  }

  private static void requireExpectedVersion(
      long expected, PositiveMembershipSnapshot current, String label) {
    long actual = Long.parseLong(current.membershipVersion().get(current.tenantId()));
    if (expected != actual) {
      throw new AccountTenantRoleOperationRepository.OperationConflictException(
          "Tenant-role expected " + label + " membership version is stale");
    }
  }

  private static void requireAccountIdentity(Account account, UUID accountUuid) {
    if (account.getId() == null
        || account.getId() <= 0L
        || !accountUuid.equals(account.getAccountUuid())
        || account.getAccountUuidProvenance() == null
        || !Objects.equals(account.getAccountUuidSourceNumericId(), account.getId())) {
      throw new IllegalStateException(
          "Canonical tenant-role Account row identity is contradictory");
    }
  }

  private static List<String> with(List<String> current, String role) {
    Set<String> result = new LinkedHashSet<>(current);
    result.add(role);
    return AccountTenantMembershipRoleSnapshotRepository.canonicalizeRoleSet(result);
  }

  private static List<String> without(List<String> current, String role) {
    Set<String> result = new LinkedHashSet<>(current);
    result.remove(role);
    return AccountTenantMembershipRoleSnapshotRepository.canonicalizeRoleSet(result);
  }

  private static long increment(long value, String label) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Tenant-role " + label + " is exhausted", overflow);
    }
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Tenant-role mutation requires an active Account write transaction");
    }
  }

  private record RoleChange(
      UUID accountUuid,
      PositiveMembershipSnapshot current,
      List<String> roles,
      boolean invalidated) {}

  private record PreparedChange(
      RoleChange change,
      AccountTenantMembership membership,
      PairAuthority pair,
      CompositeSnapshot authority) {}

  private record AccountFenceCapture(
      String schema,
      String accountId,
      String generation,
      String sourceVersion,
      String issuanceFence,
      String issuanceFenceSourceVersion,
      String checkpointStream,
      String checkpointSequence,
      String canonicalEventJson) {}

  private record AuditPayload(
      String schema,
      String requestId,
      String actorAccountUuid,
      String tenantUuid,
      String targetAccountUuid,
      String action,
      List<AuditMember> members) {}

  private record AuditMember(
      String accountUuid,
      String membershipVersion,
      String membershipAuthorityGeneration,
      String eventSequence,
      String eventId,
      String eventDigest,
      boolean callerBoundAuthorityInvalidated) {}
}
