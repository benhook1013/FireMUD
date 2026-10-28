package net.firedevops.firemud.common.account.admission;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseState;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionOriginalAckReceiptEvidence;
import net.firedevops.firemud.account.v1.AcquireGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.security.AccountJwtExactValues;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionCandidate;
import net.firedevops.firemud.gamesession.v1.CanonicalPlayableTargetEvidence;

/**
 * Closed schema-1 transport mapping for Account's exact original public-production NEW lease.
 *
 * <p>Parsed values establish shape, unchanged bytes, and request/result consistency only. They do
 * not authenticate the presented JWT, establish current Account authority, prove physical COMMIT or
 * receipt durability, or authorize Game Session admission.
 */
public final class AccountGameplayAdmissionLeaseGrpcCodec {
  public static final int MAX_ACQUIRE_REQUEST_WIRE_BYTES =
      CanonicalGameplayAdmissionDecisionReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES
          + GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES
          + 1024;
  public static final int MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES =
      AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES
          + GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES
          + 1024;
  public static final int MAX_RESPONSE_WIRE_BYTES =
      AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES + 8 * 1024;

  private static final int SCHEMA_VERSION = 1;
  private static final BigInteger UINT64_MAX =
      BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AccountGameplayAdmissionLeaseGrpcCodec() {}

  /** Parsed acquisition request. Its GSA credential is transient and redacted from diagnostics. */
  public static final class AcquireRequest {
    private final UUID requestId;
    private final String presentedInitialGsACompactJwt;
    private final Candidate expectedCandidate;
    private final Target expectedTarget;
    private final BigInteger bindingGeneration;

    private AcquireRequest(
        UUID requestId,
        String presentedInitialGsACompactJwt,
        Candidate expectedCandidate,
        Target expectedTarget,
        BigInteger bindingGeneration) {
      this.requestId = requestId;
      this.presentedInitialGsACompactJwt = presentedInitialGsACompactJwt;
      this.expectedCandidate = expectedCandidate;
      this.expectedTarget = expectedTarget;
      this.bindingGeneration = bindingGeneration;
    }

    public UUID requestId() {
      return requestId;
    }

    public String presentedInitialGsACompactJwt() {
      return presentedInitialGsACompactJwt;
    }

    public Candidate expectedCandidate() {
      return expectedCandidate;
    }

    public Target expectedTarget() {
      return expectedTarget;
    }

    public BigInteger bindingGeneration() {
      return bindingGeneration;
    }

    @Override
    public String toString() {
      return "AccountGameplayAdmissionLeaseGrpcCodec.AcquireRequest[redacted]";
    }
  }

  /** One exact original-operation request; construction is restricted to this codec. */
  public static final class OriginalLeaseRequest {
    private final OriginalLeasePurpose purpose;
    private final String presentedInitialGsACompactJwt;
    private final AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence;
    private final byte[] originalLeaseEvidenceUtf8;
    private final UUID bindingDecisionId;

    private OriginalLeaseRequest(
        OriginalLeasePurpose purpose,
        String presentedInitialGsACompactJwt,
        AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
        byte[] originalLeaseEvidenceUtf8,
        UUID bindingDecisionId) {
      this.purpose = purpose;
      this.presentedInitialGsACompactJwt = presentedInitialGsACompactJwt;
      this.originalLeaseEvidence = originalLeaseEvidence;
      this.originalLeaseEvidenceUtf8 = originalLeaseEvidenceUtf8.clone();
      this.bindingDecisionId = bindingDecisionId;
    }

    public String presentedInitialGsACompactJwt() {
      return presentedInitialGsACompactJwt;
    }

    public AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence() {
      return originalLeaseEvidence;
    }

    public byte[] originalLeaseEvidenceUtf8() {
      return originalLeaseEvidenceUtf8.clone();
    }

    /** Null means that the decision identity is unknown, never that no decision exists. */
    public UUID bindingDecisionId() {
      return bindingDecisionId;
    }

    @Override
    public String toString() {
      return "AccountGameplayAdmissionLeaseGrpcCodec.OriginalLeaseRequest[redacted]";
    }
  }

  /** Complete original-operation response shape with a redacted, typed receipt carrier. */
  public static final class OperationEvidence {
    private final AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence;
    private final State state;
    private final UUID bindingDecisionId;
    private final UUID orphanCleanupId;
    private final OriginalAckReceiptEvidence originalAckReceipt;

    private OperationEvidence(
        AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
        State state,
        UUID bindingDecisionId,
        UUID orphanCleanupId,
        OriginalAckReceiptEvidence originalAckReceipt) {
      this.originalLeaseEvidence = originalLeaseEvidence;
      this.state = state;
      this.bindingDecisionId = bindingDecisionId;
      this.orphanCleanupId = orphanCleanupId;
      this.originalAckReceipt = originalAckReceipt;
    }

    public AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence() {
      return originalLeaseEvidence;
    }

    public State state() {
      return state;
    }

    public UUID bindingDecisionId() {
      return bindingDecisionId;
    }

    public UUID orphanCleanupId() {
      return orphanCleanupId;
    }

    public OriginalAckReceiptEvidence originalAckReceipt() {
      return originalAckReceipt;
    }

    @Override
    public String toString() {
      return "AccountGameplayAdmissionLeaseGrpcCodec.OperationEvidence[state=" + state + "]";
    }
  }

