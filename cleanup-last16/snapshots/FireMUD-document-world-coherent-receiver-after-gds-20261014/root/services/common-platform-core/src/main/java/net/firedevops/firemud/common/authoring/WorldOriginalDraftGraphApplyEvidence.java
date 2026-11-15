package net.firedevops.firemud.common.authoring;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Immutable original apply/recovery values; decoding supplies no workload authorization. */
public final class WorldOriginalDraftGraphApplyEvidence {
  // Preserve the original Account producer's admitted byte boundary.
  public static final int MAX_BINDING_BYTES = AccountOriginalDraftOrderGrpcCodec.MAX_BINDING_BYTES;
  // World readback includes framed input, base64 operation material and the retained graph.
  public static final int MAX_OWNER_READBACK_BYTES = 16 * 1024 * 1024;

  private WorldOriginalDraftGraphApplyEvidence() {}

  public record Request(int schemaVersion, String targetNamespace, byte[] originalAccountBinding) {
    public static final int SCHEMA_VERSION = 1;

    public Request {
      if (schemaVersion != SCHEMA_VERSION || !GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException(
            "Canonical original World apply schema and namespace required");
      }
      requireBytes(originalAccountBinding, MAX_BINDING_BYTES);
      originalAccountBinding = originalAccountBinding.clone();
      var account = DraftAuthorizationFenceBinding.fromStored(originalAccountBinding);
      var draft = draftBinding(account);
      if (!List.of(
                  DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
                  DraftAuthorizationFenceBinding.Owner.WORLD)
              .equals(account.requiredOwners())
          || !List.of(
                  DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                  DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE)
              .equals(draft.requiredOwners())
          || !Arrays.equals(account.normalizedInput(), draft.canonicalBytes())
          || !account.requestId().equals(draft.requestId())
          || !account.commitId().equals(draft.commitId())
          || !account.tenantId().equals(draft.target().canonicalTenantId())
          || !account.versionId().equals(draft.target().canonicalVersionId())) {
        throw new IllegalArgumentException(
            "Original World apply requires the exact complete GD+World binding");
      }
    }

    public static Request create(String namespace, byte[] binding) {
      return new Request(SCHEMA_VERSION, namespace, binding);
    }

    @Override
    public byte[] originalAccountBinding() {
      return originalAccountBinding.clone();
    }

    public DraftAuthorizationFenceBinding accountBinding() {
      return DraftAuthorizationFenceBinding.fromStored(originalAccountBinding);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request that
          && schemaVersion == that.schemaVersion
          && targetNamespace.equals(that.targetNamespace)
          && Arrays.equals(originalAccountBinding, that.originalAccountBinding);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(schemaVersion, targetNamespace)
          + Arrays.hashCode(originalAccountBinding);
    }
  }

  public record Result(
      Request request, DraftAuthorizationFenceBinding.OwnerReadback ownerReadback) {
    public Result {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(ownerReadback, "ownerReadback");
      requireBytes(ownerReadback.canonicalBytes(), MAX_OWNER_READBACK_BYTES);
      if (ownerReadback.owner() != DraftAuthorizationFenceBinding.Owner.WORLD
          || ownerReadback.outcome() != DraftAuthorizationFenceBinding.Outcome.COMMITTED) {
        throw new IllegalArgumentException(
            "World original apply requires COMMITTED/APPLIED evidence");
      }
      // Internal structural correlation only. This creates no Account read permission or RPC.
      new WorldDraftTerminalReadEvidence(
          WorldDraftTerminalReadEvidence.Request.create(
              request.targetNamespace(), request.originalAccountBinding()),
          Optional.of(ownerReadback));
    }

    public String resultIdentity() {
      try {
        // Schema has already passed the single normative committed-result validator above.
        String schema =
            new tools.jackson.databind.ObjectMapper()
                .readTree(ownerReadback.result())
                .get("schema")
                .textValue();
        return schema + ":" + request.accountBinding().operationId();
      } catch (RuntimeException invalid) {
        throw new IllegalStateException("Validated World result could not be decoded", invalid);
      }
    }

    public List<AppliedEpoch> appliedEpochs() {
      return draftBinding(request.accountBinding())
          .affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT)
          .stream()
          .map(
              unit ->
                  new AppliedEpoch(
                      unit.aggregateType(),
                      unit.aggregateId(),
                      unit.scopeType(),
                      unit.scopeId(),
                      unit.expectedEpoch(),
                      new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
          .toList();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Result that
          && request.equals(that.request)
          && Arrays.equals(ownerReadback.canonicalBytes(), that.ownerReadback.canonicalBytes());
    }

    @Override
    public int hashCode() {
      return 31 * request.hashCode() + Arrays.hashCode(ownerReadback.canonicalBytes());
    }
  }

  private static DraftCommitBinding draftBinding(DraftAuthorizationFenceBinding account) {
    return DraftCommitBinding.fromStored(
        new String(account.gameDesignBinding(), StandardCharsets.UTF_8), account.inputDigest());
  }

  static void requireBytes(byte[] bytes, int maximum) {
    if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
      throw new IllegalArgumentException(
          "Bounded nonempty canonical original World apply bytes required");
    }
  }
}
