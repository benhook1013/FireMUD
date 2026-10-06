package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
import net.firedevops.firemud.accountservice.dto.AccountTenantCreationBootstrapResult;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapDigest;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.Claim;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.Completion;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.StoredOperation;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Account-owned atomic membership bootstrap for an exact fresh tenant creator operation.
 *
 * <p>This service is intentionally not exposed by REST or gRPC. A new commit requires a separately
 * registered current Account authorization/source participant; no such production participant is
 * registered by this slice, so new bootstrap attempts remain default-denied.
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Injected Account repositories and source participants are internal collaborators.")
public class AccountTenantCreationBootstrapService {
  public static final String AUDIT_EVENT_TYPE = "ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED";
  private static final String ROLE_SNAPSHOT_JSON = "[\"tenantAdmin\"]";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AccountRepository accountRepository;
  private final FreshTenantIdentityAssociationRepository tenantAssociationRepository;
  private final AccountMembershipAuthorityEventProducer eventProducer;
  private final AccountMembershipPairAuthorityRepository pairAuthorityRepository;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;
  private final AccountAuthorityOutboxRepository authorityOutboxRepository;
  private final AccountAuditOutboxRepository auditOutboxRepository;
  private final AccountTenantCreationBootstrapOperationRepository operationRepository;
  private final ObjectProvider<AccountTenantCreationBootstrapAuthorizationSource>
      authorizationSourceProvider;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Spring transaction proxy requires a non-final class; constructor only validates its internal collaborators and publishes no partial instance.")
  public AccountTenantCreationBootstrapService(
      AccountRepository accountRepository,
      FreshTenantIdentityAssociationRepository tenantAssociationRepository,
      AccountMembershipAuthorityEventProducer eventProducer,
      AccountMembershipPairAuthorityRepository pairAuthorityRepository,
      AccountTenantMembershipRepository membershipRepository,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository,
      AccountAuthorityOutboxRepository authorityOutboxRepository,
      AccountAuditOutboxRepository auditOutboxRepository,
      AccountTenantCreationBootstrapOperationRepository operationRepository,
      ObjectProvider<AccountTenantCreationBootstrapAuthorizationSource>
          authorizationSourceProvider) {
    this.accountRepository = Objects.requireNonNull(accountRepository);
    this.tenantAssociationRepository = Objects.requireNonNull(tenantAssociationRepository);
    this.eventProducer = Objects.requireNonNull(eventProducer);
    this.pairAuthorityRepository = Objects.requireNonNull(pairAuthorityRepository);
    this.membershipRepository = Objects.requireNonNull(membershipRepository);
    this.roleSnapshotRepository = Objects.requireNonNull(roleSnapshotRepository);
    this.authorityOutboxRepository = Objects.requireNonNull(authorityOutboxRepository);
    this.auditOutboxRepository = Objects.requireNonNull(auditOutboxRepository);
    this.operationRepository = Objects.requireNonNull(operationRepository);
    this.authorizationSourceProvider = Objects.requireNonNull(authorizationSourceProvider);
  }

