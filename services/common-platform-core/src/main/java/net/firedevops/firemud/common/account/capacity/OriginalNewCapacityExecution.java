package net.firedevops.firemud.common.account.capacity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Exact original-NEW execution binding. Content is not an Account grant or owner attestation. */
public record OriginalNewCapacityExecution(
    TenantCapacityAdmission.Request request,
    TenantCapacityAdmission.Result reservation,
    UUID transitionId,
    String candidateDigest,
    String selectedOwnerId,
    long selectedOwnerFence) {
  public OriginalNewCapacityExecution {
    requireOriginalNewRequest(request);
    Objects.requireNonNull(reservation).requireBinding(request);
    TenantCapacityAdmission.uuid(transitionId);
    if (selectedOwnerId == null
        || selectedOwnerId.isBlank()
        || !selectedOwnerId.equals(selectedOwnerId.trim())
        || selectedOwnerId.codePointCount(0, selectedOwnerId.length()) > 120
        || selectedOwnerId.codePoints().anyMatch(cp -> cp == 0 || (cp >= 0xD800 && cp <= 0xDFFF)))
      throw new IllegalArgumentException("Exact selected owner instance required");
    if ((reservation.state() != TenantCapacityAdmission.State.RESERVED
            && reservation.state() != TenantCapacityAdmission.State.FINALIZED)
        || !request.identity().admissionId().equals(transitionId)
        || selectedOwnerFence <= 0
        || candidateDigest == null
        || !candidateDigest.matches("sha256:[0-9a-f]{64}"))
      throw new IllegalArgumentException("Exact original NEW capacity execution required");
  }

  public static void requireOriginalNewRequest(TenantCapacityAdmission.Request request) {
    Objects.requireNonNull(request).requireCommittable();
    if (!(request.context() instanceof TenantCapacityAdmission.PlayerContext player)
        || !"SHARED".equals(player.playableStateScope())
        || request.operation() != TenantCapacityAdmission.Operation.CAPACITY_CREATING
        || !Long.valueOf(1).equals(request.capacityDelta())
        || !request.priorShape().equals(new TenantCapacityAdmission.Shape(0, 0, 0))
        || !request.requestedShape().equals(new TenantCapacityAdmission.Shape(1, 0, 0))) {
      throw new IllegalArgumentException("Exact original NEW capacity request required");
    }
  }

  /** Canonical JSON escapes runtime identity text; the attempt includes exact Account evidence. */
  public byte[] canonicalBytes() {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("schema", "gs-original-new-capacity-execution/v1");
    fields.put("tenantId", request.identity().tenantId().toString());
    fields.put("requestId", request.identity().requestId().toString());
    fields.put("admissionId", request.identity().admissionId().toString());
    fields.put("admissionAttemptId", request.admissionAttemptId().toString());
    fields.put("capacityRearmFence", Long.toString(request.capacityRearmFence()));
    fields.put("mutationDigest", request.mutationDigest());
    fields.put("reservationId", reservation.reservationId().toString());
    fields.put("reservationFence", Long.toString(reservation.reservationFence()));
    // Floor milliseconds, never extend Account's microsecond expiry at Redis's TIME boundary.
    fields.put("expiresAtMillis", Long.toString(reservation.expiresAt().toEpochMilli()));
    fields.put("expiresAt", reservation.expiresAt().toString());
    fields.put("snapshotIdentity", reservation.snapshotIdentity());
    fields.put("evaluationIdentity", reservation.evaluationIdentity());
    fields.put("transitionId", transitionId.toString());
    fields.put("candidateDigest", candidateDigest);
    fields.put("selectedOwnerId", selectedOwnerId);
    fields.put("selectedOwnerFence", Long.toString(selectedOwnerFence));
    fields.put(
        "canonicalAttemptPayload",
        Base64.getEncoder().encodeToString(CapacityAdmissionDigest.preimage(request)));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(fields));
    } catch (java.io.IOException malformed) {
      throw new IllegalArgumentException("Invalid capacity execution", malformed);
    }
  }

  public String digest() {
    return "sha256:" + sha256(canonicalBytes());
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  public byte[] terminalEvidence(String disposition, String physicalDigest, String receiptDigest) {
    if (!("COMMITTED".equals(disposition) || "NOT_EXECUTED_AFTER_AUTHORIZATION".equals(disposition))
        || physicalDigest == null
        || !physicalDigest.matches("sha256:[0-9a-f]{64}")
        || receiptDigest == null
        || !receiptDigest.matches("sha256:[0-9a-f]{64}"))
      throw new IllegalArgumentException("Exact terminal owner evidence required");
    var output = new java.io.ByteArrayOutputStream();
    for (byte[] item :
        new byte[][] {
          "gs-original-new-capacity-result/v1".getBytes(StandardCharsets.US_ASCII),
          digest().getBytes(StandardCharsets.US_ASCII),
          disposition.getBytes(StandardCharsets.US_ASCII),
          physicalDigest.getBytes(StandardCharsets.US_ASCII),
          receiptDigest.getBytes(StandardCharsets.US_ASCII)
        }) {
      output.writeBytes(Integer.toString(item.length).getBytes(StandardCharsets.US_ASCII));
      output.write(':');
      output.writeBytes(item);
    }
    return output.toByteArray();
  }

  @Override
  public String toString() {
    return "OriginalNewCapacityExecution[redacted]";
  }
}
