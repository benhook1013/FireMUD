package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.account.sourceintake.AccountSelectedOwnerIntakeSettlementReceipt;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadClient;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadClient;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered composition for settling one exact finalized selected-owner intake operation.
 *
 * <p>The Account source reservation repository owns source-lock ordering and immutable settlement
 * persistence. This service only permits its pending participation to advance after an exact
 * authenticated owner COMMITTED_EMPTY receipt has been read outside SQL.
 */
public final class AccountSelectedOwnerIntakeSettlementService {
  private static final String AUTOMATION_READER = "automation-scripting-service";
  private static final String ENTITY_READER = "entity-management-service";
  private static final String GAME_DESIGN_CALLER = "game-design-service";
  private static final String ACCOUNT_READER = "account-service";

  private final AccountSelectedOwnerIntakeSourceReservationRepository repository;
  private final AutomationSelectedSourceIntakeTerminalReadClient automationClient;
  private final EntitySelectedSourceIntakeTerminalReadClient entityClient;
  private final TransactionTemplate ownerTransaction;
  private final String namespace;

  public AccountSelectedOwnerIntakeSettlementService(
      AccountSelectedOwnerIntakeSourceReservationRepository repository,
      AutomationSelectedSourceIntakeTerminalReadClient automationClient,
      PlatformTransactionManager transactionManager,
      String namespace) {
    this(repository, automationClient, null, transactionManager, namespace);
  }