  /**
   * Replays only an exact committed historical result, or runs a new commit under a current-source
   * participant. The creator evidence value itself is data and does not authorize this method.
   */
  @Transactional
  public AccountTenantCreationBootstrapResult bootstrap(FreshTenantCreatorEvidence evidence) {
    requireWritableOwnerTransaction();
    Objects.requireNonNull(evidence, "Authenticated creator evidence is required");
    UUID creatorUuid = evidence.initiatingAccountId();
    UUID tenantUuid = evidence.creationEvidence().canonicalTenantId();
    Account creator =
        accountRepository
            .findByAccountUuidForUpdate(creatorUuid)
            .orElseThrow(() -> new IllegalStateException("Creator Account row is absent"));
    requireAccountIdentity(creator, creatorUuid);

    UUID requestId = evidence.accountAuthorizationOperationId();
    Optional<StoredOperation> original = operationRepository.findForUpdate(requestId);
    if (original.isPresent()) {
      return replayExactOriginal(evidence, original.orElseThrow());
    }
    Optional<UUID> tenantOperation = operationRepository.findRequestIdByTenantForUpdate(tenantUuid);
    if (tenantOperation.isPresent()) {
      throw new AccountTenantCreationBootstrapOperationRepository.OperationConflictException(
          "Fresh tenant already has a different immutable creator-bootstrap operation");
    }

    AccountTenantCreationBootstrapAuthorizationSource currentSource =
        authorizationSourceProvider.getIfAvailable();
    if (currentSource == null) {
      throw new IllegalStateException(
          "No current Account creator-authorization/source participant is registered");
    }
    AtomicBoolean callbackRan = new AtomicBoolean();
    AtomicReference<AccountTenantCreationBootstrapResult> committed = new AtomicReference<>();
    AccountTenantCreationBootstrapResult returned =
        currentSource.withCurrentAuthorizationAndSourceParticipation(
            evidence,
            () -> {
              if (!callbackRan.compareAndSet(false, true)) {
                throw new IllegalStateException(
                    "Account creator authorization/source participant repeated its commit callback");
              }
              AccountTenantCreationBootstrapResult result = commitNew(evidence, creator);
              committed.set(result);
              return result;
            });
    if (!callbackRan.get() || returned == null || !returned.equals(committed.get())) {
      throw new IllegalStateException(
          "Account creator authorization/source participant did not preserve the owner result");
    }
    return returned;
  }

  /**
   * Reads an already committed creator-control source under the existing Account/source locks. This
   * immutable source capture authenticates no caller and grants no authoring or gameplay
   * permission. Historical receipt recovery is verified separately from current source equality.
   */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public AccountMembershipAuthorityEventProducer.CreatorControlCaptureSources
      readExistingCreatorControlCaptureSources(UUID accountUuid, UUID tenantUuid) {
    requireWritableOwnerTransaction();
    return eventProducer.readExistingCreatorControlCaptureSources(
        accountUuid,
        tenantUuid,
        () -> {
          UUID requestId =
              operationRepository
                  .findRequestIdByTenantForUpdate(tenantUuid)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Committed creator-bootstrap operation is absent"));
          StoredOperation operation =
              operationRepository
                  .findForUpdate(requestId)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Committed creator-bootstrap receipt is absent"));
          FreshTenantCreationEvidence creation =
              tenantAssociationRepository
                  .read(tenantUuid)
                  .orElseThrow(
                      () -> new IllegalStateException("Creator fresh tenant source is absent"));
          if (!accountUuid.equals(operation.initiatingAccountUuid())) {
            throw new IllegalStateException("Creator-bootstrap receipt belongs to another Account");
          }
          FreshTenantCreatorEvidence evidence =
              new FreshTenantCreatorEvidence(
                  1,
                  creation,
                  accountUuid,
                  operation.accountAuthorizationOperationId(),
                  operation.accountAuthorizationDigest(),
                  operation.creatorEvidenceDigest());
          replayExactOriginal(evidence, operation);
          operationRepository.assertNoContradictoryMembershipHistory(accountUuid, tenantUuid);
          return operation;
        });
  }

