package net.firedevops.firemud.common.account.sourceintake;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Immutable evidence for the distinct selected-owner Account authorization producer transport. */
public final class SelectedOwnerIntakeAuthorizationProducerEvidence {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_SELECTION_BYTES = SelectedOwnerIntakeSourceReadScope.MAX_BYTES;

  private SelectedOwnerIntakeAuthorizationProducerEvidence() {}

  /** Closed producer request. The selected Draft bytes are retained and returned exactly. */
  public static final class Request {
    private final int schemaVersion;
    private final String targetNamespace;
    private final UUID transportRequestId;
    private final UUID intakeRequestId;
    private final Owner owner;
    private final byte[] selectedDraftBinding;

    public Request(
        int schemaVersion,
        String targetNamespace,
        UUID transportRequestId,
        UUID intakeRequestId,
        Owner owner,
        byte[] selectedDraftBinding) {
      if (schemaVersion != SCHEMA_VERSION
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || !isSupportedOwner(owner)) {
        throw invalid();
      }
      DraftAuthorizationFenceBinding.requireUuid(transportRequestId);
      DraftAuthorizationFenceBinding.requireUuid(intakeRequestId);
      if (transportRequestId.equals(intakeRequestId)) throw invalid();
      if (selectedDraftBinding == null
          || selectedDraftBinding.length == 0
          || selectedDraftBinding.length > MAX_SELECTION_BYTES) {
        throw invalid();
      }
      this.schemaVersion = schemaVersion;
      this.targetNamespace = targetNamespace;
      this.transportRequestId = transportRequestId;
      this.intakeRequestId = intakeRequestId;
      this.owner = owner;
      this.selectedDraftBinding = selectedDraftBinding.clone();
      DraftCommitBinding selected = decodeSelection(this.selectedDraftBinding);
      if (transportRequestId.equals(selected.requestId())
          || transportRequestId.equals(selected.commitId())) {
        throw invalid();
      }
    }

    public static Request create(
        String namespace, UUID intakeRequestId, Owner owner, DraftCommitBinding selected) {
      Objects.requireNonNull(selected, "selected Draft binding is required");
      UUID correlation;
      do {
        correlation = UUID.randomUUID();
      } while (correlation.equals(intakeRequestId)
          || correlation.equals(selected.requestId())
          || correlation.equals(selected.commitId()));
      return new Request(
          SCHEMA_VERSION,
          namespace,
          correlation,
          intakeRequestId,
          owner,
          selected.canonicalBytes());
    }

    public int schemaVersion() {
      return schemaVersion;
    }

    public String targetNamespace() {
      return targetNamespace;
    }

    public UUID transportRequestId() {
      return transportRequestId;
    }

    public UUID intakeRequestId() {
      return intakeRequestId;
    }

    public Owner owner() {
      return owner;
    }

    public byte[] selectedDraftBinding() {
      return selectedDraftBinding.clone();
    }

    public DraftCommitBinding selected() {
      return decodeSelection(selectedDraftBinding);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Request request)) return false;
      return schemaVersion == request.schemaVersion
          && targetNamespace.equals(request.targetNamespace)
          && transportRequestId.equals(request.transportRequestId)
          && intakeRequestId.equals(request.intakeRequestId)
          && owner == request.owner
          && Arrays.equals(selectedDraftBinding, request.selectedDraftBinding);
    }

    @Override
    public int hashCode() {
      int result =
          Objects.hash(schemaVersion, targetNamespace, transportRequestId, intakeRequestId, owner);
      return 31 * result + Arrays.hashCode(selectedDraftBinding);
    }

    @Override
    public String toString() {
      return "SelectedOwnerIntakeAuthorizationProducer.Request[redacted]";
    }
  }

  /** Exact immutable authorization returned by Account, including its canonical digest. */
  public static final class Result {
    private final Request request;
    private final SelectedOwnerIntakeAuthorizationBinding binding;
    private final String digest;

    public Result(Request request, SelectedOwnerIntakeAuthorizationBinding binding, String digest) {
      this.request = Objects.requireNonNull(request, "producer request is required");
      this.binding = Objects.requireNonNull(binding, "finalized authorization binding is required");
      this.digest = Objects.requireNonNull(digest, "authorization digest is required");
      requireBindingMatches(request, binding);
      if (!binding.digest().equals(digest)) throw invalid();
    }

    public Request request() {
      return request;
    }

    public SelectedOwnerIntakeAuthorizationBinding binding() {
      return binding;
    }

    public String digest() {
      return digest;
    }

    public byte[] canonicalBindingBytes() {
      return binding.canonicalBytes();
    }

    @Override
    public String toString() {
      return "SelectedOwnerIntakeAuthorizationProducer.Result[redacted]";
    }
  }

  static void requireBindingMatches(
      Request request, SelectedOwnerIntakeAuthorizationBinding binding) {
    if (binding.owner() != request.owner()
        || !binding.targetNamespace().equals(request.targetNamespace())
        || !binding.intakeRequestId().equals(request.intakeRequestId())
        || !Arrays.equals(binding.selected().canonicalBytes(), request.selectedDraftBinding())
        || !binding
            .intendedReader()
            .equals(intendedReader(request.owner(), request.targetNamespace()))
        || !binding.purpose().equals(purpose(request.owner()))) {
      throw invalid();
    }
  }

  static boolean isSupportedOwner(Owner owner) {
    return owner == Owner.ENTITY_MANAGEMENT || owner == Owner.AUTOMATION_SCRIPTING;
  }

  static String intendedReader(Owner owner, String namespace) {
    String workload =
        switch (owner) {
          case ENTITY_MANAGEMENT -> "entity-management-service";
          case AUTOMATION_SCRIPTING -> "automation-scripting-service";
          default -> throw invalid();
        };
    return "spiffe://firemud/ns/" + namespace + "/sa/" + workload;
  }

  static String purpose(Owner owner) {
    return switch (owner) {
      case ENTITY_MANAGEMENT -> "ENTITY_INTAKE_RETENTION";
      case AUTOMATION_SCRIPTING -> "AUTOMATION_INTAKE_RETENTION";
      default -> throw invalid();
    };
  }

  private static DraftCommitBinding decodeSelection(byte[] bytes) {
    try {
      String json = new String(bytes, StandardCharsets.UTF_8);
      DraftCommitBinding selected =
          DraftCommitBinding.fromStored(json, DraftAuthorizationFenceBinding.digest(bytes));
      if (!Arrays.equals(bytes, selected.canonicalBytes())) throw invalid();
      return selected;
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Invalid selected-owner intake authorization producer evidence");
  }
}
