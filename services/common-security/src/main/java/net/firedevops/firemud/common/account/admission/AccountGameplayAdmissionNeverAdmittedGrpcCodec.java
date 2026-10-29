package net.firedevops.firemud.common.account.admission;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AbortNeverAdmittedGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AbortNeverAdmittedGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseState;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.gamesession.v1.GetOriginalNewNeverAdmittedDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetOriginalNewNeverAdmittedDecisionResponse;

/**
 * Closed shape-only negative-purpose transport. No value here grants admission or authenticates a
 * peer.
 */
public final class AccountGameplayAdmissionNeverAdmittedGrpcCodec {
  public static final int MAX_WIRE_BYTES = 256 * 1024;
  public static final String PURPOSE = "original-new-never-admitted-closure/v1";

  private AccountGameplayAdmissionNeverAdmittedGrpcCodec() {}

  public record Request(
      String presentedInitialGsACompactJwt,
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      UUID transitionId,
      UUID abandonmentId) {
    @Override
    public String toString() {
      return "NeverAdmittedRequest[redacted]";
    }
  }

  public record DecisionRequest(
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      UUID transitionId,
      UUID abandonmentId) {
    @Override
    public String toString() {
      return "NeverAdmittedDecisionRequest[redacted]";
    }
  }

  public record NegativeDecisionEvidence(
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      UUID transitionId,
      UUID abandonmentId,
      String candidateDigest,
      String closureDigest,
      Candidate candidate,
      Target targetEvidence,
      BigInteger bindingGeneration) {
    @Override
    public String toString() {
      return "NeverAdmittedDecisionEvidence[redacted]";
    }
  }

  public record AbortEvidence(
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      UUID transitionId,
      UUID abandonmentId,
      String candidateDigest,
      String closureDigest,
      UUID orphanCleanupId,
      UUID negativeResolutionId) {
    @Override
    public String toString() {
      return "NeverAdmittedAbortEvidence[redacted]";
    }
  }

  public static AbortNeverAdmittedGameplayAdmissionLeaseRequest toRequest(
      String jwt,
      AccountGameplayAdmissionLeaseEvidence lease,
      UUID transitionId,
      UUID abandonmentId) {
    var wire =
        AbortNeverAdmittedGameplayAdmissionLeaseRequest.newBuilder()
            .addSchemaVersion(1)
            .addPresentedInitialGsaCompactJwt(jwt)
            .addOriginalLeaseEvidenceUtf8(bytes(lease))
            .addTransitionUuid(canonical(transitionId))
            .addAbandonmentUuid(canonical(abandonmentId))
            .build();
    parseRequest(wire);
    return wire;
  }

  public static Request parseRequest(AbortNeverAdmittedGameplayAdmissionLeaseRequest wire) {
    closed(wire);
    schema(wire.getSchemaVersionList());
    // Reuse the existing strict transient JWT and fresh NEW lease parser, never positive evidence.
    var original =
        AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(
            ReadGameplayAdmissionLeaseRequest.newBuilder()
                .addSchemaVersion(1)
                .addPresentedInitialGsaCompactJwt(one(wire.getPresentedInitialGsaCompactJwtList()))
                .addOriginalLeaseEvidenceUtf8(one(wire.getOriginalLeaseEvidenceUtf8List()))
                .build());
    return new Request(
        original.presentedInitialGsACompactJwt(),
        original.originalLeaseEvidence(),
        uuid(one(wire.getTransitionUuidList())),
        uuid(one(wire.getAbandonmentUuidList())));
  }

  public static GetOriginalNewNeverAdmittedDecisionRequest toDecisionRequest(
      UUID transitionId, UUID abandonmentId, AccountGameplayAdmissionLeaseEvidence lease) {
    var wire =
        GetOriginalNewNeverAdmittedDecisionRequest.newBuilder()
            .addSchemaVersion(1)
            .addTransitionUuid(canonical(transitionId))
            .addAbandonmentUuid(canonical(abandonmentId))
            .addExpectedLeaseEvidenceUtf8(bytes(lease))
            .build();
    parseDecisionRequest(wire);
    return wire;
  }

  public static DecisionRequest parseDecisionRequest(
      GetOriginalNewNeverAdmittedDecisionRequest wire) {
    closed(wire);
    schema(wire.getSchemaVersionList());
    return new DecisionRequest(
        lease(one(wire.getExpectedLeaseEvidenceUtf8List())),
        uuid(one(wire.getTransitionUuidList())),
        uuid(one(wire.getAbandonmentUuidList())));
  }

