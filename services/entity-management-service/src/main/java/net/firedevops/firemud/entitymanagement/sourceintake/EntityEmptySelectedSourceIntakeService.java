package net.firedevops.firemud.entitymanagement.sourceintake;

import java.util.Objects;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderCredentials;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadClient;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered preparation service for authenticated, freshly founded empty Entity scopes. */
public final class EntityEmptySelectedSourceIntakeService {
  private final String configuredNamespace;
  private final EntityEmptySelectedSourceIntakeRepository repository;
  private final SelectedOwnerIntakeAuthorizationReadClient authorizationReadClient;
  private final SelectedOwnerWorldInventoryReadClient worldInventoryReadClient;

  public EntityEmptySelectedSourceIntakeService(
      String configuredNamespace,
      EntityEmptySelectedSourceIntakeRepository repository,
      SelectedOwnerIntakeAuthorizationReadClient authorizationReadClient,
      SelectedOwnerWorldInventoryReadClient worldInventoryReadClient) {
    if (!GrpcPeerIdentity.isValidNamespace(configuredNamespace)) {
      throw new IllegalArgumentException("Configured Entity namespace is required");
    }
    this.configuredNamespace = configuredNamespace;
    this.repository = Objects.requireNonNull(repository, "repository");
    this.authorizationReadClient =
        Objects.requireNonNull(authorizationReadClient, "authorizationReadClient");
    this.worldInventoryReadClient =
        Objects.requireNonNull(worldInventoryReadClient, "worldInventoryReadClient");
  }

  /**
   * Replays an exact retained receipt or prepares a fresh owner-local retention attempt. This
   * method does not publish, activate, create nonempty templates, or settle Account participation.
   */
  public EntityEmptySelectedSourceIntakeReceipt retain(
      String targetNamespace,
      SelectedOwnerIntakeAuthorizationBinding originalBinding,
      WorldSelectedDraftPublicationFreezeEvidence exactFreeze) {
    requireConfiguredNamespace(targetNamespace);
    requireAuthenticatedGameDesignCaller(targetNamespace);
    requireNoAmbientOwnerSql();
    requireRequest(targetNamespace, originalBinding, exactFreeze);
    String requestDigest =
        EntityEmptySelectedSourceIntakeReceipt.requestDigest(
            targetNamespace, originalBinding, exactFreeze);

    var retained = repository.read(targetNamespace, originalBinding.intakeRequestId());
    if (retained.isPresent()) {
      EntityEmptySelectedSourceIntakeReceipt receipt = retained.orElseThrow();
      receipt.requireSameRequest(targetNamespace, originalBinding, exactFreeze);
      return receipt;
    }

    SelectedOwnerIntakeAuthorizationReadEvidence.Request authorizationRequest =
        SelectedOwnerIntakeAuthorizationReadEvidence.Request.create(
            targetNamespace, originalBinding);
    var authorizationEvidence = authorizationReadClient.read(authorizationRequest);
    if (authorizationEvidence == null
        || !authorizationRequest.equals(authorizationEvidence.request())) {
      throw new IllegalStateException("Account did not echo the exact selected-owner read request");
    }

    SelectedOwnerWorldInventoryReadEvidence.Request worldRequest =
        SelectedOwnerWorldInventoryReadEvidence.Request.create(
            targetNamespace, originalBinding, exactFreeze);
    var worldEvidence = worldInventoryReadClient.read(worldRequest);
    if (worldEvidence == null || !worldRequest.equals(worldEvidence.request())) {
      throw new IllegalStateException("World did not echo the exact selected-owner read request");
    }

    // Typed inputs bind bytes only. The authenticated clients supply producer identity; Account's
    // distinct source participation remains protected until an exact owner terminal is settled.
    SelectedOwnerEmptySourceInputs inputs =
        new SelectedOwnerEmptySourceInputs(originalBinding, worldEvidence);
    requireEmptyWorldEntityClosure(worldEvidence.inventory().publicEvidence());

    // A second HELD sample narrows the gap after the remote World read. It does not extend or prove
    // continuous Account protection through Entity's local commit.
    SelectedOwnerIntakeAuthorizationReadEvidence.Request finalAuthorizationRequest =
        SelectedOwnerIntakeAuthorizationReadEvidence.Request.create(
            targetNamespace, originalBinding);
    var finalAuthorizationEvidence = authorizationReadClient.read(finalAuthorizationRequest);
    if (finalAuthorizationEvidence == null
        || !finalAuthorizationRequest.equals(finalAuthorizationEvidence.request())) {
      throw new IllegalStateException("Account did not echo the final selected-owner read request");
    }

    EntityEmptySelectedSourceIntakeReceipt receipt = repository.retainFresh(inputs, requestDigest);
    receipt.requireSameRequest(targetNamespace, originalBinding, exactFreeze);
    return receipt;
  }

