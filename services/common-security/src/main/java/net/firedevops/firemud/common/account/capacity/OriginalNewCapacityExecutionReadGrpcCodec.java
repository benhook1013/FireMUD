package net.firedevops.firemud.common.account.capacity;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalNewCapacityFinalResponse;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewCapacityExecutionRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewCapacityExecutionResponse;

/** Closed original-NEW readback content. Authentication and actual state remain owner duties. */
public final class OriginalNewCapacityExecutionReadGrpcCodec {
  public static final int MAX_REQUEST_BYTES = 320 * 1024;
  public static final int MAX_RESPONSE_BYTES = 3 * 1024 * 1024;

  private OriginalNewCapacityExecutionReadGrpcCodec() {}

  public record Selector(
      TenantCapacityAdmission.Request request, TenantCapacityAdmission.Result reservation) {
    public Selector {
      request.requireCommittable();
      reservation.requireBinding(request);
      if (!(request.context() instanceof TenantCapacityAdmission.PlayerContext)) throw invalid();
    }
  }

  public record Readback(
      OriginalNewCapacityExecution execution,
      String disposition,
      String physicalDigest,
      String receiptDigest,
      byte[] canonicalReadback,
      String currentOwnerId,
      long currentOwnerFence) {
    public Readback {
      if (currentOwnerId == null
          || !currentOwnerId.matches("[A-Za-z0-9._:-]{1,120}")
          || currentOwnerFence < execution.selectedOwnerFence()
          || (currentOwnerFence == execution.selectedOwnerFence()
              && !currentOwnerId.equals(execution.selectedOwnerId()))) throw invalid();
      byte[] expected = execution.terminalEvidence(disposition, physicalDigest, receiptDigest);
      if (!Arrays.equals(expected, canonicalReadback)) throw invalid();
      canonicalReadback = canonicalReadback.clone();
    }

    @Override
    public byte[] canonicalReadback() {
      return canonicalReadback.clone();
    }

    @Override
    public String toString() {
      return "OriginalNewCapacityReadback[redacted]";
    }
  }

  public record HistoricalFinalReadback(
      OriginalNewCapacityExecution execution,
      UUID fenceOperationId,
      String finalSessionSha256,
      long admissionAtMillis,
      long continuityExpiresAtMillis,
      String inventorySnapshotRevision,
      String cohortDigest,
      String barrierTargetDigest) {
    public HistoricalFinalReadback {
      TenantCapacityAdmission.uuid(fenceOperationId);
      if (execution.reservation().state() != TenantCapacityAdmission.State.RESERVED
          || finalSessionSha256 == null
          || !finalSessionSha256.matches("[0-9a-f]{64}")
          || admissionAtMillis <= 0
          || continuityExpiresAtMillis <= admissionAtMillis
          || inventorySnapshotRevision == null
          || !inventorySnapshotRevision.matches("[1-9][0-9]*")
          || cohortDigest == null
          || !cohortDigest.matches("sha256:[0-9a-f]{64}")
          || barrierTargetDigest == null
          || !barrierTargetDigest.matches("sha256:[0-9a-f]{64}")) throw invalid();
    }

    @Override
    public String toString() {
      return "OriginalNewHistoricalFinalReadback[redacted]";
    }
  }

  public static ReadOriginalNewCapacityExecutionRequest toRequest(Selector selector) {
    var wire =
        ReadOriginalNewCapacityExecutionRequest.newBuilder()
            .addAttempt(TenantCapacityAdmissionGrpcCodec.toRequest(selector.request()))
            .addReservation(
                TenantCapacityAdmissionGrpcCodec.toResponse(
                    selector.request(), selector.reservation()))
            .build();
    clean(wire, MAX_REQUEST_BYTES);
    return wire;
  }

