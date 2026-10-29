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
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionAbortResolutionEvidence;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseState;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionOriginalAckReceiptEvidence;
import net.firedevops.firemud.account.v1.AcquireGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ConfirmOriginalNewGameplayAdmissionRequest;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.IssueOriginalNewGameplayCredentialRequest;
import net.firedevops.firemud.account.v1.OriginalNewAdmissionConfirmationResponse;
import net.firedevops.firemud.account.v1.OriginalNewGameplayCredentialResult;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountJwtExactValues;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionCandidate;
import net.firedevops.firemud.gamesession.v1.CanonicalPlayableTargetEvidence;
import tools.jackson.databind.json.JsonMapper;

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
      AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES
          + GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES
          + 16 * 1024;

  private static final int SCHEMA_VERSION = 1;
  private static final BigInteger UINT64_MAX =
      BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final JsonMapper JSON = JsonMapper.builder().build();

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

  /** Exact original-lease issuance request. The initial LOGIN JWT is transient and redacted. */
  public static final class OriginalNewCredentialRequest {
    private final String presentedInitialGsACompactJwt;
    private final AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence;
    private final byte[] originalLeaseEvidenceUtf8;

    private OriginalNewCredentialRequest(
        String presentedInitialGsACompactJwt,
        AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
        byte[] originalLeaseEvidenceUtf8) {
      this.presentedInitialGsACompactJwt = presentedInitialGsACompactJwt;
      this.originalLeaseEvidence = originalLeaseEvidence;
      this.originalLeaseEvidenceUtf8 = originalLeaseEvidenceUtf8.clone();
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

    @Override
    public String toString() {
      return "AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredentialRequest[redacted]";
    }
  }

  /** Internal Account-to-Game-Session credential carrier; diagnostics never reveal its contents. */
  public static final class OriginalNewCredential implements AutoCloseable {
    private final String signedBoundGsACompactJwt;
    private byte[] opaqueRebindHandle;
    private final UUID lineageId;
    private final UUID tokenJti;
    private final String tokenSha256;
    private final long issuanceFence;
    private final UUID originalLeaseId;
    private final long originalLeaseFence;
    private final String originalAckReceiptXid;
    private final String checkpointsSha256;

    private OriginalNewCredential(
        String signedBoundGsACompactJwt,
        byte[] opaqueRebindHandle,
        UUID lineageId,
        UUID tokenJti,
        String tokenSha256,
        long issuanceFence,
        UUID originalLeaseId,
        long originalLeaseFence,
        String originalAckReceiptXid,
        String checkpointsSha256) {
      this.signedBoundGsACompactJwt = signedBoundGsACompactJwt;
      this.opaqueRebindHandle = opaqueRebindHandle.clone();
      this.lineageId = lineageId;
      this.tokenJti = tokenJti;
      this.tokenSha256 = tokenSha256;
      this.issuanceFence = issuanceFence;
      this.originalLeaseId = originalLeaseId;
      this.originalLeaseFence = originalLeaseFence;
      this.originalAckReceiptXid = originalAckReceiptXid;
      this.checkpointsSha256 = checkpointsSha256;
    }

    public String signedBoundGsACompactJwt() {
      return signedBoundGsACompactJwt;
    }

    public byte[] opaqueRebindHandle() {
      if (opaqueRebindHandle == null)
        throw new IllegalStateException("Credential result is closed");
      return opaqueRebindHandle.clone();
    }

    public UUID lineageId() {
      return lineageId;
    }

    public UUID tokenJti() {
      return tokenJti;
    }

    public String tokenSha256() {
      return tokenSha256;
    }

    public long issuanceFence() {
      return issuanceFence;
    }

    public UUID originalLeaseId() {
      return originalLeaseId;
    }

    public long originalLeaseFence() {
      return originalLeaseFence;
    }

    public String originalAckReceiptXid() {
      return originalAckReceiptXid;
    }

    public String checkpointsSha256() {
      return checkpointsSha256;
    }

    @Override
    public void close() {
      if (opaqueRebindHandle != null) Arrays.fill(opaqueRebindHandle, (byte) 0);
      opaqueRebindHandle = null;
    }

    @Override
    public String toString() {
      return "AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential[redacted]";
    }
  }

  /** Exact Account confirmation recovery request; no caller-supplied proof or time fields. */
  public static final class OriginalFinalAdmissionConfirmationRequest {
    private final String presentedInitialGsACompactJwt;
    private final AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence;
    private final byte[] originalLeaseEvidenceUtf8;

    private OriginalFinalAdmissionConfirmationRequest(
        String compactJwt, AccountGameplayAdmissionLeaseEvidence lease, byte[] exactLeaseBytes) {
      this.presentedInitialGsACompactJwt = compactJwt;
      this.originalLeaseEvidence = lease;
      this.originalLeaseEvidenceUtf8 = exactLeaseBytes.clone();
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

    @Override
    public String toString() {
      return "AccountGameplayAdmissionLeaseGrpcCodec.OriginalFinalAdmissionConfirmationRequest[redacted]";
    }
  }

  /** Exact current Account confirmation anchors; values alone are not an admission proof. */
  public static final class OriginalFinalAdmissionConfirmation {
    private final UUID lineageId;
    private final UUID originalLeaseId;
    private final long originalLeaseFence;
    private final long admissionAtMillis;
    private final long continuityExpiresAtMillis;
    private final String confirmationReadbackSha256;

    private OriginalFinalAdmissionConfirmation(
        UUID lineageId,
        UUID originalLeaseId,
        long originalLeaseFence,
        long admissionAtMillis,
        long continuityExpiresAtMillis,
        String confirmationReadbackSha256) {
      this.lineageId = lineageId;
      this.originalLeaseId = originalLeaseId;
      this.originalLeaseFence = originalLeaseFence;
      this.admissionAtMillis = admissionAtMillis;
      this.continuityExpiresAtMillis = continuityExpiresAtMillis;
      this.confirmationReadbackSha256 = confirmationReadbackSha256;
    }

    public UUID lineageId() {
      return lineageId;
    }

    public UUID originalLeaseId() {
      return originalLeaseId;
    }

    public long originalLeaseFence() {
      return originalLeaseFence;
    }

    public long admissionAtMillis() {
      return admissionAtMillis;
    }

    public long continuityExpiresAtMillis() {
      return continuityExpiresAtMillis;
    }

    public String confirmationReadbackSha256() {
      return confirmationReadbackSha256;
    }

    @Override
    public String toString() {
      return "AccountGameplayAdmissionLeaseGrpcCodec.OriginalFinalAdmissionConfirmation[redacted]";
    }
  }

  /** Separate shape-only Account resolution; it proves neither cleanup nor admission. */
  public record AbortResolutionEvidence(
      UUID resolutionId,
      UUID originalLeaseId,
      BigInteger originalLeaseFence,
      String originalLeaseSha256,
      UUID orphanCleanupId,
      UUID bindingDecisionId) {
    public AbortResolutionEvidence {
      highEntropyUuid(resolutionId);
      highEntropyUuid(originalLeaseId);
      positiveDecimal(Objects.requireNonNull(originalLeaseFence, "originalLeaseFence"));
      digest(originalLeaseSha256);
      highEntropyUuid(orphanCleanupId);
      highEntropyUuid(bindingDecisionId);
    }

    @Override
    public String toString() {
      return "AccountGameplayAdmissionLeaseGrpcCodec.AbortResolutionEvidence[redacted]";
    }
  }

  /** Complete original-operation response shape with a redacted, typed receipt carrier. */
  public static final class OperationEvidence {
    private final AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence;
    private final State state;
    private final UUID bindingDecisionId;
    private final UUID orphanCleanupId;
    private final OriginalAckReceiptEvidence originalAckReceipt;
    private final AbortResolutionEvidence abortResolution;

    private OperationEvidence(
        AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
        State state,
        UUID bindingDecisionId,
        UUID orphanCleanupId,
        OriginalAckReceiptEvidence originalAckReceipt,
        AbortResolutionEvidence abortResolution) {
      this.originalLeaseEvidence = originalLeaseEvidence;
      this.state = state;
      this.bindingDecisionId = bindingDecisionId;
      this.orphanCleanupId = orphanCleanupId;
      this.originalAckReceipt = originalAckReceipt;
      this.abortResolution = abortResolution;
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

    public AbortResolutionEvidence abortResolution() {
      return abortResolution;
    }

    /** Compares wire shape only; this authenticates no owner or completed cleanup. */
    public boolean matchesAbortTransition(UUID transitionId) {
      if (state != State.ABORTED || transitionId == null) return false;
      if (bindingDecisionId != null) {
        return abortResolution == null && bindingDecisionId.equals(transitionId);
      }
      return abortResolution != null
          && abortResolution.bindingDecisionId().equals(transitionId)
          && leaseId(originalLeaseEvidence).equals(abortResolution.originalLeaseId())
          && originalLeaseEvidence.leaseFence().equals(abortResolution.originalLeaseFence())
          && originalLeaseEvidence.sha256().equals(abortResolution.originalLeaseSha256())
          && Objects.equals(orphanCleanupId, abortResolution.orphanCleanupId());
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

  public static IssueOriginalNewGameplayCredentialRequest toIssueOriginalNewCredentialRequest(
      String presentedInitialGsACompactJwt, byte[] originalLeaseEvidenceUtf8) {
    byte[] leaseBytes = requireOriginalLeaseEvidenceBytes(originalLeaseEvidenceUtf8);
    var request =
        IssueOriginalNewGameplayCredentialRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addPresentedInitialGsaCompactJwt(compactJwt(presentedInitialGsACompactJwt))
            .addOriginalLeaseEvidenceUtf8(ByteString.copyFrom(leaseBytes))
            .build();
    requireWireSize(
        request.getSerializedSize(),
        MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES,
        "original-new credential request");
    parseIssueOriginalNewCredentialRequest(request);
    return request;
  }

  public static OriginalNewCredentialRequest parseIssueOriginalNewCredentialRequest(
      IssueOriginalNewGameplayCredentialRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireSize(
        request.getSerializedSize(),
        MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES,
        "original-new credential request");
    requireNoUnknownFields(request.getUnknownFields().asMap().isEmpty(), "original-new request");
    requireSchema(one(request.getSchemaVersionList(), "originalNew.schemaVersion"));
    String compactJwt =
        compactJwt(one(request.getPresentedInitialGsaCompactJwtList(), "originalNew.presentedJwt"));
    byte[] leaseBytes =
        one(request.getOriginalLeaseEvidenceUtf8List(), "originalNew.originalLeaseEvidenceUtf8")
            .toByteArray();
    AccountGameplayAdmissionLeaseEvidence evidence = parseFreshNewLease(leaseBytes);
    requirePresentedJwtMatchesLease(evidence, compactJwt);
    return new OriginalNewCredentialRequest(compactJwt, evidence, leaseBytes);
  }

  public static ConfirmOriginalNewGameplayAdmissionRequest
      toConfirmOriginalNewGameplayAdmissionRequest(
          String presentedInitialGsACompactJwt, byte[] originalLeaseEvidenceUtf8) {
    byte[] leaseBytes = requireOriginalLeaseEvidenceBytes(originalLeaseEvidenceUtf8);
    var request =
        ConfirmOriginalNewGameplayAdmissionRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addPresentedInitialGsaCompactJwt(compactJwt(presentedInitialGsACompactJwt))
            .addOriginalLeaseEvidenceUtf8(ByteString.copyFrom(leaseBytes))
            .build();
    requireWireSize(
        request.getSerializedSize(),
        MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES,
        "original-new admission confirmation request");
    parseConfirmOriginalNewGameplayAdmissionRequest(request);
    return request;
  }

  public static OriginalFinalAdmissionConfirmationRequest
      parseConfirmOriginalNewGameplayAdmissionRequest(
          ConfirmOriginalNewGameplayAdmissionRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireSize(
        request.getSerializedSize(),
        MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES,
        "original-new admission confirmation request");
    requireNoUnknownFields(request.getUnknownFields().asMap().isEmpty(), "confirmation request");
    requireSchema(one(request.getSchemaVersionList(), "confirmation.schemaVersion"));
    String compactJwt =
        compactJwt(
            one(request.getPresentedInitialGsaCompactJwtList(), "confirmation.presentedJwt"));
    byte[] leaseBytes =
        one(request.getOriginalLeaseEvidenceUtf8List(), "confirmation.originalLeaseEvidence")
            .toByteArray();
    AccountGameplayAdmissionLeaseEvidence lease = parseFreshNewLease(leaseBytes);
    requirePresentedJwtMatchesLease(lease, compactJwt);
    return new OriginalFinalAdmissionConfirmationRequest(compactJwt, lease, leaseBytes);
  }

  public static OriginalNewAdmissionConfirmationResponse
      toConfirmOriginalNewGameplayAdmissionResponse(
          OriginalFinalAdmissionConfirmationRequest request,
          UUID lineageId,
          UUID originalLeaseId,
          long originalLeaseFence,
          long admissionAtMillis,
          long continuityExpiresAtMillis,
          String confirmationReadbackSha256) {
    Objects.requireNonNull(request);
    UUID leaseId = leaseId(request.originalLeaseEvidence());
    long leaseFence = positiveLong(request.originalLeaseEvidence().leaseFence());
    if (!leaseId.equals(originalLeaseId)
        || leaseFence != originalLeaseFence
        || admissionAtMillis <= 0L
        || continuityExpiresAtMillis <= admissionAtMillis) {
      throw invalid("Confirmation changed the exact original lease or deadline anchors");
    }
    var response =
        OriginalNewAdmissionConfirmationResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addLineageUuid(highEntropyUuid(lineageId).toString())
            .addOriginalLeaseUuid(leaseId.toString())
            .addOriginalLeaseFence(Long.toString(leaseFence))
            .addAdmissionAtMs(Long.toString(admissionAtMillis))
            .addContinuityExpiresAtMs(Long.toString(continuityExpiresAtMillis))
            .addConfirmationReadbackSha256(digest(confirmationReadbackSha256))
            .build();
    requireWireSize(response.getSerializedSize(), 1024, "original-new confirmation response");
    parseConfirmOriginalNewGameplayAdmissionResponse(request, response);
    return response;
  }

  public static OriginalFinalAdmissionConfirmation parseConfirmOriginalNewGameplayAdmissionResponse(
      OriginalFinalAdmissionConfirmationRequest request,
      OriginalNewAdmissionConfirmationResponse response) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(response);
    requireWireSize(response.getSerializedSize(), 1024, "original-new confirmation response");
    requireNoUnknownFields(response.getUnknownFields().asMap().isEmpty(), "confirmation response");
    requireSchema(one(response.getSchemaVersionList(), "confirmationResponse.schemaVersion"));
    UUID lineage =
        highEntropyUuid(one(response.getLineageUuidList(), "confirmationResponse.lineageId"));
    UUID lease =
        highEntropyUuid(one(response.getOriginalLeaseUuidList(), "confirmationResponse.leaseId"));
    long leaseFence =
        positiveLongDecimal(
            one(response.getOriginalLeaseFenceList(), "confirmationResponse.leaseFence"),
            "leaseFence");
    long admissionAt =
        positiveLongDecimal(
            one(response.getAdmissionAtMsList(), "confirmationResponse.admissionAtMs"),
            "admissionAtMs");
    long continuityExpires =
        positiveLongDecimal(
            one(
                response.getContinuityExpiresAtMsList(),
                "confirmationResponse.continuityExpiresAtMs"),
            "continuityExpiresAtMs");
    String readbackDigest =
        digest(
            one(
                response.getConfirmationReadbackSha256List(),
                "confirmationResponse.readbackSha256"));
    if (!lease.equals(leaseId(request.originalLeaseEvidence()))
        || leaseFence != positiveLong(request.originalLeaseEvidence().leaseFence())
        || continuityExpires <= admissionAt) {
      throw invalid("Confirmation response differs from the exact original lease");
    }
    return new OriginalFinalAdmissionConfirmation(
        lineage, lease, leaseFence, admissionAt, continuityExpires, readbackDigest);
  }

  public static AccountGameplayAdmissionLeaseResponse toIssueOriginalNewCredentialResponse(
      OriginalNewCredentialRequest request,
      AccountGameplayAdmissionLeaseEvidence originalLeaseEvidence,
      State state,
      UUID bindingDecisionId,
      OriginalAckReceiptEvidence originalAckReceipt,
      OriginalNewCredential credential) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(credential, "credential");
    if (!request.originalLeaseEvidence().hasSameIdentity(originalLeaseEvidence)
        || !Arrays.equals(
            request.originalLeaseEvidenceUtf8(), canonicalLeaseBytes(originalLeaseEvidence))) {
      throw invalid("Original-new result changed the exact lease evidence");
    }
    AccountGameplayAdmissionLeaseResponse base =
        buildResponse(
            request.originalLeaseEvidenceUtf8(),
            originalLeaseEvidence,
            state,
            bindingDecisionId,
            null,
            originalAckReceipt);
    var response =
        base.toBuilder().addOriginalNewCredential(toWireOriginalNewCredential(credential)).build();
    OriginalNewCredential parsed = parseIssueOriginalNewCredentialResponse(request, response);
    parsed.close();
    return response;
  }

  public static OriginalNewCredential parseIssueOriginalNewCredentialResponse(
      OriginalNewCredentialRequest request, AccountGameplayAdmissionLeaseResponse response) {
    Objects.requireNonNull(request, "request");
    OperationEvidence operation = parseResponse(response, true);
    if (operation.state() != State.COMMITTED
        || operation.bindingDecisionId() == null
        || operation.originalAckReceipt() == null
        || operation.orphanCleanupId() != null
        || !Arrays.equals(
            request.originalLeaseEvidenceUtf8(),
            response.getOriginalLeaseEvidenceUtf8(0).toByteArray())
        || !request.originalLeaseEvidence().hasSameIdentity(operation.originalLeaseEvidence())) {
      throw invalid("Original-new credential result requires the exact committed original lease");
    }
    OriginalNewCredential result =
        fromWireOriginalNewCredential(one(response.getOriginalNewCredentialList(), "originalNew"));
    try {
      validateOriginalNewCredential(
          operation.originalLeaseEvidence(), operation.originalAckReceipt(), result);
      return result;
    } catch (RuntimeException rejected) {
      result.close();
      throw rejected;
    }
  }

  /** Builds the redacted internal result value for Account's protected envelope recovery path. */
  public static OriginalNewCredential originalNewCredential(
      String signedBoundGsACompactJwt,
      byte[] opaqueRebindHandle,
      UUID lineageId,
      UUID tokenJti,
      String tokenSha256,
      long issuanceFence,
      UUID originalLeaseId,
      long originalLeaseFence,
      String originalAckReceiptXid,
      String checkpointsSha256) {
    if (opaqueRebindHandle == null
        || opaqueRebindHandle.length != 32
        || issuanceFence <= 0L
        || originalLeaseFence <= 0L) {
      throw invalid("Original-new credential result metadata is malformed");
    }
    return new OriginalNewCredential(
        compactJwt(signedBoundGsACompactJwt),
        opaqueRebindHandle,
        highEntropyUuid(lineageId),
        highEntropyUuid(tokenJti),
        digest(tokenSha256),
        issuanceFence,
        highEntropyUuid(originalLeaseId),
        originalLeaseFence,
        xid(originalAckReceiptXid),
        digest(checkpointsSha256));
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

  /** Preserves the original unknown terminal result and adds separate pending resolution. */
  public static AccountGameplayAdmissionLeaseResponse toResolvedAbortResponse(
      OriginalLeaseRequest request,
      AccountGameplayAdmissionLeaseEvidence originalLease,
      UUID originalCleanupId,
      AbortResolutionEvidence resolution) {
    requirePurpose(request, OriginalLeasePurpose.ABORT);
    Objects.requireNonNull(resolution, "resolution");
    AccountGameplayAdmissionLeaseResponse response =
        buildResponse(
                request.originalLeaseEvidenceUtf8(),
                originalLease,
                State.ABORTED,
                null,
                originalCleanupId,
                null)
            .toBuilder()
            .addAbortResolution(toWireAbortResolution(resolution))
            .build();
    parseAbortResponse(request, response);
    return response;
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
    boolean allowAbortResolution =
        request.purpose == OriginalLeasePurpose.ABORT && request.bindingDecisionId() != null;
    OperationEvidence evidence = parseResponse(response, false, allowAbortResolution);
    if (evidence.abortResolution() != null
        && !request.bindingDecisionId().equals(evidence.abortResolution().bindingDecisionId())) {
      throw invalid("Abort resolution changed the supplied binding decision identity");
    }
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
    return parseResponse(response, false);
  }

  private static OperationEvidence parseResponse(
      AccountGameplayAdmissionLeaseResponse response, boolean allowOriginalNewCredential) {
    return parseResponse(response, allowOriginalNewCredential, false);
  }

  private static OperationEvidence parseResponse(
      AccountGameplayAdmissionLeaseResponse response,
      boolean allowOriginalNewCredential,
      boolean allowAbortResolution) {
    Objects.requireNonNull(response, "response");
    requireWireSize(response.getSerializedSize(), MAX_RESPONSE_WIRE_BYTES, "lease response");
    requireNoUnknownFields(response.getUnknownFields().asMap().isEmpty(), "lease response");
    if (allowOriginalNewCredential) {
      if (response.getOriginalNewCredentialCount() != 1) {
        throw invalid("Original-new response must carry one credential result");
      }
    } else if (response.getOriginalNewCredentialCount() != 0) {
      throw invalid("Lease response cannot carry an original-new credential result");
    }
    if (!allowAbortResolution && response.getAbortResolutionCount() != 0) {
      throw invalid("Only a decision-selected Abort response may carry abort resolution");
    }
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
    AccountGameplayAdmissionAbortResolutionEvidence resolutionMessage =
        optional(response.getAbortResolutionList(), "response.abortResolution");
    AbortResolutionEvidence resolution =
        resolutionMessage == null ? null : fromWireAbortResolution(resolutionMessage);
    if (resolution != null
        && (state != State.ABORTED
            || decisionId != null
            || receipt != null
            || !leaseId(lease).equals(resolution.originalLeaseId())
            || !lease.leaseFence().equals(resolution.originalLeaseFence())
            || !lease.sha256().equals(resolution.originalLeaseSha256())
            || !Objects.equals(cleanupId, resolution.orphanCleanupId()))) {
      throw invalid("Abort resolution differs from the exact unknown terminal operation");
    }
    return new OperationEvidence(lease, state, decisionId, cleanupId, receipt, resolution);
  }

  private static AccountGameplayAdmissionAbortResolutionEvidence toWireAbortResolution(
      AbortResolutionEvidence resolution) {
    return AccountGameplayAdmissionAbortResolutionEvidence.newBuilder()
        .addSchemaVersion(SCHEMA_VERSION)
        .addResolutionUuid(resolution.resolutionId().toString())
        .addOriginalLeaseUuid(resolution.originalLeaseId().toString())
        .addOriginalLeaseFence(positiveDecimal(resolution.originalLeaseFence()))
        .addOriginalLeaseSha256(resolution.originalLeaseSha256())
        .addOrphanCleanupUuid(resolution.orphanCleanupId().toString())
        .addBindingDecisionUuid(resolution.bindingDecisionId().toString())
        .build();
  }

  private static AbortResolutionEvidence fromWireAbortResolution(
      AccountGameplayAdmissionAbortResolutionEvidence message) {
    requireNoUnknownFields(message.getUnknownFields().asMap().isEmpty(), "abort resolution");
    requireSchema(one(message.getSchemaVersionList(), "abortResolution.schemaVersion"));
    return new AbortResolutionEvidence(
        highEntropyUuid(one(message.getResolutionUuidList(), "abortResolution.resolutionId")),
        highEntropyUuid(one(message.getOriginalLeaseUuidList(), "abortResolution.originalLeaseId")),
        positiveCounter(
            one(message.getOriginalLeaseFenceList(), "abortResolution.originalLeaseFence")),
        digest(one(message.getOriginalLeaseSha256List(), "abortResolution.originalLeaseSha256")),
        highEntropyUuid(one(message.getOrphanCleanupUuidList(), "abortResolution.orphanCleanupId")),
        highEntropyUuid(
            one(message.getBindingDecisionUuidList(), "abortResolution.bindingDecisionId")));
  }

  private static OriginalNewGameplayCredentialResult toWireOriginalNewCredential(
      OriginalNewCredential credential) {
    byte[] handle = credential.opaqueRebindHandle();
    try {
      return OriginalNewGameplayCredentialResult.newBuilder()
          .addSchemaVersion(SCHEMA_VERSION)
          .addSignedBoundGsaCompactJwt(credential.signedBoundGsACompactJwt())
          .addOpaqueRebindHandle(ByteString.copyFrom(handle))
          .addLineageUuid(credential.lineageId().toString())
          .addTokenJtiUuid(credential.tokenJti().toString())
          .addTokenSha256(credential.tokenSha256())
          .addIssuanceFence(Long.toString(credential.issuanceFence()))
          .addOriginalLeaseUuid(credential.originalLeaseId().toString())
          .addOriginalLeaseFence(Long.toString(credential.originalLeaseFence()))
          .addOriginalAckReceiptXid(credential.originalAckReceiptXid())
          .addCheckpointsSha256(credential.checkpointsSha256())
          .build();
    } finally {
      Arrays.fill(handle, (byte) 0);
    }
  }

  private static OriginalNewCredential fromWireOriginalNewCredential(
      OriginalNewGameplayCredentialResult wire) {
    requireNoUnknownFields(wire.getUnknownFields().asMap().isEmpty(), "original-new credential");
    int schema = one(wire.getSchemaVersionList(), "originalNew.schemaVersion");
    requireSchema(schema);
    String jwt = compactJwt(one(wire.getSignedBoundGsaCompactJwtList(), "originalNew.compactJwt"));
    byte[] handle = one(wire.getOpaqueRebindHandleList(), "originalNew.rebindHandle").toByteArray();
    if (handle.length != 32) throw invalid("Opaque rebind handle must be exactly 32 bytes");
    try {
      return originalNewCredential(
          jwt,
          handle,
          highEntropyUuid(one(wire.getLineageUuidList(), "originalNew.lineageId")),
          highEntropyUuid(one(wire.getTokenJtiUuidList(), "originalNew.tokenJti")),
          digest(one(wire.getTokenSha256List(), "originalNew.tokenSha256")),
          positiveLongDecimal(
              one(wire.getIssuanceFenceList(), "originalNew.issuanceFence"), "issuanceFence"),
          highEntropyUuid(one(wire.getOriginalLeaseUuidList(), "originalNew.leaseId")),
          positiveLongDecimal(
              one(wire.getOriginalLeaseFenceList(), "originalNew.leaseFence"), "leaseFence"),
          xid(one(wire.getOriginalAckReceiptXidList(), "originalNew.ackReceiptXid")),
          digest(one(wire.getCheckpointsSha256List(), "originalNew.checkpointsSha256")));
    } finally {
      Arrays.fill(handle, (byte) 0);
    }
  }

  private static void validateOriginalNewCredential(
      AccountGameplayAdmissionLeaseEvidence lease,
      OriginalAckReceiptEvidence receipt,
      OriginalNewCredential result) {
    byte[] jwtBytes = compactJwtUtf8(result.signedBoundGsACompactJwt());
    try {
      Map<String, Object> claims = parseCompactJwtClaims(result.signedBoundGsACompactJwt());
      Map<?, ?> carrier = lease.carrier();
      Map<?, ?> scope = object(carrier.get("bindingScope"), "bindingScope");
      Map<?, ?> baseline = object(carrier.get("membershipBaseline"), "membershipBaseline");
      Map<?, ?> tokenIdentity =
          object(carrier.get("tokenIdentityEvidence"), "tokenIdentityEvidence");
      String accountId = string(scope, "accountId");
      String tenantId = string(scope, "tenantId");
      String originalJti = string(tokenIdentity, "tokenJti");
      long issuanceFence = positiveLongValue(carrier.get("issuanceFence"), "issuanceFence");
      long leaseFence = positiveLong(lease.leaseFence());
      byte[] checkpoints = canonicalJsonBytes(carrier.get("outboxCheckpoints"));
      try {
        if (!sha256(jwtBytes).equals(result.tokenSha256())
            || result.lineageId().equals(result.tokenJti())
            || result.tokenJti().toString().equals(originalJti)
            || !leaseId(lease).equals(result.originalLeaseId())
            || leaseFence != result.originalLeaseFence()
            || issuanceFence != result.issuanceFence()
            || !sha256(checkpoints).equals(result.checkpointsSha256())
            || receipt == null
            || !receipt.receiptXid().equals(result.originalAckReceiptXid())
            || !accountId.equals(claims.get("sub"))
            || !accountId.equals(claims.get("accountId"))
            || !tenantId.equals(claims.get("tenantId"))
            || !result.tokenJti().toString().equals(claims.get("jti"))
            || positiveClaim(claims, "tokenGeneration") != 1L
            || positiveClaim(claims, "issuanceFence") != issuanceFence
            || !Objects.equals(carrier.get("authorityTuple"), claims.get("authorityTuple"))
            || !Objects.equals(baseline.get("membershipVersion"), claims.get("membershipVersion"))
            || claims.containsKey("globalRoles")
            || claims.containsKey("scopedRoles")) {
          throw invalid("Original-new credential differs from the exact committed lease binding");
        }
      } finally {
        Arrays.fill(checkpoints, (byte) 0);
      }
    } finally {
      Arrays.fill(jwtBytes, (byte) 0);
    }
  }

  private static Map<String, Object> parseCompactJwtClaims(String compact) {
    String[] parts = compact.split("\\.", -1);
    if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
      throw invalid("Signed bound GSA compact JWT is malformed");
    }
    byte[] payload;
    try {
      payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
    } catch (IllegalArgumentException malformed) {
      throw invalid("Signed bound GSA compact JWT payload is malformed");
    }
    try {
      Map<String, Object> claims = JSON.readValue(payload, Map.class);
      GameSessionAccountDelegationJwtProfileValidator.validateClaims(claims);
      return claims;
    } catch (Exception malformed) {
      throw invalid("Signed bound GSA claims are malformed");
    } finally {
      Arrays.fill(payload, (byte) 0);
    }
  }

  private static byte[] canonicalJsonBytes(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception malformed) {
      throw invalid("Original lease checkpoints are malformed");
    }
  }

  private static UUID leaseId(AccountGameplayAdmissionLeaseEvidence lease) {
    return highEntropyUuid(string(lease.carrier(), "leaseId"));
  }

  private static long positiveLongValue(Object value, String field) {
    try {
      return AccountJwtExactValues.positiveDecimalCounter(value).longValueExact();
    } catch (RuntimeException malformed) {
      throw invalid(field + " is outside its supported positive signed-long range");
    }
  }

  private static long positiveClaim(Map<String, Object> claims, String field) {
    return positiveLongValue(claims.get(field), field);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
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
