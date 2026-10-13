package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadClient;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account-confirmed composition for the exact recipient-specific retained World inventory. */
final class WorldSelectedOwnerInventoryReadService {
  private final String trustedNamespace;
  private final WorldSelectedPublicationArtifactInventoryReadService inventoryReadService;
  private final SelectedOwnerIntakeWorldClosureAuthorizationReadClient accountReadClient;

  WorldSelectedOwnerInventoryReadService(
      String trustedNamespace,
      WorldSelectedPublicationArtifactInventoryReadService inventoryReadService,
      SelectedOwnerIntakeWorldClosureAuthorizationReadClient accountReadClient) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = Objects.requireNonNull(trustedNamespace, "trustedNamespace");
    this.inventoryReadService =
        Objects.requireNonNull(inventoryReadService, "inventoryReadService");
    this.accountReadClient = Objects.requireNonNull(accountReadClient, "accountReadClient");
  }

  SelectedOwnerWorldInventoryReadEvidence read(
      SelectedOwnerWorldInventoryReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("World owner inventory read requires no ambient SQL");
    }
    if (!trustedNamespace.equals(request.targetNamespace())) {
      throw new SecurityException("World owner inventory namespace differs from this workload");
    }
    requireAuthenticatedRecipient(request);
    var accountRequest =
        SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create(
            trustedNamespace, request.authorizationBinding());
    while (accountRequest.readRequestId().equals(request.readRequestId())) {
      accountRequest =
          SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create(
              trustedNamespace, request.authorizationBinding());
    }
    var accountEvidence = accountReadClient.readHeld(accountRequest);
    if (accountEvidence == null || !sameAccountRequest(accountRequest, accountEvidence.request())) {
      throw new SecurityException("Account changed the exact World closure authorization read");
    }

    var inventory = inventoryReadService.readRetained(request.freezeEvidence());
    return new SelectedOwnerWorldInventoryReadEvidence(request, inventory);
  }

  private void requireAuthenticatedRecipient(
      SelectedOwnerWorldInventoryReadEvidence.Request request) {
    var binding = request.authorizationBinding();
    String service =
        binding.owner() == Owner.ENTITY_MANAGEMENT
            ? "entity-management-service"
            : "automation-scripting-service";
    String expected = "spiffe://firemud/ns/" + trustedNamespace + "/sa/" + service;
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (!expected.equals(binding.intendedReader())
        || peer == null
        || !expected.equals(peer.uri())
        || !peer.isService(service)
        || !peer.isInNamespace(trustedNamespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new SecurityException("Exact same-namespace selected-owner workload is required");
    }
  }

  private static boolean sameAccountRequest(
      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request expected,
      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request actual) {
    return actual != null
        && expected.schemaVersion() == actual.schemaVersion()
        && expected.targetNamespace().equals(actual.targetNamespace())
        && expected.readRequestId().equals(actual.readRequestId())
        && expected.intendedReader().equals(actual.intendedReader())
        && expected.closureReadPurpose().equals(actual.closureReadPurpose())
        && Arrays.equals(expected.binding().canonicalBytes(), actual.binding().canonicalBytes());
  }
}
