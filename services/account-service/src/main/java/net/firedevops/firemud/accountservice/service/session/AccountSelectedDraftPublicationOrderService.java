package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadEvidence;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Standalone, unregistered composition of authenticated Game Design selection read and distinct
 * Account publication ordering. SELECTED attests immutable reservation only; Account's existing
 * owner primitive remains the authority for the original creator, currentness and source holds.
 * World freeze/capture, settlement and runtime admission are separate boundaries.
 */
public final class AccountSelectedDraftPublicationOrderService {
  private final AuthoredDraftPublishSelectionReadClient selectionClient;
  private final AccountPublicationAuthorizationService owner;
  private final String workloadNamespace;

  public AccountSelectedDraftPublicationOrderService(
      AuthoredDraftPublishSelectionReadClient selectionClient,
      AccountPublicationAuthorizationService owner,
      String workloadNamespace) {
    this.selectionClient = Objects.requireNonNull(selectionClient, "selectionClient");
    this.owner = Objects.requireNonNull(owner, "owner");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * The caller's selection is lookup data. Only the exact authenticated Game Design read is passed
   * to Account's local producer, with the original creator JWT and environment unchanged. The
   * remote read completes before the producer opens its own owner transaction.
   */
  public AccountPublicationAuthorizationBinding authorize(
      String compactJwt,
      AuthoredDraftPublishSelectionBinding lookupSelection,
      CapturedEnvironmentBoundary environment) {
    requireGameDesignPeer();
    requireNoAmbientTransaction();
    Objects.requireNonNull(lookupSelection, "lookupSelection");
    var request =
        AuthoredDraftPublishSelectionReadEvidence.Request.create(
            workloadNamespace, lookupSelection);
    var evidence = selectionClient.read(request);
    if (evidence == null
        || !request.equals(evidence.request())
        || !Arrays.equals(
            lookupSelection.canonicalBytes(), evidence.request().binding().canonicalBytes())
        || !lookupSelection.digest().equals(evidence.request().binding().digest())) {
      throw new IllegalStateException(
          "Authenticated Game Design selection differs from the exact publication order request");
    }
    requireNoAmbientTransaction();
    return owner.authorize(compactJwt, evidence.request().binding(), environment);
  }

  private void requireGameDesignPeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    }
    if (!("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service")
        .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Design workload required")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Selected publication ordering requires no ambient transaction")
          .asRuntimeException();
    }
  }
}