  private AccountTenantCreationBootstrapResult commitNew(
      FreshTenantCreatorEvidence evidence, Account creator) {
    requireWritableOwnerTransaction();
    UUID creatorUuid = evidence.initiatingAccountId();
    UUID tenantUuid = evidence.creationEvidence().canonicalTenantId();
    FreshTenantCreationEvidence associated =
        tenantAssociationRepository
            .read(tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Fresh Game Design tenant source is absent"));
    if (!associated.equals(evidence.creationEvidence())) {
      throw new IllegalStateException(
          "Fresh Game Design tenant source differs from creator qualification evidence");
    }

    AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot baseline =
        eventProducer.readFreshNeverJoinedMembershipSnapshot(creatorUuid, tenantUuid);
    if (!creatorUuid.toString().equals(baseline.accountId())
        || !tenantUuid.toString().equals(baseline.tenantId())
        || baseline.membershipExists()
        || !"MISSING".equals(baseline.membershipLifecycleState())
        || baseline.gameplayAdmissionAllowed()
        || !List.of(
                new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                    baseline.outboxStreamKey(), "0"))
            .equals(
                baseline.outboxCheckpoints().stream()
                    .filter(
                        checkpoint ->
                            checkpoint.outboxStreamKey().equals(baseline.outboxStreamKey()))
                    .toList())) {
      throw new IllegalStateException(
          "Creator bootstrap baseline is not exact sequence-zero absence");
    }

    operationRepository.assertNoContradictoryMembershipHistory(creatorUuid, tenantUuid);
    VerifiedTenantProvenance provenance = freshProvenance(associated);
    PairAuthority priorPair =
        pairAuthorityRepository
            .readForUpdate(creatorUuid, tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Positive creator membership baseline is absent"));
    long baselineVersion =
        positiveLong(baseline.membershipVersion().get(tenantUuid.toString()), "membership version");
    long baselineGeneration =
        positiveLong(baseline.membershipAuthorityGeneration(), "membership authority generation");
    if (!priorPair.accountUuid().equals(creatorUuid)
        || !priorPair.tenantUuid().equals(tenantUuid)
        || !priorPair.provenance().equals(provenance)
        || priorPair.membershipExists()
        || priorPair.membershipVersion() != baselineVersion
        || priorPair.membershipAuthorityGeneration() != baselineGeneration
        || priorPair.lastEventSequence() != 0L
        || priorPair.lastEventId() != null
        || priorPair.lastEventDigest() != null
        || priorPair.lastTransitionInvalidated()) {
      throw new IllegalStateException("Creator pair differs from its same-fence positive baseline");
    }

    byte[] creatorPayload = AccountTenantCreationBootstrapDigest.creatorEvidencePayload(evidence);
    byte[] sourcePayload = AccountTenantCreationBootstrapDigest.sourceSnapshotPayload(baseline);
    String sourceDigest = AccountTenantCreationBootstrapDigest.sha256(sourcePayload);
    byte[] requestPayload =
        AccountTenantCreationBootstrapDigest.requestPayload(creatorPayload, sourcePayload);
    String requestDigest = AccountTenantCreationBootstrapDigest.sha256(requestPayload);
    Claim claim =
        operationRepository.claim(
            evidence,
            creatorPayload,
            sourcePayload,
            sourceDigest,
            requestPayload,
            requestDigest,
            baselineVersion,
            baselineGeneration);
    if (!claim.claimed()) {
      return replayExactOriginal(evidence, claim.operation());
    }

    long newVersion = increment(baselineVersion, "membership version");
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setAccount(creator);
    membership.setTenantId(null);
    membership.setTenantUuid(tenantUuid);
    membership.setTenantProvenanceKind(TenantProvenanceKind.FRESH_GAME_DESIGN.name());
    membership.setTenantSourceOperationId(associated.operationId());
    membership.setTenantProvenanceDigest(associated.evidenceDigest());
    membership.setGameplayAdmissionAllowed(false);
    membership.setLifecycleState("ACTIVE");
    membership.setMembershipVersion(newVersion);
    membership.setMembershipAuthorityGeneration(baselineGeneration);
    membership.setAuthorityProvenance("TENANT_CREATION");
    AccountTenantMembership persisted =
        membershipRepository.saveCanonical(membership, creatorUuid, tenantUuid, provenance);
    RoleSnapshot roles =
        roleSnapshotRepository.replaceCanonical(
            persisted, creatorUuid, tenantUuid, provenance, newVersion, List.of("tenantAdmin"));
    if (!roles.roles().equals(List.of("tenantAdmin"))) {
      throw new IllegalStateException("Creator membership role readback is not tenantAdmin-only");
    }

    String requestId = evidence.accountAuthorizationOperationId().toString();
    String streamKey =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + creatorUuid
            + "/"
            + tenantUuid;
    if (authorityOutboxRepository.readCheckpoint(streamKey).isPresent()) {
      throw new IllegalStateException(
          "Creator membership stream acquired history before bootstrap");
    }
    String eventId =
        UUID.nameUUIDFromBytes(
                (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + requestId)
                    .getBytes(StandardCharsets.UTF_8))
            .toString();
    MembershipEvent[] candidate = new MembershipEvent[1];
    Event event =
        authorityOutboxRepository.append(
            streamKey,
            requestId,
            sequence -> {
              if (sequence != 1L) {
                throw new IllegalStateException(
                    "First creator membership event must use sequence one");
              }
              MembershipEvent sealed =
                  MembershipAuthorityEventV1Codec.seal(
                      eventPreimage(
                          baseline,
                          creatorUuid,
                          tenantUuid,
                          newVersion,
                          baselineGeneration,
                          streamKey,
                          requestId,
                          eventId));
              candidate[0] = sealed;
              return new EventEvidence(
                  sealed.eventId(), sealed.eventDigest(), sealed.canonicalJsonUtf8());
            });
    MembershipEvent expectedEvent = candidate[0];
    Event eventReadback =
        authorityOutboxRepository
            .findEvent(streamKey, requestId)
            .orElseThrow(
                () -> new IllegalStateException("Creator membership event readback is absent"));
    Checkpoint checkpoint =
        authorityOutboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(
                () -> new IllegalStateException("Creator membership checkpoint is absent"));
    MembershipEvent verifiedEvent =
        MembershipAuthorityEventV1Codec.verify(
            new String(eventReadback.payload(), StandardCharsets.UTF_8));
    if (expectedEvent == null
        || !event.equals(eventReadback)
        || !checkpoint.outboxStreamKey().equals(streamKey)
        || checkpoint.outboxSequence() != 1L
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())
        || !verifiedEvent.canonicalJson().equals(expectedEvent.canonicalJson())
        || !verifiedEvent.roles().equals(List.of("tenantAdmin"))
        || verifiedEvent.gameplayAdmissionAllowed()
        || verifiedEvent.callerBoundAuthorityInvalidated()
        || !Long.toString(newVersion)
            .equals(verifiedEvent.membershipVersion().get(tenantUuid.toString()))) {
      throw new IllegalStateException("Creator membership event or checkpoint readback differs");
    }

    PairAuthority advanced =
        pairAuthorityRepository.commitTransition(
            priorPair, new PairTransition(true, 1L, event.eventId(), event.eventDigest(), false));
    if (!advanced.membershipExists()
        || advanced.membershipVersion() != newVersion
        || advanced.membershipAuthorityGeneration() != baselineGeneration
        || advanced.lastEventSequence() != 1L
        || !event.eventId().equals(advanced.lastEventId())
        || !event.eventDigest().equals(advanced.lastEventDigest())
        || advanced.lastTransitionInvalidated()) {
      throw new IllegalStateException("Creator membership pair transition readback differs");
    }

    byte[] rolesPayload = ROLE_SNAPSHOT_JSON.getBytes(StandardCharsets.UTF_8);
    UUID auditEventId =
        UUID.nameUUIDFromBytes(
            ("account-tenant-creation-bootstrap-audit/v1:" + requestId)
                .getBytes(StandardCharsets.UTF_8));
    byte[] auditPayload =
        auditPayload(
            evidence,
            requestDigest,
            sourceDigest,
            newVersion,
            baselineGeneration,
            event,
            checkpoint);
    String auditText = new String(auditPayload, StandardCharsets.UTF_8);
    AccountAuditEnvelope audit =
        auditOutboxRepository.appendCanonicalTenant(
            auditEventId, tenantUuid.toString(), AUDIT_EVENT_TYPE, auditText);
    byte[] resultPayload =
        AccountTenantCreationBootstrapDigest.resultPayload(
            requestId,
            requestDigest,
            sourceDigest,
            tenantUuid,
            verifiedEvent.membershipVersion(),
            Long.toString(baselineGeneration),
            streamKey,
            requestId,
            checkpoint.outboxSequence(),
            event.eventId(),
            event.eventDigest(),
            event.payload(),
            audit.auditEventId(),
            audit.eventType(),
            audit.occurredAt().toString(),
            audit.payloadDigest(),
            audit.payload().getBytes(StandardCharsets.UTF_8),
            creatorPayload,
            sourcePayload);
    String resultDigest = AccountTenantCreationBootstrapDigest.sha256(resultPayload);
    StoredOperation completed =
        operationRepository.complete(
            evidence.accountAuthorizationOperationId(),
            new Completion(
                persisted.getId(),
                newVersion,
                baselineGeneration,
                "ACTIVE",
                false,
                rolesPayload,
                streamKey,
                requestId,
                checkpoint.outboxSequence(),
                event.eventId(),
                event.eventDigest(),
                false,
                event.payload(),
                audit.auditEventId(),
                audit.eventType(),
                audit.occurredAt(),
                audit.payloadDigest(),
                audit.payload().getBytes(StandardCharsets.UTF_8),
                resultPayload,
                resultDigest));

    verifyCommittedReadback(completed, persisted, roles, advanced, event, audit);
    return completed.result();
  }

