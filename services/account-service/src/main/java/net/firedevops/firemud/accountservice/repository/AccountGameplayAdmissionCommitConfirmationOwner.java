package net.firedevops.firemud.accountservice.repository;

import io.grpc.Status;
import java.util.Objects;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionCommitConfirmation;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseWireCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered, non-admitting owner for database proof that an original lease COMMIT preceded its
 * unchanged expiry. It evaluates no current source authority and does not finalize or admit.
 */
public final class AccountGameplayAdmissionCommitConfirmationOwner {
  private final AccountGameplayAdmissionCommitConfirmationRepository repository;
  private final TransactionTemplate createTransaction;
  private final TransactionTemplate readTransaction;
  private final String namespace;

  public AccountGameplayAdmissionCommitConfirmationOwner(
      AccountGameplayAdmissionCommitConfirmationRepository repository,
      PlatformTransactionManager transactionManager,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    this.namespace = namespace;
    PlatformTransactionManager manager = Objects.requireNonNull(transactionManager);
    createTransaction = transaction(manager);
    readTransaction = transaction(manager);
  }

  /**
   * Creates/replays the temporal receipt, waits for that transaction to commit, then independently
   * reads the durable receipt in a fresh transaction before returning it.
   */
  public AccountGameplayAdmissionCommitConfirmation confirm(
      FinalizeGameplayAdmissionLeaseRequest request) {
    RequestIdentity identity = authenticateAndParse(request);
    rejectAmbientTransaction();
    try {
      AccountGameplayAdmissionCommitConfirmation created =
          Objects.requireNonNull(
              createTransaction.execute(
                  ignored ->
                      repository.confirmCommitted(identity.evidence(), identity.decisionId())));
      AccountGameplayAdmissionCommitConfirmation read =
          Objects.requireNonNull(
              readTransaction.execute(
                  ignored ->
                      repository.readCommitConfirmation(
                          identity.evidence(), identity.decisionId())));
      if (!created.equals(read)) throw unavailable();
      return read;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  /** Reads an existing proof only; it never creates or repairs a receipt. */
  public AccountGameplayAdmissionCommitConfirmation read(
      FinalizeGameplayAdmissionLeaseRequest request) {
    RequestIdentity identity = authenticateAndParse(request);
    rejectAmbientTransaction();
    try {
      return Objects.requireNonNull(
          readTransaction.execute(
              ignored ->
                  repository.readCommitConfirmation(identity.evidence(), identity.decisionId())));
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private RequestIdentity authenticateAndParse(FinalizeGameplayAdmissionLeaseRequest request) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null)
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    if (!GrpcPeerIdentity.isValidNamespace(namespace)
        || !peer.uri().equals("spiffe://firemud/ns/" + namespace + "/sa/game-session-service"))
      throw Status.PERMISSION_DENIED
          .withDescription("Game Session workload required")
          .asRuntimeException();
    if (request == null || !request.hasLease() || !request.getUnknownFields().asMap().isEmpty())
      throw invalidRequest();

    AccountGameplayAdmissionLeaseEvidence evidence;
    try {
      evidence = AccountGameplayAdmissionLeaseWireCodec.parseReference(request.getLease());
    } catch (IllegalArgumentException failure) {
      throw invalidRequest();
    }
    var decision = canonicalDecision(request.getBindingDecisionId());
    if (!namespace.equals(evidence.carrier().get("targetNamespace"))
        || !peer.uri().equals(evidence.carrier().get("callerWorkload")))
      throw Status.PERMISSION_DENIED
          .withDescription("Admission lease caller mismatch")
          .asRuntimeException();
    return new RequestIdentity(evidence, decision);
  }

  private static TransactionTemplate transaction(PlatformTransactionManager manager) {
    TransactionTemplate template = new TransactionTemplate(manager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    template.setReadOnly(false);
    return template;
  }

  private static void rejectAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION
          .withDescription("Owned Account transaction required")
          .asRuntimeException();
  }

  private static java.util.UUID canonicalDecision(String text) {
    try {
      java.util.UUID value = java.util.UUID.fromString(text);
      if (!value.toString().equals(text) || value.version() != 4 || value.variant() != 2)
        throw new IllegalArgumentException();
      return value;
    } catch (RuntimeException failure) {
      throw invalidRequest();
    }
  }

  private static io.grpc.StatusRuntimeException invalidRequest() {
    return Status.INVALID_ARGUMENT
        .withDescription("Malformed Account admission confirmation request")
        .asRuntimeException();
  }

  private static io.grpc.StatusRuntimeException unavailable() {
    return Status.UNAVAILABLE
        .withDescription("Durable Account admission commit confirmation unavailable")
        .asRuntimeException();
  }

  private record RequestIdentity(
      net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence
          evidence,
      java.util.UUID decisionId) {}
}
