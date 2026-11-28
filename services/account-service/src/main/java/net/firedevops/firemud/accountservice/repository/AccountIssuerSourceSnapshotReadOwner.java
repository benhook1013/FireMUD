package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.ReadCurrentIssuerAuthoritySourceRequest;
import net.firedevops.firemud.account.v1.ReadExactIssuerAuthorityEventRequest;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotEvidence;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotGrpcCodec;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotGrpcCodec.ExactEventReadback;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotGrpcCodec.ExactEventRequest;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered, authenticated owner for current and retained Account issuer source reads. */
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

    var current = readInOwnerTransaction(() -> readCurrent(parsed, peer));
    return new ReadResult(parsed, peer.uri(), current);
  }

  /**
   * Reads a retained issuer event and the independently current source in one owner transaction.
   */
  public ExactEventReadResult readExactEvent(ReadExactIssuerAuthorityEventRequest request) {
    GrpcPeerIdentity peer = requireGameSessionPeer();
    rejectAmbientTransaction();
    if (request == null) {
      throw new IllegalArgumentException("Malformed Account issuer source request");
    }
    ExactEventRequest parsed =
        AccountIssuerSourceSnapshotGrpcCodec.fromExactEventRequest(
            request, workloadNamespace, peer.uri());

    ExactEventReadback readback = readInOwnerTransaction(() -> readSelected(parsed, peer));
    return new ExactEventReadResult(parsed, peer.uri(), readback);
  }

  private <T> T readInOwnerTransaction(Supplier<T> read) {
    try {
      return Objects.requireNonNull(
          transaction.execute(
              ignored -> {
                if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                  throw failedPrecondition("Owned writable Account source transaction required");
                }
                return read.get();
              }),
          "Account issuer source transaction returned no evidence");
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (DataAccessException | TransactionException unavailable) {
      throw unavailable();
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
  }

  private AccountIssuerSourceSnapshotEvidence readCurrent(
      AccountIssuerSourceSnapshotGrpcCodec.ReadRequest request, GrpcPeerIdentity peer) {
    final AccountIssuerSourceSnapshotEvidence current;
    try {
      var source = sourceRepository.readCurrentCanonicalIssuerSource(request.issuerId());
      current = snapshotEvidence(source, request, peer);
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

  private ExactEventReadback readSelected(ExactEventRequest request, GrpcPeerIdentity peer) {
    try {
      var readback =
          sourceRepository.readExactCanonicalIssuerEvent(
              request.scope().issuerId(),
              request.outboxSequence(),
              request.expectedEventId(),
              request.expectedEventDigest());
      return new ExactEventReadback(
          snapshotEvidence(readback.currentSnapshot(), request.scope(), peer),
          readback.selectedEvent());
    } catch (IllegalArgumentException | IllegalStateException invalidSource) {
      throw failedPrecondition("Exact Account issuer event is unavailable or inconsistent");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private static AccountIssuerSourceSnapshotEvidence snapshotEvidence(
      CanonicalIssuerSourceSnapshot source,
      AccountIssuerSourceSnapshotGrpcCodec.ReadRequest request,
      GrpcPeerIdentity peer) {
    IssuerGenerationProjection projection = IssuerGenerationProjection.fromSource(source);
    return new AccountIssuerSourceSnapshotEvidence(
        request.reconciliationOperationId(),
        request.targetNamespace(),
        peer.uri(),
        projection.issuerId(),
        projection.issuerAuthGeneration(),
        projection.sourceVersion(),
        projection.outboxStreamKey(),
        projection.lastAppliedSourceOutboxSequence(),
        projection.sourceEvent());
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
        || SessionContext.hasAuthenticatedCallerContext()
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

  /** Context required by the unregistered gRPC adapter to encode the historical readback. */
  public record ExactEventReadResult(
      ExactEventRequest request, String authenticatedCallerWorkload, ExactEventReadback readback) {
    public ExactEventReadResult {
      Objects.requireNonNull(request);
      Objects.requireNonNull(authenticatedCallerWorkload);
      Objects.requireNonNull(readback);
    }
  }
}
