package net.firedevops.firemud.gamedesign.publication;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered composition of the original Account intake producer and the selected-release receipt
 * owner. The caller supplies the stable intake identity for both first admission and exact
 * recovery; this class does not create or replace that identity.
 */
public final class SelectedDraftGameLogicIntakeCommandService {
  private final SelectionReader selections;
  private final GameLogicIntakeAuthorizationClient account;
  private final ReceiptRetainer receipts;
  private final String namespace;

  public SelectedDraftGameLogicIntakeCommandService(
      DSLContext dsl,
      GameLogicIntakeAuthorizationClient account,
      SelectedDraftGameLogicReceiptService receipts,
      String namespace) {
    this(
        repositoryReader(dsl),
        account,
        Objects.requireNonNull(receipts, "receipts")::retain,
        namespace);
  }

  SelectedDraftGameLogicIntakeCommandService(
      SelectionReader selections,
      GameLogicIntakeAuthorizationClient account,
      ReceiptRetainer receipts,
      String namespace) {
    this.selections = Objects.requireNonNull(selections, "selections");
    this.account = Objects.requireNonNull(account, "account");
    this.receipts = Objects.requireNonNull(receipts, "receipts");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
  }

  /** Obtains a fresh finalized Account authorization, then retains its exact selected receipt. */
  public SelectedDraftGameLogicReceipt authorizeAndRetain(
      AuthoredDraftPublishSelection selection,
      GameLogicIntakeAuthorizationEvidence.Request request,
      String originalCreatorCredential) {
    return produce(selection, request, originalCreatorCredential, account::authorize);
  }

  /** Recovers the caller's original intake identity after an uncertain acknowledgement. */
  public SelectedDraftGameLogicReceipt recoverAndRetain(
      AuthoredDraftPublishSelection selection,
      GameLogicIntakeAuthorizationEvidence.Request request,
      String originalCreatorCredential) {
    return produce(selection, request, originalCreatorCredential, account::recover);
  }

  private SelectedDraftGameLogicReceipt produce(
      AuthoredDraftPublishSelection selection,
      GameLogicIntakeAuthorizationEvidence.Request request,
      String originalCreatorCredential,
      AuthorizationCall call) {
    requireNoTransaction();
    requireRequestMatchesSelection(selection, request);

    var retainedSelection =
        selections
            .read(selection)
            .orElseThrow(
                () -> new IllegalStateException("SELECTED_PUBLICATION_SELECTION_UNAVAILABLE"));
    if (!Arrays.equals(selection.canonicalBytes(), retainedSelection.canonicalBytes()))
      throw new IllegalStateException("Selected publication differs from immutable selection");

    // The durable owner read above is outside the ambient caller transaction. Account's producer
    // must also remain outside any ambient SQL transaction.
    requireNoTransaction();
    final GameLogicIntakeAuthorizationEvidence.Result result;
    try {
      result = call.invoke(request, originalCreatorCredential);
    } catch (RuntimeException denied) {
      // The protected original credential must never be reflected by an injected client error.
      throw new IllegalStateException("Account intake authorization denied or unavailable");
    }
    var authorization = requireFinalized(request, result);
    return receipts.retain(selection, authorization);
  }

  private void requireRequestMatchesSelection(
      AuthoredDraftPublishSelection selection,
      GameLogicIntakeAuthorizationEvidence.Request request) {
    Objects.requireNonNull(selection, "selection");
    Objects.requireNonNull(request, "request");
    if (!namespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException("Intake authorization namespace mismatch");
    byte[] selectionBytes = selection.canonicalBytes();
    if (selectionBytes.length > SelectedDraftGameLogicReceipt.MAX_SELECTION_BYTES
        || !Arrays.equals(
            selection.selectedCommit().canonicalBytes(), request.selectedDraftBinding()))
      throw new IllegalArgumentException("Intake request differs from selected Draft binding");
    // Re-parse the caller's selection so only the canonical immutable representation proceeds.
    var canonical =
        AuthoredDraftPublishSelection.fromStored(selection.canonicalJson(), selection.digest());
    if (!Arrays.equals(selectionBytes, canonical.canonicalBytes()))
      throw new IllegalArgumentException("Noncanonical selected publication");
  }

  private static GameLogicIntakeAuthorizationBinding requireFinalized(
      GameLogicIntakeAuthorizationEvidence.Request request,
      GameLogicIntakeAuthorizationEvidence.Result result) {
    if (result == null || !sameRequest(request, result.request()))
      throw new IllegalStateException("Account intake authorization request readback conflict");
    if (result.outcome() != GameLogicIntakeAuthorizationEvidence.Outcome.FINALIZED
        || result.authorization().isEmpty())
      throw new IllegalStateException("Account intake authorization is not finalized");

    var authorization = result.authorization().orElseThrow();
    if (!request.intakeRequestId().equals(authorization.intakeRequestId())
        || !Arrays.equals(
            request.selectedDraftBinding(), authorization.source().binding().canonicalBytes()))
      throw new IllegalStateException("Account intake authorization differs from exact request");
    return authorization;
  }

  private static boolean sameRequest(
      GameLogicIntakeAuthorizationEvidence.Request expected,
      GameLogicIntakeAuthorizationEvidence.Request actual) {
    return actual != null
        && expected.schemaVersion() == actual.schemaVersion()
        && expected.targetNamespace().equals(actual.targetNamespace())
        && expected.transportRequestId().equals(actual.transportRequestId())
        && expected.intakeRequestId().equals(actual.intakeRequestId())
        && Arrays.equals(expected.selectedDraftBinding(), actual.selectedDraftBinding());
  }

  private static SelectionReader repositoryReader(DSLContext dsl) {
    Objects.requireNonNull(dsl, "dsl");
    return selection ->
        new AuthoredDraftPublishSelectionRepository(dsl, new DraftCommitCoordinatorRepository(dsl))
            .read(
                selection.intent().canonicalTenantId(),
                selection.intent().canonicalVersionId(),
                selection.intent().publishRequestId())
            .map(AuthoredDraftPublishSelectionRepository.SelectionSnapshot::selection);
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Account intake authorization requires no ambient SQL");
  }

  @FunctionalInterface
  interface SelectionReader {
    Optional<AuthoredDraftPublishSelection> read(AuthoredDraftPublishSelection selection);
  }

  @FunctionalInterface
  interface ReceiptRetainer {
    SelectedDraftGameLogicReceipt retain(
        AuthoredDraftPublishSelection selection, GameLogicIntakeAuthorizationBinding authorization);
  }

  @FunctionalInterface
  private interface AuthorizationCall {
    GameLogicIntakeAuthorizationEvidence.Result invoke(
        GameLogicIntakeAuthorizationEvidence.Request request, String originalCreatorCredential);
  }
}
