package net.firedevops.firemud.gamedesign.publication;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementReadClient;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeRetainClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeRetainEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered derived-evidence producer. Requires a separately issued original intake order;
 * configured authenticated clients, rather than canonical bytes alone, establish owner provenance.
 */
public final class SelectedDraftGameLogicReceiptService {
  private final SelectedDraftGameLogicReceiptRepository repository;
  private final SelectionReader selections;
  private final GameLogicIntakeRetainClient gameLogic;
  private final AccountGameLogicIntakeSettlementReadClient account;
  private final TransactionTemplate transaction;
  private final String namespace;

  public SelectedDraftGameLogicReceiptService(
      DSLContext dsl,
      PlatformTransactionManager transactions,
      GameLogicIntakeRetainClient gameLogic,
      AccountGameLogicIntakeSettlementReadClient account,
      String namespace) {
    this(
        new SelectedDraftGameLogicReceiptRepository(dsl),
        selection ->
            new AuthoredDraftPublishSelectionRepository(
                    dsl, new DraftCommitCoordinatorRepository(dsl))
                .read(
                    selection.intent().canonicalTenantId(),
                    selection.intent().canonicalVersionId(),
                    selection.intent().publishRequestId())
                .map(AuthoredDraftPublishSelectionRepository.SelectionSnapshot::selection),
        transactions,
        gameLogic,
        account,
        namespace);
  }

  SelectedDraftGameLogicReceiptService(
      SelectedDraftGameLogicReceiptRepository repository,
      SelectionReader selections,
      PlatformTransactionManager transactions,
      GameLogicIntakeRetainClient gameLogic,
      AccountGameLogicIntakeSettlementReadClient account,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    this.selections = Objects.requireNonNull(selections);
    this.gameLogic = Objects.requireNonNull(gameLogic);
    this.account = Objects.requireNonNull(account);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transaction.setReadOnly(false);
  }

  public SelectedDraftGameLogicReceipt retain(
      AuthoredDraftPublishSelection selection, GameLogicIntakeAuthorizationBinding authorization) {
    requireNoTransaction();
    SelectedDraftGameLogicReceipt.requireExactSelection(selection, authorization);
    var retainedSelection =
        selections
            .read(selection)
            .orElseThrow(
                () -> new IllegalStateException("SELECTED_PUBLICATION_SELECTION_UNAVAILABLE"));
    if (!Arrays.equals(selection.canonicalBytes(), retainedSelection.canonicalBytes())) {
      throw new IllegalStateException("Selected publication differs from immutable selection");
    }
    var prior = repository.read(selection, authorization);
    if (prior.isPresent()) {
      prior.get().requireExactRequest(selection, authorization);
      if (!namespace.equals(prior.get().receipt().terminal().operation().targetNamespace()))
        throw new IllegalStateException("Retained intake namespace conflict");
      return prior.get();
    }
    var retainRequest = GameLogicIntakeRetainEvidence.Request.create(namespace, authorization);
    var retained = gameLogic.retain(retainRequest);
    if (retained == null || !retainRequest.equals(retained.request())) {
      throw new IllegalStateException("Game Logic retain request readback conflict");
    }
    if (!namespace.equals(retained.terminal().operation().targetNamespace())
        || !Arrays.equals(authorization.canonicalBytes(), retained.terminal().authorizationBytes())
        || !authorization.digest().equals(retained.terminal().authorizationDigest()))
      throw new IllegalStateException("Game Logic intake authorization or namespace conflict");
    // Settle either genuine terminal. ABORTED releases only its own participation and still
    // cannot become a positive publication receipt.
    var accountRequest =
        AccountGameLogicIntakeSettlementReadEvidence.Request.create(namespace, authorization);
    var settled = account.read(accountRequest);
    if (settled == null
        || !accountRequest.equals(settled.request())
        || !Arrays.equals(
            retained.terminal().canonicalBytes(), settled.receipt().terminal().canonicalBytes())
        || !retained.terminal().digest().equals(settled.receipt().terminal().digest())) {
      throw new IllegalStateException("Account settlement differs from exact retained intake");
    }
    var candidate = new SelectedDraftGameLogicReceipt(selection, authorization, settled.receipt());
    return Objects.requireNonNull(transaction.execute(status -> repository.retain(candidate)));
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Receipt owner RPCs require no ambient SQL transaction");
  }

  @FunctionalInterface
  interface SelectionReader {
    Optional<AuthoredDraftPublishSelection> read(AuthoredDraftPublishSelection selection);
  }
}