  private AccountTenantCreationBootstrapResult replayExactOriginal(
      FreshTenantCreatorEvidence evidence, StoredOperation operation) {
    byte[] creatorPayload = AccountTenantCreationBootstrapDigest.creatorEvidencePayload(evidence);
    if (!operation.requestId().equals(evidence.accountAuthorizationOperationId())
        || !operation.initiatingAccountUuid().equals(evidence.initiatingAccountId())
        || !operation.tenantUuid().equals(evidence.creationEvidence().canonicalTenantId())
        || !Arrays.equals(operation.creatorEvidencePayload(), creatorPayload)
        || !operation.creatorEvidenceDigest().equals(evidence.evidenceDigest())
        || !operation.creationOperationId().equals(evidence.creationEvidence().operationId())
        || !operation.creationRequestId().equals(evidence.creationEvidence().creationRequestId())
        || !operation
            .accountAuthorizationOperationId()
            .equals(evidence.accountAuthorizationOperationId())
        || !operation.accountAuthorizationDigest().equals(evidence.accountAuthorizationDigest())) {
      throw new AccountTenantCreationBootstrapOperationRepository.OperationConflictException(
          "Creator-bootstrap operation ID was reused with changed creator or authorization source");
    }
    requireCommittedOperation(operation);
    byte[] sourcePayload = operation.sourceSnapshotPayload();
    byte[] requestPayload =
        AccountTenantCreationBootstrapDigest.requestPayload(creatorPayload, sourcePayload);
    if (!AccountTenantCreationBootstrapDigest.sha256(sourcePayload)
            .equals(operation.sourceSnapshotDigest())
        || !Arrays.equals(requestPayload, operation.requestPayload())
        || !AccountTenantCreationBootstrapDigest.sha256(requestPayload)
            .equals(operation.requestDigest())
        || !AccountTenantCreationBootstrapDigest.sha256(operation.resultPayload())
            .equals(operation.resultDigest())) {
      throw new IllegalStateException(
          "Creator-bootstrap immutable source or result digest differs");
    }

    MembershipEvent event =
        MembershipAuthorityEventV1Codec.verify(
            new String(operation.eventPayload(), StandardCharsets.UTF_8));
    String expectedStream =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + evidence.initiatingAccountId()
            + "/"
            + evidence.creationEvidence().canonicalTenantId();
    if (!expectedStream.equals(operation.eventStreamKey())
        || !operation.requestId().toString().equals(operation.eventRequestId())
        || operation.eventSequence() != 1L
        || !event.outboxStreamKey().equals(operation.eventStreamKey())
        || !event.requestId().equals(operation.eventRequestId())
        || !event.eventId().equals(operation.eventId())
        || !event.eventDigest().equals(operation.eventDigest())
        || !"ACTIVE".equals(event.membershipLifecycleState())
        || event.gameplayAdmissionAllowed()
        || event.callerBoundAuthorityInvalidated()
        || !event.roles().equals(List.of("tenantAdmin"))
        || !Map.of(operation.tenantUuid().toString(), Long.toString(operation.membershipVersion()))
            .equals(event.membershipVersion())) {
      throw new IllegalStateException("Creator-bootstrap historical event receipt is inconsistent");
    }
    Event originalEvent =
        authorityOutboxRepository
            .findEvent(operation.eventStreamKey(), operation.eventRequestId())
            .orElseThrow(() -> new IllegalStateException("Original creator event is absent"));
    if (originalEvent.outboxSequence() != operation.eventSequence()
        || !originalEvent.eventId().equals(operation.eventId())
        || !originalEvent.eventDigest().equals(operation.eventDigest())
        || !Arrays.equals(originalEvent.payload(), operation.eventPayload())) {
      throw new IllegalStateException("Original creator event readback differs from its receipt");
    }

    AccountAuditEnvelope expectedAudit = auditEnvelope(operation);
    AccountAuditEnvelope actualAudit =
        auditOutboxRepository
            .findExactCanonicalTenantEnvelopeForUpdate(expectedAudit)
            .orElseThrow(
                () -> new IllegalStateException("Original creator audit envelope is absent"));
    if (!actualAudit.equals(expectedAudit)) {
      throw new IllegalStateException("Original creator audit envelope differs from its receipt");
    }
    byte[] expectedResultPayload =
        AccountTenantCreationBootstrapDigest.resultPayload(
            operation.requestId().toString(),
            operation.requestDigest(),
            operation.sourceSnapshotDigest(),
            operation.tenantUuid(),
            event.membershipVersion(),
            Long.toString(operation.membershipAuthorityGeneration()),
            operation.eventStreamKey(),
            operation.eventRequestId(),
            operation.eventSequence(),
            operation.eventId(),
            operation.eventDigest(),
            operation.eventPayload(),
            operation.auditEventId(),
            operation.auditEventType(),
            operation.auditOccurredAt().toString(),
            operation.auditPayloadDigest(),
            operation.auditPayload(),
            operation.creatorEvidencePayload(),
            operation.sourceSnapshotPayload());
    if (!Arrays.equals(expectedResultPayload, operation.resultPayload())) {
      throw new IllegalStateException("Creator-bootstrap historical result bytes differ");
    }
    // This is immutable historical recovery. It intentionally does not read or rewrite current
    // membership, roles, pair authority, or later JOIN state.
    return operation.result();
  }

