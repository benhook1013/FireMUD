package net.firedevops.firemud.accountservice.authordraft;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered, read-only owner proof of the original Account COMMIT_ORDER while settlement is
 * still PENDING. It neither creates authorization nor changes source/order/readback state.
 */
public final class AccountDraftCommitOrderReadService {
  private final DraftAuthorizationFenceRepository repository;
  private final String trustedNamespace;
  private final TransactionTemplate ownerTransaction;

  public AccountDraftCommitOrderReadService(
      DraftAuthorizationFenceRepository repository,
      PlatformTransactionManager transactionManager,
      String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.trustedNamespace = trustedNamespace;
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /**
   * Returns only after this owner transaction reads the exact retained operation, COMMIT_ORDER, and
   * PENDING settlement. Absence, mismatch, reservation/revocation, or any settlement denies.
   */
  public void requireHeld(DraftCommitOrderReadEvidence.Request request) {
    requireAuthenticatedWorldPeer();
    Objects.requireNonNull(request, "request");
    if (!trustedNamespace.equals(request.targetNamespace())) {
      throw denied("COMMIT_ORDER read namespace differs from the Account workload namespace");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Owned Account COMMIT_ORDER read transaction required")
          .asRuntimeException();
    }

    DraftAuthorizationFenceBinding requested =
        DraftAuthorizationFenceBinding.fromStored(request.originalAccountBinding());
    if (!Arrays.equals(request.originalAccountBinding(), requested.canonicalBytes())) {
      throw invalid("Complete canonical original Account binding is required");
    }
    try {
      ownerTransaction.execute(
          status -> {
            DraftAuthorizationFenceBinding retained =
                repository
                    .readOriginalBinding(requested.operationId())
                    .orElseThrow(AccountDraftCommitOrderReadService::unheld);
            if (!Arrays.equals(retained.canonicalBytes(), request.originalAccountBinding())) {
              throw unheld();
            }

            var fence = repository.read(retained);
            if (fence.ordering() != Ordering.COMMIT_ORDER
                || !Arrays.equals(fence.binding(), request.originalAccountBinding())) {
              throw unheld();
            }
            Settlement settlement = repository.readSettlement(retained);
            if (settlement != Settlement.PENDING) throw unheld();
            return null;
          });
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (DataAccessException | org.jooq.exception.DataAccessException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Account COMMIT_ORDER owner read is unavailable")
          .asRuntimeException();
    } catch (IllegalArgumentException inconsistent) {
      // The request has already passed canonical decoding; this is corrupt or substituted storage.
      throw unheld();
    } catch (RuntimeException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Account COMMIT_ORDER owner read is unavailable")
          .asRuntimeException();
    }
  }

  private void requireAuthenticatedWorldPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    }
    String expected = "spiffe://firemud/ns/" + trustedNamespace + "/sa/world-management-service";
    if (!expected.equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace World workload required")
          .asRuntimeException();
    }
  }

  private static StatusRuntimeException unheld() {
    return Status.FAILED_PRECONDITION
        .withDescription("Exact held original Account COMMIT_ORDER is unavailable")
        .asRuntimeException();
  }

  private static StatusRuntimeException denied(String description) {
    return Status.PERMISSION_DENIED.withDescription(description).asRuntimeException();
  }

  private static StatusRuntimeException invalid(String description) {
    return Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException();
  }
}
