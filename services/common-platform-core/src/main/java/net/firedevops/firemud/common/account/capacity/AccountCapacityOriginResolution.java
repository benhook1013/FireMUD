package net.firedevops.firemud.common.account.capacity;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result;

/**
 * Exact Account origin decision content, not authentication or execution proof. A receiver must
 * authenticate Account and compare the complete canonical request to its retained OPEN origin.
 * NO_ACCEPT_RETAINED is a durable refusal excluding a future commit at this rearm fence; absence is
 * UNKNOWN. Neither outcome releases an existing reservation or supplies a Game Session CLOSED
 * proof. Historical terminal quarantine is unchanged.
 */
public record AccountCapacityOriginResolution(
    Request request,
    Status status,
    Result reservation,
    UUID denialId,
    AccountCapacityOriginContinuation.Policy continuationPolicy) {
  public enum Status {
    RESERVATION_RETAINED,
    NO_ACCEPT_RETAINED,
    UNKNOWN
  }

  /** Convenience for decisions without a reservation; accepted results require retained policy. */
  public AccountCapacityOriginResolution(
      Request request, Status status, Result reservation, UUID denialId) {
    this(
        request,
        status,
        reservation,
        denialId,
        status == Status.RESERVATION_RETAINED
            ? null
            : AccountCapacityOriginContinuation.preAccount(request));
  }

  public AccountCapacityOriginResolution {
    Objects.requireNonNull(request).requireStructurallyValid();
    Objects.requireNonNull(status);
    Objects.requireNonNull(continuationPolicy);
    if (status != Status.RESERVATION_RETAINED
        && !AccountCapacityOriginContinuation.preAccount(request).equals(continuationPolicy))
      throw new IllegalArgumentException("Exact pre-Account policy required");
    switch (status) {
      case RESERVATION_RETAINED -> {
        Objects.requireNonNull(reservation).requireBinding(request);
        if (!AccountCapacityOriginContinuation.retained(
                request, reservation, continuationPolicy.deadline())
            .equals(continuationPolicy))
          throw new IllegalArgumentException("Exact retained policy required");
        if (denialId != null) throw new IllegalArgumentException("Contradictory origin decision");
      }
      case NO_ACCEPT_RETAINED -> {
        TenantCapacityAdmission.uuid(denialId);
        if (reservation != null)
          throw new IllegalArgumentException("Contradictory origin decision");
      }
      case UNKNOWN -> {
        if (reservation != null || denialId != null)
          throw new IllegalArgumentException("Unknown has no fabricated authority");
      }
    }
  }

  /** Versioned length-prefixed encoding; includes every exact reservation field. */
  public byte[] canonicalBytes() {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      field(out, "account-capacity-origin-resolution/v1");
      byte[] requestBytes = CapacityAdmissionDigest.preimage(request);
      out.writeInt(requestBytes.length);
      out.write(requestBytes);
      field(out, status.name());
      field(out, continuationPolicy.deadline().toString());
      out.writeInt(continuationPolicy.maxObservations());
      if (status == Status.NO_ACCEPT_RETAINED) field(out, denialId.toString());
      if (status == Status.RESERVATION_RETAINED) {
        field(out, reservation.identity().tenantId().toString());
        field(out, reservation.identity().requestId().toString());
        field(out, reservation.identity().admissionId().toString());
        field(out, reservation.admissionAttemptId().toString());
        out.writeLong(reservation.capacityRearmFence());
        field(out, reservation.mutationDigest());
        field(out, reservation.reservationId().toString());
        out.writeLong(reservation.reservationFence());
        field(out, reservation.expiresAt().toString());
        field(out, reservation.state().name());
        field(out, reservation.snapshotIdentity());
        field(out, reservation.evaluationIdentity());
      }
      out.flush();
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("In-memory encoding failed", impossible);
    }
  }

  public String digest() {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void field(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  @Override
  public String toString() {
    return "AccountCapacityOriginResolution[redacted]";
  }
}