  public static Selector parseRequest(ReadOriginalNewCapacityExecutionRequest wire) {
    try {
      clean(wire, MAX_REQUEST_BYTES);
      var request = TenantCapacityAdmissionGrpcCodec.parseRequest(one(wire.getAttemptList()));
      return new Selector(
          request,
          TenantCapacityAdmissionGrpcCodec.requireResponse(
              request, one(wire.getReservationList())));
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  public static ReadOriginalNewCapacityExecutionResponse toResponse(
      Selector selector, Readback result) {
    if (!selector.request().equals(result.execution().request())
        || !selector.reservation().equals(result.execution().reservation())) throw invalid();
    var execution = result.execution();
    var wire =
        ReadOriginalNewCapacityExecutionResponse.newBuilder()
            .addSelector(toRequest(selector))
            .addTransitionId(execution.transitionId().toString())
            .addCandidateDigest(execution.candidateDigest())
            .addSelectedOwnerId(execution.selectedOwnerId())
            .addSelectedOwnerFence(execution.selectedOwnerFence())
            .addCanonicalExecution(ByteString.copyFrom(execution.canonicalBytes()))
            .addDisposition(result.disposition())
            .addPhysicalDigest(result.physicalDigest())
            .addReceiptDigest(result.receiptDigest())
            .addCanonicalReadback(ByteString.copyFrom(result.canonicalReadback()))
            .addCurrentOwnerId(result.currentOwnerId())
            .addCurrentOwnerFence(result.currentOwnerFence())
            .build();
    clean(wire, MAX_RESPONSE_BYTES);
    return wire;
  }

  public static Readback fromResponse(
      Selector selector, ReadOriginalNewCapacityExecutionResponse wire) {
    clean(wire, MAX_RESPONSE_BYTES);
    if (!selector.equals(parseRequest(one(wire.getSelectorList())))) throw invalid();
    var execution =
        new OriginalNewCapacityExecution(
            selector.request(),
            selector.reservation(),
            uuid(one(wire.getTransitionIdList())),
            one(wire.getCandidateDigestList()),
            one(wire.getSelectedOwnerIdList()),
            one(wire.getSelectedOwnerFenceList()));
    if (!Arrays.equals(
        execution.canonicalBytes(), one(wire.getCanonicalExecutionList()).toByteArray()))
      throw invalid();
    return new Readback(
        execution,
        one(wire.getDispositionList()),
        one(wire.getPhysicalDigestList()),
        one(wire.getReceiptDigestList()),
        one(wire.getCanonicalReadbackList()).toByteArray(),
        one(wire.getCurrentOwnerIdList()),
        one(wire.getCurrentOwnerFenceList()));
  }

  public static ReadHistoricalOriginalNewCapacityFinalResponse toHistoricalResponse(
      Selector selector, HistoricalFinalReadback receipt) {
    var execution = receipt.execution();
    if (!selector.request().equals(execution.request())
        || !selector.reservation().equals(execution.reservation())) throw invalid();
    var wire =
        ReadHistoricalOriginalNewCapacityFinalResponse.newBuilder()
            .addSelector(toRequest(selector))
            .addFenceOperationId(receipt.fenceOperationId().toString())
            .addTransitionId(execution.transitionId().toString())
            .addCandidateDigest(execution.candidateDigest())
            .addSelectedOwnerId(execution.selectedOwnerId())
            .addSelectedOwnerFence(execution.selectedOwnerFence())
            .addCanonicalExecution(ByteString.copyFrom(execution.canonicalBytes()))
            .addFinalSessionSha256(receipt.finalSessionSha256())
            .addAdmissionAtMs(receipt.admissionAtMillis())
            .addContinuityExpiresAtMs(receipt.continuityExpiresAtMillis())
            .addInventorySnapshotRevision(receipt.inventorySnapshotRevision())
            .addCohortDigest(receipt.cohortDigest())
            .addBarrierTargetDigest(receipt.barrierTargetDigest())
            .build();
    clean(wire, MAX_RESPONSE_BYTES);
    return wire;
  }

  public static HistoricalFinalReadback fromHistoricalResponse(
      Selector selector, ReadHistoricalOriginalNewCapacityFinalResponse wire) {
    clean(wire, MAX_RESPONSE_BYTES);
    if (!selector.equals(parseRequest(one(wire.getSelectorList())))) throw invalid();
    var execution =
        new OriginalNewCapacityExecution(
            selector.request(),
            selector.reservation(),
            uuid(one(wire.getTransitionIdList())),
            one(wire.getCandidateDigestList()),
            one(wire.getSelectedOwnerIdList()),
            one(wire.getSelectedOwnerFenceList()));
    if (!Arrays.equals(
        execution.canonicalBytes(), one(wire.getCanonicalExecutionList()).toByteArray()))
      throw invalid();
    return new HistoricalFinalReadback(
        execution,
        uuid(one(wire.getFenceOperationIdList())),
        one(wire.getFinalSessionSha256List()),
        one(wire.getAdmissionAtMsList()),
        one(wire.getContinuityExpiresAtMsList()),
        one(wire.getInventorySnapshotRevisionList()),
        one(wire.getCohortDigestList()),
        one(wire.getBarrierTargetDigestList()));
  }

  private static <T> T one(List<T> values) {
    if (values.size() != 1) throw invalid();
    return values.getFirst();
  }

  private static void clean(Message wire, int maximum) {
    if (wire == null
        || wire.getSerializedSize() <= 0
        || wire.getSerializedSize() > maximum
        || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
  }

  private static UUID uuid(String text) {
    UUID id = UUID.fromString(text);
    if (!id.toString().equals(text)) throw invalid();
    return TenantCapacityAdmission.uuid(id);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Exact original NEW capacity read required");
  }
}
