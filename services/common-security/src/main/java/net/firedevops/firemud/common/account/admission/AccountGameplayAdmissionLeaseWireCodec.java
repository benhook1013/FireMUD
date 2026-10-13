package net.firedevops.firemud.common.account.admission;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GameplayAdmissionLeaseOperationReadback;
import net.firedevops.firemud.account.v1.GameplayAdmissionLeaseReference;
import net.firedevops.firemud.account.v1.GameplayAdmissionLeaseState;

/**
 * Non-authorizing protobuf wrapper for the existing closed admission evidence codec.
 *
 * <p>No signature, peer, current source, registry, restriction, admission or physical commit is
 * established here. Even a valid COMMITTED readback shape is not an authenticated owner receipt.
 * Counter strings and the original canonical evidence remain unchanged.
 */
public final class AccountGameplayAdmissionLeaseWireCodec {
  private AccountGameplayAdmissionLeaseWireCodec() {}

  public static GameplayAdmissionLeaseReference encodeReference(
      AccountGameplayAdmissionLeaseEvidence evidence) {
    if (evidence == null) throw invalid();
    var reference =
        GameplayAdmissionLeaseReference.newBuilder()
            .setRequestId((String) evidence.carrier().get("requestId"))
            .setEvidenceCanonicalJson(
                ByteString.copyFrom(evidence.canonicalJson(), StandardCharsets.UTF_8))
            .setEvidenceSha256(evidence.sha256())
            .build();
    parseReference(reference);
    return reference;
  }

  /** Parses exact UTF-8 bytes without repairing, reordering or reconstructing any carrier. */
  public static AccountGameplayAdmissionLeaseEvidence parseReference(
      GameplayAdmissionLeaseReference reference) {
    if (reference == null || !reference.getUnknownFields().asMap().isEmpty()) throw invalid();
    requireUuid(reference.getRequestId());
    ByteString bytes = reference.getEvidenceCanonicalJson();
    if (bytes.isEmpty()
        || bytes.size() > AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES
        || !bytes.isValidUtf8()
        || !reference.getEvidenceSha256().matches("[0-9a-f]{64}")) throw invalid();
    try {
      var evidence = AccountGameplayAdmissionLeaseEvidence.parseCanonical(bytes.toStringUtf8());
      if (!reference.getRequestId().equals(evidence.carrier().get("requestId"))
          || !reference.getEvidenceSha256().equals(evidence.sha256())) throw invalid();
      return evidence;
    } catch (RuntimeException failure) {
      // No evidence bytes, token metadata or parser internals are included in wire failures.
      throw invalid();
    }
  }

  /** Encodes terminal presence exactly; absent decision remains unknown, never fabricated. */
  public static GameplayAdmissionLeaseOperationReadback encodeOperation(
      AccountGameplayAdmissionLeaseEvidence evidence,
      GameplayAdmissionLeaseState state,
      UUID bindingDecisionId,
      UUID orphanCleanupId) {
    if (state == null
        || state == GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_UNSPECIFIED
        || state == GameplayAdmissionLeaseState.UNRECOGNIZED) throw invalid();
    var result =
        GameplayAdmissionLeaseOperationReadback.newBuilder()
            .setLease(encodeReference(evidence))
            .setState(state)
            .setPendingOrphanCleanup(
                state == GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED);
    if (bindingDecisionId != null) result.setBindingDecisionId(bindingDecisionId.toString());
    if (orphanCleanupId != null) result.setOrphanCleanupId(orphanCleanupId.toString());
    var operation = result.build();
    validateOperation(operation);
    return operation;
  }

  /** Validates storage readback shape only and returns its exact original evidence. */
  public static AccountGameplayAdmissionLeaseEvidence validateOperation(
      GameplayAdmissionLeaseOperationReadback operation) {
    if (operation == null
        || !operation.hasLease()
        || !operation.getUnknownFields().asMap().isEmpty()) throw invalid();
    var evidence = parseReference(operation.getLease());
    if (operation.hasBindingDecisionId()) requireUuid(operation.getBindingDecisionId());
    if (operation.hasOrphanCleanupId()) requireUuid(operation.getOrphanCleanupId());
    switch (operation.getState()) {
      case GAMEPLAY_ADMISSION_LEASE_STATE_PENDING -> {
        if (operation.hasBindingDecisionId()
            || operation.hasOrphanCleanupId()
            || operation.getPendingOrphanCleanup()) throw invalid();
      }
      case GAMEPLAY_ADMISSION_LEASE_STATE_COMMITTED -> {
        if (!operation.hasBindingDecisionId()
            || operation.hasOrphanCleanupId()
            || operation.getPendingOrphanCleanup()) throw invalid();
      }
      case GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED -> {
        if (!operation.hasOrphanCleanupId() || !operation.getPendingOrphanCleanup())
          throw invalid();
      }
      default -> throw invalid();
    }
    return evidence;
  }

  private static void requireUuid(String text) {
    try {
      UUID value = UUID.fromString(text);
      if (!value.toString().equals(text) || value.version() != 4 || value.variant() != 2)
        throw invalid();
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Malformed Account admission lease wire evidence");
  }
}