  private void verifyCommittedReadback(
      StoredOperation completed,
      AccountTenantMembership expectedMembership,
      RoleSnapshot expectedRoles,
      PairAuthority expectedPair,
      Event expectedEvent,
      AccountAuditEnvelope expectedAudit) {
    AccountTenantMembership membership =
        membershipRepository
            .findCanonicalMembershipForUpdate(expectedPair.accountUuid(), expectedPair.tenantUuid())
            .orElseThrow(() -> new IllegalStateException("Creator membership readback is absent"));
    RoleSnapshot roles =
        roleSnapshotRepository
            .findForCanonicalUpdate(
                expectedPair.accountUuid(),
                expectedPair.tenantUuid(),
                expectedPair.provenance(),
                expectedMembership.getId(),
                expectedMembership.getMembershipVersion())
            .orElseThrow(
                () -> new IllegalStateException("Creator role snapshot readback is absent"));
    PairAuthority pair =
        pairAuthorityRepository
            .readForUpdate(expectedPair.accountUuid(), expectedPair.tenantUuid())
            .orElseThrow(
                () -> new IllegalStateException("Creator pair authority readback is absent"));
    Event event =
        authorityOutboxRepository
            .findEvent(expectedEvent.outboxStreamKey(), expectedEvent.requestId())
            .orElseThrow(() -> new IllegalStateException("Creator event readback is absent"));
    MembershipEvent eventSnapshot =
        MembershipAuthorityEventV1Codec.verify(new String(event.payload(), StandardCharsets.UTF_8));
    AccountTenantCreationBootstrapResult result = completed.result();
    AccountAuditEnvelope audit =
        auditOutboxRepository
            .findExactCanonicalTenantEnvelopeForUpdate(expectedAudit)
            .orElseThrow(() -> new IllegalStateException("Creator audit readback is absent"));
    StoredOperation receipt =
        operationRepository
            .findForUpdate(completed.requestId())
            .orElseThrow(() -> new IllegalStateException("Creator operation receipt is absent"));
    if (!sameMembership(expectedMembership, membership)
        || !expectedRoles.equals(roles)
        || !expectedPair.equals(pair)
        || !expectedEvent.equals(event)
        || !expectedAudit.equals(audit)
        || !result.membershipVersion().equals(eventSnapshot.membershipVersion())
        || !Map.of(
                expectedPair.tenantUuid().toString(),
                Long.toString(expectedMembership.getMembershipVersion()))
            .equals(result.membershipVersion())
        || !"COMMITTED".equals(receipt.status())
        || !completed.resultDigest().equals(receipt.resultDigest())
        || !Arrays.equals(completed.resultPayload(), receipt.resultPayload())
        || !Arrays.equals(completed.eventPayload(), receipt.eventPayload())
        || !Arrays.equals(completed.auditPayload(), receipt.auditPayload())) {
      throw new IllegalStateException(
          "Creator membership, roles, pair, event, audit, or receipt readback differs");
    }
  }

