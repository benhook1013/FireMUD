package net.firedevops.firemud.common.account.sourceintake;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Closed transport correlation and the complete immutable Account settlement response. */
public final class SelectedOwnerIntakeSettlementEvidence {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_REQUEST_BYTES =
      SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES + 64 * 1024;
  public static final int MAX_RESPONSE_BYTES =
      AccountSelectedOwnerIntakeSettlementReceipt.MAX_BYTES + MAX_REQUEST_BYTES + 64 * 1024;

  private SelectedOwnerIntakeSettlementEvidence() {}

  /** The correlation is independent from the retained operation, fence and intake identities. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID transportRequestId,
      SelectedOwnerIntakeAuthorizationBinding authorizationBinding) {
    public Request {
      if (schemaVersion != SCHEMA_VERSION
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || authorizationBinding == null
          || authorizationBinding.owner() != Owner.AUTOMATION_SCRIPTING
          || !targetNamespace.equals(authorizationBinding.targetNamespace())
          || !"account-automation-intake-authorization/v1".equals(authorizationBinding.schema())
          || !"AUTOMATION_INTAKE_RETENTION".equals(authorizationBinding.purpose())) {
        throw invalid("Canonical same-namespace Automation authorization required");
      }
      DraftAuthorizationFenceBinding.requireUuid(transportRequestId);
      if (transportRequestId.equals(authorizationBinding.operationId())
          || transportRequestId.equals(authorizationBinding.fenceId())
          || transportRequestId.equals(authorizationBinding.intakeRequestId())) {
        throw invalid("Independent settlement transport correlation required");
      }
      byte[] bytes = authorizationBinding.canonicalBytes();
      if (bytes.length == 0
          || bytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES
          || !DraftAuthorizationFenceBinding.digest(bytes).equals(authorizationBinding.digest())) {
        throw invalid("Canonical original authorization is required");
      }
      var decoded = SelectedOwnerIntakeAuthorizationBinding.fromStored(bytes);
      if (!Arrays.equals(bytes, decoded.canonicalBytes())
          || !authorizationBinding.digest().equals(decoded.digest())) {
        throw invalid("Canonical original authorization is required");
      }
    }

    public static Request create(
        String namespace, SelectedOwnerIntakeAuthorizationBinding authorizationBinding) {
      Objects.requireNonNull(authorizationBinding, "Original authorization is required");
      UUID correlation;
      do {
        correlation = UUID.randomUUID();
      } while (correlation.equals(authorizationBinding.operationId())
          || correlation.equals(authorizationBinding.fenceId())
          || correlation.equals(authorizationBinding.intakeRequestId()));
      return new Request(SCHEMA_VERSION, namespace, correlation, authorizationBinding);
    }

    public byte[] authorizationBindingBytes() {
      return authorizationBinding.canonicalBytes();
    }

    public String authorizationBindingDigest() {
      return authorizationBinding.digest();
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof Request other
          && schemaVersion == other.schemaVersion
          && targetNamespace.equals(other.targetNamespace)
          && transportRequestId.equals(other.transportRequestId)
          && Arrays.equals(authorizationBindingBytes(), other.authorizationBindingBytes());
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          schemaVersion,
          targetNamespace,
          transportRequestId,
          Arrays.hashCode(authorizationBindingBytes()));
    }
  }

  /** Full settlement receipt paired with the exact request that Account echoed. */
  public record Result(Request request, AccountSelectedOwnerIntakeSettlementReceipt receipt) {
    public Result {
      Objects.requireNonNull(request, "Settlement request is required");
      Objects.requireNonNull(receipt, "Complete Account settlement receipt is required");
      byte[] receiptBytes = receipt.canonicalBytes();
      if (receiptBytes.length == 0
          || receiptBytes.length > AccountSelectedOwnerIntakeSettlementReceipt.MAX_BYTES
          || !DraftAuthorizationFenceBinding.digest(receiptBytes).equals(receipt.digest())
          || !request.targetNamespace().equals(receipt.targetNamespace())
          || !Arrays.equals(
              request.authorizationBindingBytes(), receipt.authorizationBinding().canonicalBytes())
          || !request
              .authorizationBindingDigest()
              .equals(receipt.authorizationBinding().digest())) {
        throw invalid("Settlement receipt differs from the exact original authorization");
      }
    }
  }

  private static IllegalArgumentException invalid(String description) {
    return new IllegalArgumentException(description);
  }
}