  /** Typed Common carrier for original-ACK receipt fields; it proves no receipt durability. */
  public static final class OriginalAckReceiptEvidence {
    private final int schemaVersion;
    private final UUID requestId;
    private final UUID accountId;
    private final UUID leaseId;
    private final long leaseFence;
    private final String evidenceSha256;
    private final UUID bindingDecisionId;
    private final long expiresAtMs;
    private final String finalizationXid;
    private final long committedBeforeMs;
    private final String receiptXid;

    private OriginalAckReceiptEvidence(
        int schemaVersion,
        UUID requestId,
        UUID accountId,
        UUID leaseId,
        long leaseFence,
        String evidenceSha256,
        UUID bindingDecisionId,
        long expiresAtMs,
        String finalizationXid,
        long committedBeforeMs,
        String receiptXid) {
      this.schemaVersion = schemaVersion;
      this.requestId = Objects.requireNonNull(requestId, "requestId");
      this.accountId = Objects.requireNonNull(accountId, "accountId");
      this.leaseId = Objects.requireNonNull(leaseId, "leaseId");
      this.leaseFence = leaseFence;
      this.evidenceSha256 = Objects.requireNonNull(evidenceSha256, "evidenceSha256");
      this.bindingDecisionId = Objects.requireNonNull(bindingDecisionId, "bindingDecisionId");
      this.expiresAtMs = expiresAtMs;
      this.finalizationXid = Objects.requireNonNull(finalizationXid, "finalizationXid");
      this.committedBeforeMs = committedBeforeMs;
      this.receiptXid = Objects.requireNonNull(receiptXid, "receiptXid");
      validateReceiptShape(this);
    }

    /**
     * Builds a shape-only typed receipt from Account DTO fields; this does not prove durability.
     */
    public static OriginalAckReceiptEvidence fromFields(
        int schemaVersion,
        UUID requestId,
        UUID accountId,
        UUID leaseId,
        long leaseFence,
        String evidenceSha256,
        UUID bindingDecisionId,
        long expiresAtMs,
        String finalizationXid,
        long committedBeforeMs,
        String receiptXid) {
      return new OriginalAckReceiptEvidence(
          schemaVersion,
          requestId,
          accountId,
          leaseId,
          leaseFence,
          evidenceSha256,
          bindingDecisionId,
          expiresAtMs,
          finalizationXid,
          committedBeforeMs,
          receiptXid);
    }

    public int schemaVersion() {
      return schemaVersion;
    }

    public UUID requestId() {
      return requestId;
    }

    public UUID accountId() {
      return accountId;
    }

    public UUID leaseId() {
      return leaseId;
    }

    public long leaseFence() {
      return leaseFence;
    }

    public String evidenceSha256() {
      return evidenceSha256;
    }

    public UUID bindingDecisionId() {
      return bindingDecisionId;
    }

    public long expiresAtMs() {
      return expiresAtMs;
    }

    public String finalizationXid() {
      return finalizationXid;
    }

    public long committedBeforeMs() {
      return committedBeforeMs;
    }

    public String receiptXid() {
      return receiptXid;
    }

    @Override
    public String toString() {
      return "AccountGameplayAdmissionLeaseGrpcCodec.OriginalAckReceiptEvidence[redacted]";
    }
  }

  public enum State {
    PENDING,
    COMMITTED,
    ABORTED
  }

  /**
   * Serializes an expected acquisition request through the same closed parser used by receivers.
   */
  public static AcquireGameplayAdmissionLeaseRequest toAcquireRequest(
      UUID requestId,
      String presentedInitialGsACompactJwt,
      Candidate expectedCandidate,
      Target expectedTarget,
      BigInteger bindingGeneration) {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(expectedCandidate, "expectedCandidate");
    Objects.requireNonNull(expectedTarget, "expectedTarget");
    Objects.requireNonNull(bindingGeneration, "bindingGeneration");
    var request =
        AcquireGameplayAdmissionLeaseRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addRequestUuid(requestId.toString())
            .addPresentedInitialGsaCompactJwt(compactJwt(presentedInitialGsACompactJwt))
            .addExpectedCandidate(
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.toCandidate(expectedCandidate))
            .addExpectedTarget(
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetEvidence(expectedTarget))
            .addBindingGeneration(positiveDecimal(bindingGeneration))
            .build();
    requireWireSize(request.getSerializedSize(), MAX_ACQUIRE_REQUEST_WIRE_BYTES, "acquire request");
    parseAcquireRequest(request);
    return request;
  }

  /** Parses an acquisition request; expected values remain Account comparison inputs only. */
  public static AcquireRequest parseAcquireRequest(AcquireGameplayAdmissionLeaseRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireSize(request.getSerializedSize(), MAX_ACQUIRE_REQUEST_WIRE_BYTES, "acquire request");
    requireNoUnknownFields(request.getUnknownFields().asMap().isEmpty(), "acquire request");
    requireSchema(one(request.getSchemaVersionList(), "acquire.schemaVersion"));
    UUID requestId = highEntropyUuid(one(request.getRequestUuidList(), "acquire.requestId"));
    String compactJwt =
        compactJwt(one(request.getPresentedInitialGsaCompactJwtList(), "acquire.presentedJwt"));
    CanonicalGameplayAdmissionCandidate candidateMessage =
        one(request.getExpectedCandidateList(), "acquire.expectedCandidate");
    Candidate candidate =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseCandidate(candidateMessage);
    if (!CanonicalGameplayAdmissionDecisionReadGrpcCodec.toCandidate(candidate)
        .equals(candidateMessage)) {
      throw invalid("Acquire candidate is not its exact existing closed encoding");
    }
    CanonicalPlayableTargetEvidence targetMessage =
        one(request.getExpectedTargetList(), "acquire.expectedTarget");
    Target target =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetEvidence(targetMessage);
    if (!CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetEvidence(target)
        .equals(targetMessage)) {
      throw invalid("Acquire target is not its exact existing closed encoding");
    }
    requireAcquireCandidateMatchesTarget(candidate, target);
    BigInteger generation =
        AccountJwtExactValues.positiveDecimalCounter(
            one(request.getBindingGenerationList(), "acquire.bindingGeneration"));
    return new AcquireRequest(requestId, compactJwt, candidate, target, generation);
  }

