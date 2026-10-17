package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered orchestration boundary for authenticated original StartSession protection. */
public final class WorldOriginalStartSessionProtectedActivationService {
  private final WorldCanonicalInstanceActivationRepository activationRepository;
  private final WorldCanonicalInstanceActivationService activationService;
  private final AccountStartSessionActivationProtectionAcquisitionClient accountProtectionClient;
  private final String trustedNamespace;

  public WorldOriginalStartSessionProtectedActivationService(
      WorldCanonicalInstanceActivationRepository activationRepository,
      WorldCanonicalInstanceActivationService activationService,
      AccountStartSessionActivationProtectionAcquisitionClient accountProtectionClient,
      String trustedNamespace) {
    this.activationRepository =
        Objects.requireNonNull(activationRepository, "activationRepository");
    this.activationService = Objects.requireNonNull(activationService, "activationService");
    this.accountProtectionClient =
        Objects.requireNonNull(accountProtectionClient, "accountProtectionClient");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  /**
   * Returns an exact historical terminal before contacting Account. An existing PENDING operation
   * remains owned by its first invocation and can never be restarted by a later request.
   */
  public WorldOriginalStartSessionActivationTerminalEvidence activate(
      AccountStartSessionActivationProtectionAcquisitionInput input) {
    Objects.requireNonNull(input, "activation acquisition input required");
    requireNoAmbientTransaction();
    input.requireTargetNamespace(trustedNamespace);

    var prior = activationRepository.readProtectedActivationByAcquisitionInput(input);
    if (prior.isPresent()) {
      var readback = prior.orElseThrow();
      if (readback.pending()) {
        throw new PendingActivationException(
            "World activation is already PENDING; it cannot reacquire authority or restart its lifecycle CAS");
      }
      return readback.terminalEvidence();
    }

    // The distinct Account protection is acquired before any World writable transaction. This
    // interface is the authenticated Account workload client; the carrier itself is integrity-only.
    var protection = accountProtectionClient.acquire(input);
    AccountStartSessionActivationProtectionAcquisitionGrpcCodec.toResponse(
        input, trustedNamespace, protection);

    // The durable Account pending source guard spans World begin/CAS/terminal persistence. Closing
    // this process-local handle intentionally performs no settlement or remote release.
    WorldCanonicalInstanceActivationService.HeldActivationAuthority held =
        new WorldCanonicalInstanceActivationService.HeldActivationAuthority() {
          private boolean closed;

          @Override
          public void requireHeld() {
            if (closed) {
              throw new WorldCanonicalInstanceActivationService.ActivationDeniedException(
                  "Local activation invocation authority is closed");
            }
          }

          @Override
          public void close() {
            closed = true;
          }
        };
    return activationService.activateProtected(protection, held);
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World protected activation must acquire Account authority outside an ambient owner transaction");
    }
  }

  public static final class PendingActivationException extends IllegalStateException {
    public PendingActivationException(String message) {
      super(message);
    }
  }
}
