package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import net.firedevops.firemud.account.v1.ReadCurrentIssuerAuthoritySourceRequest;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotEvidence;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered, authenticated owner read for one exact current Account issuer source snapshot. */
public final class AccountIssuerSourceSnapshotReadOwner {
  private final AccountAuthoritySourceEvidenceRepository sourceRepository;
  private final TransactionTemplate transaction;
  private final String workloadNamespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Private injected source repository collaborator; the owner never exposes it")
  public AccountIssuerSourceSnapshotReadOwner(
      AccountAuthoritySourceEvidenceRepository sourceRepository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.sourceRepository = Objects.requireNonNull(sourceRepository);
    this.workloadNamespace = Objects.requireNonNull(workloadNamespace);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
  }

  /**
   * Authenticates first, then validates the closed request before opening the owner transaction.
   */
  public ReadResult read(ReadCurrentIssuerAuthoritySourceRequest request) {
    GrpcPeerIdentity peer = requireGameSessionPeer();
    rejectAmbientTransaction();
    if (request == null) {
      throw new IllegalArgumentException("Malformed Account issuer source request");
    }
    AccountIssuerSourceSnapshotGrpcCodec.ReadRequest parsed =
        AccountIssuerSourceSnapshotGrpcCodec.fromRequest(request, workloadNamespace, peer.uri());

    final AccountIssuerSourceSnapshotEvidence current;
    try {
      current =
          Objects.requireNonNull(
              transaction.execute(
                  ignored -> {
                    if (!TransactionSynchronizationManager.isActualTransactionActive()
                        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                      throw failedPrecondition(
                          "Owned writable Account source transaction required");
                    }
                    return readCurrent(parsed, peer);
                  }),
              "Account issuer source transaction returned no snapshot");
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (DataAccessException | TransactionException unavailable) {
      throw unavailable();
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
    return new ReadResult(parsed, peer.uri(), current);
  }

  private AccountIssuerSourceSnapshotEvidence readCurrent(
      AccountIssuerSourceSnapshotGrpcCodec.ReadRequest request, GrpcPeerIdentity peer) {
    final AccountIssuerSourceSnapshotEvidence current;
    try {
      var source = sourceRepository.readCurrentCanonicalIssuerSource(request.issuerId());
      IssuerGenerationProjection projection = IssuerGenerationProjection.fromSource(source);
      current =
          new AccountIssuerSourceSnapshotEvidence(
              request.reconciliationOperationId(),
              request.targetNamespace(),
              peer.uri(),
              projection.issuerId(),
              projection.issuerAuthGeneration(),
              projection.sourceVersion(),
              projection.outboxStreamKey(),
              projection.lastAppliedSourceOutboxSequence(),
              projection.sourceEvent());
    } catch (IllegalArgumentException | IllegalStateException invalidSource) {
      throw failedPrecondition("Account issuer source snapshot is unavailable or inconsistent");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }

    request
        .expectedSource()
        .ifPresent(
            expected -> {
              if (!expected.equals(current)) {
                throw failedPrecondition(
                    "Account issuer source differs from the exact expected snapshot");
              }
            });
    return current;
  }

  private GrpcPeerIdentity requireGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Game Session workload identity required")
          .asRuntimeException();
    }
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || !peer.isInNamespace(workloadNamespace)
        || !peer.isService("game-session-service")
        || !peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")) {
      throw Status.PERMISSION_DENIED
          .withDescription("Same-namespace Game Session workload required")
          .asRuntimeException();
    }
    return peer;
  }

  private static void rejectAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw failedPrecondition("Account issuer source read requires its own transaction");
    }
  }

  private static StatusRuntimeException failedPrecondition(String description) {
    return Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException();
  }

  private static StatusRuntimeException unavailable() {
    return Status.UNAVAILABLE
        .withDescription("Account issuer source is temporarily unavailable")
        .asRuntimeException();
  }

  /** Context required by the unregistered gRPC adapter to encode the closed response. */
  public record ReadResult(
      AccountIssuerSourceSnapshotGrpcCodec.ReadRequest request,
      String authenticatedCallerWorkload,
      AccountIssuerSourceSnapshotEvidence evidence) {
    public ReadResult {
      Objects.requireNonNull(request);
      Objects.requireNonNull(authenticatedCallerWorkload);
      Objects.requireNonNull(evidence);
    }
  }
}