  public static FinalizeGameplayAdmissionLeaseRequest toFinalizeRequest(
      String presentedInitialGsACompactJwt,
      byte[] originalLeaseEvidenceUtf8,
      UUID bindingDecisionId) {
    Objects.requireNonNull(bindingDecisionId, "bindingDecisionId");
    byte[] leaseBytes = requireOriginalLeaseEvidenceBytes(originalLeaseEvidenceUtf8);
    var request =
        FinalizeGameplayAdmissionLeaseRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addPresentedInitialGsaCompactJwt(compactJwt(presentedInitialGsACompactJwt))
            .addOriginalLeaseEvidenceUtf8(ByteString.copyFrom(leaseBytes))
            .addBindingDecisionUuid(highEntropyUuid(bindingDecisionId).toString())
            .build();
    requireWireSize(
        request.getSerializedSize(), MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES, "finalize request");
    parseFinalizeRequest(request);
    return request;
  }

  public static OriginalLeaseRequest parseFinalizeRequest(
      FinalizeGameplayAdmissionLeaseRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireSize(
        request.getSerializedSize(), MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES, "finalize request");
    requireNoUnknownFields(request.getUnknownFields().asMap().isEmpty(), "finalize request");
    requireSchema(one(request.getSchemaVersionList(), "finalize.schemaVersion"));
    String compactJwt =
        compactJwt(one(request.getPresentedInitialGsaCompactJwtList(), "finalize.presentedJwt"));
    byte[] leaseBytes =
        one(request.getOriginalLeaseEvidenceUtf8List(), "finalize.originalLeaseEvidenceUtf8")
            .toByteArray();
    AccountGameplayAdmissionLeaseEvidence evidence = parseFreshNewLease(leaseBytes);
    requirePresentedJwtMatchesLease(evidence, compactJwt);
    UUID decisionId =
        highEntropyUuid(one(request.getBindingDecisionUuidList(), "finalize.decisionId"));
    return new OriginalLeaseRequest(
        OriginalLeasePurpose.FINALIZE, compactJwt, evidence, leaseBytes, decisionId);
  }

  public static ReadGameplayAdmissionLeaseRequest toReadRequest(
      String presentedInitialGsACompactJwt, byte[] originalLeaseEvidenceUtf8) {
    byte[] leaseBytes = requireOriginalLeaseEvidenceBytes(originalLeaseEvidenceUtf8);
    var request =
        ReadGameplayAdmissionLeaseRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addPresentedInitialGsaCompactJwt(compactJwt(presentedInitialGsACompactJwt))
            .addOriginalLeaseEvidenceUtf8(ByteString.copyFrom(leaseBytes))
            .build();
    requireWireSize(
        request.getSerializedSize(), MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES, "read request");
    parseReadRequest(request);
    return request;
  }

  public static OriginalLeaseRequest parseReadRequest(ReadGameplayAdmissionLeaseRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireSize(
        request.getSerializedSize(), MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES, "read request");
    requireNoUnknownFields(request.getUnknownFields().asMap().isEmpty(), "read request");
    requireSchema(one(request.getSchemaVersionList(), "read.schemaVersion"));
    String compactJwt =
        compactJwt(one(request.getPresentedInitialGsaCompactJwtList(), "read.presentedJwt"));
    byte[] leaseBytes =
        one(request.getOriginalLeaseEvidenceUtf8List(), "read.originalLeaseEvidenceUtf8")
            .toByteArray();
    AccountGameplayAdmissionLeaseEvidence evidence = parseFreshNewLease(leaseBytes);
    requirePresentedJwtMatchesLease(evidence, compactJwt);
    return new OriginalLeaseRequest(
        OriginalLeasePurpose.READ, compactJwt, evidence, leaseBytes, null);
  }

  public static AbortGameplayAdmissionLeaseRequest toAbortRequest(
      String presentedInitialGsACompactJwt,
      byte[] originalLeaseEvidenceUtf8,
      UUID bindingDecisionId) {
    byte[] leaseBytes = requireOriginalLeaseEvidenceBytes(originalLeaseEvidenceUtf8);
    var builder =
        AbortGameplayAdmissionLeaseRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addPresentedInitialGsaCompactJwt(compactJwt(presentedInitialGsACompactJwt))
            .addOriginalLeaseEvidenceUtf8(ByteString.copyFrom(leaseBytes));
    if (bindingDecisionId != null) {
      builder.addBindingDecisionUuid(highEntropyUuid(bindingDecisionId).toString());
    }
    var request = builder.build();
    requireWireSize(
        request.getSerializedSize(), MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES, "abort request");
    parseAbortRequest(request);
    return request;
  }