  private Map<String, Object> eventPreimage(
      AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot baseline,
      UUID creatorUuid,
      UUID tenantUuid,
      long membershipVersion,
      long membershipGeneration,
      String streamKey,
      String requestId,
      String eventId) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    event.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
    event.put("eventId", eventId);
    event.put("requestId", requestId);
    event.put("outboxStreamKey", streamKey);
    event.put("outboxSequence", "1");
    event.put("sourceScope", "membership/" + creatorUuid + "/" + tenantUuid);
    event.put("accountId", creatorUuid.toString());
    event.put("tenantId", tenantUuid.toString());
    event.put("membershipExists", true);
    event.put("membershipLifecycleState", "ACTIVE");
    event.put("membershipVersion", Map.of(tenantUuid.toString(), Long.toString(membershipVersion)));
    event.put("membershipAuthorityGeneration", Long.toString(membershipGeneration));
    event.put(
        "authorityTuple",
        AccountTenantCreationBootstrapDigest.authorityTupleMap(baseline.authorityTuple()));
    event.put("issuanceFence", baseline.issuanceFence());
    event.put("roles", List.of("tenantAdmin"));
    event.put("gameplayAdmissionAllowed", false);
    event.put("callerBoundAuthorityInvalidated", false);
    return event;
  }

  private byte[] auditPayload(
      FreshTenantCreatorEvidence evidence,
      String requestDigest,
      String sourceSnapshotDigest,
      long membershipVersion,
      long membershipGeneration,
      Event event,
      Checkpoint checkpoint) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "account-tenant-creation-bootstrap-audit/v1");
    payload.put("requestId", evidence.accountAuthorizationOperationId().toString());
    payload.put("requestDigest", requestDigest);
    payload.put("initiatingAccountUuid", evidence.initiatingAccountId().toString());
    payload.put("tenantUuid", evidence.creationEvidence().canonicalTenantId().toString());
    payload.put("creationRequestId", evidence.creationEvidence().creationRequestId().toString());
    payload.put("creationOperationId", evidence.creationEvidence().operationId().toString());
    payload.put("creationRequestDigest", evidence.creationEvidence().requestDigest());
    payload.put("creationEvidenceDigest", evidence.creationEvidence().evidenceDigest());
    payload.put(
        "accountAuthorizationOperationId", evidence.accountAuthorizationOperationId().toString());
    payload.put("accountAuthorizationDigest", evidence.accountAuthorizationDigest());
    payload.put("creatorEvidenceDigest", evidence.evidenceDigest());
    payload.put("sourceSnapshotDigest", sourceSnapshotDigest);
    payload.put(
        "membershipVersion",
        Map.of(
            evidence.creationEvidence().canonicalTenantId().toString(),
            Long.toString(membershipVersion)));
    payload.put("membershipAuthorityGeneration", Long.toString(membershipGeneration));
    payload.put("outboxStreamKey", checkpoint.outboxStreamKey());
    payload.put("outboxSequence", Long.toString(checkpoint.outboxSequence()));
    payload.put("eventId", event.eventId());
    payload.put("eventDigest", event.eventDigest());
    payload.put("callerBoundAuthorityInvalidated", false);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(payload));
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException("Creator-bootstrap audit serialization failed", failure);
    }
  }

  private AccountAuditEnvelope auditEnvelope(StoredOperation operation) {
    return new AccountAuditEnvelope(
        operation.auditEventId(),
        "tenant",
        AccountAuditTenantIdentity.canonicalTenantV2(operation.tenantUuid().toString()),
        "account-service",
        operation.auditEventType(),
        operation.auditOccurredAt(),
        1,
        1,
        operation.auditPayloadDigest(),
        new String(operation.auditPayload(), StandardCharsets.UTF_8));
  }

  private static VerifiedTenantProvenance freshProvenance(FreshTenantCreationEvidence source) {
    return new VerifiedTenantProvenance(
        null,
        TenantProvenanceKind.FRESH_GAME_DESIGN,
        source.operationId(),
        source.evidenceDigest());
  }

  private static void requireCommittedOperation(StoredOperation operation) {
    if (!"COMMITTED".equals(operation.status())
        || operation.membershipId() == null
        || operation.membershipVersion() == null
        || operation.membershipAuthorityGeneration() == null
        || !"ACTIVE".equals(operation.membershipLifecycleState())
        || !Boolean.FALSE.equals(operation.gameplayAdmissionAllowed())
        || !Boolean.FALSE.equals(operation.callerBoundAuthorityInvalidated())
        || operation.membershipRolesPayload() == null
        || operation.eventPayload() == null
        || operation.auditPayload() == null
        || operation.resultPayload() == null
        || operation.resultDigest() == null
        || operation.committedAt() == null) {
      throw new IllegalStateException("Incomplete creator-bootstrap operation cannot be replayed");
    }
  }

  private static boolean sameMembership(
      AccountTenantMembership expected, AccountTenantMembership actual) {
    return Objects.equals(expected.getId(), actual.getId())
        && Objects.equals(
            expected.getAccount().getAccountUuid(), actual.getAccount().getAccountUuid())
        && Objects.equals(expected.getTenantUuid(), actual.getTenantUuid())
        && Objects.equals(expected.getTenantId(), actual.getTenantId())
        && Objects.equals(expected.getTenantProvenanceKind(), actual.getTenantProvenanceKind())
        && Objects.equals(
            expected.getTenantSourceOperationId(), actual.getTenantSourceOperationId())
        && Objects.equals(expected.getTenantProvenanceDigest(), actual.getTenantProvenanceDigest())
        && Objects.equals(expected.getLifecycleState(), actual.getLifecycleState())
        && expected.isGameplayAdmissionAllowed() == actual.isGameplayAdmissionAllowed()
        && expected.getMembershipVersion() == actual.getMembershipVersion()
        && expected.getMembershipAuthorityGeneration() == actual.getMembershipAuthorityGeneration()
        && Objects.equals(expected.getAuthorityProvenance(), actual.getAuthorityProvenance());
  }

  private static void requireAccountIdentity(Account account, UUID expectedUuid) {
    if (account.getId() == null
        || account.getId() <= 0L
        || !expectedUuid.equals(account.getAccountUuid())
        || account.getAccountUuidProvenance() == null
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())) {
      throw new IllegalStateException(
          "Creator Account UUID provenance is incomplete or mismatched");
    }
  }

  private static long positiveLong(String value, String label) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw new IllegalStateException("Creator-bootstrap " + label + " is not a positive decimal");
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L) {
        throw new IllegalStateException("Creator-bootstrap " + label + " is not positive");
      }
      return parsed;
    } catch (NumberFormatException overflow) {
      throw new IllegalStateException(
          "Creator-bootstrap " + label + " exceeds Account BIGINT", overflow);
    }
  }

  private static long increment(long value, String label) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Account " + label + " is exhausted", overflow);
    }
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Creator bootstrap requires a writable Account transaction");
    }
  }
}
