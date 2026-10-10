package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered authenticated producer of the exact original Account COMMIT_ORDER. */
public final class AccountOriginalDraftOrderService {
  private final AccountControlUiActorService actors;
  private final String namespace;

  public AccountOriginalDraftOrderService(AccountControlUiActorService actors, String namespace) {
    this.actors = Objects.requireNonNull(actors);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account namespace required");
    this.namespace = namespace;
  }

  public void claim(
      String originalCredential,
      DraftAuthorizationFenceBinding original,
      CapturedEnvironmentBoundary environment) {
    claimWithEnvironmentCapture(originalCredential, original, () -> environment);
  }

  public void claimWithEnvironmentCapture(
      String originalCredential,
      DraftAuthorizationFenceBinding original,
      Supplier<CapturedEnvironmentBoundary> environment) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (!("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    Objects.requireNonNull(original);
    // Workload-authenticated recovery reads the original held authority, never creator currentness.
    // Only definitive absence enters the existing fresh creator producer; read failures deny.
    if (actors.readHeldOriginalDraft(original)) return;
    // The existing owner performs signed/current creator and complete source validation, then
    // reserves and orders this exact original operation atomically. No input is rewritten.
    var ordered = actors.claimOriginalDraft(originalCredential, original, environment.get());
    if (ordered == null
        || ordered.ordering() != DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER
        || ordered.reservedAt() == null
        || ordered.orderedAt() == null
        || !Arrays.equals(original.canonicalBytes(), ordered.binding()))
      throw new IllegalStateException("Exact original Account COMMIT_ORDER unavailable");
  }
}