  public static OriginalLeaseRequest parseAbortRequest(AbortGameplayAdmissionLeaseRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireSize(
        request.getSerializedSize(), MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES, "abort request");
    requireNoUnknownFields(request.getUnknownFields().asMap().isEmpty(), "abort request");
    requireSchema(one(request.getSchemaVersionList(), "abort.schemaVersion"));
    String compactJwt =
        compactJwt(one(request.getPresentedInitialGsaCompactJwtList(), "abort.presentedJwt"));
    byte[] leaseBytes =
        one(request.getOriginalLeaseEvidenceUtf8List(), "abort.originalLeaseEvidenceUtf8")
            .toByteArray();
    AccountGameplayAdmissionLeaseEvidence evidence = parseFreshNewLease(leaseBytes);
    requirePresentedJwtMatchesLease(evidence, compactJwt);
    String decisionText = optional(request.getBindingDecisionUuidList(), "abort.decisionId");
    UUID decisionId = decisionText == null ? null : highEntropyUuid(decisionText);
    return new OriginalLeaseRequest(
        OriginalLeasePurpose.ABORT, compactJwt, evidence, leaseBytes, decisionId);
  }

  /**
   * Serializes a complete original-operation response and revalidates it against the acquisition.
   */
  public static AccountGameplayAdmissionLeaseResponse toAcquireResponse(
      AcquireRequest request,
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      State state,
      UUID bindingDecisionId,
      UUID orphanCleanupId,
      OriginalAckReceiptEvidence originalAckReceipt) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(originalLeaseEvidence, "originalLeaseEvidence");
    byte[] leaseBytes = canonicalLeaseBytes(originalLeaseEvidence);
    AccountGameplayAdmissionLeaseResponse response =
        buildResponse(
            leaseBytes,
            originalLeaseEvidence,
            state,
            bindingDecisionId,
            orphanCleanupId,
            originalAckReceipt);
    parseAcquireResponse(request, response);
    return response;
  }

  public static AccountGameplayAdmissionLeaseResponse toFinalizeResponse(
      OriginalLeaseRequest request,
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      State state,
      UUID bindingDecisionId,
      UUID orphanCleanupId,
      OriginalAckReceiptEvidence originalAckReceipt) {
    requirePurpose(request, OriginalLeasePurpose.FINALIZE);
    return toOriginalLeaseResponse(
        request,
        originalLeaseEvidence,
        state,
        bindingDecisionId,
        orphanCleanupId,
        originalAckReceipt);
  }

  public static AccountGameplayAdmissionLeaseResponse toReadResponse(
      OriginalLeaseRequest request,
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      State state,
      UUID bindingDecisionId,
      UUID orphanCleanupId,
      OriginalAckReceiptEvidence originalAckReceipt) {
    requirePurpose(request, OriginalLeasePurpose.READ);
    return toOriginalLeaseResponse(
        request,
        originalLeaseEvidence,
        state,
        bindingDecisionId,
        orphanCleanupId,
        originalAckReceipt);
  }

  public static AccountGameplayAdmissionLeaseResponse toAbortResponse(
      OriginalLeaseRequest request,
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      State state,
      UUID bindingDecisionId,
      UUID orphanCleanupId,
      OriginalAckReceiptEvidence originalAckReceipt) {
    requirePurpose(request, OriginalLeasePurpose.ABORT);
    return toOriginalLeaseResponse(
        request,
        originalLeaseEvidence,
        state,
        bindingDecisionId,
        orphanCleanupId,
        originalAckReceipt);
  }

  public static OperationEvidence parseAcquireResponse(
      AcquireRequest request, AccountGameplayAdmissionLeaseResponse response) {
    Objects.requireNonNull(request, "request");
    OperationEvidence evidence = parseResponse(response);
    requireAcquireLeaseMatchesRequest(request, evidence.originalLeaseEvidence());
    requirePresentedJwtMatchesLease(
        evidence.originalLeaseEvidence(), request.presentedInitialGsACompactJwt());
    return evidence;
  }

  private static void requireAcquireCandidateMatchesTarget(Candidate candidate, Target target) {
    if (!candidate.tenantId().equals(target.canonicalTenantId())
        || !candidate.playableStateNamespaceId().equals(target.playableStateNamespaceId())
        || !candidate.playableStateScope().equals(target.playableStateScope())
        || !candidate.gameInstanceId().equals(target.canonicalGameInstanceId())
        || candidate.runtimeGameInstanceId() != target.gameInstanceId()) {
      throw invalid("Expected candidate differs from its exact target identity");
    }
  }

  private static void requireAcquireLeaseMatchesRequest(
      AcquireRequest request, AccountGameplayAdmissionLeaseEvidence lease) {
    Map<?, ?> carrier = lease.carrier();
    Map<?, ?> scope = object(carrier.get("bindingScope"), "bindingScope");
    Map<?, ?> authorityTuple = object(carrier.get("authorityTuple"), "authorityTuple");
    Candidate candidate = request.expectedCandidate();
    Target target = request.expectedTarget();
    boolean matches =
        request.requestId().equals(highEntropyUuid(string(carrier, "requestId")))
            && request
                .bindingGeneration()
                .equals(positiveCounter(string(scope, "bindingGeneration")))
            && candidate.accountId().toString().equals(string(scope, "accountId"))
            && candidate.tenantId().toString().equals(string(scope, "tenantId"))
            && target.canonicalTenantId().toString().equals(string(scope, "tenantId"))
            && target.realmId().toString().equals(string(scope, "realmId"))
            && target.worldSlug().equals(string(scope, "worldSlug"))
            && target.realmSlug().equals(string(scope, "realmSlug"))
            && candidate
                .playableStateNamespaceId()
                .toString()
                .equals(string(scope, "playableStateNamespaceId"))
            && target
                .playableStateNamespaceId()
                .toString()
                .equals(string(scope, "playableStateNamespaceId"))
            && candidate.playableStateScope().equals(string(scope, "playableStateScope"))
            && target.playableStateScope().equals(string(scope, "playableStateScope"))
            && candidate.gameInstanceId().toString().equals(string(scope, "gameInstanceId"))
            && target.canonicalGameInstanceId().toString().equals(string(scope, "gameInstanceId"))
            && candidate.characterId().toString().equals(string(scope, "characterId"))
            && candidate.sessionId().toString().equals(string(scope, "sessionId"))
            && Long.toString(target.catalogRevision()).equals(string(scope, "catalogRevision"))
            && Long.toString(target.pointerVersion()).equals(string(scope, "pointerVersion"))
            && candidate.regionId().toString().equals(string(scope, "regionId"))
            && candidate.regionEpoch().toString().equals(string(scope, "regionEpoch"))
            && candidate
                .issuerAuthGeneration()
                .toString()
                .equals(string(authorityTuple, "issuerAuthGeneration"));
    if (!matches) {
      throw invalid("Acquire lease changed an exact expected binding-scope value");
    }
    // Target version, private row IDs, and owner-proof fields do not exist in the lease. Account
    // must retain their independent fresh-target comparisons at its owning boundary.
  }

  private static void requirePresentedJwtMatchesLease(
      AccountGameplayAdmissionLeaseEvidence lease, String presentedInitialGsACompactJwt) {
    Map<?, ?> tokenIdentity =
        object(lease.carrier().get("tokenIdentityEvidence"), "tokenIdentityEvidence");
    if (!compactJwtSha256(presentedInitialGsACompactJwt)
        .equals(string(tokenIdentity, "tokenSHA256"))) {
      throw invalid("Presented initial GSA credential changed the exact lease token identity");
    }
  }

  public static OperationEvidence parseFinalizeResponse(
      OriginalLeaseRequest request, AccountGameplayAdmissionLeaseResponse response) {
    requirePurpose(request, OriginalLeasePurpose.FINALIZE);
    return parseOriginalLeaseResponse(request, response);
  }

  public static OperationEvidence parseReadResponse(
      OriginalLeaseRequest request, AccountGameplayAdmissionLeaseResponse response) {
    requirePurpose(request, OriginalLeasePurpose.READ);
    return parseOriginalLeaseResponse(request, response);
  }

  public static OperationEvidence parseAbortResponse(
      OriginalLeaseRequest request, AccountGameplayAdmissionLeaseResponse response) {
    requirePurpose(request, OriginalLeasePurpose.ABORT);
    return parseOriginalLeaseResponse(request, response);
  }

  private static AccountGameplayAdmissionLeaseResponse toOriginalLeaseResponse(
      OriginalLeaseRequest request,
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      State state,
      UUID bindingDecisionId,
      UUID orphanCleanupId,
      OriginalAckReceiptEvidence originalAckReceipt) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(originalLeaseEvidence, "originalLeaseEvidence");
    if (!request.originalLeaseEvidence().hasSameIdentity(originalLeaseEvidence)
        || !request
            .originalLeaseEvidence()
            .hasSameIdentity(parseFreshNewLease(request.originalLeaseEvidenceUtf8()))) {
      throw invalid("Owner readback differs from the exact original lease request");
    }
    if (request.purpose == OriginalLeasePurpose.FINALIZE
        && bindingDecisionId != null
        && !bindingDecisionId.equals(request.bindingDecisionId())) {
      throw invalid("Finalization response changed the requested decision identity");
    }
    AccountGameplayAdmissionLeaseResponse response =
        buildResponse(
            request.originalLeaseEvidenceUtf8(),
            originalLeaseEvidence,
            state,
            bindingDecisionId,
            orphanCleanupId,
            originalAckReceipt);
    parseOriginalLeaseResponse(request, response);
    return response;
  }

  private static OperationEvidence parseOriginalLeaseResponse(
      OriginalLeaseRequest request, AccountGameplayAdmissionLeaseResponse response) {
    Objects.requireNonNull(request, "request");
    OperationEvidence evidence = parseResponse(response);
    byte[] actualLeaseBytes = response.getOriginalLeaseEvidenceUtf8(0).toByteArray();
    if (!Arrays.equals(request.originalLeaseEvidenceUtf8, actualLeaseBytes)
        || !request.originalLeaseEvidence().hasSameIdentity(evidence.originalLeaseEvidence())) {
      throw invalid("Response changed the complete original lease bytes");
    }
    if (request.bindingDecisionId() != null
        && evidence.bindingDecisionId() != null
        && !request.bindingDecisionId().equals(evidence.bindingDecisionId())) {
      throw invalid("Response changed the supplied binding decision identity");
    }
    return evidence;
  }

  private static AccountGameplayAdmissionLeaseResponse buildResponse(
      byte[] originalLeaseEvidenceUtf8,
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      State state,
      UUID bindingDecisionId,
      UUID orphanCleanupId,
      OriginalAckReceiptEvidence originalAckReceipt) {
    Objects.requireNonNull(originalLeaseEvidenceUtf8, "originalLeaseEvidenceUtf8");
    Objects.requireNonNull(originalLeaseEvidence, "originalLeaseEvidence");
    Objects.requireNonNull(state, "state");
    byte[] canonicalBytes = canonicalLeaseBytes(originalLeaseEvidence);
    if (!Arrays.equals(originalLeaseEvidenceUtf8, canonicalBytes)
        || !originalLeaseEvidence.hasSameIdentity(parseFreshNewLease(originalLeaseEvidenceUtf8))) {
      throw invalid("Response must preserve the complete canonical original lease");
    }
    requireFreshNewLease(originalLeaseEvidence);
    UUID decisionId = bindingDecisionId == null ? null : highEntropyUuid(bindingDecisionId);
    UUID cleanupId = orphanCleanupId == null ? null : highEntropyUuid(orphanCleanupId);
    validateOperationState(originalLeaseEvidence, state, decisionId, cleanupId, originalAckReceipt);

    var builder =
        AccountGameplayAdmissionLeaseResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addOriginalLeaseEvidenceUtf8(ByteString.copyFrom(originalLeaseEvidenceUtf8))
            .addState(toWireState(state));
    if (decisionId != null) builder.addBindingDecisionUuid(decisionId.toString());
    if (cleanupId != null) builder.addOrphanCleanupUuid(cleanupId.toString());
    if (originalAckReceipt != null)
      builder.addOriginalAckReceipt(toWireReceipt(originalAckReceipt));
    AccountGameplayAdmissionLeaseResponse response = builder.build();
    requireWireSize(response.getSerializedSize(), MAX_RESPONSE_WIRE_BYTES, "lease response");
    return response;
  }

  private static OperationEvidence parseResponse(AccountGameplayAdmissionLeaseResponse response) {
    Objects.requireNonNull(response, "response");
    requireWireSize(response.getSerializedSize(), MAX_RESPONSE_WIRE_BYTES, "lease response");
    requireNoUnknownFields(response.getUnknownFields().asMap().isEmpty(), "lease response");
    requireSchema(one(response.getSchemaVersionList(), "response.schemaVersion"));
    byte[] leaseBytes =
        one(response.getOriginalLeaseEvidenceUtf8List(), "response.leaseEvidence").toByteArray();
    AccountGameplayAdmissionLeaseEvidence lease = parseFreshNewLease(leaseBytes);
    State state = fromWireState(one(response.getStateValueList(), "response.state"));
    String decisionText =
        optional(response.getBindingDecisionUuidList(), "response.bindingDecisionId");
    String cleanupText = optional(response.getOrphanCleanupUuidList(), "response.orphanCleanupId");
    UUID decisionId = decisionText == null ? null : highEntropyUuid(decisionText);
    UUID cleanupId = cleanupText == null ? null : highEntropyUuid(cleanupText);
    AccountGameplayAdmissionOriginalAckReceiptEvidence receiptMessage =
        optional(response.getOriginalAckReceiptList(), "response.originalAckReceipt");
    OriginalAckReceiptEvidence receipt =
        receiptMessage == null ? null : fromWireReceipt(receiptMessage);
    validateOperationState(lease, state, decisionId, cleanupId, receipt);
    return new OperationEvidence(lease, state, decisionId, cleanupId, receipt);
  }

  private static void validateOperationState(
      AccountGameplayAdmissionLeaseEvidence lease,
      State state,
      UUID bindingDecisionId,
      UUID orphanCleanupId,
      OriginalAckReceiptEvidence receipt) {
    requireFreshNewLease(lease);
    switch (state) {
      case PENDING -> {
        if (bindingDecisionId != null || orphanCleanupId != null || receipt != null) {
          throw invalid("PENDING response must not carry terminal evidence");
        }
      }
      case COMMITTED -> {
        if (bindingDecisionId == null || orphanCleanupId != null || receipt == null) {
          throw invalid("COMMITTED response requires its decision and complete ACK receipt");
        }
        validateReceiptMatches(lease, bindingDecisionId, receipt);
      }
      case ABORTED -> {
        if (orphanCleanupId == null || receipt != null) {
          throw invalid("ABORTED response requires pending cleanup and no ACK receipt");
        }
      }
    }
  }

  private static void validateReceiptMatches(
      AccountGameplayAdmissionLeaseEvidence lease,
      UUID bindingDecisionId,
      OriginalAckReceiptEvidence receipt) {
    Map<?, ?> carrier = lease.carrier();
    Map<?, ?> scope = object(carrier.get("bindingScope"), "bindingScope");
    UUID requestId = highEntropyUuid(string(carrier, "requestId"));
    UUID accountId = canonicalNonNilUuid(string(scope, "accountId"));
    UUID leaseId = highEntropyUuid(string(carrier, "leaseId"));
    long leaseFence = positiveLong(lease.leaseFence());
    long expiresAtMs = positiveLongDecimal(string(carrier, "expiresAt"), "expiresAt");
    if (receipt.schemaVersion() != 1
        || !receipt.requestId().equals(requestId)
        || !receipt.accountId().equals(accountId)
        || !receipt.leaseId().equals(leaseId)
        || receipt.leaseFence() != leaseFence
        || !receipt.evidenceSha256().equals(lease.sha256())
        || !receipt.bindingDecisionId().equals(bindingDecisionId)
        || receipt.expiresAtMs() != expiresAtMs
        || receipt.committedBeforeMs() >= expiresAtMs) {
      throw invalid("Original ACK receipt differs from the exact committed lease operation");
    }
  }

  private static AccountGameplayAdmissionOriginalAckReceiptEvidence toWireReceipt(
      OriginalAckReceiptEvidence receipt) {
    return AccountGameplayAdmissionOriginalAckReceiptEvidence.newBuilder()
        .addSchemaVersion(receipt.schemaVersion())
        .addRequestUuid(receipt.requestId().toString())
        .addAccountUuid(receipt.accountId().toString())
        .addLeaseUuid(receipt.leaseId().toString())
        .addLeaseFence(Long.toString(receipt.leaseFence()))
        .addEvidenceSha256(receipt.evidenceSha256())
        .addBindingDecisionUuid(receipt.bindingDecisionId().toString())
        .addExpiresAtMs(Long.toString(receipt.expiresAtMs()))
        .addFinalizationXid(receipt.finalizationXid())
        .addCommittedBeforeMs(Long.toString(receipt.committedBeforeMs()))
        .addReceiptXid(receipt.receiptXid())
        .build();
  }

  private static OriginalAckReceiptEvidence fromWireReceipt(
      AccountGameplayAdmissionOriginalAckReceiptEvidence message) {
    requireNoUnknownFields(message.getUnknownFields().asMap().isEmpty(), "ACK receipt");
    int schema = one(message.getSchemaVersionList(), "ACK.schemaVersion");
    return new OriginalAckReceiptEvidence(
        schema,
        highEntropyUuid(one(message.getRequestUuidList(), "ACK.requestId")),
        canonicalNonNilUuid(one(message.getAccountUuidList(), "ACK.accountId")),
        highEntropyUuid(one(message.getLeaseUuidList(), "ACK.leaseId")),
        positiveLongDecimal(one(message.getLeaseFenceList(), "ACK.leaseFence"), "ACK.leaseFence"),
        digest(one(message.getEvidenceSha256List(), "ACK.evidenceSha256")),
        highEntropyUuid(one(message.getBindingDecisionUuidList(), "ACK.bindingDecisionId")),
        positiveLongDecimal(
            one(message.getExpiresAtMsList(), "ACK.expiresAtMs"), "ACK.expiresAtMs"),
        xid(one(message.getFinalizationXidList(), "ACK.finalizationXid")),
        positiveLongDecimal(
            one(message.getCommittedBeforeMsList(), "ACK.committedBeforeMs"),
            "ACK.committedBeforeMs"),
        xid(one(message.getReceiptXidList(), "ACK.receiptXid")));
  }

  private static void validateReceiptShape(OriginalAckReceiptEvidence receipt) {
    if (receipt.schemaVersion != 1
        || receipt.requestId.version() != 4
        || receipt.requestId.variant() != 2
        || NIL_UUID.equals(receipt.accountId)
        || receipt.leaseId.version() != 4
        || receipt.leaseId.variant() != 2
        || receipt.leaseFence <= 0L
        || !receipt.evidenceSha256.matches("[0-9a-f]{64}")
        || receipt.bindingDecisionId.version() != 4
        || receipt.bindingDecisionId.variant() != 2
        || receipt.expiresAtMs <= 0L
        || receipt.committedBeforeMs <= 0L
        || receipt.committedBeforeMs >= receipt.expiresAtMs) {
      throw invalid("ACK receipt fields are outside their existing supported form");
    }
    requireCanonicalXid(receipt.finalizationXid);
    requireCanonicalXid(receipt.receiptXid);
    if (receipt.finalizationXid.equals(receipt.receiptXid)) {
      throw invalid("ACK receipt XIDs must identify distinct transactions");
    }
  }

  private static byte[] canonicalLeaseBytes(AccountGameplayAdmissionLeaseEvidence evidence) {
    Objects.requireNonNull(evidence, "originalLeaseEvidence");
    requireFreshNewLease(evidence);
    return evidence.canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] requireOriginalLeaseEvidenceBytes(byte[] bytes) {
    AccountGameplayAdmissionLeaseEvidence evidence = parseFreshNewLease(bytes);
    if (!Arrays.equals(bytes, canonicalLeaseBytes(evidence))) {
      throw invalid("Original lease bytes are not the exact canonical UTF-8");
    }
    return bytes.clone();
  }

  private static AccountGameplayAdmissionLeaseEvidence parseFreshNewLease(byte[] bytes) {
    Objects.requireNonNull(bytes, "originalLeaseEvidenceUtf8");
    if (bytes.length > AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES) {
      throw invalid("Original lease evidence exceeds its existing byte bound");
    }
    String json;
    try {
      json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid("Original lease evidence is not strict UTF-8");
    }
    AccountGameplayAdmissionLeaseEvidence evidence;
    try {
      evidence = AccountGameplayAdmissionLeaseEvidence.parseCanonical(json);
    } catch (RuntimeException malformed) {
      throw invalid("Original lease evidence is malformed");
    }
    if (!Arrays.equals(bytes, canonicalLeaseBytesWithoutRecursion(evidence))) {
      throw invalid("Original lease bytes are not the exact canonical UTF-8");
    }
    requireFreshNewLease(evidence);
    return evidence;
  }

  private static byte[] canonicalLeaseBytesWithoutRecursion(
      AccountGameplayAdmissionLeaseEvidence evidence) {
    return evidence.canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  private static void requireFreshNewLease(AccountGameplayAdmissionLeaseEvidence evidence) {
    if (evidence.leaseKind() != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
        || evidence.carrier().containsKey("resumeEpisodeId")
        || evidence.carrier().containsKey("expectedOldBindingGeneration")) {
      throw invalid("Only the exact fresh NEW-binding lease form is supported");
    }
  }

  private static String compactJwt(String value) {
    byte[] utf8 = compactJwtUtf8(value);
    Arrays.fill(utf8, (byte) 0);
    return value;
  }

  private static String compactJwtSha256(String value) {
    byte[] utf8 = compactJwtUtf8(value);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    } finally {
      Arrays.fill(utf8, (byte) 0);
    }
  }

  private static byte[] compactJwtUtf8(String value) {
    Objects.requireNonNull(value, "presentedInitialGsACompactJwt");
    if (value.isBlank() || !value.equals(value.trim())) {
      throw invalid("Presented initial GSA compact JWT is malformed");
    }
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      if (encoded.remaining() > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES) {
        throw invalid("Presented initial GSA compact JWT exceeds its existing byte bound");
      }
      byte[] utf8 = new byte[encoded.remaining()];
      encoded.get(utf8);
      return utf8;
    } catch (CharacterCodingException malformed) {
      throw invalid("Presented initial GSA compact JWT has invalid text encoding");
    }
  }

  private static UUID highEntropyUuid(String value) {
    UUID parsed = canonicalNonNilUuid(value);
    if (parsed.version() != 4 || parsed.variant() != 2) {
      throw invalid("UUID must be a canonical high-entropy UUIDv4");
    }
    return parsed;
  }

  private static UUID highEntropyUuid(UUID value) {
    Objects.requireNonNull(value, "UUID");
    if (NIL_UUID.equals(value) || value.version() != 4 || value.variant() != 2) {
      throw invalid("UUID must be a canonical high-entropy UUIDv4");
    }
    return value;
  }

  private static UUID canonicalNonNilUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw invalid("UUID must be canonical and non-nil");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw invalid("UUID must be canonical and non-nil");
    }
  }

  private static String digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw invalid("Evidence digest must be lowercase SHA-256 hex");
    }
    return value;
  }

  private static String xid(String value) {
    requireCanonicalXid(value);
    return value;
  }

  private static void requireCanonicalXid(String value) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw invalid("Transaction ID must be canonical positive unsigned-64 decimal");
    }
    try {
      BigInteger xid = new BigInteger(value);
      if (xid.signum() <= 0 || xid.compareTo(UINT64_MAX) > 0) {
        throw invalid("Transaction ID exceeds its supported unsigned-64 range");
      }
    } catch (NumberFormatException malformed) {
      throw invalid("Transaction ID must be canonical positive unsigned-64 decimal");
    }
  }

  private static long positiveLongDecimal(String value, String field) {
    try {
      return AccountJwtExactValues.positiveDecimalCounter(value).longValueExact();
    } catch (RuntimeException malformed) {
      throw invalid(field + " is outside its canonical positive signed-long range");
    }
  }

  private static long positiveLong(BigInteger value) {
    try {
      long parsed = value.longValueExact();
      if (parsed <= 0L) throw invalid("Lease counter is not a positive signed-long value");
      return parsed;
    } catch (ArithmeticException outsideRange) {
      throw invalid("Lease counter exceeds its supported signed-long range");
    }
  }

  private static BigInteger positiveCounter(String value) {
    return AccountJwtExactValues.positiveDecimalCounter(value);
  }

  private static String positiveDecimal(BigInteger value) {
    if (value.signum() <= 0) throw invalid("Binding generation must be positive");
    return value.toString();
  }

  private static AccountGameplayAdmissionLeaseState toWireState(State state) {
    return switch (state) {
      case PENDING ->
          AccountGameplayAdmissionLeaseState.ACCOUNT_GAMEPLAY_ADMISSION_LEASE_STATE_PENDING;
      case COMMITTED ->
          AccountGameplayAdmissionLeaseState.ACCOUNT_GAMEPLAY_ADMISSION_LEASE_STATE_COMMITTED;
      case ABORTED ->
          AccountGameplayAdmissionLeaseState.ACCOUNT_GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED;
    };
  }

  private static State fromWireState(int value) {
    return switch (value) {
      case 1 -> State.PENDING;
      case 2 -> State.COMMITTED;
      case 3 -> State.ABORTED;
      default -> throw invalid("Unsupported original lease operation state");
    };
  }

  private static void requirePurpose(OriginalLeaseRequest request, OriginalLeasePurpose purpose) {
    Objects.requireNonNull(request, "request");
    if (request.purpose != purpose) throw invalid("Original lease request has the wrong purpose");
  }

  private static void requireSchema(int schema) {
    if (schema != SCHEMA_VERSION) throw invalid("Unsupported Account admission lease schema");
  }

  private static void requireNoUnknownFields(boolean noUnknownFields, String field) {
    if (!noUnknownFields) throw invalid("Unknown " + field + " field");
  }

  private static void requireWireSize(int actual, int maximum, String field) {
    if (actual > maximum) throw invalid(field + " exceeds its finite wire bound");
  }

  private static Map<?, ?> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> map)) throw invalid(field + " must be an object");
    return map;
  }

  private static String string(Map<?, ?> object, String field) {
    Object value = object.get(field);
    if (!(value instanceof String text)) throw invalid(field + " must be canonical text");
    return text;
  }

  private static <T> T one(List<T> values, String field) {
    if (values.size() != 1 || values.get(0) == null) {
      throw invalid(field + " must occur exactly once");
    }
    return values.get(0);
  }

  private static <T> T optional(List<T> values, String field) {
    if (values.size() > 1) throw invalid(field + " must be absent or occur once");
    return values.isEmpty() ? null : Objects.requireNonNull(values.get(0), field);
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private enum OriginalLeasePurpose {
    FINALIZE,
    READ,
    ABORT
  }
}