  private void requireConfiguredNamespace(String targetNamespace) {
    if (!configuredNamespace.equals(targetNamespace)) {
      throw new SecurityException(
          "Selected Entity intake namespace differs from the configured Entity owner namespace");
    }
  }

  private static void requireAuthenticatedGameDesignCaller(String targetNamespace) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw new SecurityException("Authenticated same-namespace Game Design peer is required");
    }
    if (AccountSelectedPublicationOrderCredentials.CONTEXT_KEY.get() != null
        || SessionContext.hasAuthenticatedCallerContext()
        || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
        || !targetNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + targetNamespace + "/sa/game-design-service")
            .equals(peer.uri())) {
      throw new SecurityException(
          "Selected Entity intake requires the exact same-namespace Game Design peer");
    }
  }

  private static void requireRequest(
      String targetNamespace,
      SelectedOwnerIntakeAuthorizationBinding binding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    Objects.requireNonNull(binding, "original Account authorization binding is required");
    Objects.requireNonNull(freezeEvidence, "exact World freeze is required");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)
        || !targetNamespace.equals(binding.targetNamespace())
        || binding.owner() != Owner.ENTITY_MANAGEMENT
        || !binding.schema().equals("account-entity-intake-authorization/v1")
        || !binding.purpose().equals("ENTITY_INTAKE_RETENTION")) {
      throw new IllegalArgumentException(
          "Fresh Entity intake requires its exact finalized Account owner binding");
    }
    var freeze = freezeEvidence.request();
    if (!targetNamespace.equals(freeze.targetNamespace())
        || !binding.tenantId().equals(freeze.canonicalTenantId())
        || !binding.versionId().equals(freeze.canonicalVersionId())
        || !binding
            .selected()
            .equals(freeze.accountBinding().input().selection().selectedCommit())) {
      throw new IllegalArgumentException(
          "Entity owner binding differs from the exact selected World freeze");
    }
  }

  private static void requireEmptyWorldEntityClosure(
      WorldSelectedPublicationArtifactInventoryEvidence.PublicEvidence inventory) {
    if (!WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA.equals(
            inventory.schema())
        || inventory.schemaVersion()
            != WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION
        || !WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL.equals(
            inventory.sourceModel().modelId())
        || inventory.sourceModel().graphSchemaVersion()
            != WorldSelectedPublicationArtifactInventoryEvidence
                .INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION
        || inventory.sourceModel().inboundSourceClosure() == null) {
      throw new IllegalArgumentException(
          "Entity empty-source intake requires the complete supported World closure");
    }
    var sourceModel = inventory.sourceModel();
    int spawnCount =
        sourceModel.familyCounts().stream()
            .filter(family -> "WORLD_ENTITY_SPAWN_BINDING".equals(family.family()))
            .mapToInt(WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount::rowCount)
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("World population family state is unavailable"));
    if (spawnCount != 0
        || sourceModel.spawnBindingCount() != 0
        || !sourceModel.spawnBindingInputs().isEmpty()) {
      throw new IllegalArgumentException(
          "Entity empty-source intake cannot retain World population bindings");
    }
    var closure = sourceModel.inboundSourceClosure();
    if (closure.schemaVersion() != 1 || closure.familyCounts().size() != 7) {
      throw new IllegalArgumentException("World inbound closure family vector is incomplete");
    }
    for (var family : closure.familyCounts()) {
      if (family.count() != 0) {
        throw new IllegalArgumentException(
            "Entity empty-source intake cannot retain World item, loot, behavior, or hook references");
      }
    }
  }

  private static void requireNoAmbientOwnerSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account and World evidence reads must run outside Entity SQL");
    }
  }
}
