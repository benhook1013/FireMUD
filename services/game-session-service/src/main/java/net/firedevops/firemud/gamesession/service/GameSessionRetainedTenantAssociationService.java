package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient.LegacyGameSessionTenantAssociationReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository.AssociationReceipt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unwired source-to-owner transaction boundary for audited retained-tenant association. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Trusted owner dependencies and namespace are validated before use; no resource or"
            + " finalizer is acquired and this class is intentionally not a Spring bean.")
public class GameSessionRetainedTenantAssociationService {
  private static final int SIGNATURE_BYTES = 64;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final GameDesignRuntimeTenantIdentityClient sourceClient;
  private final GameSessionRetainedTenantAssociationRepository repository;
  private final String workloadNamespace;
  private final TransactionTemplate ownerTransaction;

  public GameSessionRetainedTenantAssociationService(
      GameDesignRuntimeTenantIdentityClient sourceClient,
      GameSessionRetainedTenantAssociationRepository repository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.sourceClient = Objects.requireNonNull(sourceClient, "sourceClient");
    this.repository = Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Resolves the authenticated Game Design owner receipt before opening a short local owner
   * transaction, then commits its retained-row association.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public AssociationReceipt associate(
      String associationRequestId,
      String canonicalTenantId,
      String approvalOperationId,
      String legacyGameSessionTenantId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Retained-tenant association source read must begin outside an owner transaction");
    }
    UUID requestUuid = parseCanonicalNonNilUuid(associationRequestId, "associationRequestId");
    UUID tenantUuid = parseCanonicalNonNilUuid(canonicalTenantId, "canonicalTenantId");
    UUID approvalOperationUuid =
        parseCanonicalNonNilUuid(approvalOperationId, "approvalOperationId");
    String legacyKey = parseCanonicalPositiveBigint(legacyGameSessionTenantId);

    UUID readRequestUuid;
    do {
      readRequestUuid = UUID.randomUUID();
    } while (readRequestUuid.equals(requestUuid) || readRequestUuid.equals(approvalOperationUuid));

    LegacyGameSessionTenantAssociationReceipt ownerReceipt =
        sourceClient.resolveLegacyGameSessionTenantAssociation(
            tenantUuid.toString(),
            approvalOperationUuid.toString(),
            legacyKey,
            readRequestUuid.toString());
    LegacyGameSessionTenantAssociationReceipt validatedReceipt =
        validateOwnerReceipt(ownerReceipt, tenantUuid, approvalOperationUuid, legacyKey);

    return ownerTransaction.execute(status -> repository.register(requestUuid, validatedReceipt));
  }

  private LegacyGameSessionTenantAssociationReceipt validateOwnerReceipt(
      LegacyGameSessionTenantAssociationReceipt receipt,
      UUID canonicalTenantId,
      UUID approvalOperationId,
      String legacyTenantKey) {
    if (receipt == null || receipt.evidence() == null) {
      throw new IllegalStateException("Authenticated Game Design approval receipt is missing");
    }
    GameSessionTenantAssociationEvidence received = receipt.evidence();
    GameSessionTenantAssociationEvidence evidence;
    try {
      evidence =
          new GameSessionTenantAssociationEvidence(
              received.schemaVersion(),
              received.operationId(),
              received.targetNamespace(),
              received.signerKeyId(),
              received.approvedBy(),
              received.approvalReference(),
              received.signedAt(),
              received.sourceCapturedAt(),
              received.legacyGameSessionTenantId(),
              received.canonicalTenantId(),
              received.sourceGameRowId(),
              received.sourceGameTenantKey(),
              received.provenanceKind(),
              received.gameSessionProjectionDigest(),
              received.gameSessionEvidenceDigest());
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "Authenticated Game Design approval manifest is invalid", exception);
    }
    if (!workloadNamespace.equals(evidence.targetNamespace())
        || !approvalOperationId.equals(evidence.operationId())
        || !canonicalTenantId.equals(evidence.canonicalTenantId())
        || !legacyTenantKey.equals(evidence.legacyGameSessionTenantId())) {
      throw new IllegalStateException(
          "Authenticated Game Design approval receipt does not match the exact association");
    }
    String manifestDigest = receipt.manifestDigest();
    if (manifestDigest == null || !evidence.manifestDigest().equals(manifestDigest)) {
      throw new IllegalStateException(
          "Authenticated Game Design approval digest does not match its manifest");
    }
    requireCanonicalSignature(receipt.ed25519Signature());
    return new LegacyGameSessionTenantAssociationReceipt(
        evidence, manifestDigest, receipt.ed25519Signature());
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID", exception);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
    return parsed;
  }

  private static String parseCanonicalPositiveBigint(String value) {
    if (value == null || value.length() > 19 || !value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(
          "legacyGameSessionTenantId must be canonical positive BIGINT text");
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0 || !Long.toString(parsed).equals(value)) {
        throw new IllegalArgumentException(
            "legacyGameSessionTenantId must be canonical positive BIGINT text");
      }
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "legacyGameSessionTenantId must fit a positive PostgreSQL BIGINT", exception);
    }
    return value;
  }

  private static void requireCanonicalSignature(String signature) {
    if (signature == null) {
      throw new IllegalStateException("Authenticated Game Design approval signature is missing");
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(signature);
      if (bytes.length != SIGNATURE_BYTES
          || !Base64.getEncoder().encodeToString(bytes).equals(signature)) {
        throw new IllegalStateException(
            "Authenticated Game Design approval signature is not canonical Ed25519 base64");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Authenticated Game Design approval signature is invalid", exception);
    }
  }
}
