package net.firedevops.firemud.common.publication;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact read correlation and public inventory for an Entity or Automation intake. */
public record SelectedOwnerWorldInventoryReadEvidence(
    Request request, WorldSelectedPublicationArtifactInventoryEvidence inventory) {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_INTAKE_AUTHORIZATION_BYTES =
      SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES;
  public static final int MAX_PUBLIC_INVENTORY_BYTES = 8 * 1024 * 1024;

  /** Includes the 16 MiB owner binding, duplicated bounded freeze order, inventory, and framing. */
  public static final int MAX_WIRE_BYTES = 32 * 1024 * 1024;

  public SelectedOwnerWorldInventoryReadEvidence {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(inventory, "inventory");
    if (!request.freezeEvidence().equals(inventory.freezeEvidence())
        || inventory.canonicalBytes().length == 0
        || inventory.canonicalBytes().length > MAX_PUBLIC_INVENTORY_BYTES) {
      throw new IllegalArgumentException(
          "World owner inventory differs from exact bounded request");
    }
  }

  /** A new read UUID is independent from both Account intake and World intake correlations. */
  public static Request create(
      String namespace,
      SelectedOwnerIntakeAuthorizationBinding binding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    UUID readRequestId;
    do {
      readRequestId = UUID.randomUUID();
    } while (Request.collides(readRequestId, binding, freezeEvidence));
    return new Request(SCHEMA_VERSION, namespace, readRequestId, binding, freezeEvidence);
  }

  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      SelectedOwnerIntakeAuthorizationBinding authorizationBinding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    public Request {
      if (schemaVersion != SCHEMA_VERSION
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || readRequestId == null
          || new UUID(0L, 0L).equals(readRequestId)) {
        throw new IllegalArgumentException("Canonical selected-owner World read identity required");
      }
      Objects.requireNonNull(authorizationBinding, "authorizationBinding");
      Objects.requireNonNull(freezeEvidence, "freezeEvidence");
      var scope = authorizationBinding.content().scope();
      var freeze = freezeEvidence.request();
      var selected = freeze.accountBinding().input().selection().selectedCommit();
      var publicationBinding = freeze.accountBinding();
      if (!targetNamespace.equals(scope.targetNamespace())
          || !targetNamespace.equals(freeze.targetNamespace())
          || !scope.selected().target().canonicalTenantId().equals(freeze.canonicalTenantId())
          || !scope.selected().target().canonicalVersionId().equals(freeze.canonicalVersionId())
          || !java.util.Arrays.equals(scope.selected().canonicalBytes(), selected.canonicalBytes())
          || collides(readRequestId, authorizationBinding, freezeEvidence)
          || readRequestId.equals(publicationBinding.operationId())
          || readRequestId.equals(publicationBinding.fenceId())) {
        throw new IllegalArgumentException(
            "Selected-owner source binding differs from exact World freeze selection");
      }
      String expectedPurpose =
          authorizationBinding.owner() == Owner.ENTITY_MANAGEMENT
              ? "ENTITY_INTAKE_RETENTION"
              : "AUTOMATION_INTAKE_RETENTION";
      if (!authorizationBinding.purpose().equals(expectedPurpose)
          || !authorizationBinding
              .intendedReader()
              .equals(ownerIntendedReader(authorizationBinding.owner(), targetNamespace))) {
        throw new IllegalArgumentException(
            "Original selected-owner recipient or retention purpose differs");
      }
      if (authorizationBinding.canonicalBytes().length > MAX_INTAKE_AUTHORIZATION_BYTES) {
        throw new IllegalArgumentException("Selected-owner authorization binding exceeds limit");
      }
    }

    public static Request create(
        String namespace,
        SelectedOwnerIntakeAuthorizationBinding binding,
        WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
      return SelectedOwnerWorldInventoryReadEvidence.create(namespace, binding, freezeEvidence);
    }

    public String closureReadPurpose() {
      return authorizationBinding.owner() == Owner.ENTITY_MANAGEMENT
          ? "ENTITY_INTAKE_WORLD_CLOSURE_READ"
          : "AUTOMATION_INTAKE_WORLD_CLOSURE_READ";
    }

    private static String ownerIntendedReader(Owner owner, String namespace) {
      String workload =
          owner == Owner.ENTITY_MANAGEMENT
              ? "entity-management-service"
              : "automation-scripting-service";
      return "spiffe://firemud/ns/" + namespace + "/sa/" + workload;
    }

    private static boolean collides(
        UUID candidate,
        SelectedOwnerIntakeAuthorizationBinding binding,
        WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
      var account = freezeEvidence.request().accountBinding();
      var acknowledgement = freezeEvidence.acknowledgement();
      return candidate.equals(binding.operationId())
          || candidate.equals(binding.fenceId())
          || candidate.equals(binding.intakeRequestId())
          || candidate.equals(account.operationId())
          || candidate.equals(account.fenceId())
          || candidate.equals(acknowledgement.intakeRequestId())
          || candidate.equals(acknowledgement.publicationFence());
    }
  }
}
