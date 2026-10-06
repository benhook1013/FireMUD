package net.firedevops.firemud.worldmanagement.tenant;

import java.util.UUID;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;

/** Exact durable selector evidence tied to its original Account operation and stored graph. */
public record WorldDraftStartLocationReceipt(
    String targetNamespace,
    UUID operationId,
    UUID requestId,
    UUID commitId,
    UUID authorizationFenceId,
    String accountBindingDigest,
    String bindingDigest,
    RoomTemplateRef startLocation,
    String graphDigest,
    String receiptDigest) {
  public WorldDraftStartLocationReceipt {
    new WorldDraftStartLocationEvidence(
        targetNamespace,
        operationId,
        requestId,
        commitId,
        authorizationFenceId,
        accountBindingDigest,
        bindingDigest,
        startLocation,
        graphDigest,
        receiptDigest);
  }

  static WorldDraftStartLocationReceipt create(
      WorldDraftGraphApplication application, byte[] graphBytes) {
    var operation = application.operation();
    RoomTemplateRef selector =
        application
            .plan()
            .graph()
            .freshGraphDeclaration()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "World start-location receipt requires an original graph declaration"))
            .startLocation();
    String graphDigest = WorldDraftGraphAppliedResult.digest(graphBytes);
    var evidence =
        WorldDraftStartLocationEvidence.create(
            operation.ownerBinding().targetNamespace(),
            operation.operationId(),
            operation.requestId(),
            operation.commitId(),
            operation.authorizationFenceId(),
            operation.accountBindingDigest(),
            operation.binding().digest(),
            selector,
            graphDigest);
    return new WorldDraftStartLocationReceipt(
        evidence.targetNamespace(),
        evidence.operationId(),
        evidence.requestId(),
        evidence.commitId(),
        evidence.authorizationFenceId(),
        evidence.accountBindingDigest(),
        evidence.bindingDigest(),
        evidence.startLocation(),
        evidence.graphDigest(),
        evidence.receiptDigest());
  }

  byte[] canonicalBytes() {
    return asEvidence().canonicalBytes();
  }

  private WorldDraftStartLocationEvidence asEvidence() {
    return new WorldDraftStartLocationEvidence(
        targetNamespace,
        operationId,
        requestId,
        commitId,
        authorizationFenceId,
        accountBindingDigest,
        bindingDigest,
        startLocation,
        graphDigest,
        receiptDigest);
  }
}
