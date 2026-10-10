package net.firedevops.firemud.automationscripting.sourceintake;

import java.util.Objects;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderCredentials;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadClient;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered preparation service for authenticated, freshly founded empty Automation scopes.
 *
 * <p>Account's HELD reads are point-in-time samples. They do not extend through Automation's local
 * commit and do not settle the original Account source participation.
 *
 * <p>The shared selected-source value checks exact canonical bytes and typed snapshots for all six
 * existing Game Design source families. Authenticated Account and World clients establish the
 * remote read identities and HELD/inventory results; typed integrity alone does not authenticate
 * either producer or establish continuous source-writer protection.
 */
public final class AutomationEmptySelectedSourceIntakeService {
  private final AutomationEmptySelectedSourceIntakeRepository repository;
  private final SelectedOwnerIntakeAuthorizationReadClient authorizationReadClient;
  private final SelectedOwnerWorldInventoryReadClient worldInventoryReadClient;

  public AutomationEmptySelectedSourceIntakeService(
      AutomationEmptySelectedSourceIntakeRepository repository,
      SelectedOwnerIntakeAuthorizationReadClient authorizationReadClient,
      SelectedOwnerWorldInventoryReadClient worldInventoryReadClient) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.authorizationReadClient =
        Objects.requireNonNull(authorizationReadClient, "authorizationReadClient");
    this.worldInventoryReadClient =
        Objects.requireNonNull(worldInventoryReadClient, "worldInventoryReadClient");
  }

  /**
   * Returns an exact prior receipt for a lost-response retry, or prepares a fresh retention
   * attempt. This operation does not create scripts, bindings, runtime markers, or activation
   * state.
   */
  public AutomationEmptySelectedSourceIntakeReceipt retain(
      String targetNamespace,
      SelectedOwnerIntakeAuthorizationBinding originalBinding,
      WorldSelectedDraftPublicationFreezeEvidence exactFreeze) {
    requireAuthenticatedGameDesignCaller(targetNamespace);
    requireNoAmbientOwnerSql();
    requireRequest(targetNamespace, originalBinding, exactFreeze);
    String requestDigest =
        AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
            targetNamespace, originalBinding, exactFreeze);

    // A committed receipt is authoritative for exact lost-response retries, even after Account's
    // temporary HELD state has subsequently settled. It never implies that an unrecorded request
    // was aborted.
    var retained = repository.read(targetNamespace, originalBinding.intakeRequestId());
    if (retained.isPresent()) {
      AutomationEmptySelectedSourceIntakeReceipt receipt = retained.orElseThrow();
      receipt.requireSameRequest(targetNamespace, originalBinding, exactFreeze);
      return receipt;
    }

    SelectedOwnerIntakeAuthorizationReadEvidence.Request authorizationReadRequest =
        SelectedOwnerIntakeAuthorizationReadEvidence.Request.create(
            targetNamespace, originalBinding);
    var authorizationEvidence = authorizationReadClient.read(authorizationReadRequest);
    if (authorizationEvidence == null
        || !authorizationReadRequest.equals(authorizationEvidence.request())) {
      throw new IllegalStateException("Account did not echo the exact selected-owner read request");
    }

    SelectedOwnerWorldInventoryReadEvidence.Request worldReadRequest =
        SelectedOwnerWorldInventoryReadEvidence.Request.create(
            targetNamespace, originalBinding, exactFreeze);
    var worldEvidence = worldInventoryReadClient.read(worldReadRequest);
    if (worldEvidence == null || !worldReadRequest.equals(worldEvidence.request())) {
      throw new IllegalStateException("World did not echo the exact selected-owner read request");
    }

    // This shared value validates only exact evidence integrity, complete supported inbound World
    // closure, and the typed empty Automation declaration. The authenticated clients above—not
    // the value itself—supply producer identity and HELD authorization.
    SelectedOwnerEmptySourceInputs inputs =
        new SelectedOwnerEmptySourceInputs(originalBinding, worldEvidence);

    // Re-sample HELD after the remote World read to reduce the gap before local commit. This is
    // only a point-in-time observation: finalized pending Account source participation remains
    // under its source-writer exclusion guards. This preparation neither extends nor settles or
    // releases them; exact Automation terminal settlement is not implemented.
    SelectedOwnerIntakeAuthorizationReadEvidence.Request finalAuthorizationReadRequest =
        SelectedOwnerIntakeAuthorizationReadEvidence.Request.create(
            targetNamespace, originalBinding);
    var finalAuthorizationEvidence = authorizationReadClient.read(finalAuthorizationReadRequest);
    if (finalAuthorizationEvidence == null
        || !finalAuthorizationReadRequest.equals(finalAuthorizationEvidence.request())) {
      throw new IllegalStateException("Account did not echo the exact final HELD read request");
    }

    AutomationEmptySelectedSourceIntakeReceipt receipt =
        repository.retainFresh(inputs, requestDigest);
    receipt.requireSameRequest(targetNamespace, originalBinding, exactFreeze);
    return receipt;
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
          "Selected Automation intake requires the exact same-namespace Game Design peer");
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
        || binding.owner() != Owner.AUTOMATION_SCRIPTING
        || !binding.schema().equals("account-automation-intake-authorization/v1")
        || !binding.purpose().equals("AUTOMATION_INTAKE_RETENTION")) {
      throw new IllegalArgumentException(
          "Fresh Automation intake requires its exact finalized Account owner binding");
    }
    var freeze = freezeEvidence.request();
    if (!targetNamespace.equals(freeze.targetNamespace())
        || !binding.tenantId().equals(freeze.canonicalTenantId())
        || !binding.versionId().equals(freeze.canonicalVersionId())
        || !binding
            .selected()
            .equals(freeze.accountBinding().input().selection().selectedCommit())) {
      throw new IllegalArgumentException(
          "Automation owner binding differs from the exact selected World freeze");
    }
  }

  private static void requireNoAmbientOwnerSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account and World evidence reads must run outside Automation owner SQL");
    }
  }
}
