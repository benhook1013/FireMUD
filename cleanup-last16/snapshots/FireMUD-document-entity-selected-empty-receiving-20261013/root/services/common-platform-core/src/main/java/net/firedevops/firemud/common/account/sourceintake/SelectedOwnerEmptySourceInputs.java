package net.firedevops.firemud.common.account.sourceintake;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigOwnerSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;

/**
 * Exact local inputs for composing an approved fresh empty Entity or Automation intake.
 *
 * <p>This value binds the supplied original Account owner authorization to the exact World
 * inventory response and the matching typed authored declaration. It is integrity/input binding
 * only: its inputs do not authenticate either producer, establish current or through-commit Account
 * protection, census the complete owner database, or produce an owner receipt.
 */
public final class SelectedOwnerEmptySourceInputs {
  private final SelectedOwnerIntakeAuthorizationBinding authorizationBinding;
  private final SelectedOwnerWorldInventoryReadEvidence worldInventoryReadEvidence;
  private final SelectedOwnerIntakeSourceContent sourceContent;
  private final TemplateConfigOwnerSourceInventoryDeclaration ownerSourceInventoryDeclaration;

  public SelectedOwnerEmptySourceInputs(
      SelectedOwnerIntakeAuthorizationBinding authorizationBinding,
      SelectedOwnerWorldInventoryReadEvidence worldInventoryReadEvidence) {
    this.authorizationBinding =
        Objects.requireNonNull(authorizationBinding, "authorizationBinding");
    this.worldInventoryReadEvidence =
        Objects.requireNonNull(worldInventoryReadEvidence, "worldInventoryReadEvidence");
    this.sourceContent = authorizationBinding.content();
    requireExactWorldBinding(authorizationBinding, worldInventoryReadEvidence);
    requireSupportedWorldClosure(authorizationBinding.owner(), worldInventoryReadEvidence);
    this.ownerSourceInventoryDeclaration =
        requireOwnerDeclaration(authorizationBinding.owner(), sourceContent.templateConfigSource());
    requireNoOwnerTemplateReferences(
        authorizationBinding.owner(), sourceContent.templateConfigSource());
  }

  /** Returns the exact supplied original Account owner authorization binding. */
  public SelectedOwnerIntakeAuthorizationBinding authorizationBinding() {
    return authorizationBinding;
  }

  /** Returns the exact supplied World response, including its original read request echo. */
  public SelectedOwnerWorldInventoryReadEvidence worldInventoryReadEvidence() {
    return worldInventoryReadEvidence;
  }

  /** Returns the immutable source content carried by the original authorization binding. */
  public SelectedOwnerIntakeSourceContent sourceContent() {
    return sourceContent;
  }

  /** Returns the original typed owner declaration and its unchanged source revision provenance. */
  public TemplateConfigOwnerSourceInventoryDeclaration ownerSourceInventoryDeclaration() {
    return ownerSourceInventoryDeclaration;
  }

  private static void requireExactWorldBinding(
      SelectedOwnerIntakeAuthorizationBinding authorization,
      SelectedOwnerWorldInventoryReadEvidence worldEvidence) {
    var request = worldEvidence.request();
    var echoedAuthorization = request.authorizationBinding();
    if (!Arrays.equals(authorization.canonicalBytes(), echoedAuthorization.canonicalBytes())
        || !authorization.digest().equals(echoedAuthorization.digest())) {
      throw new IllegalArgumentException(
          "World inventory read does not echo the exact original owner authorization");
    }

    var scope = authorization.content().scope();
    var freeze = request.freezeEvidence().request();
    var selected = authorization.selected();
    var echoedSelected = freeze.accountBinding().input().selection().selectedCommit();
    var publicInventory = worldEvidence.inventory().publicEvidence();
    var publicOwner = publicInventory.ownerScope();
    if (!authorization.targetNamespace().equals(request.targetNamespace())
        || !scope.targetNamespace().equals(request.targetNamespace())
        || !request.targetNamespace().equals(freeze.targetNamespace())
        || !request.targetNamespace().equals(publicOwner.targetNamespace())
        || !selected.target().canonicalTenantId().equals(freeze.canonicalTenantId())
        || !selected.target().canonicalVersionId().equals(freeze.canonicalVersionId())
        || !selected.target().canonicalTenantId().equals(publicOwner.canonicalTenantId())
        || !selected.target().canonicalVersionId().equals(publicOwner.canonicalVersionId())
        || !Arrays.equals(selected.canonicalBytes(), echoedSelected.canonicalBytes())
        || !selected.digest().equals(echoedSelected.digest())
        || !Arrays.equals(
            selected.canonicalBytes(), request.authorizationBinding().selected().canonicalBytes())
        || !selected.digest().equals(request.authorizationBinding().selected().digest())
        || !selected
            .commitId()
            .toString()
            .equals(publicInventory.selectedApplication().appliedCommitId())
        || !selected.digest().equals(publicInventory.selectedApplication().bindingDigest())) {
      throw new IllegalArgumentException(
          "World inventory read differs from the exact selected owner scope or commit");
    }
  }

  private static void requireSupportedWorldClosure(
      Owner owner, SelectedOwnerWorldInventoryReadEvidence worldEvidence) {
    WorldSelectedPublicationArtifactInventoryEvidence.PublicEvidence inventory =
        worldEvidence.inventory().publicEvidence();
    var sourceModel = inventory.sourceModel();
    if (!WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA.equals(
            inventory.schema())
        || inventory.schemaVersion()
            != WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION
        || !WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL.equals(
            sourceModel.modelId())
        || sourceModel.graphSchemaVersion()
            != WorldSelectedPublicationArtifactInventoryEvidence
                .INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION
        || sourceModel.inboundSourceClosure() == null) {
      throw new IllegalArgumentException(
          "Selected World inventory requires the supported complete v2 inbound closure");
    }
    if (owner == Owner.ENTITY_MANAGEMENT && sourceModel.spawnBindingCount() != 0) {
      throw new IllegalArgumentException(
          "Entity empty-source intake cannot bind a nonempty World spawn inventory");
    }
  }

  private static TemplateConfigOwnerSourceInventoryDeclaration requireOwnerDeclaration(
      Owner owner, TemplateConfigSourceSnapshot snapshot) {
    if (snapshot.ownerSourceInventoryDeclarations().isEmpty()) {
      throw new IllegalArgumentException(
          "Selected template config requires its original v2 owner inventory declaration");
    }
    return snapshot.ownerSourceInventoryDeclarations().stream()
        .filter(declaration -> declaration.owner() == owner)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Selected template config has no declaration for the authorized owner"));
  }

  private static void requireNoOwnerTemplateReferences(
      Owner owner, TemplateConfigSourceSnapshot snapshot) {
    for (var entry : snapshot.entries()) {
      boolean conflict =
          switch (owner) {
            case ENTITY_MANAGEMENT -> entry.config().hasEntityReferences();
            case AUTOMATION_SCRIPTING -> entry.config().hasAutomationReferences();
            default -> throw new IllegalArgumentException("Unsupported selected source owner");
          };
      if (conflict) {
        throw new IllegalArgumentException(
            "Selected template config contains a reference owned by the intake recipient");
      }
    }
  }
}