  public AccountSelectedOwnerIntakeSettlementService(
      AccountSelectedOwnerIntakeSourceReservationRepository repository,
      AutomationSelectedSourceIntakeTerminalReadClient automationClient,
      EntitySelectedSourceIntakeTerminalReadClient entityClient,
      PlatformTransactionManager transactionManager,
      String namespace) {
    this.entityClient = entityClient;
    this.repository = Objects.requireNonNull(repository, "source reservation repository required");
    this.automationClient = Objects.requireNonNull(automationClient, "Automation client required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
    ownerTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager required"));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /**
   * Settles or recovers the exact finalized owner authorization selected by Game Design.
   *
   * <p>The authorization is a lookup key, not caller authority. Account revalidates its complete
   * retained value before checking for an immutable prior settlement or contacting Automation.
   */
  public AccountSelectedOwnerIntakeSettlementReceipt settle(
      SelectedOwnerIntakeAuthorizationBinding original) {
    requireGameDesignPeer();
    requireNoAmbientTransaction();
    requireOriginalBinding(original);

    Lookup lookup =
        ownerTransaction.execute(
            ignored -> {
              try {
                repository.readFinalAuthorization(original);
                Optional<AccountSelectedOwnerIntakeSettlementReceipt> prior =
                    Objects.requireNonNull(
                        repository.findSettlement(original),
                        "Account settlement lookup returned no result");
                return new Lookup(prior.orElse(null));
              } catch (IllegalArgumentException absentOrChanged) {
                throw Status.FAILED_PRECONDITION
                    .withDescription("Exact finalized selected-owner authorization is unavailable")
                    .withCause(absentOrChanged)
                    .asRuntimeException();
              }
            });
    if (lookup == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Account authorization transaction returned no result")
          .asRuntimeException();
    }
    if (lookup.prior() != null) {
      return requireSettlement(lookup.prior(), original, null);
    }

    if (original.owner() == Owner.ENTITY_MANAGEMENT) {
      return settleEntity(original);
    }

    AutomationSelectedSourceIntakeTerminalReadEvidence.Request request =
        AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(namespace, original);
    requireNoAmbientTransaction();
    AutomationSelectedSourceIntakeTerminalReadEvidence remote;
    try {
      remote = automationClient.read(request);
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Automation terminal evidence is unavailable")
          .withCause(unavailable)
          .asRuntimeException();
    }
    AutomationSelectedSourceIntakeTerminalReadEvidence exactEvidence =
        requireExactTerminal(request, original, remote);

    requireNoAmbientTransaction();
    AccountSelectedOwnerIntakeSettlementReceipt committed =
        ownerTransaction.execute(ignored -> repository.settleCommittedEmpty(exactEvidence));
    requireSettlement(committed, original, exactEvidence);

    // This independent lookup is deliberately lookup-only. An unknown first commit remains
    // unresolved; replaying settleCommittedEmpty here could conceal its outcome.
    requireNoAmbientTransaction();
    AccountSelectedOwnerIntakeSettlementReceipt readback =
        ownerTransaction.execute(
            ignored ->
                Objects.requireNonNull(
                        repository.findSettlement(original),
                        "Account settlement readback returned no result")
                    .orElseThrow(
                        () ->
                            Status.FAILED_PRECONDITION
                                .withDescription("Committed Account settlement is not readable")
                                .asRuntimeException()));
    requireSettlement(readback, original, exactEvidence);
    if (!sameSettlement(committed, readback)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Committed Account settlement readback differs")
          .asRuntimeException();
    }
    return readback;
  }

  private AccountSelectedOwnerIntakeSettlementReceipt settleEntity(
      SelectedOwnerIntakeAuthorizationBinding original) {
    var request =
        EntitySelectedSourceIntakeTerminalReadEvidence.Request.create(namespace, original);
    requireNoAmbientTransaction();
    final EntitySelectedSourceIntakeTerminalReadEvidence remote;
    try {
      remote = entityClient.read(request);
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Entity terminal evidence is unavailable")
          .withCause(unavailable)
          .asRuntimeException();
    }
    final EntitySelectedSourceIntakeTerminalReadEvidence exact;
    try {
      if (remote == null
          || !request.equals(remote.request())
          || !workloadUri(namespace, ACCOUNT_READER).equals(remote.request().intendedReader())
          || !EntitySelectedSourceIntakeTerminalReadEvidence.TERMINAL_READ_PURPOSE.equals(
              remote.request().terminalReadPurpose())) {
        throw new IllegalArgumentException("Entity changed the complete terminal-read request");
      }
      var receipt = remote.receipt();
      byte[] bytes = receipt.canonicalBytes();
      var canonical = EntityEmptySelectedSourceIntakeReceipt.fromStored(bytes);
      if (bytes.length == 0
          || bytes.length > EntityEmptySelectedSourceIntakeReceipt.MAX_BYTES
          || !Arrays.equals(bytes, canonical.canonicalBytes())
          || !DraftAuthorizationFenceBinding.digest(bytes).equals(receipt.receiptDigest())
          || !canonical.receiptDigest().equals(receipt.receiptDigest())
          || !"COMMITTED_EMPTY".equals(receipt.outcome())
          || !namespace.equals(receipt.targetNamespace())
          || !original.operationId().equals(receipt.operationId())
          || !original.fenceId().equals(receipt.fenceId())
          || !original.intakeRequestId().equals(receipt.intakeRequestId())
          || !original.tenantId().equals(receipt.canonicalTenantId())
          || !original.versionId().equals(receipt.canonicalVersionId())
          || !original.selected().commitId().equals(receipt.selectedCommitId())
          || !Arrays.equals(original.canonicalBytes(), receipt.authorizationBindingBytes())
          || !original.digest().equals(receipt.authorizationBindingDigest())) {
        throw new IllegalArgumentException(
            "Entity terminal differs from the original authorization");
      }
      exact = new EntitySelectedSourceIntakeTerminalReadEvidence(request, canonical);
    } catch (IllegalArgumentException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact original Entity terminal is required")
          .withCause(invalid)
          .asRuntimeException();
    }
    requireNoAmbientTransaction();
    var committed =
        ownerTransaction.execute(ignored -> repository.settleEntityCommittedEmpty(exact));
    requireSettlement(committed, original, exact);
    requireNoAmbientTransaction();
    var readback =
        ownerTransaction.execute(
            ignored ->
                Objects.requireNonNull(
                        repository.findSettlement(original), "Account lookup returned no result")
                    .orElseThrow(
                        () ->
                            Status.FAILED_PRECONDITION
                                .withDescription(
                                    "Committed Account Entity settlement is not readable")
                                .asRuntimeException()));
    requireSettlement(readback, original, exact);
    if (!sameSettlement(committed, readback)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Committed Account Entity readback differs")
          .asRuntimeException();
    }
    return readback;
  }

  private void requireGameDesignPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Authenticated Game Design workload is required")
          .asRuntimeException();
    }
    String expectedUri = workloadUri(namespace, GAME_DESIGN_CALLER);
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !expectedUri.equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "Exact same-namespace Game Design workload without end-user context required")
          .asRuntimeException();
    }
  }

  private void requireOriginalBinding(SelectedOwnerIntakeAuthorizationBinding original) {
    if (original == null
        || (original.owner() != Owner.AUTOMATION_SCRIPTING
            && original.owner() != Owner.ENTITY_MANAGEMENT)
        || !namespace.equals(original.targetNamespace())
        || !(original.owner() == Owner.AUTOMATION_SCRIPTING
            ? "account-automation-intake-authorization/v1".equals(original.schema())
                && "AUTOMATION_INTAKE_RETENTION".equals(original.purpose())
                && workloadUri(namespace, AUTOMATION_READER).equals(original.intendedReader())
            : entityClient != null
                && "account-entity-intake-authorization/v1".equals(original.schema())
                && "ENTITY_INTAKE_RETENTION".equals(original.purpose())
                && workloadUri(namespace, ENTITY_READER).equals(original.intendedReader()))) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Canonical same-namespace selected-owner authorization is required")
          .asRuntimeException();
    }
    try {
      byte[] bytes = original.canonicalBytes();
      SelectedOwnerIntakeAuthorizationBinding canonical =
          SelectedOwnerIntakeAuthorizationBinding.fromStored(bytes);
      if (bytes.length == 0
          || bytes.length > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES
          || !Arrays.equals(bytes, canonical.canonicalBytes())
          || !original.digest().equals(canonical.digest())) {
        throw new IllegalArgumentException("Noncanonical selected-owner authorization");
      }
    } catch (IllegalArgumentException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Canonical same-namespace selected-owner authorization is required")
          .withCause(invalid)
          .asRuntimeException();
    }
  }

  private AutomationSelectedSourceIntakeTerminalReadEvidence requireExactTerminal(
      AutomationSelectedSourceIntakeTerminalReadEvidence.Request request,
      SelectedOwnerIntakeAuthorizationBinding original,
      AutomationSelectedSourceIntakeTerminalReadEvidence remote) {
    if (remote == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Automation returned no terminal evidence")
          .asRuntimeException();
    }
    try {
      var echoed = remote.request();
      if (!request.equals(echoed)
          || echoed.schemaVersion()
              != AutomationSelectedSourceIntakeTerminalReadEvidence.SCHEMA_VERSION
          || !namespace.equals(echoed.targetNamespace())
          || !request.readRequestId().equals(echoed.readRequestId())
          || !Arrays.equals(original.canonicalBytes(), echoed.binding().canonicalBytes())
          || !workloadUri(namespace, ACCOUNT_READER).equals(echoed.intendedReader())
          || !AutomationSelectedSourceIntakeTerminalReadEvidence.TERMINAL_READ_PURPOSE.equals(
              echoed.terminalReadPurpose())) {
        throw new IllegalArgumentException("Automation changed the complete terminal-read request");
      }

      AutomationEmptySelectedSourceIntakeReceipt receipt = remote.receipt();
      byte[] receiptBytes = receipt.canonicalBytes();
      if (receiptBytes.length == 0
          || receiptBytes.length > AutomationEmptySelectedSourceIntakeReceipt.MAX_BYTES
          || !DraftAuthorizationFenceBinding.digest(receiptBytes).equals(receipt.receiptDigest())
          || !"COMMITTED_EMPTY".equals(receipt.outcome())
          || !namespace.equals(receipt.targetNamespace())
          || !original.operationId().equals(receipt.operationId())
          || !original.fenceId().equals(receipt.fenceId())
          || !original.intakeRequestId().equals(receipt.intakeRequestId())
          || !original.tenantId().equals(receipt.canonicalTenantId())
          || !original.versionId().equals(receipt.canonicalVersionId())
          || !original.selected().commitId().equals(receipt.selectedCommitId())
          || !Arrays.equals(original.canonicalBytes(), receipt.authorizationBindingBytes())
          || !original.digest().equals(receipt.authorizationBindingDigest())) {
        throw new IllegalArgumentException(
            "Automation receipt is not the canonical original terminal");
      }
      AutomationSelectedSourceIntakeTerminalReadEvidence exact =
          new AutomationSelectedSourceIntakeTerminalReadEvidence(request, receipt);
      if (!Arrays.equals(receiptBytes, exact.receipt().canonicalBytes())
          || !receipt.receiptDigest().equals(exact.receipt().receiptDigest())) {
        throw new IllegalArgumentException("Automation terminal receipt changed during validation");
      }
      return exact;
    } catch (IllegalArgumentException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Automation terminal differs from the exact original authorization")
          .withCause(invalid)
          .asRuntimeException();
    }
  }

  private AccountSelectedOwnerIntakeSettlementReceipt requireSettlement(
      AccountSelectedOwnerIntakeSettlementReceipt receipt,
      SelectedOwnerIntakeAuthorizationBinding original,
      Object expectedOwnerTerminal) {
    if (receipt == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Account terminal settlement receipt is unavailable")
          .asRuntimeException();
    }
    try {
      if (!Arrays.equals(original.canonicalBytes(), receipt.authorizationBinding().canonicalBytes())
          || !original.digest().equals(receipt.authorizationBinding().digest())
          || !original.operationId().equals(receipt.operationId())
          || !namespace.equals(receipt.targetNamespace())
          || !Arrays.equals(
              original.canonicalBytes(),
              (original.owner() == Owner.ENTITY_MANAGEMENT
                      ? receipt.entityTerminalEvidence().request().binding()
                      : receipt.terminalEvidence().request().binding())
                  .canonicalBytes())) {
        throw new IllegalArgumentException("Account settlement changed the original binding");
      }
      AccountSelectedOwnerIntakeSettlementReceipt canonical =
          original.owner() == Owner.ENTITY_MANAGEMENT
              ? AccountSelectedOwnerIntakeSettlementReceipt.create(receipt.entityTerminalEvidence())
              : AccountSelectedOwnerIntakeSettlementReceipt.create(receipt.terminalEvidence());
      if (!Arrays.equals(canonical.canonicalBytes(), receipt.canonicalBytes())
          || !canonical.digest().equals(receipt.digest())
          || (expectedOwnerTerminal
                  instanceof AutomationSelectedSourceIntakeTerminalReadEvidence automation
              && !receipt.sameImmutableOwnerReceipt(automation))
          || (expectedOwnerTerminal instanceof EntitySelectedSourceIntakeTerminalReadEvidence entity
              && !receipt.sameImmutableOwnerReceipt(entity))) {
        throw new IllegalArgumentException(
            "Account settlement receipt changed its canonical owner value");
      }
      return receipt;
    } catch (IllegalArgumentException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Account settlement receipt differs from the original owner terminal")
          .withCause(invalid)
          .asRuntimeException();
    }
  }

  private static boolean sameSettlement(
      AccountSelectedOwnerIntakeSettlementReceipt left,
      AccountSelectedOwnerIntakeSettlementReceipt right) {
    return left.digest().equals(right.digest())
        && Arrays.equals(left.canonicalBytes(), right.canonicalBytes());
  }

  private static String workloadUri(String namespace, String service) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Account terminal settlement requires independent transactions")
          .asRuntimeException();
    }
  }

  private record Lookup(AccountSelectedOwnerIntakeSettlementReceipt prior) {}
}