  public static GetOriginalNewNeverAdmittedDecisionResponse toDecisionResponse(
      DecisionRequest request, NegativeDecisionEvidence evidence) {
    var wire =
        GetOriginalNewNeverAdmittedDecisionResponse.newBuilder()
            .addSchemaVersion(1)
            .addPurpose(PURPOSE)
            .addTransitionUuid(canonical(evidence.transitionId()))
            .addAbandonmentUuid(canonical(evidence.abandonmentId()))
            .addOriginalLeaseEvidenceUtf8(bytes(evidence.originalLeaseEvidence()))
            .addCandidateSha256(evidence.candidateDigest())
            .addClosureReadbackSha256(evidence.closureDigest())
            .addCandidate(
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.toCandidate(evidence.candidate()))
            .addTargetEvidence(
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetEvidence(
                    evidence.targetEvidence()))
            .addBindingGeneration(evidence.bindingGeneration().toString())
            .build();
    fromDecisionResponse(request, wire);
    return wire;
  }

  public static NegativeDecisionEvidence fromDecisionResponse(
      DecisionRequest request, GetOriginalNewNeverAdmittedDecisionResponse wire) {
    Objects.requireNonNull(request);
    closed(wire);
    schema(wire.getSchemaVersionList());
    if (!PURPOSE.equals(one(wire.getPurposeList()))) throw invalid();
    var lease = lease(one(wire.getOriginalLeaseEvidenceUtf8List()));
    UUID transition = uuid(one(wire.getTransitionUuidList()));
    UUID abandonment = uuid(one(wire.getAbandonmentUuidList()));
    correlate(
        request.originalLeaseEvidence(),
        request.transitionId(),
        request.abandonmentId(),
        lease,
        transition,
        abandonment);
    var candidate =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseCandidate(
            one(wire.getCandidateList()));
    var target =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetEvidence(
            one(wire.getTargetEvidenceList()));
    String generation = one(wire.getBindingGenerationList());
    if (!generation.matches("[1-9][0-9]*") || generation.length() > 128) throw invalid();
    matchLease(lease, candidate, target, generation);
    return new NegativeDecisionEvidence(
        lease,
        transition,
        abandonment,
        digest(one(wire.getCandidateSha256List())),
        digest(one(wire.getClosureReadbackSha256List())),
        candidate,
        target,
        new BigInteger(generation));
  }

  public static AbortNeverAdmittedGameplayAdmissionLeaseResponse toResponse(
      Request request, AbortEvidence evidence) {
    var wire =
        AbortNeverAdmittedGameplayAdmissionLeaseResponse.newBuilder()
            .addSchemaVersion(1)
            .addState(
                AccountGameplayAdmissionLeaseState.ACCOUNT_GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED)
            .addOriginalLeaseEvidenceUtf8(bytes(evidence.originalLeaseEvidence()))
            .addTransitionUuid(canonical(evidence.transitionId()))
            .addAbandonmentUuid(canonical(evidence.abandonmentId()))
            .addCandidateSha256(evidence.candidateDigest())
            .addClosureReadbackSha256(evidence.closureDigest())
            .addOrphanCleanupUuid(canonical(evidence.orphanCleanupId()))
            .addNegativeResolutionUuid(canonical(evidence.negativeResolutionId()))
            .build();
    parseResponse(request, wire);
    return wire;
  }

  public static AbortEvidence parseResponse(
      Request request, AbortNeverAdmittedGameplayAdmissionLeaseResponse wire) {
    Objects.requireNonNull(request);
    closed(wire);
    schema(wire.getSchemaVersionList());
    if (one(wire.getStateValueList())
        != AccountGameplayAdmissionLeaseState.ACCOUNT_GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED_VALUE)
      throw invalid();
    var lease = lease(one(wire.getOriginalLeaseEvidenceUtf8List()));
    UUID transition = uuid(one(wire.getTransitionUuidList()));
    UUID abandonment = uuid(one(wire.getAbandonmentUuidList()));
    correlate(
        request.originalLeaseEvidence(),
        request.transitionId(),
        request.abandonmentId(),
        lease,
        transition,
        abandonment);
    return new AbortEvidence(
        lease,
        transition,
        abandonment,
        digest(one(wire.getCandidateSha256List())),
        digest(one(wire.getClosureReadbackSha256List())),
        uuid(one(wire.getOrphanCleanupUuidList())),
        uuid(one(wire.getNegativeResolutionUuidList())));
  }

