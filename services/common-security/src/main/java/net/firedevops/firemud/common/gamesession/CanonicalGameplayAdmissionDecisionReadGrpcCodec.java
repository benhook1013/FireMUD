package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.security.AccountJwtExactValues;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadGrpcCodec;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionCandidate;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionStatus;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionOriginKind;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionOwnerProofOutcome;
import net.firedevops.firemud.gamesession.v1.CanonicalPlayableTargetEvidence;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadResponse;

/**
 * Closed, purpose-specific wire mapping for Account-only Game Session owner reads.
 *
 * <p>The returned values are structurally verified read evidence. They do not prove current Account
 * authority, any external acknowledgement, finalization, or gameplay admission.
 */
public final class CanonicalGameplayAdmissionDecisionReadGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES =
      AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES + 1024;
  public static final int MAX_RESPONSE_WIRE_BYTES = 256 * 1024;
  public static final int MAX_PUBLISHED_REALM_REQUEST_WIRE_BYTES =
      CanonicalGameplayRosterOwnerReadGrpcCodec.MAX_REQUEST_BYTES + 256;
  public static final int MAX_PUBLISHED_REALM_RESPONSE_WIRE_BYTES =
      CanonicalGameplayRosterOwnerReadGrpcCodec.MAX_RESPONSE_BYTES + 1024 * 1024;
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private CanonicalGameplayAdmissionDecisionReadGrpcCodec() {}

  /** Locally retained request identity; construction is restricted to this codec. */
  public static final class RequestEvidence {
    private final UUID bindingDecisionId;
    private final AccountGameplayAdmissionLeaseEvidence expectedLeaseEvidence;
    private final byte[] expectedLeaseEvidenceUtf8;

    private RequestEvidence(
        UUID bindingDecisionId,
        AccountGameplayAdmissionLeaseEvidence expectedLeaseEvidence,
        byte[] expectedLeaseEvidenceUtf8) {
      this.bindingDecisionId = bindingDecisionId;
      this.expectedLeaseEvidence = expectedLeaseEvidence;
      this.expectedLeaseEvidenceUtf8 = expectedLeaseEvidenceUtf8.clone();
    }

    public UUID bindingDecisionId() {
      return bindingDecisionId;
    }

    public AccountGameplayAdmissionLeaseEvidence expectedLeaseEvidence() {
      return expectedLeaseEvidence;
    }

    public byte[] expectedLeaseEvidenceUtf8() {
      return expectedLeaseEvidenceUtf8.clone();
    }
  }

  /** Only codec-created complete readbacks are returned to the Account caller. */
  public static final class Evidence {
    private final UUID bindingDecisionId;
    private final AccountGameplayAdmissionLeaseEvidence leaseEvidence;
    private final BigInteger bindingGeneration;
    private final BigInteger expectedOldBindingGeneration;
    private final UUID resumeEpisodeId;
    private final Candidate candidate;
    private final Target targetEvidence;

    private Evidence(
        UUID bindingDecisionId,
        AccountGameplayAdmissionLeaseEvidence leaseEvidence,
        BigInteger bindingGeneration,
        BigInteger expectedOldBindingGeneration,
        UUID resumeEpisodeId,
        Candidate candidate,
        Target targetEvidence) {
      this.bindingDecisionId = bindingDecisionId;
      this.leaseEvidence = leaseEvidence;
      this.bindingGeneration = bindingGeneration;
      this.expectedOldBindingGeneration = expectedOldBindingGeneration;
      this.resumeEpisodeId = resumeEpisodeId;
      this.candidate = candidate;
      this.targetEvidence = targetEvidence;
    }

    public UUID bindingDecisionId() {
      return bindingDecisionId;
    }

    public AccountGameplayAdmissionLeaseEvidence leaseEvidence() {
      return leaseEvidence;
    }

    public String originalLeaseEvidenceJson() {
      return leaseEvidence.canonicalJson();
    }

    public Status status() {
      return Status.PROVISIONAL;
    }

    public BigInteger bindingGeneration() {
      return bindingGeneration;
    }

    public BigInteger expectedOldBindingGeneration() {
      return expectedOldBindingGeneration;
    }

    public UUID resumeEpisodeId() {
      return resumeEpisodeId;
    }

    public Candidate candidate() {
      return candidate;
    }

    public Target targetEvidence() {
      return targetEvidence;
    }
  }

  /** Parsed pre-lease selector; its account identity and counters remain correlation only. */
  public static final class PublishedRealmRequest {
    private final CanonicalGameplayRosterOwnerReadEvidence.Request selector;

    private PublishedRealmRequest(CanonicalGameplayRosterOwnerReadEvidence.Request selector) {
      this.selector = selector;
    }

    public CanonicalGameplayRosterOwnerReadEvidence.Request selector() {
      return selector;
    }
  }

  /** Complete current target tied to the existing validated route proof and policy-set evidence. */
  public static final class PublishedRealmEvidence {
    private final CanonicalGameplayRosterOwnerReadEvidence.Request selector;
    private final CanonicalGameplayRosterOwnerReadEvidence sourceEvidence;
    private final Target currentTarget;

    private PublishedRealmEvidence(
        CanonicalGameplayRosterOwnerReadEvidence.Request selector,
        CanonicalGameplayRosterOwnerReadEvidence sourceEvidence,
        Target currentTarget) {
      this.selector = selector;
      this.sourceEvidence = sourceEvidence;
      this.currentTarget = currentTarget;
    }

    public CanonicalGameplayRosterOwnerReadEvidence.Request selector() {
      return selector;
    }

    public CanonicalGameplayRosterOwnerReadEvidence sourceEvidence() {
      return sourceEvidence;
    }

    public Target currentTarget() {
      return currentTarget;
    }
  }

  /** Complete CanonicalGameplayBindingIdentity in Account-independent common types. */
  public record Candidate(
      UUID accountId,
      UUID tenantId,
      UUID playableStateNamespaceId,
      UUID characterId,
      String playableStateScope,
      UUID gameInstanceId,
      long runtimeGameInstanceId,
      UUID sessionId,
      UUID regionId,
      BigInteger regionEpoch,
      String issuerId,
      BigInteger issuerAuthGeneration,
      BigInteger issuerIndexLayoutVersion,
      BigInteger issuerIndexPartitionCount,
      BigInteger issuerIndexPartitionCapacity) {}

  /** Complete CanonicalPlayableTarget in Account-independent common types. */
  public record Target(
      String targetNamespace,
      String tenantSlug,
      UUID canonicalTenantId,
      String worldSlug,
      String worldDisplayName,
      UUID realmId,
      String realmSlug,
      String realmDisplayName,
      long gameSessionTenantId,
      long gameInstanceId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID canonicalGameInstanceId,
      UUID canonicalVersionId,
      long runtimeVersionId,
      long catalogRevision,
      long pointerVersion,
      String admissionPointerSnapshotDigest,
      long activeWorldEpoch,
      String initialAdmissionRequestId,
      String initialAdmissionRequestDigest,
      OriginKind initialAdmissionOriginKind,
      Long expectedPriorPointerVersion,
      UUID holdId,
      UUID holdFence,
      String holdBindingDigest,
      long auditEventId,
      String ownerProofDigest,
      OwnerProofOutcome ownerProofOutcome,
      boolean positiveDurableAbort,
      Instant ownerProofTerminalAt,
      String characterCreationPolicy) {}

  public enum OriginKind {
    NO_PRIOR_POINTER,
    EXPECT_CLOSED
  }

  public enum Status {
    PROVISIONAL
  }

  public enum OwnerProofOutcome {
    PENDING,
    COMMITTED,
    ABORTED
  }

  public static GetCanonicalGameplayAdmissionDecisionRequest toRequest(
      UUID bindingDecisionId, AccountGameplayAdmissionLeaseEvidence expectedLeaseEvidence) {
    requireNonNil(bindingDecisionId, "bindingDecisionId");
    Objects.requireNonNull(expectedLeaseEvidence, "expectedLeaseEvidence");
    byte[] leaseBytes = canonicalLeaseBytes(expectedLeaseEvidence);
    GetCanonicalGameplayAdmissionDecisionRequest request =
        GetCanonicalGameplayAdmissionDecisionRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addBindingDecisionUuid(bindingDecisionId.toString())
            .addExpectedLeaseEvidenceUtf8(ByteString.copyFrom(leaseBytes))
            .build();
    requireWireSize(request.getSerializedSize(), MAX_REQUEST_WIRE_BYTES, "read request");
    return request;
  }

  /** Parses the untrusted request only after the GS workload guard has authenticated its peer. */
  public static RequestEvidence parseRequest(GetCanonicalGameplayAdmissionDecisionRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireSize(request.getSerializedSize(), MAX_REQUEST_WIRE_BYTES, "read request");
    if (!request.getUnknownFields().asMap().isEmpty()) throw invalid("Unknown request field");
    if (one(request.getSchemaVersionList(), "schemaVersion") != SCHEMA_VERSION) {
      throw invalid("Unsupported request schema");
    }
    UUID bindingDecisionId = uuid(one(request.getBindingDecisionUuidList(), "bindingDecisionId"));
    byte[] leaseBytes =
        one(request.getExpectedLeaseEvidenceUtf8List(), "expectedLeaseEvidence").toByteArray();
    AccountGameplayAdmissionLeaseEvidence lease = parseLease(leaseBytes);
    return new RequestEvidence(bindingDecisionId, lease, leaseBytes);
  }

  /** Wraps the existing complete route selector without granting it Account authority. */
  public static GetPublishedRealmAdmissionOwnerReadRequest toPublishedRealmRequest(
      CanonicalGameplayRosterOwnerReadEvidence.Request selector) {
    Objects.requireNonNull(selector, "selector");
    var request =
        GetPublishedRealmAdmissionOwnerReadRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addSelector(CanonicalGameplayRosterOwnerReadGrpcCodec.toRequest(selector))
            .build();
    requireWireSize(
        request.getSerializedSize(),
        MAX_PUBLISHED_REALM_REQUEST_WIRE_BYTES,
        "published realm read request");
    return request;
  }

  /** Parses one existing selector only after the Account-purpose caller guard has passed. */
  public static PublishedRealmRequest parsePublishedRealmRequest(
      GetPublishedRealmAdmissionOwnerReadRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireSize(
        request.getSerializedSize(),
        MAX_PUBLISHED_REALM_REQUEST_WIRE_BYTES,
        "published realm read request");
    if (!request.getUnknownFields().asMap().isEmpty()
        || one(request.getSchemaVersionList(), "published schemaVersion") != SCHEMA_VERSION) {
      throw invalid("Closed schema-1 published realm request required");
    }
    GetCanonicalGameplayRosterOwnerReadRequest selectorMessage =
        one(request.getSelectorList(), "published selector");
    CanonicalGameplayRosterOwnerReadEvidence.Request selector =
        CanonicalGameplayRosterOwnerReadGrpcCodec.fromRequest(selectorMessage);
    if (!CanonicalGameplayRosterOwnerReadGrpcCodec.toRequest(selector).equals(selectorMessage)) {
      throw invalid("Published realm selector is not its exact existing closed encoding");
    }
    return new PublishedRealmRequest(selector);
  }

  /** Produces the complete existing source carrier plus the same read's exact current route. */
  public static GetPublishedRealmAdmissionOwnerReadResponse toPublishedRealmResponse(
      PublishedRealmRequest request,
      CanonicalGameplayRosterOwnerReadEvidence sourceEvidence,
      Target currentTarget) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(sourceEvidence, "sourceEvidence");
    Objects.requireNonNull(currentTarget, "currentTarget");
    requirePublishedTargetMatchesSource(currentTarget, request.selector(), sourceEvidence);
    GetCanonicalGameplayRosterOwnerReadResponse sourceResponse =
        CanonicalGameplayRosterOwnerReadGrpcCodec.toResponse(request.selector(), sourceEvidence);
    GetPublishedRealmAdmissionOwnerReadResponse response =
        GetPublishedRealmAdmissionOwnerReadResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addPublishedSource(sourceResponse)
            .addCurrentTarget(toTargetEvidence(currentTarget))
            .build();
    requireWireSize(
        response.getSerializedSize(),
        MAX_PUBLISHED_REALM_RESPONSE_WIRE_BYTES,
        "published realm read response");
    fromPublishedRealmResponse(request, response);
    return response;
  }

  /** Reuses the existing full source validator and validates the complete route-to-source tuple. */
  public static PublishedRealmEvidence fromPublishedRealmResponse(
      PublishedRealmRequest request, GetPublishedRealmAdmissionOwnerReadResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireWireSize(
        response.getSerializedSize(),
        MAX_PUBLISHED_REALM_RESPONSE_WIRE_BYTES,
        "published realm read response");
    if (!response.getUnknownFields().asMap().isEmpty()
        || one(response.getSchemaVersionList(), "published schemaVersion") != SCHEMA_VERSION) {
      throw invalid("Closed schema-1 published realm response required");
    }
    CanonicalGameplayRosterOwnerReadEvidence sourceEvidence =
        CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(
            request.selector(), one(response.getPublishedSourceList(), "published source"));
    Target currentTarget =
        parseTargetEvidence(one(response.getCurrentTargetList(), "current target"));
    requirePublishedTargetMatchesSource(currentTarget, request.selector(), sourceEvidence);
    return new PublishedRealmEvidence(request.selector(), sourceEvidence, currentTarget);
  }

  /**
   * Serializes the actual repository readback and binds it to the exact unchanged request bytes.
   */
  public static GetCanonicalGameplayAdmissionDecisionResponse toResponse(
      RequestEvidence request,
      UUID bindingDecisionId,
      AccountGameplayAdmissionLeaseEvidence leaseEvidence,
      BigInteger bindingGeneration,
      BigInteger expectedOldBindingGeneration,
      UUID resumeEpisodeId,
      Candidate candidate,
      Target targetEvidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(bindingDecisionId, "bindingDecisionId");
    Objects.requireNonNull(leaseEvidence, "leaseEvidence");
    Objects.requireNonNull(bindingGeneration, "bindingGeneration");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(targetEvidence, "targetEvidence");
    if (!request.bindingDecisionId().equals(bindingDecisionId)
        || !request.expectedLeaseEvidence().hasSameIdentity(leaseEvidence)
        || !Arrays.equals(
            request.expectedLeaseEvidenceUtf8(), canonicalLeaseBytes(leaseEvidence))) {
      throw invalid("Stored decision differs from the original request");
    }

    GetCanonicalGameplayAdmissionDecisionResponse response =
        GetCanonicalGameplayAdmissionDecisionResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addBindingDecisionUuid(bindingDecisionId.toString())
            .addOriginalLeaseEvidenceUtf8(ByteString.copyFrom(request.expectedLeaseEvidenceUtf8()))
            .addStatus(
                CanonicalGameplayAdmissionDecisionStatus
                    .CANONICAL_GAMEPLAY_ADMISSION_DECISION_STATUS_PROVISIONAL)
            .addBindingGeneration(bindingGeneration.toString())
            .addCandidate(toCandidate(candidate))
            .addTargetEvidence(toTargetEvidence(targetEvidence))
            .build();
    var builder = response.toBuilder();
    if (expectedOldBindingGeneration != null) {
      builder.addExpectedOldBindingGeneration(expectedOldBindingGeneration.toString());
    }
    if (resumeEpisodeId != null) {
      builder.addResumeEpisodeUuid(resumeEpisodeId.toString());
    }
    response = builder.build();
    requireWireSize(response.getSerializedSize(), MAX_RESPONSE_WIRE_BYTES, "read response");
    // Apply the same closed validation to producer output, including owner-proof tuple checks.
    fromResponse(request, response);
    return response;
  }

  /** Validates every wire field and returns a complete typed readback, never a caller echo. */
  public static Evidence fromResponse(
      RequestEvidence request, GetCanonicalGameplayAdmissionDecisionResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireWireSize(response.getSerializedSize(), MAX_RESPONSE_WIRE_BYTES, "read response");
    if (!response.getUnknownFields().asMap().isEmpty()) throw invalid("Unknown response field");
    if (one(response.getSchemaVersionList(), "schemaVersion") != SCHEMA_VERSION) {
      throw invalid("Unsupported response schema");
    }
    UUID decisionId = uuid(one(response.getBindingDecisionUuidList(), "bindingDecisionId"));
    byte[] leaseBytes =
        one(response.getOriginalLeaseEvidenceUtf8List(), "leaseEvidence").toByteArray();
    if (!request.bindingDecisionId().equals(decisionId)
        || !Arrays.equals(request.expectedLeaseEvidenceUtf8(), leaseBytes)) {
      throw invalid("Response does not contain the exact original decision and lease");
    }
    AccountGameplayAdmissionLeaseEvidence lease = parseLease(leaseBytes);
    if (!request.expectedLeaseEvidence().hasSameIdentity(lease)) {
      throw invalid("Response lease differs from the original request");
    }
    if (one(response.getStatusValueList(), "status")
        != CanonicalGameplayAdmissionDecisionStatus
            .CANONICAL_GAMEPLAY_ADMISSION_DECISION_STATUS_PROVISIONAL_VALUE) {
      throw invalid("Only a durable PROVISIONAL decision can be read");
    }

    BigInteger bindingGeneration =
        positiveDecimal(
            one(response.getBindingGenerationList(), "bindingGeneration"), "bindingGeneration");
    BigInteger expectedOldBindingGeneration =
        optional(response.getExpectedOldBindingGenerationList(), "expectedOldBindingGeneration")
                == null
            ? null
            : positiveDecimal(
                response.getExpectedOldBindingGeneration(0), "expectedOldBindingGeneration");
    String resumeEpisodeText = optional(response.getResumeEpisodeUuidList(), "resumeEpisodeId");
    UUID resumeEpisodeId = resumeEpisodeText == null ? null : uuid(resumeEpisodeText);
    Candidate candidate = parseCandidate(one(response.getCandidateList(), "candidate"));
    Target target = parseTargetEvidence(one(response.getTargetEvidenceList(), "targetEvidence"));

    Map<?, ?> carrier = lease.carrier();
    Map<?, ?> scope = map(carrier.get("bindingScope"), "bindingScope");
    Object expectedOldLeaseValue = carrier.get("expectedOldBindingGeneration");
    BigInteger leaseExpectedOld =
        expectedOldLeaseValue == null
            ? null
            : positiveDecimal(
                text(expectedOldLeaseValue, "expectedOldBindingGeneration"),
                "expectedOldBindingGeneration");
    Object resumeLeaseValue = carrier.get("resumeEpisodeId");
    UUID leaseResume =
        resumeLeaseValue == null ? null : uuid(text(resumeLeaseValue, "resumeEpisodeId"));
    if (!bindingGeneration.equals(
            positiveDecimal(
                text(scope.get("bindingGeneration"), "bindingGeneration"),
                "lease bindingGeneration"))
        || !Objects.equals(expectedOldBindingGeneration, leaseExpectedOld)
        || !Objects.equals(resumeEpisodeId, leaseResume)
        || (lease.leaseKind() == AccountGameplayAdmissionLeaseEvidence.LeaseKind.RESUME
            && (expectedOldBindingGeneration == null || resumeEpisodeId == null))
        || (lease.leaseKind() != AccountGameplayAdmissionLeaseEvidence.LeaseKind.RESUME
            && resumeEpisodeId != null)) {
      throw invalid("Response generation or resume identity differs from the lease");
    }
    validateCandidateAndTargetAgainstLease(candidate, target, lease, scope);
    return new Evidence(
        decisionId,
        lease,
        bindingGeneration,
        expectedOldBindingGeneration,
        resumeEpisodeId,
        candidate,
        target);
  }

  public static Candidate parseCandidate(CanonicalGameplayAdmissionCandidate message) {
    if (!message.getUnknownFields().asMap().isEmpty()) throw invalid("Unknown candidate field");
    return new Candidate(
        uuid(one(message.getAccountUuidList(), "candidate.accountId")),
        uuid(one(message.getTenantUuidList(), "candidate.tenantId")),
        uuid(
            one(message.getPlayableStateNamespaceUuidList(), "candidate.playableStateNamespaceId")),
        uuid(one(message.getCharacterUuidList(), "candidate.characterId")),
        text(
            one(message.getPlayableStateScopeList(), "candidate.playableStateScope"),
            "candidate.playableStateScope"),
        uuid(one(message.getGameInstanceUuidList(), "candidate.gameInstanceId")),
        positiveLong(
            one(message.getRuntimeGameInstanceIdList(), "candidate.runtimeGameInstanceId"),
            "candidate.runtimeGameInstanceId"),
        uuid(one(message.getSessionUuidList(), "candidate.sessionId")),
        uuid(one(message.getRegionUuidList(), "candidate.regionId")),
        positiveDecimal(
            one(message.getRegionEpochList(), "candidate.regionEpoch"), "candidate.regionEpoch"),
        requireAccountIssuer(one(message.getIssuerIdList(), "candidate.issuerId")),
        positiveDecimal(
            one(message.getIssuerAuthGenerationList(), "candidate.issuerAuthGeneration"),
            "candidate.issuerAuthGeneration"),
        positiveDecimal(
            one(message.getIssuerIndexLayoutVersionList(), "candidate.issuerIndexLayoutVersion"),
            "candidate.issuerIndexLayoutVersion"),
        positiveDecimal(
            one(message.getIssuerIndexPartitionCountList(), "candidate.issuerIndexPartitionCount"),
            "candidate.issuerIndexPartitionCount"),
        positiveDecimal(
            one(
                message.getIssuerIndexPartitionCapacityList(),
                "candidate.issuerIndexPartitionCapacity"),
            "candidate.issuerIndexPartitionCapacity"));
  }

  private static Target parseTarget(CanonicalPlayableTargetEvidence message) {
    if (!message.getUnknownFields().asMap().isEmpty()) throw invalid("Unknown target field");
    String priorPointer =
        optional(
            message.getExpectedPriorPointerVersionList(), "target.expectedPriorPointerVersion");
    int originValue =
        one(message.getInitialAdmissionOriginKindValueList(), "target.initialAdmissionOriginKind");
    OriginKind origin =
        switch (originValue) {
          case 1 -> OriginKind.NO_PRIOR_POINTER;
          case 2 -> OriginKind.EXPECT_CLOSED;
          default -> throw invalid("Unsupported initial-admission origin");
        };
    int outcomeValue = one(message.getOwnerProofOutcomeValueList(), "target.ownerProofOutcome");
    OwnerProofOutcome outcome =
        switch (outcomeValue) {
          case 1 -> OwnerProofOutcome.PENDING;
          case 2 -> OwnerProofOutcome.COMMITTED;
          case 3 -> OwnerProofOutcome.ABORTED;
          default -> throw invalid("Unsupported initial-admission owner-proof outcome");
        };
    return new Target(
        text(
            one(message.getTargetNamespaceList(), "target.targetNamespace"),
            "target.targetNamespace"),
        text(one(message.getTenantSlugList(), "target.tenantSlug"), "target.tenantSlug"),
        uuid(one(message.getCanonicalTenantUuidList(), "target.canonicalTenantId")),
        text(one(message.getWorldSlugList(), "target.worldSlug"), "target.worldSlug"),
        text(
            one(message.getWorldDisplayNameList(), "target.worldDisplayName"),
            "target.worldDisplayName"),
        uuid(one(message.getRealmUuidList(), "target.realmId")),
        text(one(message.getRealmSlugList(), "target.realmSlug"), "target.realmSlug"),
        text(
            one(message.getRealmDisplayNameList(), "target.realmDisplayName"),
            "target.realmDisplayName"),
        positiveLong(
            one(message.getGameSessionTenantIdList(), "target.gameSessionTenantId"),
            "target.gameSessionTenantId"),
        positiveLong(
            one(message.getGameInstanceIdList(), "target.gameInstanceId"), "target.gameInstanceId"),
        uuid(one(message.getPlayableStateNamespaceUuidList(), "target.playableStateNamespaceId")),
        text(
            one(message.getPlayableStateScopeList(), "target.playableStateScope"),
            "target.playableStateScope"),
        uuid(one(message.getCanonicalGameInstanceUuidList(), "target.canonicalGameInstanceId")),
        uuid(one(message.getCanonicalVersionUuidList(), "target.canonicalVersionId")),
        positiveLong(
            one(message.getRuntimeVersionIdList(), "target.runtimeVersionId"),
            "target.runtimeVersionId"),
        positiveLongDecimal(
            one(message.getCatalogRevisionList(), "target.catalogRevision"),
            "target.catalogRevision"),
        positiveLongDecimal(
            one(message.getPointerVersionList(), "target.pointerVersion"), "target.pointerVersion"),
        text(
            one(
                message.getAdmissionPointerSnapshotDigestList(),
                "target.admissionPointerSnapshotDigest"),
            "target.admissionPointerSnapshotDigest"),
        positiveLongDecimal(
            one(message.getActiveWorldEpochList(), "target.activeWorldEpoch"),
            "target.activeWorldEpoch"),
        text(
            one(message.getInitialAdmissionRequestIdList(), "target.initialAdmissionRequestId"),
            "target.initialAdmissionRequestId"),
        text(
            one(
                message.getInitialAdmissionRequestDigestList(),
                "target.initialAdmissionRequestDigest"),
            "target.initialAdmissionRequestDigest"),
        origin,
        priorPointer == null
            ? null
            : positiveLongDecimal(priorPointer, "target.expectedPriorPointerVersion"),
        uuid(one(message.getHoldUuidList(), "target.holdId")),
        uuid(one(message.getHoldFenceUuidList(), "target.holdFence")),
        text(
            one(message.getHoldBindingDigestList(), "target.holdBindingDigest"),
            "target.holdBindingDigest"),
        positiveLong(
            one(message.getAuditEventIdList(), "target.auditEventId"), "target.auditEventId"),
        text(
            one(message.getOwnerProofDigestList(), "target.ownerProofDigest"),
            "target.ownerProofDigest"),
        outcome,
        one(message.getPositiveDurableAbortList(), "target.positiveDurableAbort"),
        instant(one(message.getOwnerProofTerminalAtList(), "target.ownerProofTerminalAt")),
        text(
            one(message.getCharacterCreationPolicyList(), "target.characterCreationPolicy"),
            "target.characterCreationPolicy"));
  }

  public static Target parseTargetEvidence(CanonicalPlayableTargetEvidence message) {
    Target target = parseTarget(Objects.requireNonNull(message, "message"));
    validateOwnerProofTuple(target);
    return target;
  }

  public static CanonicalPlayableTargetEvidence toTargetEvidence(Target target) {
    return toTarget(Objects.requireNonNull(target, "target"));
  }

  private static void validateCandidateAndTargetAgainstLease(
      Candidate candidate,
      Target target,
      AccountGameplayAdmissionLeaseEvidence lease,
      Map<?, ?> scope) {
    Map<?, ?> carrier = lease.carrier();
    Map<?, ?> authorityTuple = map(carrier.get("authorityTuple"), "authorityTuple");
    requireEqual(
        text(carrier.get("targetNamespace"), "targetNamespace"),
        target.targetNamespace(),
        "targetNamespace");
    requireEqual(
        text(scope.get("accountId"), "bindingScope.accountId"),
        candidate.accountId().toString(),
        "accountId");
    requireEqual(
        text(scope.get("tenantId"), "bindingScope.tenantId"),
        candidate.tenantId().toString(),
        "tenantId");
    requireEqual(candidate.tenantId(), target.canonicalTenantId(), "canonicalTenantId");
    requireEqual(
        text(scope.get("realmId"), "bindingScope.realmId"), target.realmId().toString(), "realmId");
    requireEqual(
        text(scope.get("worldSlug"), "bindingScope.worldSlug"), target.worldSlug(), "worldSlug");
    requireEqual(
        text(scope.get("realmSlug"), "bindingScope.realmSlug"), target.realmSlug(), "realmSlug");
    requireEqual(
        text(scope.get("playableStateNamespaceId"), "bindingScope.playableStateNamespaceId"),
        candidate.playableStateNamespaceId().toString(),
        "playableStateNamespaceId");
    requireEqual(
        candidate.playableStateNamespaceId(),
        target.playableStateNamespaceId(),
        "target playableStateNamespaceId");
    requireEqual(
        text(scope.get("playableStateScope"), "bindingScope.playableStateScope"),
        candidate.playableStateScope(),
        "playableStateScope");
    requireEqual(
        candidate.playableStateScope(), target.playableStateScope(), "target playableStateScope");
    requireEqual(
        text(scope.get("gameInstanceId"), "bindingScope.gameInstanceId"),
        candidate.gameInstanceId().toString(),
        "gameInstanceId");
    requireEqual(
        candidate.gameInstanceId(), target.canonicalGameInstanceId(), "canonicalGameInstanceId");
    requireEqual(
        text(scope.get("characterId"), "bindingScope.characterId"),
        candidate.characterId().toString(),
        "characterId");
    requireEqual(
        text(scope.get("sessionId"), "bindingScope.sessionId"),
        candidate.sessionId().toString(),
        "sessionId");
    requireEqual(
        text(scope.get("regionId"), "bindingScope.regionId"),
        candidate.regionId().toString(),
        "regionId");
    requireEqual(
        positiveDecimal(text(scope.get("regionEpoch"), "bindingScope.regionEpoch"), "regionEpoch"),
        candidate.regionEpoch(),
        "regionEpoch");
    requireEqual(
        positiveLong(target.gameInstanceId(), "target.gameInstanceId"),
        candidate.runtimeGameInstanceId(),
        "runtimeGameInstanceId");
    requireEqual(
        positiveDecimal(
            text(authorityTuple.get("issuerAuthGeneration"), "authorityTuple.issuerAuthGeneration"),
            "issuerAuthGeneration"),
        candidate.issuerAuthGeneration(),
        "issuerAuthGeneration");
    requireEqual(
        positiveDecimal(
            text(scope.get("catalogRevision"), "bindingScope.catalogRevision"), "catalogRevision"),
        BigInteger.valueOf(target.catalogRevision()),
        "catalogRevision");
    requireEqual(
        positiveDecimal(
            text(scope.get("pointerVersion"), "bindingScope.pointerVersion"), "pointerVersion"),
        BigInteger.valueOf(target.pointerVersion()),
        "pointerVersion");
  }

  private static void validateOwnerProofTuple(Target target) {
    ownerProofFromTarget(target);
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof ownerProofFromTarget(
      Target target) {
    if (!target.admissionPointerSnapshotDigest().matches("[0-9a-f]{64}")
        || target.ownerProofOutcome() != OwnerProofOutcome.COMMITTED
        || target.positiveDurableAbort()) {
      throw invalid("Stored playable target is not an exact committed pointer target");
    }
    WorldCanonicalInitialAdmissionHold.Request request =
        new WorldCanonicalInitialAdmissionHold.Request(
            target.targetNamespace(),
            target.canonicalTenantId(),
            target.worldSlug(),
            target.realmId(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            target.canonicalGameInstanceId(),
            target.canonicalVersionId(),
            target.activeWorldEpoch(),
            target.initialAdmissionRequestId(),
            target.initialAdmissionRequestDigest(),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.valueOf(
                target.initialAdmissionOriginKind().name()),
            target.catalogRevision(),
            target.expectedPriorPointerVersion());
    WorldCanonicalInitialAdmissionHold.HoldIdentity hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            request, target.holdId(), target.holdFence());
    requireEqual(hold.holdBindingDigest(), target.holdBindingDigest(), "holdBindingDigest");
    GameSessionCanonicalInitialAdmissionOwnerProof proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.valueOf(
                target.ownerProofOutcome().name()),
            target.ownerProofOutcome() == OwnerProofOutcome.COMMITTED
                ? target.pointerVersion()
                : null,
            target.ownerProofOutcome() == OwnerProofOutcome.COMMITTED
                ? target.auditEventId()
                : null,
            target.ownerProofDigest(),
            target.positiveDurableAbort(),
            target.ownerProofTerminalAt());
    return GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof));
  }

  private static void requirePublishedTargetMatchesSource(
      Target target,
      CanonicalGameplayRosterOwnerReadEvidence.Request selector,
      CanonicalGameplayRosterOwnerReadEvidence sourceEvidence) {
    if (!target.targetNamespace().equals(selector.targetNamespace())
        || !target.canonicalTenantId().equals(selector.canonicalTenantUuid())
        || !target.worldSlug().equals(selector.worldSlug())
        || !target.realmId().equals(selector.realmUuid())
        || !target.realmSlug().equals(selector.realmSlug())
        || !target.playableStateNamespaceId().equals(selector.playableStateNamespaceUuid())
        || !target.playableStateScope().equals(selector.playableStateScope())
        || !target.canonicalGameInstanceId().equals(selector.canonicalGameInstanceUuid())
        || !target.canonicalVersionId().equals(selector.canonicalVersionUuid())
        || target.catalogRevision() != selector.expectedCatalogRevision()
        || target.pointerVersion() != selector.expectedPointerVersion()
        || target.activeWorldEpoch() != selector.expectedActiveWorldEpoch()
        || !target
            .admissionPointerSnapshotDigest()
            .equals(sourceEvidence.admissionPointerSnapshotDigest())) {
      throw invalid("Published realm target differs from its exact source selector or pointer");
    }
    byte[] targetProof =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
            ownerProofFromTarget(target));
    byte[] sourceProof =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
            sourceEvidence.gameSessionOwnerProof());
    if (!Arrays.equals(targetProof, sourceProof)) {
      throw invalid("Published realm target changed the exact Game Session owner proof");
    }
    var selectedPolicy = sourceEvidence.selectedPolicyEvidence().policy();
    if (!target.characterCreationPolicy().equals(selectedPolicy.entryPolicy().name())
        || !target.worldSlug().equals(selectedPolicy.worldSlug())
        || !target.realmSlug().equals(selectedPolicy.realmSlug())) {
      throw invalid("Published realm target differs from the exact sealed policy selector");
    }
  }

  public static CanonicalGameplayAdmissionCandidate toCandidate(Candidate identity) {
    return CanonicalGameplayAdmissionCandidate.newBuilder()
        .addAccountUuid(identity.accountId().toString())
        .addTenantUuid(identity.tenantId().toString())
        .addPlayableStateNamespaceUuid(identity.playableStateNamespaceId().toString())
        .addCharacterUuid(identity.characterId().toString())
        .addPlayableStateScope(identity.playableStateScope())
        .addGameInstanceUuid(identity.gameInstanceId().toString())
        .addRuntimeGameInstanceId(identity.runtimeGameInstanceId())
        .addSessionUuid(identity.sessionId().toString())
        .addRegionUuid(identity.regionId().toString())
        .addRegionEpoch(identity.regionEpoch().toString())
        .addIssuerId(requireAccountIssuer(identity.issuerId()))
        .addIssuerAuthGeneration(identity.issuerAuthGeneration().toString())
        .addIssuerIndexLayoutVersion(identity.issuerIndexLayoutVersion().toString())
        .addIssuerIndexPartitionCount(identity.issuerIndexPartitionCount().toString())
        .addIssuerIndexPartitionCapacity(identity.issuerIndexPartitionCapacity().toString())
        .build();
  }

  private static CanonicalPlayableTargetEvidence toTarget(Target target) {
    var builder =
        CanonicalPlayableTargetEvidence.newBuilder()
            .addTargetNamespace(target.targetNamespace())
            .addTenantSlug(target.tenantSlug())
            .addCanonicalTenantUuid(target.canonicalTenantId().toString())
            .addWorldSlug(target.worldSlug())
            .addWorldDisplayName(target.worldDisplayName())
            .addRealmUuid(target.realmId().toString())
            .addRealmSlug(target.realmSlug())
            .addRealmDisplayName(target.realmDisplayName())
            .addGameSessionTenantId(target.gameSessionTenantId())
            .addGameInstanceId(target.gameInstanceId())
            .addPlayableStateNamespaceUuid(target.playableStateNamespaceId().toString())
            .addPlayableStateScope(target.playableStateScope())
            .addCanonicalGameInstanceUuid(target.canonicalGameInstanceId().toString())
            .addCanonicalVersionUuid(target.canonicalVersionId().toString())
            .addRuntimeVersionId(target.runtimeVersionId())
            .addCatalogRevision(Long.toString(target.catalogRevision()))
            .addPointerVersion(Long.toString(target.pointerVersion()))
            .addAdmissionPointerSnapshotDigest(target.admissionPointerSnapshotDigest())
            .addActiveWorldEpoch(Long.toString(target.activeWorldEpoch()))
            .addInitialAdmissionRequestId(target.initialAdmissionRequestId())
            .addInitialAdmissionRequestDigest(target.initialAdmissionRequestDigest())
            .addInitialAdmissionOriginKind(
                target.initialAdmissionOriginKind() == OriginKind.NO_PRIOR_POINTER
                    ? CanonicalGameplayAdmissionOriginKind
                        .CANONICAL_GAMEPLAY_ADMISSION_ORIGIN_KIND_NO_PRIOR_POINTER
                    : CanonicalGameplayAdmissionOriginKind
                        .CANONICAL_GAMEPLAY_ADMISSION_ORIGIN_KIND_EXPECT_CLOSED)
            .addHoldUuid(target.holdId().toString())
            .addHoldFenceUuid(target.holdFence().toString())
            .addHoldBindingDigest(target.holdBindingDigest())
            .addAuditEventId(target.auditEventId())
            .addOwnerProofDigest(target.ownerProofDigest())
            .addOwnerProofOutcome(
                switch (target.ownerProofOutcome()) {
                  case PENDING ->
                      CanonicalGameplayAdmissionOwnerProofOutcome
                          .CANONICAL_GAMEPLAY_ADMISSION_OWNER_PROOF_OUTCOME_PENDING;
                  case COMMITTED ->
                      CanonicalGameplayAdmissionOwnerProofOutcome
                          .CANONICAL_GAMEPLAY_ADMISSION_OWNER_PROOF_OUTCOME_COMMITTED;
                  case ABORTED ->
                      CanonicalGameplayAdmissionOwnerProofOutcome
                          .CANONICAL_GAMEPLAY_ADMISSION_OWNER_PROOF_OUTCOME_ABORTED;
                })
            .addPositiveDurableAbort(target.positiveDurableAbort())
            .addOwnerProofTerminalAt(target.ownerProofTerminalAt().toString())
            .addCharacterCreationPolicy(target.characterCreationPolicy());
    if (target.expectedPriorPointerVersion() != null) {
      builder.addExpectedPriorPointerVersion(target.expectedPriorPointerVersion().toString());
    }
    return builder.build();
  }

  private static AccountGameplayAdmissionLeaseEvidence parseLease(byte[] bytes) {
    if (bytes.length > AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES) {
      throw invalid("Lease evidence exceeds its existing byte limit");
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid("Lease evidence is not UTF-8");
    }
    AccountGameplayAdmissionLeaseEvidence evidence =
        AccountGameplayAdmissionLeaseEvidence.parseCanonical(text);
    if (!Arrays.equals(bytes, canonicalLeaseBytes(evidence))) {
      throw invalid("Lease evidence bytes are not the exact canonical UTF-8");
    }
    return evidence;
  }

  private static byte[] canonicalLeaseBytes(AccountGameplayAdmissionLeaseEvidence evidence) {
    return evidence.canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  private static UUID uuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw invalid("UUID is not canonical and non-nil");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid("UUID is malformed");
    }
  }

  private static BigInteger positiveDecimal(String value, String field) {
    try {
      return AccountJwtExactValues.positiveDecimalCounter(value);
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " is not canonical positive decimal text");
    }
  }

  private static long positiveLongDecimal(String value, String field) {
    try {
      return positiveDecimal(value, field).longValueExact();
    } catch (ArithmeticException outsideRange) {
      throw invalid(field + " exceeds its supported signed-long range");
    }
  }

  private static long positiveLong(long value, String field) {
    if (value <= 0) throw invalid(field + " must be positive");
    return value;
  }

  private static Instant instant(String value) {
    try {
      Instant parsed = Instant.parse(text(value, "ownerProofTerminalAt"));
      if (!parsed.toString().equals(value)) throw invalid("Terminal timestamp is not canonical");
      return parsed;
    } catch (RuntimeException malformed) {
      throw invalid("Terminal timestamp is not canonical");
    }
  }

  private static String text(String value, String field) {
    if (value == null || value.isBlank() || !value.equals(value.trim())) {
      throw invalid(field + " must be nonblank trimmed text");
    }
    return value;
  }

  private static String text(Object value, String field) {
    if (!(value instanceof String string)) throw invalid(field + " must be text");
    return text(string, field);
  }

  private static Map<?, ?> map(Object value, String field) {
    if (!(value instanceof Map<?, ?> result)) throw invalid(field + " must be an object");
    return result;
  }

  private static void requireEqual(Object expected, Object actual, String field) {
    if (!Objects.equals(expected, actual))
      throw invalid(field + " differs from the lease-bound tuple");
  }

  private static void requireNonNil(UUID value, String field) {
    if (value == null || NIL_UUID.equals(value))
      throw invalid(field + " must be a canonical non-nil UUID");
  }

  private static void requireWireSize(int size, int maximum, String field) {
    if (size <= 0 || size > maximum) throw invalid(field + " exceeds its finite wire bound");
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static String requireAccountIssuer(String issuerId) {
    if (!GameSessionAccountDelegationProfile.ISSUER.equals(issuerId)) {
      throw invalid(
          "Unsupported issuer identity; expected exact Account issuer "
              + GameSessionAccountDelegationProfile.ISSUER
              + "; legacy UUID issuer identities require explicit recovery");
    }
    return issuerId;
  }

  private static <T> T one(List<T> values, String field) {
    if (values == null || values.size() != 1 || values.getFirst() == null) {
      throw invalid(field + " must occur exactly once");
    }
    return values.getFirst();
  }

  private static <T> T optional(List<T> values, String field) {
    if (values == null || values.size() > 1) throw invalid(field + " must occur at most once");
    return values.isEmpty() ? null : values.getFirst();
  }
}