  private static void matchLease(
      AccountGameplayAdmissionLeaseEvidence lease, Candidate c, Target t, String generation) {
    Map<?, ?> scope = (Map<?, ?>) lease.carrier().get("bindingScope");
    Map<?, ?> authority = (Map<?, ?>) lease.carrier().get("authorityTuple");
    var actual =
        Map.ofEntries(
            Map.entry("accountId", c.accountId().toString()),
            Map.entry("tenantId", c.tenantId().toString()),
            Map.entry("characterId", c.characterId().toString()),
            Map.entry("sessionId", c.sessionId().toString()),
            Map.entry("regionId", c.regionId().toString()),
            Map.entry("regionEpoch", c.regionEpoch().toString()),
            Map.entry("playableStateNamespaceId", c.playableStateNamespaceId().toString()),
            Map.entry("playableStateScope", c.playableStateScope()),
            Map.entry("gameInstanceId", c.gameInstanceId().toString()),
            Map.entry("bindingGeneration", generation),
            Map.entry("realmId", t.realmId().toString()),
            Map.entry("worldSlug", t.worldSlug()),
            Map.entry("realmSlug", t.realmSlug()),
            Map.entry("catalogRevision", Long.toString(t.catalogRevision())),
            Map.entry("pointerVersion", Long.toString(t.pointerVersion())));
    for (var entry : actual.entrySet())
      if (!entry.getValue().equals(scope.get(entry.getKey()))) throw invalid();
    if (!t.targetNamespace().equals(lease.carrier().get("targetNamespace"))
        || !c.tenantId().equals(t.canonicalTenantId())
        || !c.playableStateNamespaceId().equals(t.playableStateNamespaceId())
        || !c.playableStateScope().equals(t.playableStateScope())
        || !c.gameInstanceId().equals(t.canonicalGameInstanceId())
        || c.runtimeGameInstanceId() != t.gameInstanceId()
        || !c.issuerAuthGeneration().toString().equals(authority.get("issuerAuthGeneration")))
      throw invalid();
  }

  private static void correlate(
      AccountGameplayAdmissionLeaseEvidence expected,
      UUID transition,
      UUID abandonment,
      AccountGameplayAdmissionLeaseEvidence actual,
      UUID actualTransition,
      UUID actualAbandonment) {
    if (!Arrays.equals(bytes(expected).toByteArray(), bytes(actual).toByteArray())
        || !transition.equals(actualTransition)
        || !abandonment.equals(actualAbandonment)) throw invalid();
  }

  private static ByteString bytes(AccountGameplayAdmissionLeaseEvidence lease) {
    return ByteString.copyFrom(
        Objects.requireNonNull(lease).canonicalJson(), StandardCharsets.UTF_8);
  }

  private static AccountGameplayAdmissionLeaseEvidence lease(ByteString bytes) {
    if (bytes.size() > AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES
        || !bytes.isValidUtf8()) throw invalid();
    var lease = AccountGameplayAdmissionLeaseEvidence.parseCanonical(bytes.toStringUtf8());
    if (!bytes.equals(bytes(lease))
        || lease.leaseKind() != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
        || lease.carrier().containsKey("expectedOldBindingGeneration")
        || lease.carrier().containsKey("resumeEpisodeId")) throw invalid();
    return lease;
  }

  private static void closed(Message wire) {
    if (wire == null
        || wire.getSerializedSize() > MAX_WIRE_BYTES
        || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
  }

  private static void schema(List<Integer> values) {
    if (one(values) != 1) throw invalid();
  }

  private static <T> T one(List<T> values) {
    if (values.size() != 1) throw invalid();
    return values.getFirst();
  }

  private static String digest(String value) {
    if (!value.matches("[0-9a-f]{64}")) throw invalid();
    return value;
  }

  private static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value) || id.equals(new UUID(0, 0))) throw invalid();
      return id;
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private static String canonical(UUID id) {
    return uuid(Objects.requireNonNull(id).toString()).toString();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Malformed never-admitted evidence");
  }
}
