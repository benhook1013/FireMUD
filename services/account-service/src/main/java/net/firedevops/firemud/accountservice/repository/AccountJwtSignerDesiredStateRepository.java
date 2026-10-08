package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessPromotionProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Account-owned persistence for the interim mounted JWT signer.
 *
 * <p>Account chooses and persists the operation, generation, and {@code kid}. The materializer can
 * only observe the fixed Secret, then perform one pending-slot generation/CAS or an exact
 * PREPARED-only slot promotion and return public-only results. Account remains the sole durable
 * commit authority; public prepublication, cache convergence, validator probes, and correspondence
 * evidence are separately owned prerequisites and are never inferred by this repository.
 */
@Repository
public class AccountJwtSignerDesiredStateRepository {
  public static final String PRIVATE_SECRET_NAME = "jwt-signing-keys";
  public static final String PUBLIC_JWKS_CONFIG_MAP_NAME = "jwt-jwks";
  public static final String REQUEST_DIGEST_VERSION = "account-jwt-signer-generation-operation/v1";
  public static final String OBSERVATION_DIGEST_VERSION =
      "account-jwt-signer-secret-observation/v1";
  public static final String GENERATION_REQUEST_DIGEST_VERSION =
      "account-jwt-signer-generation-request/v1";
  public static final String RECEIPT_DIGEST_VERSION = "account-jwt-signer-generation-receipt/v1";
  private static final String PROMOTION_DIGEST_VERSION =
      "account-jwt-signer-promotion-operation/v1";

  private static final String STATE_TABLE = "account_jwt_signer_desired_states";
  private static final String OPERATION_TABLE = "account_jwt_signer_generation_operations";
  private static final String OBSERVATION_TABLE = "account_jwt_signer_secret_observations";
  private static final String RESULT_TABLE = "account_jwt_signer_generation_results";
  private static final String PROMOTION_TABLE = "account_jwt_signer_promotion_operations";
  private static final String MODE = "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK";
  private static final String ALGORITHM = "RS256";
  private static final String ACTION = "MATERIALIZE_PENDING";
  private static final String PENDING_SLOT = "pending";
  private static final byte[] PENDING_SLOT_CANONICAL_BYTES =
      "[\"pending\"]".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PROMOTION_SLOTS_CANONICAL_BYTES =
      "[\"current\",\"pending\",\"previous\"]".getBytes(StandardCharsets.UTF_8);
  private static final Pattern ENVIRONMENT_ID =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern CLUSTER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern RESOURCE_VERSION = Pattern.compile("[!-~]{1,256}");
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Set<String> PUBLIC_JWK_FIELDS =
      Set.of("kty", "use", "alg", "kid", "key_ops", "n", "e");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonMapper STRICT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final DSLContext dsl;

  @Autowired
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The constructor only validates its trusted injected DSLContext; it performs no I/O or resource acquisition and defines no finalizer.")
  public AccountJwtSignerDesiredStateRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Enrolls the fixed Account environment only after both protected trust bindings and a live
   * fixed-ConfigMap observation have been reconciled by the enrollment owner.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public DesiredState initialize(Binding binding, EnrollmentIdentity enrollmentIdentity) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(binding, "JWT signer binding is required");
    Objects.requireNonNull(enrollmentIdentity, "Pinned JWT signer enrollment is required");
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + STATE_TABLE
                + " (environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "private_secret_name, public_jwks_config_map_name, record_version, "
                + "durable_active_generation, durable_active_kid, published_active_generation, "
                + "published_active_kid, generation_operation_id, prepared_operation_id, "
                + "enrollment_cluster_incarnation_uid, enrollment_namespace_uid, "
                + "enrollment_materializer_binding_digest, enrollment_materializer_config_revision, "
                + "enrollment_api_binding_digest, enrollment_api_config_revision, "
                + "enrollment_public_config_map_uid, enrollment_public_config_map_resource_version, "
                + "enrollment_public_config_map_snapshot_digest) "
                + "VALUES (?, ?, ?, ?, 'jwt-signing-keys', 'jwt-jwks', 1, NULL, NULL, NULL, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (environment_id) DO NOTHING",
            binding.environmentId(),
            binding.clusterId(),
            binding.namespace(),
            binding.mode().value(),
            UUID.fromString(enrollmentIdentity.expectedClusterIncarnationUid()),
            UUID.fromString(enrollmentIdentity.expectedNamespaceUid()),
            enrollmentIdentity.materializerTrustBindingDigest(),
            enrollmentIdentity.materializerTrustConfigRevision(),
            enrollmentIdentity.apiBindingDigest(),
            enrollmentIdentity.apiConfigRevision(),
            UUID.fromString(enrollmentIdentity.publicConfigMapUid()),
            enrollmentIdentity.publicConfigMapResourceVersion(),
            enrollmentIdentity.publicConfigMapSnapshotDigest());
    if (inserted < 0 || inserted > 1) {
      throw new StorageUnavailableException("Account JWT signer initialization was ambiguous");
    }
    Record row = selectState(binding.environmentId(), true);
    if (row == null) {
      throw new QuarantinedStateException("Initialized Account JWT signer state did not read back");
    }
    DesiredState state = decodeState(row);
    requireBinding(state, binding);
    if (state.enrollmentIdentity().isEmpty()
        || !state.enrollmentIdentity().orElseThrow().sameStablePins(enrollmentIdentity)) {
      throw new BindingMismatchException(
          "Account JWT signer enrollment trust or precreated resource identity changed");
    }
    if (inserted == 1 && !state.enrollmentIdentity().orElseThrow().equals(enrollmentIdentity)) {
      throw new QuarantinedStateException(
          "Initial Account JWT signer enrollment receipt did not read back exactly");
    }
    if (inserted == 1
        && (state.recordVersion() != 1
            || state.durableActive().isPresent()
            || state.publishedActive().isPresent()
            || state.generationOperationId().isPresent()
            || state.preparedOperationId().isPresent())) {
      throw new QuarantinedStateException(
          "New Account JWT signer state contains inferred or unexpected authority");
    }
    return state;
  }

  /**
   * Returns a pinned enrollment row even while PREPARED so startup reconciliation can inspect it.
   * Ordinary desired-state reads continue to quarantine PREPARED signer state.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<DesiredState> readEnrollmentState(Binding expectedBinding) {
    requireAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Record row = selectState(expectedBinding.environmentId(), false);
    if (row == null) {
      return Optional.empty();
    }
    DesiredState state = decodeState(row);
    requireBinding(state, expectedBinding);
    if (state.enrollmentIdentity().isEmpty()) {
      throw new QuarantinedStateException(
          "Account JWT signer state has no independently protected enrollment identity");
    }
    return Optional.of(state);
  }

  /** Reads an explicitly enrolled state; a missing row never initializes desired state. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public DesiredState read(Binding expectedBinding) {
    requireAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Record row = selectState(expectedBinding.environmentId(), false);
    if (row == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(row);
    requireBinding(state, expectedBinding);
    if (state.enrollmentIdentity().isEmpty()) {
      throw new QuarantinedStateException(
          "Account JWT signer state has no independently protected enrollment identity");
    }
    verifyPreparedIsUnavailable(state);
    if (state.generationOperationId().isPresent()) {
      StoredGenerationOperation operation =
          selectGenerationOperation(state.generationOperationId().orElseThrow(), false);
      if (operation == null) {
        throw new QuarantinedStateException("Account JWT signer generation operation is missing");
      }
      verifyOperationMatchesState(operation, state);
    }
    return state;
  }

  /**
   * Account's internal operation entrypoint. It receives no caller-selected operation, key
   * identity, resource name, fingerprint, or material. Account creates one immutable operation if
   * absent, or returns the same operation under the same protected trust snapshot.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public GenerationRequest ensureCurrentGenerationRequest(
      Binding expectedBinding, TrustFence trust) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    verifyPreparedIsUnavailable(state);
    requireEnrollmentTrust(state, trust);

    if (state.generationOperationId().isEmpty()) {
      if (state.durableActive().isPresent()) {
        throw new MissingGenerationOperationException(
            "Account has no durable rotation request for its committed signer");
      }
      return createGenerationRequest(state, trust);
    }

    StoredGenerationOperation operation =
        selectGenerationOperation(state.generationOperationId().orElseThrow(), true);
    if (operation == null) {
      throw new QuarantinedStateException("Account JWT signer generation operation is missing");
    }
    verifyOperationMatchesState(operation, state);
    requireCurrentTrust(operation, trust);
    return readGenerationRequest(operation, state, true);
  }

  /**
   * Returns the same current Account-selected request and exact phase for an authenticated peer.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public GenerationRequest readCurrentGenerationRequest(Binding expectedBinding, TrustFence trust) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    verifyPreparedIsUnavailable(state);
    requireEnrollmentTrust(state, trust);
    UUID operationId =
        state
            .generationOperationId()
            .orElseThrow(
                () ->
                    new MissingGenerationOperationException(
                        "Account has no owner-selected JWT generation operation"));
    StoredGenerationOperation operation = selectGenerationOperation(operationId, true);
    if (operation == null) {
      throw new QuarantinedStateException("Account JWT signer generation operation is missing");
    }
    verifyOperationMatchesState(operation, state);
    requireCurrentTrust(operation, trust);
    return readGenerationRequest(operation, state, true);
  }

  /**
   * Reads the exact immutable result for Account's current generation operation while locking the
   * desired-state, operation, and result rows in their canonical order. A historical result is
   * never returned after the Account operation or protected trust fence has moved.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public GenerationResult readCurrentGenerationResult(Binding expectedBinding, TrustFence trust) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    verifyPreparedIsUnavailable(state);
    requireEnrollmentTrust(state, trust);
    UUID operationId =
        state
            .generationOperationId()
            .orElseThrow(
                () ->
                    new MissingGenerationOperationException(
                        "Account has no owner-selected JWT generation operation"));
    StoredGenerationOperation operation = selectGenerationOperation(operationId, true);
    if (operation == null) {
      throw new QuarantinedStateException("Account JWT signer generation operation is missing");
    }
    verifyOperationMatchesState(operation, state);
    requireCurrentTrust(operation, trust);
    GenerationResult result = selectGenerationResult(operationId, true);
    if (result == null) {
      throw new MissingGenerationObservationException(
          "Account JWT generation result is not recorded for the current operation");
    }
    verifyGenerationResultMatchesOperation(result, operation, state);
    return result;
  }

  /**
   * Recovery-only read for the exact generation that owns the current PREPARED operation. It is
   * intentionally distinct from ordinary serving/generation reads, which continue to fail closed
   * while a promotion is unresolved.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PreparedGenerationEvidence readPreparedGenerationForRecovery(
      Binding expectedBinding, TrustFence trust) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    requireEnrollmentTrust(state, trust);
    UUID promotionId =
        state.preparedOperationId().orElseThrow(() -> new NoPreparedPromotionException());
    if (state.generationOperationId().isPresent()) {
      throw new QuarantinedStateException(
          "Account PREPARED signer state also contains a generation pointer");
    }
    StoredPromotion promotion = selectPromotion(promotionId, true);
    if (promotion == null || !"PREPARED".equals(promotion.status())) {
      throw new QuarantinedStateException("Account PREPARED JWT signer operation is missing");
    }
    requirePromotionMatchesState(promotion, state);
    requireCurrentTrust(promotion, trust);
    StoredGenerationOperation operation =
        selectGenerationOperation(promotion.generationOperationId(), true);
    GenerationResult result = selectGenerationResult(promotion.generationOperationId(), true);
    if (operation == null || result == null) {
      throw new QuarantinedStateException(
          "Prepared JWT signer operation has incomplete generation evidence");
    }
    verifyPreparedGenerationMatches(promotion, operation, result);
    return new PreparedGenerationEvidence(state, promotion.publicEvidence(), result);
  }

  /**
   * Atomically records that the authenticated materializer may receive the immutable private-slot
   * promotion operation. This marker is deliberately sticky: after it is committed, a lost RPC
   * response is an uncertain external mutation and the operation must remain PREPARED for forward
   * reconciliation.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PreparedGenerationEvidence readAndMarkPreparedPromotionDispatched(
      Binding expectedBinding, TrustFence trust) {
    requireWritableAccountTransaction();
    PreparedGenerationEvidence evidence = readPreparedGenerationForRecovery(expectedBinding, trust);
    UUID operationId = evidence.promotion().operationId();
    StoredPromotion promotion = selectPromotion(operationId, true);
    if (promotion == null || !"PREPARED".equals(promotion.status())) {
      throw new QuarantinedStateException("Account PREPARED JWT signer operation is missing");
    }
    if (!promotion.privatePromotionDispatched()) {
      int changed =
          dsl.execute(
              "UPDATE "
                  + PROMOTION_TABLE
                  + " SET private_promotion_dispatched_at = CURRENT_TIMESTAMP "
                  + "WHERE operation_id = ? AND status = 'PREPARED' "
                  + "AND private_promotion_dispatched_at IS NULL "
                  + "AND private_promotion_receipt_digest IS NULL "
                  + "AND active_jwks_receipt_digest IS NULL",
              operationId);
      if (changed != 1) {
        throw new VersionConflictException(
            "Account private promotion dispatch authorization CAS did not apply");
      }
      promotion = selectPromotion(operationId, true);
    }
    if (promotion == null
        || !promotion.privatePromotionDispatched()
        || !"PREPARED".equals(promotion.status())) {
      throw new QuarantinedStateException(
          "Account private promotion dispatch authorization did not read back exactly");
    }
    return evidence;
  }

  /**
   * Records one authenticated materializer readback of the exact fixed-Secret slot transition. Only
   * public key identities, resource UID/RV, and slot names cross this boundary; private bytes and
   * hashes of private bytes are neither accepted nor persisted.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PrivatePromotionReceipt recordPrivatePromotionResult(
      Binding expectedBinding, TrustFence trust, PrivatePromotionObservation observation) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    Objects.requireNonNull(observation, "Exact private promotion observation is required");
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    requireEnrollmentTrust(state, trust);
    if (!state.preparedOperationId().equals(Optional.of(observation.promotionOperationId()))
        || state.generationOperationId().isPresent()) {
      throw new StaleGenerationOperationException(
          "Private promotion result does not target Account's current PREPARED operation");
    }
    StoredPromotion promotion = selectPromotion(observation.promotionOperationId(), true);
    if (promotion == null || !"PREPARED".equals(promotion.status())) {
      throw new QuarantinedStateException("Account PREPARED JWT signer operation is missing");
    }
    requirePromotionMatchesState(promotion, state);
    requireCurrentTrust(promotion, trust);
    if (!promotion.requestDigest().equals(observation.promotionRequestDigest())
        || !promotion.generationOperationId().equals(observation.generationOperationId())
        || !promotion.generationOperationDigest().equals(observation.generationOperationDigest())) {
      throw new IdempotencyConflictException(
          "Private promotion result does not match the exact Account operation");
    }
    if (!promotion.privatePromotionDispatched()) {
      throw new PromotionPrerequisitesIncompleteException(
          "Private promotion result has no prior durable Account dispatch authorization");
    }
    StoredGenerationOperation operation =
        selectGenerationOperation(promotion.generationOperationId(), true);
    GenerationResult result = selectGenerationResult(promotion.generationOperationId(), true);
    if (operation == null || result == null) {
      throw new QuarantinedStateException(
          "Prepared JWT signer operation has incomplete generation evidence");
    }
    verifyPreparedGenerationMatches(promotion, operation, result);
    requirePrivatePromotionObservation(promotion, observation);
    PrivatePromotionReceipt candidate = PrivatePromotionReceipt.from(promotion, observation);
    if (promotion.privatePromotionReceiptDigest() != null) {
      if (!candidate.receiptDigest().equals(promotion.privatePromotionReceiptDigest())
          || !candidate
              .observedResourceVersion()
              .equals(promotion.privatePromotionObservedResourceVersion())) {
        throw new IdempotencyConflictException(
            "Private promotion readback changed for the same Account operation");
      }
      return candidate;
    }
    int updated =
        dsl.execute(
            "UPDATE "
                + PROMOTION_TABLE
                + " SET private_promotion_observed_resource_version = ?, "
                + "private_promotion_receipt_digest = ? WHERE operation_id = ? "
                + "AND status = 'PREPARED' AND private_promotion_dispatched_at IS NOT NULL "
                + "AND private_promotion_receipt_digest IS NULL",
            candidate.observedResourceVersion(),
            candidate.receiptDigest(),
            candidate.promotionOperationId());
    if (updated != 1) {
      throw new VersionConflictException("Account private promotion receipt CAS did not apply");
    }
    StoredPromotion readback = selectPromotion(candidate.promotionOperationId(), true);
    if (readback == null
        || !candidate.receiptDigest().equals(readback.privatePromotionReceiptDigest())
        || !candidate
            .observedResourceVersion()
            .equals(readback.privatePromotionObservedResourceVersion())) {
      throw new QuarantinedStateException(
          "Account private promotion receipt did not read back exactly");
    }
    return candidate;
  }

  /**
   * Records Account's exact readback after its fixed public ConfigMap CAS changes only the
   * generation marker from PREPUBLISHED to ACTIVE. The public receipt contains no private Secret
   * bytes or digest of private material.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ActiveJwksPromotionReceipt recordActiveJwksPromotionResult(
      Binding expectedBinding, TrustFence trust, ActiveJwksPromotionObservation observation) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    Objects.requireNonNull(observation, "Exact Account ConfigMap promotion readback is required");
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    requireEnrollmentTrust(state, trust);
    UUID promotionId =
        state
            .preparedOperationId()
            .orElseThrow(
                () ->
                    new PromotionPrerequisitesIncompleteException(
                        "Account has no PREPARED JWT signer promotion"));
    StoredPromotion promotion = selectPromotion(promotionId, true);
    if (promotion == null || !"PREPARED".equals(promotion.status())) {
      throw new QuarantinedStateException("Account PREPARED JWT signer operation is missing");
    }
    requirePromotionMatchesState(promotion, state);
    requireCurrentTrust(promotion, trust);
    if (promotion.privatePromotionReceiptDigest() == null) {
      throw new PromotionPrerequisitesIncompleteException(
          "Account cannot publish ACTIVE before the exact private-slot readback");
    }
    requireActiveJwksObservation(promotion, observation);
    ActiveJwksPromotionReceipt candidate = ActiveJwksPromotionReceipt.from(promotion, observation);
    if (promotion.activeJwksReceiptDigest() != null) {
      if (!candidate.receiptDigest().equals(promotion.activeJwksReceiptDigest())
          || !candidate.publicDataDigest().equals(promotion.activeJwksPublicDataDigest())
          || !candidate
              .observedResourceVersion()
              .equals(promotion.activeJwksObservedResourceVersion())) {
        throw new IdempotencyConflictException(
            "Active Account JWT JWKS readback changed for the same promotion operation");
      }
      return candidate;
    }
    int updated =
        dsl.execute(
            "UPDATE "
                + PROMOTION_TABLE
                + " SET active_jwks_observed_resource_version = ?, active_jwks_public_data_digest = ?, "
                + "active_jwks_receipt_digest = ? WHERE operation_id = ? AND status = 'PREPARED' "
                + "AND private_promotion_receipt_digest IS NOT NULL "
                + "AND active_jwks_receipt_digest IS NULL",
            candidate.observedResourceVersion(),
            candidate.publicDataDigest(),
            candidate.receiptDigest(),
            candidate.promotionOperationId());
    if (updated != 1) {
      throw new VersionConflictException("Account active JWKS promotion receipt CAS did not apply");
    }
    StoredPromotion readback = selectPromotion(candidate.promotionOperationId(), true);
    if (readback == null
        || !candidate.receiptDigest().equals(readback.activeJwksReceiptDigest())
        || !candidate.publicDataDigest().equals(readback.activeJwksPublicDataDigest())
        || !candidate
            .observedResourceVersion()
            .equals(readback.activeJwksObservedResourceVersion())) {
      throw new QuarantinedStateException("Account active JWKS receipt did not read back exactly");
    }
    return candidate;
  }

  /**
   * Commits only after both exact external-resource receipts are durably read back. The promotion
   * row and desired-state active fences transition in one Account transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CommittedSignerEvidence commitPreparedPromotion(
      Binding expectedBinding,
      TrustFence trust,
      UUID promotionOperationId,
      ReadinessPromotionProof readinessProof) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    requireOperationId(promotionOperationId);
    Objects.requireNonNull(readinessProof, "Current Account readiness proof is required");
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    requireEnrollmentTrust(state, trust);
    if (!state.preparedOperationId().equals(Optional.of(promotionOperationId))) {
      throw new StaleGenerationOperationException(
          "Commit does not target Account's exact PREPARED promotion");
    }
    StoredPromotion promotion = selectPromotion(promotionOperationId, true);
    if (promotion == null || !"PREPARED".equals(promotion.status())) {
      throw new QuarantinedStateException("Account PREPARED JWT signer operation is missing");
    }
    requirePromotionMatchesState(promotion, state);
    requireCurrentTrust(promotion, trust);
    if (promotion.privatePromotionReceiptDigest() == null
        || promotion.activeJwksReceiptDigest() == null) {
      throw new PromotionPrerequisitesIncompleteException(
          "Account promotion lacks exact private and public resource receipts");
    }
    if (!promotion.privatePromotionDispatched()) {
      throw new QuarantinedStateException(
          "Account promotion receipts have no durable private dispatch authorization");
    }
    StoredGenerationOperation generation =
        selectGenerationOperation(promotion.generationOperationId(), true);
    GenerationResult result = selectGenerationResult(promotion.generationOperationId(), true);
    if (generation == null || result == null) {
      throw new QuarantinedStateException("Committed signer generation evidence is missing");
    }
    verifyPreparedGenerationMatches(promotion, generation, result);
    PromotionPreparation commitPreparation =
        new PromotionPreparation(
            promotion.generationOperationId(),
            result,
            state.enrollmentIdentity().orElseThrow(),
            promotion.apiBindingDigest(),
            promotion.apiConfigRevision(),
            promotion.publicConfigMapUid(),
            promotion.expectedPublicResourceVersion(),
            promotion.prepublicationIntentDigest(),
            promotion.prepublicationReceiptDigest(),
            promotion.mountedObservationDigest(),
            promotion.readinessPlanDigest(),
            promotion.readinessEvidenceDigest(),
            promotion.expectedPublicJwksJson());
    requireReadinessProofMatches(
        result, commitPreparation, promotion, expectedBinding, trust, readinessProof);
    requireUnexpiredCurrentReadinessProof(promotion, result, readinessProof);

    // State is locked first above, matching readiness expiry/cleanup's owner lock order. The
    // terminal UPDATE repeats the clock and complete-entry fence atomically; the count query in
    // the helper is a validation read, not an entry-row lock.
    int changed =
        dsl.execute(
            "UPDATE "
                + PROMOTION_TABLE
                + " SET status = 'COMMITTED' WHERE operation_id = ? "
                + "AND status = 'PREPARED' AND private_promotion_dispatched_at IS NOT NULL "
                + "AND private_promotion_receipt_digest IS NOT NULL "
                + "AND active_jwks_receipt_digest IS NOT NULL "
                + "AND EXISTS (SELECT 1 FROM account_jwt_readiness_probe_plans plan "
                + "WHERE plan.rotation_operation_id = ? AND plan.plan_digest = ? "
                + "AND plan.validator_inventory_complete = TRUE "
                + "AND plan.not_before_epoch_seconds <= "
                + "floor(extract(epoch FROM clock_timestamp()))::BIGINT "
                + "AND plan.expires_at_epoch_seconds > "
                + "floor(extract(epoch FROM clock_timestamp()))::BIGINT) "
                + "AND (SELECT count(*) FROM account_jwt_readiness_probe_entries entry "
                + "WHERE entry.rotation_operation_id = ? AND entry.plan_digest = ?) = ? "
                + "AND (SELECT count(*) FROM account_jwt_readiness_probe_entries entry "
                + "WHERE entry.rotation_operation_id = ? AND entry.plan_digest = ? "
                + "AND entry.state = 'VERIFIED' AND entry.verification_receipt_sha256 IS NOT NULL "
                + "AND entry.verified_at_epoch_seconds < entry.expires_at_epoch_seconds "
                + "AND entry.expires_at_epoch_seconds > "
                + "floor(extract(epoch FROM clock_timestamp()))::BIGINT) = ?",
            promotionOperationId,
            promotion.generationOperationId(),
            promotion.readinessPlanDigest(),
            promotion.generationOperationId(),
            promotion.readinessPlanDigest(),
            readinessProof.verifiedProbes().size(),
            promotion.generationOperationId(),
            promotion.readinessPlanDigest(),
            readinessProof.verifiedProbes().size());
    if (changed != 1) {
      throw new VersionConflictException("Account promotion terminal CAS did not apply");
    }
    int advanced =
        dsl.execute(
            "UPDATE "
                + STATE_TABLE
                + " SET record_version = record_version + 1, durable_active_generation = ?, "
                + "durable_active_kid = ?, published_active_generation = ?, published_active_kid = ?, "
                + "prepared_operation_id = NULL, updated_at = CURRENT_TIMESTAMP "
                + "WHERE environment_id = ? AND cluster_id = ? AND kubernetes_namespace = ? "
                + "AND custody_mode = ? AND record_version = ? AND prepared_operation_id = ? "
                + "AND generation_operation_id IS NULL "
                + "AND durable_active_generation IS NOT DISTINCT FROM ? "
                + "AND durable_active_kid IS NOT DISTINCT FROM ? "
                + "AND published_active_generation IS NOT DISTINCT FROM ? "
                + "AND published_active_kid IS NOT DISTINCT FROM ?",
            parseGeneration(promotion.targetGeneration()),
            promotion.targetKid(),
            parseGeneration(promotion.targetGeneration()),
            promotion.targetKid(),
            state.binding().environmentId(),
            state.binding().clusterId(),
            state.binding().namespace(),
            state.binding().mode().value(),
            state.recordVersion(),
            promotionOperationId,
            generationValue(state.durableActive()),
            kidValue(state.durableActive()),
            generationValue(state.publishedActive()),
            kidValue(state.publishedActive()));
    if (advanced != 1) {
      throw new VersionConflictException("Account committed active-signer CAS did not apply");
    }
    StoredPromotion committed = selectPromotion(promotionOperationId, true);
    Record committedStateRow = selectState(expectedBinding.environmentId(), true);
    if (committed == null || committedStateRow == null || !"COMMITTED".equals(committed.status())) {
      throw new QuarantinedStateException("Account committed promotion did not read back");
    }
    DesiredState committedState = decodeState(committedStateRow);
    requireCommittedStateMatches(committed, committedState);
    return new CommittedSignerEvidence(
        committed.publicEvidence(),
        result,
        committedState,
        PrivatePromotionReceipt.fromStored(committed),
        ActiveJwksPromotionReceipt.fromStored(committed));
  }

  /**
   * Aborts only before either Kubernetes mutation was recorded. Once either external write may have
   * happened, the operation remains PREPARED for exact forward recovery rather than guessing
   * rollback state.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AbortedPromotion abortPreparedBeforeMutation(
      Binding expectedBinding, TrustFence trust, UUID promotionOperationId) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    requireOperationId(promotionOperationId);
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    requireEnrollmentTrust(state, trust);
    if (!state.preparedOperationId().equals(Optional.of(promotionOperationId))) {
      throw new StaleGenerationOperationException(
          "Abort does not target the exact PREPARED operation");
    }
    StoredPromotion promotion = selectPromotion(promotionOperationId, true);
    if (promotion == null || !"PREPARED".equals(promotion.status())) {
      throw new QuarantinedStateException("Account PREPARED JWT signer operation is missing");
    }
    requirePromotionMatchesState(promotion, state);
    requireCurrentTrust(promotion, trust);
    if (promotion.privatePromotionDispatched()
        || promotion.privatePromotionReceiptDigest() != null
        || promotion.activeJwksReceiptDigest() != null) {
      throw new PromotionPrerequisitesIncompleteException(
          "A dispatched promotion must remain PREPARED for forward reconciliation");
    }
    int changed =
        dsl.execute(
            "UPDATE "
                + PROMOTION_TABLE
                + " SET status = 'ABORTED' WHERE operation_id = ? "
                + "AND status = 'PREPARED' AND private_promotion_receipt_digest IS NULL "
                + "AND active_jwks_receipt_digest IS NULL "
                + "AND private_promotion_dispatched_at IS NULL",
            promotionOperationId);
    if (changed != 1)
      throw new VersionConflictException("Account promotion abort CAS did not apply");
    int advanced =
        dsl.execute(
            "UPDATE "
                + STATE_TABLE
                + " SET record_version = record_version + 1, prepared_operation_id = NULL, "
                + "updated_at = CURRENT_TIMESTAMP WHERE environment_id = ? AND record_version = ? "
                + "AND prepared_operation_id = ? AND generation_operation_id IS NULL",
            expectedBinding.environmentId(),
            state.recordVersion(),
            promotionOperationId);
    if (advanced != 1)
      throw new VersionConflictException("Account signer abort state CAS did not apply");
    StoredPromotion aborted = selectPromotion(promotionOperationId, true);
    DesiredState abortedState = decodeState(selectState(expectedBinding.environmentId(), true));
    if (aborted == null
        || !"ABORTED".equals(aborted.status())
        || abortedState.preparedOperationId().isPresent()) {
      throw new QuarantinedStateException("Account aborted promotion did not read back exactly");
    }
    return new AbortedPromotion(
        promotionOperationId,
        abortedState.recordVersion(),
        abortedState.durableActive(),
        abortedState.publishedActive());
  }

  /** Reads only the exact Account-committed generation and both durable resource receipts. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CommittedSignerEvidence> readCurrentCommittedSigner(
      Binding expectedBinding,
      TrustFence trust,
      String currentApiBindingDigest,
      String currentApiConfigRevision) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) return Optional.empty();
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    requireEnrollmentTrust(state, trust);
    EnrollmentIdentity enrollment = state.enrollmentIdentity().orElseThrow();
    requireMatch(SHA256_HEX, currentApiBindingDigest, "current public API binding digest");
    requireMatch(CLUSTER_ID, currentApiConfigRevision, "current public API config revision");
    if (!enrollment.apiBindingDigest().equals(currentApiBindingDigest)
        || !enrollment.apiConfigRevision().equals(currentApiConfigRevision)) {
      throw new BindingMismatchException("Protected Account JWKS API binding changed");
    }
    if (state.preparedOperationId().isPresent()) {
      throw new PromotionPrerequisitesIncompleteException(
          "Account signer lifecycle is unresolved and cannot serve a committed signer");
    }
    if (state.durableActive().isEmpty() || !state.durableActive().equals(state.publishedActive())) {
      throw new QuarantinedStateException(
          "Account durable and published active signer fences disagree");
    }
    ActiveSigner active = state.durableActive().orElseThrow();
    if (state.generationOperationId().isPresent()) {
      StoredGenerationOperation pending =
          selectGenerationOperation(state.generationOperationId().orElseThrow(), false);
      if (pending == null) {
        throw new QuarantinedStateException("Current Account generation operation is missing");
      }
      verifyOperationMatchesState(pending, state);
      requireCurrentTrust(pending, trust);
    }
    StoredPromotion promotion = selectCommittedPromotion(state, active);
    if (promotion == null) {
      throw new QuarantinedStateException(
          "Account active signer has no exact committed promotion receipt");
    }
    requireCurrentTrust(promotion, trust);
    requireCommittedActiveStillCurrent(promotion, state);
    if (!promotion.apiBindingDigest().equals(currentApiBindingDigest)
        || !promotion.apiConfigRevision().equals(currentApiConfigRevision)) {
      throw new BindingMismatchException(
          "Committed signer uses an obsolete Account JWKS API binding");
    }
    StoredGenerationOperation generation =
        selectGenerationOperation(promotion.generationOperationId(), false);
    GenerationResult result = selectGenerationResult(promotion.generationOperationId(), false);
    if (generation == null || result == null) {
      throw new QuarantinedStateException("Committed Account generation result is missing");
    }
    verifyPreparedGenerationMatches(promotion, generation, result);
    if (promotion.privatePromotionReceiptDigest() == null
        || promotion.activeJwksReceiptDigest() == null) {
      throw new QuarantinedStateException(
          "Committed Account signer is missing exact resource receipts");
    }
    return Optional.of(
        new CommittedSignerEvidence(
            promotion.publicEvidence(),
            result,
            state,
            PrivatePromotionReceipt.fromStored(promotion),
            ActiveJwksPromotionReceipt.fromStored(promotion)));
  }

  /**
   * Reads the original committed signer after ordinary rotation. This authenticates retained
   * lifecycle provenance only; the caller must separately verify that the original public JWK is
   * still accepted by the current protected JWKS source. No current-active equality is imposed.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<OriginalCommittedSignerEvidence> readOriginalCommittedSigner(
      Binding expectedBinding,
      TrustFence trust,
      String currentApiBindingDigest,
      String currentApiConfigRevision,
      UUID promotionOperationId,
      UUID generationOperationId) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding);
    Objects.requireNonNull(trust);
    requireOperationId(promotionOperationId);
    requireOperationId(generationOperationId);
    requireMatch(SHA256_HEX, currentApiBindingDigest, "current public API binding digest");
    requireMatch(CLUSTER_ID, currentApiConfigRevision, "current public API config revision");
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) return Optional.empty();
    DesiredState current = decodeState(stateRow);
    requireBinding(current, expectedBinding);
    requireEnrollmentTrust(current, trust);
    EnrollmentIdentity enrollment = current.enrollmentIdentity().orElseThrow();
    if (!enrollment.apiBindingDigest().equals(currentApiBindingDigest)
        || !enrollment.apiConfigRevision().equals(currentApiConfigRevision)) {
      throw new BindingMismatchException("Protected Account JWKS API binding changed");
    }
    StoredPromotion promotion = selectPromotion(promotionOperationId, false);
    if (promotion == null) return Optional.empty();
    requireCurrentTrust(promotion, trust);
    if (!"COMMITTED".equals(promotion.status())
        || !expectedBinding.equals(promotion.binding())
        || !generationOperationId.equals(promotion.generationOperationId())
        || !currentApiBindingDigest.equals(promotion.apiBindingDigest())
        || !currentApiConfigRevision.equals(promotion.apiConfigRevision())) {
      throw new QuarantinedStateException("Original committed signer identity differs");
    }
    StoredGenerationOperation generation = selectGenerationOperation(generationOperationId, false);
    GenerationResult result = selectGenerationResult(generationOperationId, false);
    if (generation == null || result == null) {
      throw new QuarantinedStateException("Original committed signer generation is absent");
    }
    requireCurrentTrust(generation, trust);
    verifyPreparedGenerationMatches(promotion, generation, result);
    if (promotion.privatePromotionReceiptDigest() == null
        || promotion.activeJwksReceiptDigest() == null) {
      throw new QuarantinedStateException("Original committed signer resource receipts are absent");
    }
    return Optional.of(
        new OriginalCommittedSignerEvidence(
            promotion.publicEvidence(),
            result,
            PrivatePromotionReceipt.fromStored(promotion),
            ActiveJwksPromotionReceipt.fromStored(promotion)));
  }

  /** Immutable historical owner readback, not an active-key or authenticated-actor grant. */
  public record OriginalCommittedSignerEvidence(
      PromotionOperationEvidence promotion,
      GenerationResult generationResult,
      PrivatePromotionReceipt privateReceipt,
      ActiveJwksPromotionReceipt publicReceipt) {
    public OriginalCommittedSignerEvidence {
      Objects.requireNonNull(promotion);
      Objects.requireNonNull(generationResult);
      Objects.requireNonNull(privateReceipt);
      Objects.requireNonNull(publicReceipt);
      if (!"COMMITTED".equals(promotion.status())
          || !promotion.generationOperationId().equals(generationResult.operationId())
          || !promotion.operationId().equals(privateReceipt.promotionOperationId())
          || !promotion.operationId().equals(publicReceipt.promotionOperationId())) {
        throw new QuarantinedStateException("Original committed signer linkage is inconsistent");
      }
    }
  }

  /** Persists the pinned materializer's read-only observation of the fixed pre-created Secret. */
  @Transactional(propagation = Propagation.MANDATORY)
  public GenerationRequest recordSecretObservation(
      Binding expectedBinding,
      TrustFence trust,
      UUID operationId,
      String operationDigest,
      String secretUid,
      String observedResourceVersion) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    requireOperationId(operationId);
    requireMatch(SHA256_HEX, operationDigest, "JWT generation operation digest");
    requireUuid(secretUid, "fixed Secret UID");
    requireMatch(RESOURCE_VERSION, observedResourceVersion, "observed Secret resourceVersion");

    LockedCurrent current = lockCurrentOperation(expectedBinding, trust, operationId);
    if (!current.operation().operationDigest().equals(operationDigest)) {
      throw new IdempotencyConflictException(
          "JWT signer Secret observation does not match the Account operation digest");
    }
    SecretObservation candidate =
        SecretObservation.from(current.operation(), secretUid, observedResourceVersion);
    SecretObservation existing = selectSecretObservation(operationId, true);
    if (existing != null) {
      if (!existing.equals(candidate)) {
        throw new IdempotencyConflictException(
            "JWT signer Secret observation changed for the same Account operation");
      }
      return readGenerationRequest(current.operation(), current.state(), true);
    }

    insertSecretObservation(candidate);
    SecretObservation readback = selectSecretObservation(operationId, true);
    if (readback == null || !readback.equals(candidate)) {
      throw new QuarantinedStateException(
          "Account JWT signer Secret observation did not read back exactly");
    }
    return toGenerationRequest(current.operation(), current.state(), readback, null);
  }

  /**
   * Persists one public-only key-generation result. Account validates the canonical RSA JWK and
   * derives the RFC 7638 fingerprint; private members, tokens, and caller fingerprints are not
   * accepted. Exact retries return the immutable receipt; changed/stale retries fail closed.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public GenerationResult recordGenerationResult(
      Binding expectedBinding,
      TrustFence trust,
      UUID operationId,
      String generationRequestDigest,
      String secretUid,
      String expectedPriorResourceVersion,
      String observedResourceVersion,
      String publicJwkJson) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    requireOperationId(operationId);
    requireMatch(SHA256_HEX, generationRequestDigest, "JWT generation request digest");
    requireUuid(secretUid, "fixed Secret UID");
    requireMatch(RESOURCE_VERSION, expectedPriorResourceVersion, "expected Secret resourceVersion");
    requireMatch(RESOURCE_VERSION, observedResourceVersion, "observed Secret resourceVersion");
    if (expectedPriorResourceVersion.equals(observedResourceVersion)) {
      throw new QuarantinedStateException("JWT signer Secret resourceVersion did not advance");
    }
    PublicJwk publicJwk = parsePublicJwk(publicJwkJson);

    LockedCurrent current = lockCurrentOperation(expectedBinding, trust, operationId);
    StoredGenerationOperation operation = current.operation();
    SecretObservation observation = selectSecretObservation(operationId, true);
    if (observation == null) {
      throw new MissingGenerationObservationException(
          "JWT signer generation cannot precede exact Secret observation");
    }
    if (!observation.secretUid().equals(secretUid)
        || !observation.observedResourceVersion().equals(expectedPriorResourceVersion)
        || !observation.generationRequestDigest().equals(generationRequestDigest)) {
      throw new StaleGenerationOperationException(
          "JWT signer generation result does not match the observed Secret fence");
    }
    if (!publicJwk.kid().equals(operation.targetKid())
        || !ALGORITHM.equals(operation.targetAlgorithm())) {
      throw new QuarantinedStateException(
          "JWT signer public JWK does not match the Account-selected key identity");
    }

    GenerationResult candidate =
        GenerationResult.from(
            operation,
            current.state().recordVersion(),
            observation,
            observedResourceVersion,
            publicJwk);
    GenerationResult existing = selectGenerationResult(operationId, true);
    if (existing != null) {
      if (!existing.equals(candidate)) {
        throw new IdempotencyConflictException(
            "JWT signer generation result changed for the same Account operation");
      }
      return existing;
    }

    insertGenerationResult(candidate);
    GenerationResult readback = selectGenerationResult(operationId, true);
    if (readback == null || !readback.equals(candidate)) {
      throw new QuarantinedStateException(
          "Account JWT signer generation result did not read back exactly");
    }
    return readback;
  }

  /**
   * Persists PREPARED only for exact owner-readback generation, prepublication, and readiness
   * evidence supplied by the lifecycle coordinator. The database independently locks and checks the
   * generation/publication/readiness rows; caller booleans and signer/JWKS agreement alone cannot
   * enter PREPARED.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PreparedPromotion prepareCurrentGeneration(
      Binding expectedBinding,
      TrustFence trust,
      PromotionPreparation preparation,
      ReadinessPromotionProof readinessProof) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedBinding, "Expected JWT signer binding is required");
    Objects.requireNonNull(trust, "Protected JWT materializer trust fence is required");
    Objects.requireNonNull(preparation, "Owner-readback promotion preparation is required");
    Objects.requireNonNull(readinessProof, "Account-verified readiness proof is required");
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    requireEnrollmentTrust(state, trust);
    EnrollmentIdentity enrollment = state.enrollmentIdentity().orElseThrow();
    if (!enrollment.sameStablePins(preparation.enrollmentIdentity())
        || !enrollment.apiBindingDigest().equals(preparation.apiBindingDigest())
        || !enrollment.apiConfigRevision().equals(preparation.apiConfigRevision())
        || !enrollment.publicConfigMapUid().equals(preparation.publicConfigMapUid())) {
      throw new BindingMismatchException(
          "JWT signer promotion trust does not match the independently pinned enrollment");
    }
    if (state.preparedOperationId().isPresent()) {
      StoredPromotion replay = selectPromotion(state.preparedOperationId().orElseThrow(), true);
      if (replay == null || !"PREPARED".equals(replay.status())) {
        throw new QuarantinedStateException("Account PREPARED promotion is missing");
      }
      requirePromotionMatchesState(replay, state);
      requireCurrentTrust(replay, trust);
      StoredGenerationOperation replayGeneration =
          selectGenerationOperation(replay.generationOperationId(), true);
      GenerationResult replayResult = selectGenerationResult(replay.generationOperationId(), true);
      if (replayGeneration == null || replayResult == null) {
        throw new QuarantinedStateException("Prepared promotion source generation is missing");
      }
      verifyPreparedGenerationMatches(replay, replayGeneration, replayResult);
      if (!replay.matchesPreparation(preparation, replayGeneration, replayResult, enrollment)) {
        throw new IdempotencyConflictException(
            "JWT signer promotion retry changed its immutable evidence");
      }
      requireReadinessProofMatches(
          replayResult, preparation, replay, expectedBinding, trust, readinessProof);
      return new PreparedPromotion(replay.operationId(), replay.requestDigest(), "PREPARED");
    }
    UUID generationOperationId =
        state
            .generationOperationId()
            .orElseThrow(
                () ->
                    new MissingGenerationOperationException(
                        "Account has no current generation operation to promote"));
    StoredGenerationOperation generation = selectGenerationOperation(generationOperationId, true);
    GenerationResult result = selectGenerationResult(generationOperationId, true);
    if (generation == null || result == null) {
      throw new PromotionPrerequisitesIncompleteException(
          "Account generation operation has no exact persisted result");
    }
    verifyOperationMatchesState(generation, state);
    requireCurrentTrust(generation, trust);
    verifyGenerationResultMatchesOperation(result, generation, state);
    if (!result.equals(preparation.generationResult())
        || !preparation.generationOperationId().equals(generationOperationId)) {
      throw new StaleGenerationOperationException(
          "Promotion preparation does not match the exact current generation result");
    }
    requireReadinessProofMatches(result, preparation, null, expectedBinding, trust, readinessProof);
    requireCurrentPublicationForPreparation(result, preparation);
    requireCurrentReadinessForPreparation(result, preparation);

    StoredPromotion candidate =
        StoredPromotion.create(preparation, state, generation, result, trust, expectedBinding);
    StoredPromotion existing = selectPromotion(candidate.operationId(), true);
    if (existing != null) {
      requirePromotionMatchesState(existing, state);
      if (!existing.equals(candidate)) {
        throw new IdempotencyConflictException(
            "JWT signer promotion request changed for the same operation");
      }
      return new PreparedPromotion(existing.operationId(), existing.requestDigest(), "PREPARED");
    }
    insertPromotion(candidate);
    int advanced = advanceToPrepared(state, candidate.operationId());
    if (advanced != 1) {
      throw new VersionConflictException("Account desired state changed while entering PREPARED");
    }
    StoredPromotion readback = selectPromotion(candidate.operationId(), true);
    Record stateReadbackRow = selectState(expectedBinding.environmentId(), true);
    if (readback == null || stateReadbackRow == null || !readback.equals(candidate)) {
      throw new QuarantinedStateException("Account PREPARED promotion did not read back exactly");
    }
    DesiredState stateReadback = decodeState(stateReadbackRow);
    requirePromotionMatchesState(readback, stateReadback);
    return new PreparedPromotion(readback.operationId(), readback.requestDigest(), "PREPARED");
  }

  private static void requireReadinessProofMatches(
      GenerationResult result,
      PromotionPreparation preparation,
      StoredPromotion preparedReplay,
      Binding expectedBinding,
      TrustFence trust,
      ReadinessPromotionProof proof) {
    var plan = proof.plan();
    var publication = proof.publication();
    if (!proof.generationResult().equals(result)
        || !preparation.generationResult().equals(result)
        || !plan.operationId().equals(result.operationId())
        || !plan.binding().equals(expectedBinding)
        || !plan.trustFence().equals(trust)
        || !plan.planDigest().equals(preparation.readinessPlanDigest())
        || !proof.readinessEvidenceDigest().equals(preparation.readinessEvidenceDigest())
        || !plan.operationDigest().equals(result.operationDigest())
        || !plan.generationRequestDigest().equals(result.generationRequestDigest())
        || !plan.generationReceiptDigest().equals(result.receiptDigest())
        || plan.desiredStateVersion() != result.desiredStateVersion()
        || !plan.targetGeneration().equals(result.targetGeneration())
        || !plan.targetKid().equals(result.targetKid())
        || !plan.targetPublicKeyFingerprint().equals(result.publicKeyFingerprint())
        || !publication.intent().intentDigest().equals(preparation.prepublicationIntentDigest())
        || !publication.receipt().receiptDigest().equals(preparation.prepublicationReceiptDigest())
        || !publication
            .mountedCorrespondence()
            .observationDigest()
            .equals(preparation.mountedObservationDigest())
        || !publication.intent().configMapUid().equals(preparation.publicConfigMapUid())
        || !publication
            .receipt()
            .observedResourceVersion()
            .equals(preparation.expectedPublicResourceVersion())
        || !publication.intent().apiBindingDigest().equals(preparation.apiBindingDigest())
        || !publication.intent().apiConfigRevision().equals(preparation.apiConfigRevision())
        || !publication.intent().jwksJson().equals(preparation.expectedPublicJwksJson())) {
      throw new PromotionPrerequisitesIncompleteException(
          "Account readiness proof does not match the exact generation and publication");
    }
    if (preparedReplay != null
        && (!preparedReplay.readinessPlanDigest().equals(plan.planDigest())
            || !preparedReplay.readinessEvidenceDigest().equals(proof.readinessEvidenceDigest()))) {
      throw new IdempotencyConflictException(
          "Readiness recovery proof changed for the exact PREPARED promotion");
    }
    if (!plan.validatorInventoryComplete()
        || proof.inventoryEvidenceReference() == null
        || !proof.inventoryEvidenceReference().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,511}")
        || proof.inventoryEvidenceDigest() == null
        || !proof.inventoryEvidenceDigest().matches("[0-9a-f]{64}")
        || proof.verifiedProbes().isEmpty()
        || !proof.readinessEvidenceDigest().matches("[0-9a-f]{64}")
        || !publication.intent().operationId().equals(result.operationId())
        || !publication.receipt().operationId().equals(result.operationId())
        || !publication.mountedCorrespondence().operationId().equals(result.operationId())
        || !publication.intent().intentDigest().equals(plan.publicationIntentDigest())
        || !publication.receipt().receiptDigest().equals(plan.publicationReceiptDigest())
        || !publication
            .mountedCorrespondence()
            .observationDigest()
            .equals(plan.mountedObservationDigest())) {
      throw new PromotionPrerequisitesIncompleteException(
          "Account readiness proof lacks exact publication or protected inventory evidence");
    }
  }

  private GenerationRequest createGenerationRequest(DesiredState state, TrustFence trust) {
    if (state.recordVersion() == Long.MAX_VALUE) {
      throw new VersionConflictException("Account JWT signer state version is exhausted");
    }
    long previousGeneration =
        Math.max(
            state.durableActive().map(value -> parseGeneration(value.generation())).orElse(0L),
            state.publishedActive().map(value -> parseGeneration(value.generation())).orElse(0L));
    if (previousGeneration == Long.MAX_VALUE) {
      throw new VersionConflictException("Account JWT signer generation is exhausted");
    }
    long targetGeneration = previousGeneration + 1;
    UUID operationId = UUID.randomUUID();
    String targetKid = "jwt-" + targetGeneration + "-" + operationId.toString().substring(0, 12);
    StoredGenerationOperation operation =
        StoredGenerationOperation.create(state, trust, operationId, targetGeneration, targetKid);
    insertGenerationOperation(operation);
    int changed = advanceToGenerationRequest(state, operationId);
    if (changed != 1) {
      throw new VersionConflictException(
          "Account JWT signer state changed during request creation");
    }
    Record readbackStateRow = selectState(state.binding().environmentId(), true);
    Record readbackOperationRow = selectGenerationOperationRow(operationId, true);
    if (readbackStateRow == null || readbackOperationRow == null) {
      throw new QuarantinedStateException("Account JWT generation request did not read back");
    }
    DesiredState readbackState = decodeState(readbackStateRow);
    StoredGenerationOperation readbackOperation = decodeGenerationOperation(readbackOperationRow);
    requireBinding(readbackState, state.binding());
    verifyOperationMatchesState(readbackOperation, readbackState);
    if (!readbackOperation.equals(operation)) {
      throw new QuarantinedStateException("Account JWT generation request changed on readback");
    }
    return readGenerationRequest(readbackOperation, readbackState, true);
  }

  private GenerationRequest readGenerationRequest(
      StoredGenerationOperation operation, DesiredState state, boolean lockEvidence) {
    GenerationResult result = selectGenerationResult(operation.operationId(), lockEvidence);
    if (result != null) {
      verifyGenerationResultMatchesOperation(result, operation, state);
      return toGenerationRequest(operation, state, null, result);
    }
    SecretObservation observation = selectSecretObservation(operation.operationId(), lockEvidence);
    if (observation != null) {
      verifySecretObservationMatchesOperation(observation, operation, state);
    }
    return toGenerationRequest(operation, state, observation, null);
  }

  private static void verifySecretObservationMatchesOperation(
      SecretObservation observation, StoredGenerationOperation operation, DesiredState state) {
    if (!observation.operationId().equals(operation.operationId())
        || !observation.binding().equals(operation.binding())
        || !observation.operationDigest().equals(operation.operationDigest())
        || observation.expectedRecordVersion() != operation.expectedRecordVersion()
        || !observation.trustFence().equals(operation.trustFence())
        || state.recordVersion() != operation.expectedRecordVersion() + 1) {
      throw new QuarantinedStateException(
          "JWT signer Secret observation is not bound to its operation");
    }
  }

  private static void verifyGenerationResultMatchesOperation(
      GenerationResult result, StoredGenerationOperation operation, DesiredState state) {
    if (!result.operationId().equals(operation.operationId())
        || !result.binding().equals(operation.binding())
        || !result.operationDigest().equals(operation.operationDigest())
        || !result.trustFence().equals(operation.trustFence())
        || result.desiredStateVersion() != state.recordVersion()
        || !result.targetGeneration().equals(operation.targetGeneration())
        || !result.targetKid().equals(operation.targetKid())
        || !result.targetAlgorithm().equals(operation.targetAlgorithm())) {
      throw new QuarantinedStateException(
          "JWT signer generation result is not bound to its operation");
    }
  }

  private GenerationRequest toGenerationRequest(
      StoredGenerationOperation operation,
      DesiredState state,
      SecretObservation observation,
      GenerationResult result) {
    GenerationPhase phase =
        result != null
            ? GenerationPhase.GENERATION_RECORDED
            : observation != null
                ? GenerationPhase.GENERATE_PENDING
                : GenerationPhase.OBSERVE_PRIVATE_SECRET;
    return new GenerationRequest(
        phase,
        operation.operationId(),
        operation.operationDigest(),
        result != null
            ? result.generationRequestDigest()
            : observation == null ? "" : observation.generationRequestDigest(),
        state.recordVersion(),
        operation.binding(),
        operation.trustFence(),
        PRIVATE_SECRET_NAME,
        operation.targetGeneration(),
        operation.targetKid(),
        operation.targetAlgorithm(),
        ACTION,
        List.of(PENDING_SLOT),
        operation.expectedPreviousActive(),
        operation.expectedPublishedActive(),
        result != null ? result.secretUid() : observation == null ? "" : observation.secretUid(),
        result != null
            ? result.expectedPriorResourceVersion()
            : observation == null ? "" : observation.observedResourceVersion(),
        result == null ? "" : result.receiptDigest(),
        result == null ? "" : result.publicKeyFingerprint(),
        result == null ? "" : result.publicJwkJson(),
        result == null ? "" : result.observedResourceVersion());
  }

  private LockedCurrent lockCurrentOperation(
      Binding expectedBinding, TrustFence trust, UUID operationId) {
    Record stateRow = selectState(expectedBinding.environmentId(), true);
    if (stateRow == null) {
      throw new MissingDesiredStateException(expectedBinding.environmentId());
    }
    DesiredState state = decodeState(stateRow);
    requireBinding(state, expectedBinding);
    verifyPreparedIsUnavailable(state);
    if (!state.generationOperationId().equals(Optional.of(operationId))) {
      throw new StaleGenerationOperationException(
          "JWT signer request does not target Account's current generation operation");
    }
    StoredGenerationOperation operation = selectGenerationOperation(operationId, true);
    if (operation == null) {
      throw new QuarantinedStateException("Account JWT signer generation operation is missing");
    }
    verifyOperationMatchesState(operation, state);
    requireCurrentTrust(operation, trust);
    return new LockedCurrent(state, operation);
  }

  private void requireCurrentTrust(StoredGenerationOperation operation, TrustFence current) {
    if (!operation.trustFence().equals(current)) {
      throw new BindingMismatchException(
          "JWT materializer trust or namespace incarnation changed for the generation operation");
    }
  }

  private static void requireEnrollmentTrust(DesiredState state, TrustFence trust) {
    EnrollmentIdentity enrolled =
        state
            .enrollmentIdentity()
            .orElseThrow(
                () ->
                    new QuarantinedStateException(
                        "Account JWT signer state has no independently protected enrollment identity"));
    if (!enrolled.expectedClusterIncarnationUid().equals(trust.expectedClusterIncarnationUid())
        || !enrolled.expectedNamespaceUid().equals(trust.expectedNamespaceUid())
        || !enrolled.materializerTrustBindingDigest().equals(trust.bindingDigest())
        || !enrolled.materializerTrustConfigRevision().equals(trust.configRevision())) {
      throw new BindingMismatchException(
          "JWT materializer trust no longer matches Account's pinned enrollment");
    }
  }

  private void verifyOperationMatchesState(
      StoredGenerationOperation operation, DesiredState state) {
    if (!state.generationOperationId().equals(Optional.of(operation.operationId()))
        || !operation.binding().equals(state.binding())
        || operation.expectedRecordVersion() == Long.MAX_VALUE
        || state.recordVersion() != operation.expectedRecordVersion() + 1
        || !operation.expectedPreviousActive().equals(state.durableActive())
        || !operation.expectedPublishedActive().equals(state.publishedActive())
        || !REQUEST_DIGEST_VERSION.equals(operation.digestVersion())
        || !generationOperationDigest(operation).equals(operation.operationDigest())) {
      throw new QuarantinedStateException(
          "Account JWT signer generation request does not match current desired state");
    }
  }

  private Record selectState(String environmentId, boolean lock) {
    return dsl.fetchOne(
        "SELECT environment_id, cluster_id, kubernetes_namespace, custody_mode, "
            + "private_secret_name, public_jwks_config_map_name, record_version, "
            + "durable_active_generation, durable_active_kid, published_active_generation, "
            + "published_active_kid, generation_operation_id, prepared_operation_id, "
            + "enrollment_cluster_incarnation_uid, enrollment_namespace_uid, "
            + "enrollment_materializer_binding_digest, enrollment_materializer_config_revision, "
            + "enrollment_api_binding_digest, enrollment_api_config_revision, "
            + "enrollment_public_config_map_uid, enrollment_public_config_map_resource_version, "
            + "enrollment_public_config_map_snapshot_digest FROM "
            + STATE_TABLE
            + " WHERE environment_id = ?"
            + (lock ? " FOR UPDATE" : ""),
        environmentId);
  }

  private StoredPromotion selectPromotion(UUID operationId, boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT p.operation_id, p.environment_id, p.cluster_id, p.kubernetes_namespace, p.custody_mode, "
                + "p.request_digest_version, p.request_digest, p.expected_record_version, "
                + "p.expected_previous_generation, p.expected_previous_kid, p.target_generation, p.target_kid, "
                + "p.target_algorithm, p.target_public_key_fingerprint, "
                + "p.expected_private_secret_resource_version, p.expected_public_jwks_resource_version, "
                + "p.expected_public_active_generation, p.expected_public_active_kid, p.operation_action, "
                + "p.allowed_private_slots_canonical_bytes, p.status, p.generation_operation_id, "
                + "p.expected_cluster_incarnation_uid, p.expected_namespace_uid, "
                + "p.materializer_trust_binding_digest, p.materializer_trust_config_revision, "
                + "p.api_binding_digest, p.api_config_revision, p.expected_private_secret_uid, "
                + "p.expected_public_config_map_uid, p.prepublication_intent_digest, "
                + "p.prepublication_receipt_digest, p.mounted_observation_digest, p.readiness_plan_digest, "
                + "p.readiness_evidence_digest, p.expected_public_jwks_json, "
                + "p.expected_active_generation_marker_json, "
                + "(p.private_promotion_dispatched_at IS NOT NULL) AS private_promotion_dispatched, "
                + "p.private_promotion_observed_resource_version, "
                + "p.private_promotion_receipt_digest, p.active_jwks_observed_resource_version, "
                + "p.active_jwks_public_data_digest, p.active_jwks_receipt_digest, "
                + "generation.operation_digest AS generation_operation_digest, "
                + "generation_result.receipt_digest AS generation_receipt_digest "
                + "FROM "
                + PROMOTION_TABLE
                + " AS p "
                + "JOIN "
                + OPERATION_TABLE
                + " AS generation "
                + "ON generation.operation_id = p.generation_operation_id "
                + "JOIN "
                + RESULT_TABLE
                + " AS generation_result "
                + "ON generation_result.operation_id = p.generation_operation_id "
                + "WHERE p.operation_id = ?"
                + (lock ? " FOR UPDATE OF p, generation, generation_result" : ""),
            operationId);
    return row == null ? null : decodePromotion(row);
  }

  private StoredPromotion selectCommittedPromotion(DesiredState state, ActiveSigner active) {
    Result<Record> rows =
        dsl.fetch(
            "SELECT operation_id FROM "
                + PROMOTION_TABLE
                + " WHERE environment_id = ? AND cluster_id = ? AND kubernetes_namespace = ? "
                + "AND custody_mode = ? AND status = 'COMMITTED' AND target_generation = ? "
                + "AND target_kid = ? ORDER BY created_at DESC LIMIT 2",
            state.binding().environmentId(),
            state.binding().clusterId(),
            state.binding().namespace(),
            state.binding().mode().value(),
            parseGeneration(active.generation()),
            active.kid());
    if (rows.size() != 1) {
      throw new QuarantinedStateException(
          "Account active signer does not resolve to exactly one committed promotion");
    }
    UUID operationId = rows.get(0).get("operation_id", UUID.class);
    return selectPromotion(operationId, false);
  }

  private static void requireCommittedStateMatches(StoredPromotion promotion, DesiredState state) {
    ActiveSigner expected = new ActiveSigner(promotion.targetGeneration(), promotion.targetKid());
    if (!"COMMITTED".equals(promotion.status())
        || !promotion.binding().equals(state.binding())
        || state.preparedOperationId().isPresent()
        || state.generationOperationId().isPresent()
        || state.durableActive().filter(expected::equals).isEmpty()
        || state.publishedActive().filter(expected::equals).isEmpty()
        || promotion.expectedRecordVersion() > Long.MAX_VALUE - 2
        || state.recordVersion() != promotion.expectedRecordVersion() + 2
        || promotion.privatePromotionReceiptDigest() == null
        || promotion.activeJwksReceiptDigest() == null) {
      throw new QuarantinedStateException(
          "Committed Account signer does not match its exact operation and active fences");
    }
  }

  private static void requireCommittedActiveStillCurrent(
      StoredPromotion promotion, DesiredState state) {
    ActiveSigner expected = new ActiveSigner(promotion.targetGeneration(), promotion.targetKid());
    if (!"COMMITTED".equals(promotion.status())
        || !promotion.binding().equals(state.binding())
        || state.preparedOperationId().isPresent()
        || state.durableActive().filter(expected::equals).isEmpty()
        || state.publishedActive().filter(expected::equals).isEmpty()
        || promotion.expectedRecordVersion() > Long.MAX_VALUE - 2
        || state.recordVersion() < promotion.expectedRecordVersion() + 2) {
      throw new QuarantinedStateException(
          "Committed Account promotion is not the current active signer");
    }
  }

  private static void requireActiveJwksObservation(
      StoredPromotion promotion, ActiveJwksPromotionObservation observation) {
    if (!promotion.publicConfigMapUid().equals(observation.configMapUid())
        || !promotion.expectedPublicResourceVersion().equals(observation.priorResourceVersion())
        || observation.priorResourceVersion().equals(observation.observedResourceVersion())
        || !promotion.expectedPublicJwksJson().equals(observation.jwksJson())
        || !promotion.expectedActiveMarkerJson().equals(observation.activeMarkerJson())) {
      throw new QuarantinedStateException(
          "Account ConfigMap CAS did not read back the exact authorized ACTIVE projection");
    }
    requireActiveMarkerMatchesPromotion(promotion, observation.activeMarkerJson());
  }

  private static void requireActiveMarkerMatchesPromotion(
      StoredPromotion promotion, String marker) {
    try {
      JsonNode root = STRICT_JSON.readTree(marker);
      if (root == null
          || !root.isObject()
          || root.size() != 11
          || !root.path("schemaVersion").isIntegralNumber()
          || root.path("schemaVersion").intValue() != 1
          || !"ACTIVE".equals(requiredText(root, "phase", 16))
          || !promotion
              .generationOperationId()
              .toString()
              .equals(requiredText(root, "operationId", 36))
          || !promotion
              .generationOperationDigest()
              .equals(requiredText(root, "operationDigest", 64))
          || !promotion
              .generationReceiptDigest()
              .equals(requiredText(root, "generationReceiptDigest", 64))
          || !markerBindingMatches(promotion, root.get("binding"))
          || !markerFenceMatches(
              promotion, root.get("expectedDurableActive"), promotion.expectedPreviousActive())
          || !markerFenceMatches(
              promotion, root.get("expectedPublishedActive"), promotion.expectedPublishedActive())
          || !markerPublicMapMatches(root.get("publicConfigMap"))
          || !markerPromotionMatches(promotion, root.get("promotion"))) {
        throw new IllegalArgumentException("Active marker mismatch");
      }
      JsonNode active = root.get("active");
      if (active == null
          || !active.isObject()
          || active.size() != 4
          || !promotion.targetGeneration().equals(requiredText(active, "generation", 19))
          || !promotion.targetKid().equals(requiredText(active, "kid", 64))
          || !ALGORITHM.equals(requiredText(active, "algorithm", 16))
          || !promotion
              .targetPublicKeyFingerprint()
              .equals(requiredText(active, "publicKeyFingerprint", 64))) {
        throw new IllegalArgumentException("Active signer mismatch");
      }
    } catch (RuntimeException ex) {
      throw new QuarantinedStateException("Account ACTIVE generation marker is malformed", ex);
    }
  }

  private static boolean markerBindingMatches(StoredPromotion promotion, JsonNode binding) {
    return binding != null
        && binding.isObject()
        && binding.size() == 9
        && promotion.binding().environmentId().equals(requiredText(binding, "environmentId", 64))
        && promotion.binding().clusterId().equals(requiredText(binding, "clusterId", 128))
        && promotion.binding().namespace().equals(requiredText(binding, "namespace", 64))
        && promotion
            .trustFence()
            .expectedClusterIncarnationUid()
            .equals(requiredText(binding, "expectedClusterIncarnationUid", 36))
        && promotion
            .trustFence()
            .expectedNamespaceUid()
            .equals(requiredText(binding, "expectedNamespaceUid", 36))
        && promotion
            .trustFence()
            .bindingDigest()
            .equals(requiredText(binding, "trustBindingDigest", 64))
        && promotion
            .trustFence()
            .configRevision()
            .equals(requiredText(binding, "trustConfigRevision", 128))
        && promotion.apiBindingDigest().equals(requiredText(binding, "apiBindingDigest", 64))
        && promotion.apiConfigRevision().equals(requiredText(binding, "apiConfigRevision", 128));
  }

  private static boolean markerFenceMatches(
      StoredPromotion promotion, JsonNode fence, Optional<ActiveSigner> expected) {
    return fence != null && parseMarkerActive(fence).equals(expected);
  }

  private static Optional<ActiveSigner> parseMarkerActive(JsonNode fence) {
    if (fence == null || !fence.isObject()) {
      throw new IllegalArgumentException("Active marker fence is malformed");
    }
    if (fence.size() == 1
        && fence.path("present").isBoolean()
        && !fence.path("present").booleanValue()) {
      return Optional.empty();
    }
    if (fence.size() == 3
        && fence.path("present").isBoolean()
        && fence.path("present").booleanValue()) {
      return Optional.of(
          new ActiveSigner(requiredText(fence, "generation", 19), requiredText(fence, "kid", 64)));
    }
    throw new IllegalArgumentException("Active marker fence is malformed");
  }

  private static boolean markerPublicMapMatches(JsonNode publicMap) {
    return publicMap != null
        && publicMap.isObject()
        && publicMap.size() == 1
        && PUBLIC_JWKS_CONFIG_MAP_NAME.equals(requiredText(publicMap, "name", 64));
  }

  private static boolean markerPromotionMatches(StoredPromotion promotion, JsonNode proof) {
    return proof != null
        && proof.isObject()
        && proof.size() == 7
        && promotion.operationId().toString().equals(requiredText(proof, "operationId", 36))
        && promotion.requestDigest().equals(requiredText(proof, "requestDigest", 64))
        && promotion
            .prepublicationIntentDigest()
            .equals(requiredText(proof, "prepublicationIntentDigest", 64))
        && promotion
            .prepublicationReceiptDigest()
            .equals(requiredText(proof, "prepublicationReceiptDigest", 64))
        && promotion
            .mountedObservationDigest()
            .equals(requiredText(proof, "mountedObservationDigest", 64))
        && promotion.readinessPlanDigest().equals(requiredText(proof, "readinessPlanDigest", 64))
        && promotion
            .readinessEvidenceDigest()
            .equals(requiredText(proof, "readinessEvidenceDigest", 64));
  }

  private static String activeMarkerJson(StoredPromotion promotion) {
    Map<String, Object> marker = new LinkedHashMap<>();
    marker.put("schemaVersion", 1);
    marker.put("phase", "ACTIVE");
    marker.put("operationId", promotion.generationOperationId().toString());
    marker.put("operationDigest", promotion.generationOperationDigest());
    marker.put("generationReceiptDigest", promotion.generationReceiptDigest());
    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("environmentId", promotion.binding().environmentId());
    binding.put("clusterId", promotion.binding().clusterId());
    binding.put("namespace", promotion.binding().namespace());
    binding.put(
        "expectedClusterIncarnationUid", promotion.trustFence().expectedClusterIncarnationUid());
    binding.put("expectedNamespaceUid", promotion.trustFence().expectedNamespaceUid());
    binding.put("trustBindingDigest", promotion.trustFence().bindingDigest());
    binding.put("trustConfigRevision", promotion.trustFence().configRevision());
    binding.put("apiBindingDigest", promotion.apiBindingDigest());
    binding.put("apiConfigRevision", promotion.apiConfigRevision());
    marker.put("binding", binding);
    marker.put("publicConfigMap", Map.of("name", PUBLIC_JWKS_CONFIG_MAP_NAME));
    marker.put("expectedDurableActive", activeMarker(promotion.expectedPreviousActive()));
    marker.put("expectedPublishedActive", activeMarker(promotion.expectedPublishedActive()));
    marker.put(
        "active",
        Map.of(
            "generation", promotion.targetGeneration(),
            "kid", promotion.targetKid(),
            "algorithm", promotion.targetAlgorithm(),
            "publicKeyFingerprint", promotion.targetPublicKeyFingerprint()));
    marker.put(
        "promotion",
        Map.of(
            "operationId", promotion.operationId().toString(),
            "requestDigest", promotion.requestDigest(),
            "prepublicationIntentDigest", promotion.prepublicationIntentDigest(),
            "prepublicationReceiptDigest", promotion.prepublicationReceiptDigest(),
            "mountedObservationDigest", promotion.mountedObservationDigest(),
            "readinessPlanDigest", promotion.readinessPlanDigest(),
            "readinessEvidenceDigest", promotion.readinessEvidenceDigest()));
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(marker)),
          StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new StorageUnavailableException("Account ACTIVE JWT marker could not be canonicalized");
    }
  }

  private static Map<String, Object> activeMarker(Optional<ActiveSigner> active) {
    Map<String, Object> marker = new LinkedHashMap<>();
    marker.put("present", active.isPresent());
    active.ifPresent(
        signer -> {
          marker.put("generation", signer.generation());
          marker.put("kid", signer.kid());
        });
    return marker;
  }

  private static String requiredText(JsonNode object, String name, int maxLength) {
    JsonNode value = object.get(name);
    if (value == null
        || !value.isTextual()
        || value.textValue().isEmpty()
        || value.textValue().length() > maxLength) {
      throw new IllegalArgumentException("Account JWT signer JSON field is malformed");
    }
    return value.textValue();
  }

  private static void requirePromotionMatchesState(StoredPromotion promotion, DesiredState state) {
    if (!promotion.binding().equals(state.binding())
        || !state.preparedOperationId().equals(Optional.of(promotion.operationId()))
        || state.generationOperationId().isPresent()
        || state.recordVersion() != promotion.expectedRecordVersion() + 1
        || !promotion.expectedPreviousActive().equals(state.durableActive())
        || !promotion.expectedPublishedActive().equals(state.publishedActive())
        || !promotion.requestDigest().equals(promotionRequestDigest(promotion))) {
      throw new QuarantinedStateException(
          "Account PREPARED promotion does not match its exact desired-state fence");
    }
  }

  private static void requireCurrentTrust(StoredPromotion promotion, TrustFence current) {
    if (!promotion.trustFence().equals(current)) {
      throw new BindingMismatchException(
          "JWT materializer trust or namespace incarnation changed during promotion");
    }
  }

  private static void verifyPreparedGenerationMatches(
      StoredPromotion promotion, StoredGenerationOperation operation, GenerationResult result) {
    if (!promotion.generationOperationId().equals(operation.operationId())
        || !promotion.binding().equals(operation.binding())
        || !promotion.operationDigestForGeneration().equals(operation.operationDigest())
        || !promotion.expectedPreviousActive().equals(operation.expectedPreviousActive())
        || !promotion.expectedPublishedActive().equals(operation.expectedPublishedActive())
        || !promotion.targetGeneration().equals(operation.targetGeneration())
        || !promotion.targetKid().equals(operation.targetKid())
        || !ALGORITHM.equals(operation.targetAlgorithm())
        || !promotion.trustFence().equals(operation.trustFence())
        || !promotion.generationOperationId().equals(result.operationId())
        || !promotion.generationReceiptDigest().equals(result.receiptDigest())
        || !promotion.secretUid().equals(result.secretUid())
        || !promotion.expectedPrivateResourceVersion().equals(result.observedResourceVersion())
        || !promotion.targetPublicKeyFingerprint().equals(result.publicKeyFingerprint())) {
      throw new QuarantinedStateException(
          "Prepared promotion is not bound to the exact source generation result");
    }
  }

  private static void requirePrivatePromotionObservation(
      StoredPromotion promotion, PrivatePromotionObservation observation) {
    Optional<PublicKeyIdentity> expectedPrevious =
        promotion
            .expectedPreviousActive()
            .map(
                active ->
                    new PublicKeyIdentity(
                        active.generation(),
                        active.kid(),
                        publicFingerprintForKid(promotion.expectedPublicJwksJson(), active.kid())));
    PublicKeyIdentity expectedCurrent =
        new PublicKeyIdentity(
            promotion.targetGeneration(),
            promotion.targetKid(),
            promotion.targetPublicKeyFingerprint());
    List<String> expectedSlots =
        expectedPrevious.isPresent() ? List.of("current", "previous") : List.of("current");
    if (!observation.secretUid().equals(promotion.secretUid())
        || !observation
            .expectedPriorResourceVersion()
            .equals(promotion.expectedPrivateResourceVersion())
        || observation.expectedPriorResourceVersion().equals(observation.observedResourceVersion())
        || !expectedCurrent.equals(observation.current())
        || !expectedPrevious.equals(observation.previous())
        || !expectedSlots.equals(observation.resultingSlots())) {
      throw new QuarantinedStateException(
          "Materializer did not read back the exact authorized private slot transition");
    }
  }

  private static String publicFingerprintForKid(String jwksJson, String kid) {
    try {
      JsonNode root = STRICT_JSON.readTree(jwksJson);
      JsonNode keys = root == null ? null : root.get("keys");
      if (keys == null || !keys.isArray()) {
        throw new IllegalArgumentException("JWKS keys are malformed");
      }
      String found = null;
      for (JsonNode key : keys) {
        if (key != null && key.isObject() && kid.equals(requiredText(key, "kid", 64))) {
          if (found != null) {
            throw new IllegalArgumentException("JWKS contains a duplicate kid");
          }
          found =
              digest(
                  Map.of(
                      "e",
                      requiredText(key, "e", 16),
                      "kty",
                      "RSA",
                      "n",
                      requiredText(key, "n", 4096)));
        }
      }
      if (found == null) {
        throw new IllegalArgumentException("Expected prior public key is missing from JWKS");
      }
      return found;
    } catch (Exception ex) {
      throw new QuarantinedStateException("Expected prior public key identity is unavailable", ex);
    }
  }

  private StoredPromotion decodePromotion(Record row) {
    try {
      byte[] slots = row.get("allowed_private_slots_canonical_bytes", byte[].class);
      if (!Arrays.equals(PROMOTION_SLOTS_CANONICAL_BYTES, slots)
          || !"PROMOTE_PENDING".equals(row.get("operation_action", String.class))) {
        throw new QuarantinedStateException(
            "Account promotion action or fixed Secret slots are malformed");
      }
      StoredPromotion promotion =
          new StoredPromotion(
              row.get("operation_id", UUID.class),
              new Binding(
                  row.get("environment_id", String.class),
                  row.get("cluster_id", String.class),
                  row.get("kubernetes_namespace", String.class),
                  CustodyMode.fromValue(row.get("custody_mode", String.class))),
              Math.toIntExact(
                  positive(
                      row.get("request_digest_version", Short.class).longValue(),
                      "promotion digest version")),
              row.get("request_digest", String.class),
              positive(
                  row.get("expected_record_version", Long.class), "expected signer state version"),
              activeOptional(
                  row.get("expected_previous_generation", Long.class),
                  row.get("expected_previous_kid", String.class),
                  "previous signer"),
              activeOptional(
                  row.get("expected_public_active_generation", Long.class),
                  row.get("expected_public_active_kid", String.class),
                  "published previous signer"),
              canonicalGeneration(
                  row.get("target_generation", Long.class), "promotion target generation"),
              row.get("target_kid", String.class),
              row.get("target_algorithm", String.class),
              row.get("target_public_key_fingerprint", String.class),
              row.get("expected_private_secret_resource_version", String.class),
              row.get("expected_public_jwks_resource_version", String.class),
              new TrustFence(
                  row.get("expected_cluster_incarnation_uid", UUID.class).toString(),
                  row.get("expected_namespace_uid", UUID.class).toString(),
                  row.get("materializer_trust_binding_digest", String.class),
                  row.get("materializer_trust_config_revision", String.class)),
              row.get("generation_operation_id", UUID.class),
              row.get("generation_operation_digest", String.class),
              row.get("generation_receipt_digest", String.class),
              row.get("api_binding_digest", String.class),
              row.get("api_config_revision", String.class),
              row.get("expected_private_secret_uid", UUID.class).toString(),
              row.get("expected_public_config_map_uid", UUID.class).toString(),
              row.get("prepublication_intent_digest", String.class),
              row.get("prepublication_receipt_digest", String.class),
              row.get("mounted_observation_digest", String.class),
              row.get("readiness_plan_digest", String.class),
              row.get("readiness_evidence_digest", String.class),
              row.get("expected_public_jwks_json", String.class),
              row.get("expected_active_generation_marker_json", String.class),
              row.get("status", String.class),
              Boolean.TRUE.equals(row.get("private_promotion_dispatched", Boolean.class)),
              row.get("private_promotion_observed_resource_version", String.class),
              row.get("private_promotion_receipt_digest", String.class),
              row.get("active_jwks_observed_resource_version", String.class),
              row.get("active_jwks_public_data_digest", String.class),
              row.get("active_jwks_receipt_digest", String.class));
      if (promotion.requestDigestVersion() != 1
          || !promotionRequestDigest(promotion).equals(promotion.requestDigest())) {
        throw new QuarantinedStateException("Account promotion operation digest is invalid");
      }
      return promotion;
    } catch (RuntimeException ex) {
      if (ex instanceof QuarantinedStateException) {
        throw ex;
      }
      throw new QuarantinedStateException("Account promotion operation is malformed", ex);
    }
  }

  private static String promotionRequestDigest(StoredPromotion promotion) {
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("digestVersion", PROMOTION_DIGEST_VERSION);
    preimage.put("operationId", promotion.operationId().toString());
    preimage.put("generationOperationId", promotion.generationOperationId().toString());
    preimage.put("generationOperationDigest", promotion.generationOperationDigest());
    preimage.put("generationReceiptDigest", promotion.generationReceiptDigest());
    preimage.put("environmentId", promotion.binding().environmentId());
    preimage.put("clusterId", promotion.binding().clusterId());
    preimage.put("namespace", promotion.binding().namespace());
    preimage.put("custodyMode", promotion.binding().mode().value());
    preimage.put("expectedRecordVersion", Long.toString(promotion.expectedRecordVersion()));
    preimage.put("expectedPreviousActive", activeDigestValue(promotion.expectedPreviousActive()));
    preimage.put("expectedPublishedActive", activeDigestValue(promotion.expectedPublishedActive()));
    preimage.put("targetGeneration", promotion.targetGeneration());
    preimage.put("targetKid", promotion.targetKid());
    preimage.put("targetAlgorithm", ALGORITHM);
    preimage.put("targetPublicKeyFingerprint", promotion.targetPublicKeyFingerprint());
    preimage.put("expectedPrivateSecretUid", promotion.secretUid());
    preimage.put(
        "expectedPrivateSecretResourceVersion", promotion.expectedPrivateResourceVersion());
    preimage.put("expectedPublicConfigMapUid", promotion.publicConfigMapUid());
    preimage.put("expectedPublicJwksResourceVersion", promotion.expectedPublicResourceVersion());
    preimage.put(
        "expectedClusterIncarnationUid", promotion.trustFence().expectedClusterIncarnationUid());
    preimage.put("expectedNamespaceUid", promotion.trustFence().expectedNamespaceUid());
    preimage.put("materializerTrustBindingDigest", promotion.trustFence().bindingDigest());
    preimage.put("materializerTrustConfigRevision", promotion.trustFence().configRevision());
    preimage.put("apiBindingDigest", promotion.apiBindingDigest());
    preimage.put("apiConfigRevision", promotion.apiConfigRevision());
    preimage.put("prepublicationIntentDigest", promotion.prepublicationIntentDigest());
    preimage.put("prepublicationReceiptDigest", promotion.prepublicationReceiptDigest());
    preimage.put("mountedObservationDigest", promotion.mountedObservationDigest());
    preimage.put("readinessPlanDigest", promotion.readinessPlanDigest());
    preimage.put("readinessEvidenceDigest", promotion.readinessEvidenceDigest());
    preimage.put("publicJwksSha256", sha256(promotion.expectedPublicJwksJson()));
    preimage.put("operationAction", "PROMOTE_PENDING");
    preimage.put("allowedPrivateSlots", List.of("current", "pending", "previous"));
    return digest(preimage);
  }

  private Record selectGenerationOperationRow(UUID operationId, boolean lock) {
    return dsl.fetchOne(
        "SELECT operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
            + "operation_digest_version, operation_digest, expected_record_version, "
            + "expected_previous_generation, expected_previous_kid, "
            + "expected_published_generation, expected_published_kid, target_generation, target_kid, "
            + "target_algorithm, expected_cluster_incarnation_uid, expected_namespace_uid, "
            + "trust_binding_digest, trust_config_revision, operation_action, "
            + "allowed_private_slots_canonical_bytes FROM "
            + OPERATION_TABLE
            + " WHERE operation_id = ?"
            + (lock ? " FOR UPDATE" : ""),
        operationId);
  }

  private StoredGenerationOperation selectGenerationOperation(UUID operationId, boolean lock) {
    Record row = selectGenerationOperationRow(operationId, lock);
    return row == null ? null : decodeGenerationOperation(row);
  }

  private SecretObservation selectSecretObservation(UUID operationId, boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "operation_digest, expected_record_version, expected_cluster_incarnation_uid, "
                + "expected_namespace_uid, trust_binding_digest, trust_config_revision, "
                + "private_secret_name, secret_uid, observed_resource_version, observation_digest, "
                + "generation_request_digest FROM "
                + OBSERVATION_TABLE
                + " WHERE operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            operationId);
    return row == null ? null : decodeSecretObservation(row);
  }

  private GenerationResult selectGenerationResult(UUID operationId, boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "operation_digest, generation_request_digest, desired_state_version, "
                + "expected_cluster_incarnation_uid, expected_namespace_uid, trust_binding_digest, "
                + "trust_config_revision, private_secret_name, secret_uid, "
                + "expected_prior_resource_version, observed_resource_version, target_generation, "
                + "target_kid, target_algorithm, public_key_fingerprint, public_jwk_json, receipt_digest "
                + "FROM "
                + RESULT_TABLE
                + " WHERE operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            operationId);
    return row == null ? null : decodeGenerationResult(row);
  }

  private void insertGenerationOperation(StoredGenerationOperation operation) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OPERATION_TABLE
                + " (operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "operation_digest_version, operation_digest, expected_record_version, "
                + "expected_previous_generation, expected_previous_kid, "
                + "expected_published_generation, expected_published_kid, target_generation, target_kid, "
                + "target_algorithm, expected_cluster_incarnation_uid, expected_namespace_uid, "
                + "trust_binding_digest, trust_config_revision, operation_action, "
                + "allowed_private_slots_canonical_bytes) "
                + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MATERIALIZE_PENDING', ?) ",
            operation.operationId(),
            operation.binding().environmentId(),
            operation.binding().clusterId(),
            operation.binding().namespace(),
            operation.binding().mode().value(),
            operation.operationDigest(),
            operation.expectedRecordVersion(),
            generationValue(operation.expectedPreviousActive()),
            kidValue(operation.expectedPreviousActive()),
            generationValue(operation.expectedPublishedActive()),
            kidValue(operation.expectedPublishedActive()),
            parseGeneration(operation.targetGeneration()),
            operation.targetKid(),
            operation.targetAlgorithm(),
            UUID.fromString(operation.trustFence().expectedClusterIncarnationUid()),
            UUID.fromString(operation.trustFence().expectedNamespaceUid()),
            operation.trustFence().bindingDigest(),
            operation.trustFence().configRevision(),
            PENDING_SLOT_CANONICAL_BYTES);
    if (inserted != 1) {
      throw new StorageUnavailableException(
          "Account JWT generation operation insert was ambiguous");
    }
  }

  private void requireCurrentPublicationForPreparation(
      GenerationResult result, PromotionPreparation preparation) {
    Record evidence =
        dsl.fetchOne(
            "SELECT intent.operation_id, intent.intent_digest, intent.environment_id, intent.cluster_id, "
                + "intent.kubernetes_namespace, intent.custody_mode, intent.operation_digest, "
                + "intent.generation_request_digest, intent.generation_receipt_digest, "
                + "intent.desired_state_version, intent.expected_cluster_incarnation_uid, "
                + "intent.expected_namespace_uid, intent.trust_binding_digest, "
                + "intent.trust_config_revision, intent.api_binding_digest, intent.api_config_revision, "
                + "intent.config_map_name, intent.config_map_uid, intent.jwks_json, "
                + "receipt.receipt_digest, receipt.observed_resource_version, "
                + "mount.observation_digest FROM account_jwt_jwks_prepublication_intents AS intent "
                + "JOIN account_jwt_jwks_publication_receipts AS receipt "
                + "ON receipt.operation_id = intent.operation_id "
                + "AND receipt.intent_digest = intent.intent_digest "
                + "JOIN account_jwt_jwks_mount_observations AS mount "
                + "ON mount.operation_id = intent.operation_id "
                + "WHERE intent.operation_id = ? FOR UPDATE OF intent, receipt, mount",
            result.operationId());
    if (evidence == null
        || !preparation
            .prepublicationIntentDigest()
            .equals(evidence.get("intent_digest", String.class))
        || !preparation
            .prepublicationReceiptDigest()
            .equals(evidence.get("receipt_digest", String.class))
        || !preparation
            .mountedObservationDigest()
            .equals(evidence.get("observation_digest", String.class))
        || !preparation
            .publicConfigMapUid()
            .equals(evidence.get("config_map_uid", UUID.class).toString())
        || !preparation
            .expectedPublicResourceVersion()
            .equals(evidence.get("observed_resource_version", String.class))
        || !preparation.apiBindingDigest().equals(evidence.get("api_binding_digest", String.class))
        || !preparation
            .apiConfigRevision()
            .equals(evidence.get("api_config_revision", String.class))
        || !preparation.expectedPublicJwksJson().equals(evidence.get("jwks_json", String.class))
        || !result.operationId().equals(evidence.get("operation_id", UUID.class))
        || !result.operationDigest().equals(evidence.get("operation_digest", String.class))
        || !result
            .generationRequestDigest()
            .equals(evidence.get("generation_request_digest", String.class))
        || !result.receiptDigest().equals(evidence.get("generation_receipt_digest", String.class))
        || result.desiredStateVersion() != evidence.get("desired_state_version", Long.class)) {
      throw new PromotionPrerequisitesIncompleteException(
          "Exact Account JWKS prepublication, API readback, or mount receipt is unavailable");
    }
  }

  private void requireCurrentReadinessForPreparation(
      GenerationResult result, PromotionPreparation preparation) {
    Record plan =
        dsl.fetchOne(
            "SELECT plan_digest, operation_digest, generation_request_digest, "
                + "generation_receipt_digest, desired_state_version, target_generation, target_kid, "
                + "target_public_key_fingerprint, publication_intent_digest, "
                + "publication_receipt_digest, mounted_observation_digest, "
                + "validator_inventory_complete, expires_at_epoch_seconds "
                + "FROM account_jwt_readiness_probe_plans WHERE rotation_operation_id = ? "
                + "AND plan_digest = ? FOR UPDATE",
            result.operationId(),
            preparation.readinessPlanDigest());
    if (plan == null
        || !Boolean.TRUE.equals(plan.get("validator_inventory_complete", Boolean.class))
        || plan.get("expires_at_epoch_seconds", Long.class) == null
        || plan.get("expires_at_epoch_seconds", Long.class) == null
        || !result.operationDigest().equals(plan.get("operation_digest", String.class))
        || !result
            .generationRequestDigest()
            .equals(plan.get("generation_request_digest", String.class))
        || !result.receiptDigest().equals(plan.get("generation_receipt_digest", String.class))
        || result.desiredStateVersion() != plan.get("desired_state_version", Long.class)
        || !result
            .targetGeneration()
            .equals(String.valueOf(plan.get("target_generation", Long.class)))
        || !result.targetKid().equals(plan.get("target_kid", String.class))
        || !result
            .publicKeyFingerprint()
            .equals(plan.get("target_public_key_fingerprint", String.class))
        || !preparation
            .prepublicationIntentDigest()
            .equals(plan.get("publication_intent_digest", String.class))
        || !preparation
            .prepublicationReceiptDigest()
            .equals(plan.get("publication_receipt_digest", String.class))
        || !preparation
            .mountedObservationDigest()
            .equals(plan.get("mounted_observation_digest", String.class))) {
      throw new PromotionPrerequisitesIncompleteException(
          "Complete current Account JWT readiness proof is unavailable");
    }
    Record entries =
        dsl.fetchOne(
            "SELECT count(*) AS total, count(*) FILTER (WHERE state = 'VERIFIED' "
                + "AND verification_receipt_sha256 IS NOT NULL "
                + "AND verified_at_epoch_seconds < expires_at_epoch_seconds) AS verified "
                + "FROM account_jwt_readiness_probe_entries WHERE rotation_operation_id = ? "
                + "AND plan_digest = ? AND EXISTS (SELECT 1 FROM account_jwt_readiness_probe_plans "
                + "WHERE rotation_operation_id = ? AND plan_digest = ? "
                + "AND expires_at_epoch_seconds > floor(extract(epoch FROM CURRENT_TIMESTAMP))::BIGINT)",
            result.operationId(),
            preparation.readinessPlanDigest(),
            result.operationId(),
            preparation.readinessPlanDigest());
    if (entries == null
        || entries.get("total", Long.class) == null
        || entries.get("total", Long.class) <= 0
        || !entries.get("total", Long.class).equals(entries.get("verified", Long.class))) {
      throw new PromotionPrerequisitesIncompleteException(
          "Every exact current Account JWT validator probe must be VERIFIED");
    }
  }

  /**
   * Rechecks the owner-created typed proof at the durable COMMITTED boundary. A proof read before
   * this transaction is insufficient: the plan and every VERIFIED entry must still be inside their
   * recorded validity window when Account commits the promotion.
   */
  private void requireUnexpiredCurrentReadinessProof(
      StoredPromotion promotion, GenerationResult result, ReadinessPromotionProof proof) {
    var expectedPlan = proof.plan();
    Record currentPlan =
        dsl.fetchOne(
            "SELECT plan_digest, operation_digest, generation_request_digest, "
                + "generation_receipt_digest, desired_state_version, target_generation, target_kid, "
                + "target_public_key_fingerprint, publication_intent_digest, "
                + "publication_receipt_digest, mounted_observation_digest, "
                + "applicability_matrix_digest, validator_inventory_complete, "
                + "maximum_cache_age_seconds, not_before_epoch_seconds, expires_at_epoch_seconds, "
                + "floor(extract(epoch FROM clock_timestamp()))::bigint AS checked_epoch "
                + "FROM account_jwt_readiness_probe_plans WHERE rotation_operation_id = ? "
                + "AND plan_digest = ? FOR UPDATE",
            result.operationId(),
            promotion.readinessPlanDigest());
    if (currentPlan == null
        || !Boolean.TRUE.equals(currentPlan.get("validator_inventory_complete", Boolean.class))
        || !promotion.readinessPlanDigest().equals(currentPlan.get("plan_digest", String.class))
        || !result.operationDigest().equals(currentPlan.get("operation_digest", String.class))
        || !result
            .generationRequestDigest()
            .equals(currentPlan.get("generation_request_digest", String.class))
        || !result
            .receiptDigest()
            .equals(currentPlan.get("generation_receipt_digest", String.class))
        || result.desiredStateVersion() != currentPlan.get("desired_state_version", Long.class)
        || !result
            .targetGeneration()
            .equals(String.valueOf(currentPlan.get("target_generation", Long.class)))
        || !result.targetKid().equals(currentPlan.get("target_kid", String.class))
        || !result
            .publicKeyFingerprint()
            .equals(currentPlan.get("target_public_key_fingerprint", String.class))
        || !promotion
            .prepublicationIntentDigest()
            .equals(currentPlan.get("publication_intent_digest", String.class))
        || !promotion
            .prepublicationReceiptDigest()
            .equals(currentPlan.get("publication_receipt_digest", String.class))
        || !promotion
            .mountedObservationDigest()
            .equals(currentPlan.get("mounted_observation_digest", String.class))
        || !expectedPlan
            .applicabilityMatrixDigest()
            .equals(currentPlan.get("applicability_matrix_digest", String.class))
        || expectedPlan.maximumCacheAgeSeconds()
            != currentPlan.get("maximum_cache_age_seconds", Short.class)
        || expectedPlan.notBeforeEpochSecond()
            != currentPlan.get("not_before_epoch_seconds", Long.class)
        || expectedPlan.expiresAtEpochSecond()
            != currentPlan.get("expires_at_epoch_seconds", Long.class)
        || currentPlan.get("checked_epoch", Long.class) == null
        || currentPlan.get("checked_epoch", Long.class) < expectedPlan.notBeforeEpochSecond()
        || currentPlan.get("checked_epoch", Long.class) >= expectedPlan.expiresAtEpochSecond()) {
      throw new PromotionPrerequisitesIncompleteException(
          "Exact Account readiness proof is missing, changed, or expired at commit");
    }

    Record currentEntries =
        dsl.fetchOne(
            "SELECT count(*) AS total, count(*) FILTER (WHERE state = 'VERIFIED' "
                + "AND verification_receipt_sha256 IS NOT NULL "
                + "AND verified_at_epoch_seconds < expires_at_epoch_seconds "
                + "AND expires_at_epoch_seconds > floor(extract(epoch FROM clock_timestamp()))::bigint) "
                + "AS verified FROM account_jwt_readiness_probe_entries "
                + "WHERE rotation_operation_id = ? AND plan_digest = ?",
            result.operationId(),
            promotion.readinessPlanDigest());
    Long total = currentEntries == null ? null : currentEntries.get("total", Long.class);
    Long verified = currentEntries == null ? null : currentEntries.get("verified", Long.class);
    if (total == null
        || verified == null
        || total <= 0L
        || total != proof.verifiedProbes().size()
        || !total.equals(verified)) {
      throw new PromotionPrerequisitesIncompleteException(
          "Every exact Account validator proof must remain VERIFIED and unexpired at commit");
    }
  }

  private void insertPromotion(StoredPromotion promotion) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + PROMOTION_TABLE
                + " (operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "request_digest_version, request_digest, expected_record_version, "
                + "expected_previous_generation, expected_previous_kid, target_generation, target_kid, "
                + "target_algorithm, target_public_key_fingerprint, "
                + "expected_private_secret_resource_version, expected_public_jwks_resource_version, "
                + "expected_public_active_generation, expected_public_active_kid, operation_action, "
                + "allowed_private_slots_canonical_bytes, status, generation_operation_id, "
                + "expected_cluster_incarnation_uid, expected_namespace_uid, "
                + "materializer_trust_binding_digest, materializer_trust_config_revision, "
                + "api_binding_digest, api_config_revision, expected_private_secret_uid, "
                + "expected_public_config_map_uid, prepublication_intent_digest, "
                + "prepublication_receipt_digest, mounted_observation_digest, readiness_plan_digest, "
                + "readiness_evidence_digest, expected_public_jwks_json, "
                + "expected_active_generation_marker_json) "
                + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 'RS256', ?, ?, ?, ?, ?, "
                + "'PROMOTE_PENDING', ?, 'PREPARED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            promotion.operationId(),
            promotion.binding().environmentId(),
            promotion.binding().clusterId(),
            promotion.binding().namespace(),
            promotion.binding().mode().value(),
            promotion.requestDigest(),
            promotion.expectedRecordVersion(),
            generationValue(promotion.expectedPreviousActive()),
            kidValue(promotion.expectedPreviousActive()),
            parseGeneration(promotion.targetGeneration()),
            promotion.targetKid(),
            promotion.targetPublicKeyFingerprint(),
            promotion.expectedPrivateResourceVersion(),
            promotion.expectedPublicResourceVersion(),
            generationValue(promotion.expectedPublishedActive()),
            kidValue(promotion.expectedPublishedActive()),
            PROMOTION_SLOTS_CANONICAL_BYTES,
            promotion.generationOperationId(),
            UUID.fromString(promotion.trustFence().expectedClusterIncarnationUid()),
            UUID.fromString(promotion.trustFence().expectedNamespaceUid()),
            promotion.trustFence().bindingDigest(),
            promotion.trustFence().configRevision(),
            promotion.apiBindingDigest(),
            promotion.apiConfigRevision(),
            UUID.fromString(promotion.secretUid()),
            UUID.fromString(promotion.publicConfigMapUid()),
            promotion.prepublicationIntentDigest(),
            promotion.prepublicationReceiptDigest(),
            promotion.mountedObservationDigest(),
            promotion.readinessPlanDigest(),
            promotion.readinessEvidenceDigest(),
            promotion.expectedPublicJwksJson(),
            promotion.expectedActiveMarkerJson());
    if (inserted != 1) {
      throw new StorageUnavailableException("Account promotion operation insert was ambiguous");
    }
  }

  private int advanceToPrepared(DesiredState state, UUID promotionOperationId) {
    return dsl.execute(
        "UPDATE "
            + STATE_TABLE
            + " SET record_version = record_version + 1, generation_operation_id = NULL, "
            + "prepared_operation_id = ?, updated_at = CURRENT_TIMESTAMP WHERE environment_id = ? "
            + "AND cluster_id = ? AND kubernetes_namespace = ? AND custody_mode = ? "
            + "AND record_version = ? AND generation_operation_id = ? AND prepared_operation_id IS NULL "
            + "AND durable_active_generation IS NOT DISTINCT FROM ? "
            + "AND durable_active_kid IS NOT DISTINCT FROM ? "
            + "AND published_active_generation IS NOT DISTINCT FROM ? "
            + "AND published_active_kid IS NOT DISTINCT FROM ?",
        promotionOperationId,
        state.binding().environmentId(),
        state.binding().clusterId(),
        state.binding().namespace(),
        state.binding().mode().value(),
        state.recordVersion(),
        state.generationOperationId().orElseThrow(),
        generationValue(state.durableActive()),
        kidValue(state.durableActive()),
        generationValue(state.publishedActive()),
        kidValue(state.publishedActive()));
  }

  private int advanceToGenerationRequest(DesiredState state, UUID operationId) {
    return dsl.execute(
        "UPDATE "
            + STATE_TABLE
            + " SET record_version = record_version + 1, generation_operation_id = ?, "
            + "updated_at = CURRENT_TIMESTAMP WHERE environment_id = ? AND cluster_id = ? "
            + "AND kubernetes_namespace = ? AND custody_mode = ? "
            + "AND private_secret_name = 'jwt-signing-keys' "
            + "AND public_jwks_config_map_name = 'jwt-jwks' AND record_version = ? "
            + "AND durable_active_generation IS NOT DISTINCT FROM ? "
            + "AND durable_active_kid IS NOT DISTINCT FROM ? "
            + "AND published_active_generation IS NOT DISTINCT FROM ? "
            + "AND published_active_kid IS NOT DISTINCT FROM ? "
            + "AND generation_operation_id IS NULL AND prepared_operation_id IS NULL",
        operationId,
        state.binding().environmentId(),
        state.binding().clusterId(),
        state.binding().namespace(),
        state.binding().mode().value(),
        state.recordVersion(),
        generationValue(state.durableActive()),
        kidValue(state.durableActive()),
        generationValue(state.publishedActive()),
        kidValue(state.publishedActive()));
  }

  private void insertSecretObservation(SecretObservation observation) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OBSERVATION_TABLE
                + " (operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "operation_digest_version, operation_digest, expected_record_version, "
                + "expected_cluster_incarnation_uid, expected_namespace_uid, trust_binding_digest, "
                + "trust_config_revision, private_secret_name, secret_uid, observed_resource_version, "
                + "observation_digest_version, observation_digest, generation_request_digest) "
                + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 'jwt-signing-keys', ?, ?, 1, ?, ?)",
            observation.operationId(),
            observation.binding().environmentId(),
            observation.binding().clusterId(),
            observation.binding().namespace(),
            observation.binding().mode().value(),
            observation.operationDigest(),
            observation.expectedRecordVersion(),
            UUID.fromString(observation.trustFence().expectedClusterIncarnationUid()),
            UUID.fromString(observation.trustFence().expectedNamespaceUid()),
            observation.trustFence().bindingDigest(),
            observation.trustFence().configRevision(),
            UUID.fromString(observation.secretUid()),
            observation.observedResourceVersion(),
            observation.observationDigest(),
            observation.generationRequestDigest());
    if (inserted != 1) {
      throw new StorageUnavailableException("Account JWT Secret observation insert was ambiguous");
    }
  }

  private void insertGenerationResult(GenerationResult result) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + RESULT_TABLE
                + " (operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "operation_digest, generation_request_digest, desired_state_version, "
                + "expected_cluster_incarnation_uid, expected_namespace_uid, trust_binding_digest, "
                + "trust_config_revision, private_secret_name, secret_uid, "
                + "expected_prior_resource_version, observed_resource_version, target_generation, "
                + "target_kid, target_algorithm, public_key_fingerprint, public_jwk_json, receipt_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'jwt-signing-keys', ?, ?, ?, ?, ?, 'RS256', ?, ?, ?)",
            result.operationId(),
            result.binding().environmentId(),
            result.binding().clusterId(),
            result.binding().namespace(),
            result.binding().mode().value(),
            result.operationDigest(),
            result.generationRequestDigest(),
            result.desiredStateVersion(),
            UUID.fromString(result.trustFence().expectedClusterIncarnationUid()),
            UUID.fromString(result.trustFence().expectedNamespaceUid()),
            result.trustFence().bindingDigest(),
            result.trustFence().configRevision(),
            UUID.fromString(result.secretUid()),
            result.expectedPriorResourceVersion(),
            result.observedResourceVersion(),
            parseGeneration(result.targetGeneration()),
            result.targetKid(),
            result.publicKeyFingerprint(),
            result.publicJwkJson(),
            result.receiptDigest());
    if (inserted != 1) {
      throw new StorageUnavailableException("Account JWT generation result insert was ambiguous");
    }
  }

  private DesiredState decodeState(Record row) {
    try {
      if (!PRIVATE_SECRET_NAME.equals(row.get("private_secret_name", String.class))
          || !PUBLIC_JWKS_CONFIG_MAP_NAME.equals(
              row.get("public_jwks_config_map_name", String.class))) {
        throw new QuarantinedStateException("Account JWT signer resource binding is malformed");
      }
      return new DesiredState(
          new Binding(
              row.get("environment_id", String.class),
              row.get("cluster_id", String.class),
              row.get("kubernetes_namespace", String.class),
              CustodyMode.fromValue(row.get("custody_mode", String.class))),
          positive(row.get("record_version", Long.class), "Account JWT signer record version"),
          activeOptional(
              row.get("durable_active_generation", Long.class),
              row.get("durable_active_kid", String.class),
              "durable active signer"),
          activeOptional(
              row.get("published_active_generation", Long.class),
              row.get("published_active_kid", String.class),
              "published active signer"),
          Optional.ofNullable(row.get("generation_operation_id", UUID.class)),
          Optional.ofNullable(row.get("prepared_operation_id", UUID.class)),
          enrollmentIdentity(row));
    } catch (RuntimeException ex) {
      throw new QuarantinedStateException("Account JWT signer desired state is malformed", ex);
    }
  }

  private static Optional<EnrollmentIdentity> enrollmentIdentity(Record row) {
    UUID clusterUid = row.get("enrollment_cluster_incarnation_uid", UUID.class);
    UUID namespaceUid = row.get("enrollment_namespace_uid", UUID.class);
    String materializerDigest = row.get("enrollment_materializer_binding_digest", String.class);
    String materializerRevision = row.get("enrollment_materializer_config_revision", String.class);
    String apiDigest = row.get("enrollment_api_binding_digest", String.class);
    String apiRevision = row.get("enrollment_api_config_revision", String.class);
    UUID configMapUid = row.get("enrollment_public_config_map_uid", UUID.class);
    String resourceVersion = row.get("enrollment_public_config_map_resource_version", String.class);
    String snapshotDigest = row.get("enrollment_public_config_map_snapshot_digest", String.class);
    boolean allAbsent =
        clusterUid == null
            && namespaceUid == null
            && materializerDigest == null
            && materializerRevision == null
            && apiDigest == null
            && apiRevision == null
            && configMapUid == null
            && resourceVersion == null
            && snapshotDigest == null;
    if (allAbsent) {
      return Optional.empty();
    }
    if (clusterUid == null
        || namespaceUid == null
        || materializerDigest == null
        || materializerRevision == null
        || apiDigest == null
        || apiRevision == null
        || configMapUid == null
        || resourceVersion == null
        || snapshotDigest == null) {
      throw new QuarantinedStateException("Account JWT signer enrollment pins are partial");
    }
    return Optional.of(
        new EnrollmentIdentity(
            clusterUid.toString(),
            namespaceUid.toString(),
            materializerDigest,
            materializerRevision,
            apiDigest,
            apiRevision,
            configMapUid.toString(),
            resourceVersion,
            snapshotDigest));
  }

  private StoredGenerationOperation decodeGenerationOperation(Record row) {
    try {
      String slotBytes =
          new String(
              row.get("allowed_private_slots_canonical_bytes", byte[].class),
              StandardCharsets.UTF_8);
      if (!"[\"pending\"]".equals(slotBytes)) {
        throw new QuarantinedStateException("JWT signer operation private slots are malformed");
      }
      Integer digestVersion = row.get("operation_digest_version", Integer.class);
      StoredGenerationOperation operation =
          new StoredGenerationOperation(
              row.get("operation_id", UUID.class),
              new Binding(
                  row.get("environment_id", String.class),
                  row.get("cluster_id", String.class),
                  row.get("kubernetes_namespace", String.class),
                  CustodyMode.fromValue(row.get("custody_mode", String.class))),
              digestVersion != null && digestVersion == 1 ? REQUEST_DIGEST_VERSION : "unsupported",
              row.get("operation_digest", String.class),
              positive(row.get("expected_record_version", Long.class), "expected state version"),
              activeOptional(
                  row.get("expected_previous_generation", Long.class),
                  row.get("expected_previous_kid", String.class),
                  "expected active signer"),
              activeOptional(
                  row.get("expected_published_generation", Long.class),
                  row.get("expected_published_kid", String.class),
                  "expected published signer"),
              canonicalGeneration(row.get("target_generation", Long.class), "target generation"),
              row.get("target_kid", String.class),
              row.get("target_algorithm", String.class),
              new TrustFence(
                  row.get("expected_cluster_incarnation_uid", UUID.class).toString(),
                  row.get("expected_namespace_uid", UUID.class).toString(),
                  row.get("trust_binding_digest", String.class),
                  row.get("trust_config_revision", String.class)));
      if (!ACTION.equals(row.get("operation_action", String.class))) {
        throw new QuarantinedStateException("JWT signer operation action is unsupported");
      }
      if (!generationOperationDigest(operation).equals(operation.operationDigest())) {
        throw new QuarantinedStateException("JWT signer operation digest is invalid");
      }
      return operation;
    } catch (RuntimeException ex) {
      if (ex instanceof QuarantinedStateException) {
        throw ex;
      }
      throw new QuarantinedStateException(
          "Account JWT signer generation operation is malformed", ex);
    }
  }

  private SecretObservation decodeSecretObservation(Record row) {
    try {
      SecretObservation observation =
          new SecretObservation(
              row.get("operation_id", UUID.class),
              new Binding(
                  row.get("environment_id", String.class),
                  row.get("cluster_id", String.class),
                  row.get("kubernetes_namespace", String.class),
                  CustodyMode.fromValue(row.get("custody_mode", String.class))),
              row.get("operation_digest", String.class),
              positive(row.get("expected_record_version", Long.class), "expected state version"),
              new TrustFence(
                  row.get("expected_cluster_incarnation_uid", UUID.class).toString(),
                  row.get("expected_namespace_uid", UUID.class).toString(),
                  row.get("trust_binding_digest", String.class),
                  row.get("trust_config_revision", String.class)),
              row.get("private_secret_name", String.class),
              row.get("secret_uid", UUID.class).toString(),
              row.get("observed_resource_version", String.class),
              row.get("observation_digest", String.class),
              row.get("generation_request_digest", String.class));
      if (!observationDigest(
                  observation.operationDigest(),
                  observation.trustFence(),
                  observation.secretUid(),
                  observation.observedResourceVersion())
              .equals(observation.observationDigest())
          || !generationRequestDigest(observation).equals(observation.generationRequestDigest())) {
        throw new QuarantinedStateException("JWT signer Secret observation digest is invalid");
      }
      return observation;
    } catch (RuntimeException ex) {
      if (ex instanceof QuarantinedStateException) {
        throw ex;
      }
      throw new QuarantinedStateException("Account JWT Secret observation is malformed", ex);
    }
  }

  private GenerationResult decodeGenerationResult(Record row) {
    try {
      GenerationResult result =
          new GenerationResult(
              row.get("operation_id", UUID.class),
              new Binding(
                  row.get("environment_id", String.class),
                  row.get("cluster_id", String.class),
                  row.get("kubernetes_namespace", String.class),
                  CustodyMode.fromValue(row.get("custody_mode", String.class))),
              row.get("operation_digest", String.class),
              row.get("generation_request_digest", String.class),
              positive(row.get("desired_state_version", Long.class), "desired state version"),
              new TrustFence(
                  row.get("expected_cluster_incarnation_uid", UUID.class).toString(),
                  row.get("expected_namespace_uid", UUID.class).toString(),
                  row.get("trust_binding_digest", String.class),
                  row.get("trust_config_revision", String.class)),
              row.get("private_secret_name", String.class),
              row.get("secret_uid", UUID.class).toString(),
              row.get("expected_prior_resource_version", String.class),
              row.get("observed_resource_version", String.class),
              canonicalGeneration(row.get("target_generation", Long.class), "target generation"),
              row.get("target_kid", String.class),
              row.get("target_algorithm", String.class),
              row.get("public_key_fingerprint", String.class),
              row.get("public_jwk_json", String.class),
              row.get("receipt_digest", String.class));
      PublicJwk jwk = parsePublicJwk(result.publicJwkJson());
      if (!jwk.kid().equals(result.targetKid())
          || !jwk.fingerprint().equals(result.publicKeyFingerprint())
          || !generationReceiptDigest(result).equals(result.receiptDigest())) {
        throw new QuarantinedStateException("JWT signer generation receipt digest is invalid");
      }
      return result;
    } catch (RuntimeException ex) {
      if (ex instanceof QuarantinedStateException) {
        throw ex;
      }
      throw new QuarantinedStateException("Account JWT generation result is malformed", ex);
    }
  }

  private static void verifyPreparedIsUnavailable(DesiredState state) {
    if (state.preparedOperationId().isPresent()) {
      throw new PromotionPrerequisitesIncompleteException(
          "Existing PREPARED signer state requires unavailable lifecycle evidence");
    }
  }

  private static void requireBinding(DesiredState state, Binding expected) {
    if (!state.binding().equals(expected)) {
      throw new BindingMismatchException(
          "Account JWT signer environment, cluster, namespace, or custody binding changed");
    }
  }

  private static String generationOperationDigest(StoredGenerationOperation operation) {
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("digestVersion", REQUEST_DIGEST_VERSION);
    preimage.put("environmentId", operation.binding().environmentId());
    preimage.put("clusterId", operation.binding().clusterId());
    preimage.put("namespace", operation.binding().namespace());
    preimage.put("custodyMode", operation.binding().mode().value());
    preimage.put("privateResource", Map.of("kind", "Secret", "name", PRIVATE_SECRET_NAME));
    preimage.put("operationId", operation.operationId().toString());
    preimage.put("expectedRecordVersion", Long.toString(operation.expectedRecordVersion()));
    preimage.put("expectedPreviousActive", activeDigestValue(operation.expectedPreviousActive()));
    preimage.put("expectedPublishedActive", activeDigestValue(operation.expectedPublishedActive()));
    preimage.put("targetGeneration", operation.targetGeneration());
    preimage.put("targetKid", operation.targetKid());
    preimage.put("targetAlgorithm", operation.targetAlgorithm());
    preimage.put("operationAction", ACTION);
    preimage.put("allowedPrivateSlots", List.of(PENDING_SLOT));
    preimage.put(
        "expectedClusterIncarnationUid", operation.trustFence().expectedClusterIncarnationUid());
    preimage.put("expectedNamespaceUid", operation.trustFence().expectedNamespaceUid());
    preimage.put("trustBindingDigest", operation.trustFence().bindingDigest());
    preimage.put("trustConfigRevision", operation.trustFence().configRevision());
    return digest(preimage);
  }

  private static String observationDigest(
      String operationDigest, TrustFence trust, String secretUid, String resourceVersion) {
    return digest(
        Map.of(
            "digestVersion", OBSERVATION_DIGEST_VERSION,
            "operationDigest", operationDigest,
            "privateResource", Map.of("kind", "Secret", "name", PRIVATE_SECRET_NAME),
            "expectedClusterIncarnationUid", trust.expectedClusterIncarnationUid(),
            "expectedNamespaceUid", trust.expectedNamespaceUid(),
            "trustBindingDigest", trust.bindingDigest(),
            "trustConfigRevision", trust.configRevision(),
            "secretUid", secretUid,
            "observedResourceVersion", resourceVersion));
  }

  private static String generationRequestDigest(SecretObservation observation) {
    return digest(
        Map.of(
            "digestVersion", GENERATION_REQUEST_DIGEST_VERSION,
            "operationDigest", observation.operationDigest(),
            "observationDigest", observation.observationDigest(),
            "secretUid", observation.secretUid(),
            "expectedPriorResourceVersion", observation.observedResourceVersion(),
            "target", "pending"));
  }

  private static String generationReceiptDigest(GenerationResult result) {
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("digestVersion", RECEIPT_DIGEST_VERSION);
    preimage.put("operationId", result.operationId().toString());
    preimage.put("operationDigest", result.operationDigest());
    preimage.put("generationRequestDigest", result.generationRequestDigest());
    preimage.put("desiredStateVersion", Long.toString(result.desiredStateVersion()));
    preimage.put("environmentId", result.binding().environmentId());
    preimage.put("clusterId", result.binding().clusterId());
    preimage.put("namespace", result.binding().namespace());
    preimage.put(
        "expectedClusterIncarnationUid", result.trustFence().expectedClusterIncarnationUid());
    preimage.put("expectedNamespaceUid", result.trustFence().expectedNamespaceUid());
    preimage.put("trustBindingDigest", result.trustFence().bindingDigest());
    preimage.put("trustConfigRevision", result.trustFence().configRevision());
    preimage.put("privateResource", Map.of("kind", "Secret", "name", PRIVATE_SECRET_NAME));
    preimage.put("secretUid", result.secretUid());
    preimage.put("expectedPriorResourceVersion", result.expectedPriorResourceVersion());
    preimage.put("observedResourceVersion", result.observedResourceVersion());
    preimage.put("targetGeneration", result.targetGeneration());
    preimage.put("targetKid", result.targetKid());
    preimage.put("targetAlgorithm", result.targetAlgorithm());
    preimage.put("publicKeyFingerprint", result.publicKeyFingerprint());
    preimage.put("publicJwk", result.publicJwkJson());
    return digest(preimage);
  }

  private static String digest(Object value) {
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new IllegalStateException("Account JWT signer evidence digest is unavailable", ex);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable for JWT promotion evidence", ex);
    }
  }

  private static Object activeDigestValue(Optional<ActiveSigner> active) {
    if (active.isEmpty()) {
      return Map.of("present", false);
    }
    ActiveSigner value = active.orElseThrow();
    return Map.of("present", true, "generation", value.generation(), "kid", value.kid());
  }

  private static PublicJwk parsePublicJwk(String json) {
    if (json == null || json.isEmpty() || json.getBytes(StandardCharsets.UTF_8).length > 8_192) {
      throw new IllegalArgumentException("JWT signer public JWK is missing or too large");
    }
    try {
      JsonNode node = STRICT_JSON.readTree(json);
      if (node == null || !node.isObject() || node.size() != PUBLIC_JWK_FIELDS.size()) {
        throw new IllegalArgumentException("JWT signer public JWK has an unsupported shape");
      }
      for (Map.Entry<String, JsonNode> property : node.properties()) {
        if (!PUBLIC_JWK_FIELDS.contains(property.getKey())) {
          throw new IllegalArgumentException("JWT signer public JWK has an unsupported field");
        }
      }
      String canonicalJson =
          new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
      if (!canonicalJson.equals(json)) {
        throw new IllegalArgumentException("JWT signer public JWK must be canonical RFC 8785 JSON");
      }
      String kty = requiredText(node, "kty", 8);
      String use = requiredText(node, "use", 8);
      String algorithm = requiredText(node, "alg", 8);
      String kid = requiredText(node, "kid", 64);
      String modulus = requiredText(node, "n", 4_096);
      String exponent = requiredText(node, "e", 16);
      JsonNode keyOps = node.get("key_ops");
      if (!"RSA".equals(kty)
          || !"sig".equals(use)
          || !ALGORITHM.equals(algorithm)
          || !KID.matcher(kid).matches()
          || keyOps == null
          || !keyOps.isArray()
          || keyOps.size() != 1
          || !keyOps.get(0).isTextual()
          || !"verify".equals(keyOps.get(0).textValue())) {
        throw new IllegalArgumentException("JWT signer public JWK does not meet RS256 policy");
      }
      byte[] modulusBytes = decodeCanonicalBase64Url(modulus, 2_048);
      byte[] exponentBytes = decodeCanonicalBase64Url(exponent, 8);
      try {
        if (modulusBytes.length == 0
            || modulusBytes[0] == 0
            || exponentBytes.length == 0
            || exponentBytes[0] == 0) {
          throw new IllegalArgumentException("JWT signer RSA public numbers are malformed");
        }
        BigInteger modulusInteger = new BigInteger(1, modulusBytes);
        BigInteger exponentInteger = new BigInteger(1, exponentBytes);
        if (modulusInteger.bitLength() < 3_072
            || modulusInteger.bitLength() > 16_384
            || !modulusInteger.testBit(0)
            || !BigInteger.valueOf(65_537).equals(exponentInteger)) {
          throw new IllegalArgumentException("JWT signer RSA public key is outside policy");
        }
      } finally {
        java.util.Arrays.fill(modulusBytes, (byte) 0);
        java.util.Arrays.fill(exponentBytes, (byte) 0);
      }
      String fingerprint =
          digest(
              Map.of(
                  "e", exponent,
                  "kty", "RSA",
                  "n", modulus));
      return new PublicJwk(kid, canonicalJson, fingerprint);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("JWT signer public JWK is malformed", ex);
    }
  }

  private static byte[] decodeCanonicalBase64Url(String encoded, int maxBytes) {
    final byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(encoded);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("JWT signer RSA public number is malformed", ex);
    }
    byte[] canonical = Base64.getUrlEncoder().withoutPadding().encode(decoded);
    boolean matches = canonical.length == encoded.length();
    for (int index = 0; matches && index < canonical.length; index++) {
      matches = (char) Byte.toUnsignedInt(canonical[index]) == encoded.charAt(index);
    }
    java.util.Arrays.fill(canonical, (byte) 0);
    if (decoded.length == 0 || decoded.length > maxBytes || !matches) {
      java.util.Arrays.fill(decoded, (byte) 0);
      throw new IllegalArgumentException("JWT signer RSA public number is not canonical");
    }
    return decoded;
  }

  private static ActiveSigner active(Long generation, String kid, String field) {
    if (generation == null && kid == null) {
      return null;
    }
    if (generation == null || kid == null) {
      throw new QuarantinedStateException(field + " has an incomplete generation/kid pair");
    }
    return new ActiveSigner(Long.toString(positive(generation, field + " generation")), kid);
  }

  private static Optional<ActiveSigner> activeOptional(Long generation, String kid, String field) {
    return Optional.ofNullable(active(generation, kid, field));
  }

  private static Long generationValue(Optional<ActiveSigner> active) {
    return active.map(value -> parseGeneration(value.generation())).orElse(null);
  }

  private static String kidValue(Optional<ActiveSigner> active) {
    return active.map(ActiveSigner::kid).orElse(null);
  }

  private static String canonicalGeneration(Long value, String field) {
    return Long.toString(positive(value, field));
  }

  private static long positive(Long value, String field) {
    if (value == null || value <= 0) {
      throw new QuarantinedStateException(field + " must be positive");
    }
    return value;
  }

  private static long parseGeneration(String value) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException("Signer generation must be canonical positive decimal");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Signer generation is outside its supported range", ex);
    }
  }

  private static void requireOperationId(UUID operationId) {
    Objects.requireNonNull(operationId, "Account JWT signer operation ID is required");
    if (operationId.version() != 4) {
      throw new IllegalArgumentException("Account JWT signer operation ID must be UUIDv4");
    }
  }

  private static void requireUuid(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException("JWT signer " + field + " is missing");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0, 0))) {
        throw new IllegalArgumentException("JWT signer " + field + " is malformed");
      }
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("JWT signer " + field + " is malformed", ex);
    }
  }

  private static void requireMatch(Pattern pattern, String value, String field) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException("JWT signer " + field + " is malformed");
    }
  }

  private static void requireAccountTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Account transaction is required for JWT signer state");
    }
  }

  private static void requireWritableAccountTransaction() {
    requireAccountTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Writable Account transaction is required for JWT signer state");
    }
  }

  public enum CustodyMode {
    INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK(MODE);

    private final String value;

    CustodyMode(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }

    private static CustodyMode fromValue(String value) {
      for (CustodyMode candidate : values()) {
        if (candidate.value.equals(value)) {
          return candidate;
        }
      }
      throw new QuarantinedStateException("JWT signer custody mode is unsupported");
    }
  }

  public enum GenerationPhase {
    OBSERVE_PRIVATE_SECRET,
    GENERATE_PENDING,
    GENERATION_RECORDED
  }

  public record Binding(
      String environmentId, String clusterId, String namespace, CustodyMode mode) {
    public Binding {
      requireMatch(ENVIRONMENT_ID, environmentId, "environment ID");
      requireMatch(CLUSTER_ID, clusterId, "cluster ID");
      requireMatch(ENVIRONMENT_ID, namespace, "Kubernetes namespace");
      Objects.requireNonNull(mode, "JWT signer custody mode is required");
    }
  }

  /** Independently protected trust identity captured by every immutable operation and receipt. */
  public record TrustFence(
      String expectedClusterIncarnationUid,
      String expectedNamespaceUid,
      String bindingDigest,
      String configRevision) {
    public TrustFence {
      requireUuid(expectedClusterIncarnationUid, "expected cluster incarnation UID");
      requireUuid(expectedNamespaceUid, "expected namespace UID");
      requireMatch(SHA256_HEX, bindingDigest, "materializer trust binding digest");
      requireMatch(CLUSTER_ID, configRevision, "materializer trust configuration revision");
    }
  }

  /**
   * Immutable independently protected trust and first ConfigMap observation used for enrollment.
   */
  public record EnrollmentIdentity(
      String expectedClusterIncarnationUid,
      String expectedNamespaceUid,
      String materializerTrustBindingDigest,
      String materializerTrustConfigRevision,
      String apiBindingDigest,
      String apiConfigRevision,
      String publicConfigMapUid,
      String publicConfigMapResourceVersion,
      String publicConfigMapSnapshotDigest) {
    public EnrollmentIdentity {
      requireUuid(expectedClusterIncarnationUid, "enrollment cluster incarnation UID");
      requireUuid(expectedNamespaceUid, "enrollment namespace UID");
      requireMatch(SHA256_HEX, materializerTrustBindingDigest, "materializer trust digest");
      requireMatch(CLUSTER_ID, materializerTrustConfigRevision, "materializer trust revision");
      requireMatch(SHA256_HEX, apiBindingDigest, "public JWKS API binding digest");
      requireMatch(CLUSTER_ID, apiConfigRevision, "public JWKS API configuration revision");
      requireUuid(publicConfigMapUid, "pre-created public ConfigMap UID");
      requireMatch(
          RESOURCE_VERSION, publicConfigMapResourceVersion, "initial ConfigMap resourceVersion");
      requireMatch(SHA256_HEX, publicConfigMapSnapshotDigest, "initial ConfigMap snapshot digest");
    }

    /** Stable pin equality deliberately excludes the historical first ConfigMap RV/data digest. */
    public boolean sameStablePins(EnrollmentIdentity other) {
      Objects.requireNonNull(other, "Enrollment identity is required");
      return expectedClusterIncarnationUid.equals(other.expectedClusterIncarnationUid)
          && expectedNamespaceUid.equals(other.expectedNamespaceUid)
          && materializerTrustBindingDigest.equals(other.materializerTrustBindingDigest)
          && materializerTrustConfigRevision.equals(other.materializerTrustConfigRevision)
          && apiBindingDigest.equals(other.apiBindingDigest)
          && apiConfigRevision.equals(other.apiConfigRevision)
          && publicConfigMapUid.equals(other.publicConfigMapUid);
    }
  }

  public record ActiveSigner(String generation, String kid) {
    public ActiveSigner {
      parseGeneration(generation);
      requireMatch(KID, kid, "active signer kid");
    }
  }

  public record DesiredState(
      Binding binding,
      long recordVersion,
      Optional<ActiveSigner> durableActive,
      Optional<ActiveSigner> publishedActive,
      Optional<UUID> generationOperationId,
      Optional<UUID> preparedOperationId,
      Optional<EnrollmentIdentity> enrollmentIdentity) {
    public DesiredState {
      Objects.requireNonNull(binding, "JWT signer binding is required");
      if (recordVersion <= 0) {
        throw new IllegalArgumentException("JWT signer record version must be positive");
      }
      durableActive = Objects.requireNonNull(durableActive, "Durable active signer is required");
      publishedActive =
          Objects.requireNonNull(publishedActive, "Published active signer is required");
      generationOperationId =
          Objects.requireNonNull(generationOperationId, "Generation operation state is required");
      preparedOperationId =
          Objects.requireNonNull(preparedOperationId, "Prepared operation state is required");
      enrollmentIdentity =
          Objects.requireNonNull(enrollmentIdentity, "Enrollment identity state is required");
      if (generationOperationId.isPresent() && preparedOperationId.isPresent()) {
        throw new IllegalArgumentException("Generation and PREPARED pointers cannot coexist");
      }
    }
  }

  public record GenerationRequest(
      GenerationPhase phase,
      UUID operationId,
      String operationDigest,
      String generationRequestDigest,
      long desiredStateVersion,
      Binding binding,
      TrustFence trustFence,
      String privateSecretName,
      String targetGeneration,
      String targetKid,
      String targetAlgorithm,
      String operationAction,
      List<String> allowedPrivateSlots,
      Optional<ActiveSigner> expectedActive,
      Optional<ActiveSigner> expectedPublishedActive,
      String secretUid,
      String expectedSecretResourceVersion,
      String generationReceiptDigest,
      String publicKeyFingerprint,
      String publicJwkJson,
      String observedSecretResourceVersion) {
    public GenerationRequest {
      Objects.requireNonNull(phase, "Generation phase is required");
      requireOperationId(operationId);
      requireMatch(SHA256_HEX, operationDigest, "generation operation digest");
      if (phase != GenerationPhase.OBSERVE_PRIVATE_SECRET) {
        requireMatch(SHA256_HEX, generationRequestDigest, "generation request digest");
      } else if (generationRequestDigest != null && !generationRequestDigest.isEmpty()) {
        requireMatch(SHA256_HEX, generationRequestDigest, "generation request digest");
      }
      if (desiredStateVersion <= 1) {
        throw new QuarantinedStateException("JWT generation request state version is invalid");
      }
      Objects.requireNonNull(binding, "Generation request binding is required");
      Objects.requireNonNull(trustFence, "Generation request trust fence is required");
      if (!PRIVATE_SECRET_NAME.equals(privateSecretName)
          || !ALGORITHM.equals(targetAlgorithm)
          || !ACTION.equals(operationAction)
          || !List.of(PENDING_SLOT).equals(allowedPrivateSlots)) {
        throw new QuarantinedStateException("JWT generation request fixed operation is malformed");
      }
      allowedPrivateSlots = List.copyOf(allowedPrivateSlots);
      parseGeneration(targetGeneration);
      requireMatch(KID, targetKid, "target kid");
      expectedActive = Objects.requireNonNull(expectedActive, "Expected active signer is required");
      expectedPublishedActive =
          Objects.requireNonNull(expectedPublishedActive, "Expected published signer is required");
      secretUid = secretUid == null ? "" : secretUid;
      expectedSecretResourceVersion =
          expectedSecretResourceVersion == null ? "" : expectedSecretResourceVersion;
      generationReceiptDigest = generationReceiptDigest == null ? "" : generationReceiptDigest;
      publicKeyFingerprint = publicKeyFingerprint == null ? "" : publicKeyFingerprint;
      publicJwkJson = publicJwkJson == null ? "" : publicJwkJson;
      observedSecretResourceVersion =
          observedSecretResourceVersion == null ? "" : observedSecretResourceVersion;
      if (phase != GenerationPhase.OBSERVE_PRIVATE_SECRET) {
        requireUuid(secretUid, "observed fixed Secret UID");
        requireMatch(
            RESOURCE_VERSION, expectedSecretResourceVersion, "observed Secret resourceVersion");
      }
      if (phase == GenerationPhase.GENERATION_RECORDED) {
        requireMatch(SHA256_HEX, generationReceiptDigest, "generation receipt digest");
        requireMatch(SHA256_HEX, publicKeyFingerprint, "RFC 7638 public-key fingerprint");
        parsePublicJwk(publicJwkJson);
        requireMatch(
            RESOURCE_VERSION, observedSecretResourceVersion, "result Secret resourceVersion");
      } else if (!generationReceiptDigest.isEmpty()
          || !publicKeyFingerprint.isEmpty()
          || !publicJwkJson.isEmpty()
          || !observedSecretResourceVersion.isEmpty()) {
        throw new QuarantinedStateException(
            "Incomplete JWT generation request contains result evidence");
      }
    }
  }

  public record GenerationResult(
      UUID operationId,
      Binding binding,
      String operationDigest,
      String generationRequestDigest,
      long desiredStateVersion,
      TrustFence trustFence,
      String privateSecretName,
      String secretUid,
      String expectedPriorResourceVersion,
      String observedResourceVersion,
      String targetGeneration,
      String targetKid,
      String targetAlgorithm,
      String publicKeyFingerprint,
      String publicJwkJson,
      String receiptDigest) {
    public GenerationResult {
      requireOperationId(operationId);
      Objects.requireNonNull(binding, "Generation result binding is required");
      requireMatch(SHA256_HEX, operationDigest, "generation operation digest");
      requireMatch(SHA256_HEX, generationRequestDigest, "generation request digest");
      if (desiredStateVersion <= 1) {
        throw new QuarantinedStateException("JWT generation result state version is invalid");
      }
      Objects.requireNonNull(trustFence, "Generation result trust fence is required");
      if (!PRIVATE_SECRET_NAME.equals(privateSecretName)) {
        throw new IllegalArgumentException("JWT generation result must name the fixed Secret");
      }
      requireUuid(secretUid, "generation result Secret UID");
      requireMatch(
          RESOURCE_VERSION, expectedPriorResourceVersion, "expected Secret resourceVersion");
      requireMatch(RESOURCE_VERSION, observedResourceVersion, "observed Secret resourceVersion");
      if (expectedPriorResourceVersion.equals(observedResourceVersion)) {
        throw new QuarantinedStateException("JWT signer Secret resourceVersion did not advance");
      }
      parseGeneration(targetGeneration);
      requireMatch(KID, targetKid, "target kid");
      if (!ALGORITHM.equals(targetAlgorithm)) {
        throw new IllegalArgumentException("Only RS256 JWT signer results are supported");
      }
      requireMatch(SHA256_HEX, publicKeyFingerprint, "RFC 7638 public-key fingerprint");
      requireMatch(SHA256_HEX, receiptDigest, "generation receipt digest");
      PublicJwk jwk = parsePublicJwk(publicJwkJson);
      if (!jwk.kid().equals(targetKid) || !jwk.fingerprint().equals(publicKeyFingerprint)) {
        throw new QuarantinedStateException(
            "JWT generation result public key does not match receipt");
      }
    }

    private static GenerationResult from(
        StoredGenerationOperation operation,
        long desiredStateVersion,
        SecretObservation observation,
        String observedResourceVersion,
        PublicJwk jwk) {
      GenerationResult candidate =
          new GenerationResult(
              operation.operationId(),
              operation.binding(),
              operation.operationDigest(),
              observation.generationRequestDigest(),
              desiredStateVersion,
              operation.trustFence(),
              PRIVATE_SECRET_NAME,
              observation.secretUid(),
              observation.observedResourceVersion(),
              observedResourceVersion,
              operation.targetGeneration(),
              operation.targetKid(),
              operation.targetAlgorithm(),
              jwk.fingerprint(),
              jwk.canonicalJson(),
              "0".repeat(64));
      return new GenerationResult(
          candidate.operationId(),
          candidate.binding(),
          candidate.operationDigest(),
          candidate.generationRequestDigest(),
          candidate.desiredStateVersion(),
          candidate.trustFence(),
          candidate.privateSecretName(),
          candidate.secretUid(),
          candidate.expectedPriorResourceVersion(),
          candidate.observedResourceVersion(),
          candidate.targetGeneration(),
          candidate.targetKid(),
          candidate.targetAlgorithm(),
          candidate.publicKeyFingerprint(),
          candidate.publicJwkJson(),
          generationReceiptDigest(candidate));
    }
  }

  private record StoredGenerationOperation(
      UUID operationId,
      Binding binding,
      String digestVersion,
      String operationDigest,
      long expectedRecordVersion,
      Optional<ActiveSigner> expectedPreviousActive,
      Optional<ActiveSigner> expectedPublishedActive,
      String targetGeneration,
      String targetKid,
      String targetAlgorithm,
      TrustFence trustFence) {
    private StoredGenerationOperation {
      requireOperationId(operationId);
      Objects.requireNonNull(binding, "Generation operation binding is required");
      requireMatch(SHA256_HEX, operationDigest, "generation operation digest");
      if (expectedRecordVersion <= 0 || expectedRecordVersion == Long.MAX_VALUE) {
        throw new IllegalArgumentException("Generation operation state version is invalid");
      }
      expectedPreviousActive =
          Objects.requireNonNull(expectedPreviousActive, "Expected active signer is required");
      expectedPublishedActive =
          Objects.requireNonNull(expectedPublishedActive, "Expected published signer is required");
      parseGeneration(targetGeneration);
      requireMatch(KID, targetKid, "target kid");
      if (!ALGORITHM.equals(targetAlgorithm)) {
        throw new IllegalArgumentException("Only RS256 generation operations are supported");
      }
      Objects.requireNonNull(trustFence, "Generation operation trust is required");
    }

    private static StoredGenerationOperation create(
        DesiredState state,
        TrustFence trust,
        UUID operationId,
        long targetGeneration,
        String targetKid) {
      StoredGenerationOperation unsigned =
          new StoredGenerationOperation(
              operationId,
              state.binding(),
              REQUEST_DIGEST_VERSION,
              "0".repeat(64),
              state.recordVersion(),
              state.durableActive(),
              state.publishedActive(),
              Long.toString(targetGeneration),
              targetKid,
              ALGORITHM,
              trust);
      return new StoredGenerationOperation(
          unsigned.operationId(),
          unsigned.binding(),
          unsigned.digestVersion(),
          generationOperationDigest(unsigned),
          unsigned.expectedRecordVersion(),
          unsigned.expectedPreviousActive(),
          unsigned.expectedPublishedActive(),
          unsigned.targetGeneration(),
          unsigned.targetKid(),
          unsigned.targetAlgorithm(),
          unsigned.trustFence());
    }
  }

  private record SecretObservation(
      UUID operationId,
      Binding binding,
      String operationDigest,
      long expectedRecordVersion,
      TrustFence trustFence,
      String privateSecretName,
      String secretUid,
      String observedResourceVersion,
      String observationDigest,
      String generationRequestDigest) {
    private SecretObservation {
      requireOperationId(operationId);
      Objects.requireNonNull(binding, "Secret observation binding is required");
      if (expectedRecordVersion <= 0 || expectedRecordVersion == Long.MAX_VALUE) {
        throw new IllegalArgumentException("Secret observation state version is invalid");
      }
      requireMatch(SHA256_HEX, operationDigest, "operation digest");
      Objects.requireNonNull(trustFence, "Secret observation trust is required");
      if (!PRIVATE_SECRET_NAME.equals(privateSecretName)) {
        throw new IllegalArgumentException("Secret observation must name the fixed Secret");
      }
      requireUuid(secretUid, "observed Secret UID");
      requireMatch(RESOURCE_VERSION, observedResourceVersion, "observed Secret resourceVersion");
      requireMatch(SHA256_HEX, observationDigest, "Secret observation digest");
      requireMatch(SHA256_HEX, generationRequestDigest, "generation request digest");
    }

    private static SecretObservation from(
        StoredGenerationOperation operation, String secretUid, String resourceVersion) {
      String observationDigest =
          AccountJwtSignerDesiredStateRepository.observationDigest(
              operation.operationDigest(), operation.trustFence(), secretUid, resourceVersion);
      String generationRequestDigest =
          AccountJwtSignerDesiredStateRepository.generationRequestDigest(
              operation.operationDigest(), observationDigest, secretUid, resourceVersion);
      return new SecretObservation(
          operation.operationId(),
          operation.binding(),
          operation.operationDigest(),
          operation.expectedRecordVersion(),
          operation.trustFence(),
          PRIVATE_SECRET_NAME,
          secretUid,
          resourceVersion,
          observationDigest,
          generationRequestDigest);
    }
  }

  private record PublicJwk(String kid, String canonicalJson, String fingerprint) {}

  private record LockedCurrent(DesiredState state, StoredGenerationOperation operation) {}

  private record StoredPromotion(
      UUID operationId,
      Binding binding,
      int requestDigestVersion,
      String requestDigest,
      long expectedRecordVersion,
      Optional<ActiveSigner> expectedPreviousActive,
      Optional<ActiveSigner> expectedPublishedActive,
      String targetGeneration,
      String targetKid,
      String targetAlgorithm,
      String targetPublicKeyFingerprint,
      String expectedPrivateResourceVersion,
      String expectedPublicResourceVersion,
      TrustFence trustFence,
      UUID generationOperationId,
      String generationOperationDigest,
      String generationReceiptDigest,
      String apiBindingDigest,
      String apiConfigRevision,
      String secretUid,
      String publicConfigMapUid,
      String prepublicationIntentDigest,
      String prepublicationReceiptDigest,
      String mountedObservationDigest,
      String readinessPlanDigest,
      String readinessEvidenceDigest,
      String expectedPublicJwksJson,
      String expectedActiveMarkerJson,
      String status,
      boolean privatePromotionDispatched,
      String privatePromotionObservedResourceVersion,
      String privatePromotionReceiptDigest,
      String activeJwksObservedResourceVersion,
      String activeJwksPublicDataDigest,
      String activeJwksReceiptDigest) {
    private StoredPromotion {
      requireOperationId(operationId);
      Objects.requireNonNull(binding, "Promotion binding is required");
      if (requestDigestVersion != 1
          || expectedRecordVersion <= 0L
          || expectedRecordVersion == Long.MAX_VALUE) {
        throw new QuarantinedStateException("Promotion version fence is malformed");
      }
      requireMatch(SHA256_HEX, requestDigest, "promotion request digest");
      expectedPreviousActive = Objects.requireNonNull(expectedPreviousActive);
      expectedPublishedActive = Objects.requireNonNull(expectedPublishedActive);
      parseGeneration(targetGeneration);
      requireMatch(KID, targetKid, "promotion target kid");
      if (!ALGORITHM.equals(targetAlgorithm)) {
        throw new QuarantinedStateException("Promotion target algorithm is unsupported");
      }
      requireMatch(SHA256_HEX, targetPublicKeyFingerprint, "promotion target fingerprint");
      requireMatch(
          RESOURCE_VERSION, expectedPrivateResourceVersion, "expected private Secret version");
      requireMatch(
          RESOURCE_VERSION, expectedPublicResourceVersion, "expected public ConfigMap version");
      Objects.requireNonNull(trustFence, "Promotion trust fence is required");
      requireOperationId(generationOperationId);
      requireMatch(SHA256_HEX, generationOperationDigest, "generation operation digest");
      requireMatch(SHA256_HEX, generationReceiptDigest, "generation receipt digest");
      requireMatch(SHA256_HEX, apiBindingDigest, "public API binding digest");
      requireMatch(CLUSTER_ID, apiConfigRevision, "public API config revision");
      requireUuid(secretUid, "promotion Secret UID");
      requireUuid(publicConfigMapUid, "promotion ConfigMap UID");
      requireMatch(SHA256_HEX, prepublicationIntentDigest, "prepublication intent digest");
      requireMatch(SHA256_HEX, prepublicationReceiptDigest, "prepublication receipt digest");
      requireMatch(SHA256_HEX, mountedObservationDigest, "mounted observation digest");
      requireMatch(SHA256_HEX, readinessPlanDigest, "readiness plan digest");
      requireMatch(SHA256_HEX, readinessEvidenceDigest, "readiness evidence digest");
      if (expectedPublicJwksJson == null
          || expectedPublicJwksJson.isBlank()
          || expectedPublicJwksJson.getBytes(StandardCharsets.UTF_8).length > 256 * 1024
          || expectedActiveMarkerJson == null
          || expectedActiveMarkerJson.isBlank()
          || expectedActiveMarkerJson.getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
        throw new QuarantinedStateException("Promotion public payload is missing or oversized");
      }
      if (!Set.of("PREPARED", "COMMITTED", "ABORTED").contains(status)) {
        throw new QuarantinedStateException("Promotion status is malformed");
      }
      boolean noActiveJwksReceipt =
          activeJwksObservedResourceVersion == null
              && activeJwksPublicDataDigest == null
              && activeJwksReceiptDigest == null;
      boolean completeActiveJwksReceipt =
          activeJwksObservedResourceVersion != null
              && activeJwksPublicDataDigest != null
              && activeJwksReceiptDigest != null;
      if ((privatePromotionObservedResourceVersion == null)
              != (privatePromotionReceiptDigest == null)
          || !(noActiveJwksReceipt || completeActiveJwksReceipt)) {
        throw new QuarantinedStateException("Promotion resource receipts are partial");
      }
      if ((privatePromotionReceiptDigest != null && !privatePromotionDispatched)
          || ("COMMITTED".equals(status) && !privatePromotionDispatched)
          || ("ABORTED".equals(status) && privatePromotionDispatched)) {
        throw new QuarantinedStateException(
            "Promotion dispatch state is inconsistent with its receipts or terminal status");
      }
      if (privatePromotionObservedResourceVersion != null) {
        requireMatch(
            RESOURCE_VERSION,
            privatePromotionObservedResourceVersion,
            "private promotion readback version");
        requireMatch(SHA256_HEX, privatePromotionReceiptDigest, "private promotion receipt digest");
      }
      if (activeJwksObservedResourceVersion != null) {
        requireMatch(
            RESOURCE_VERSION, activeJwksObservedResourceVersion, "active JWKS readback version");
        requireMatch(SHA256_HEX, activeJwksPublicDataDigest, "active JWKS public data digest");
        requireMatch(SHA256_HEX, activeJwksReceiptDigest, "active JWKS receipt digest");
      }
    }

    private static StoredPromotion create(
        PromotionPreparation preparation,
        DesiredState state,
        StoredGenerationOperation generation,
        GenerationResult result,
        TrustFence trust,
        Binding binding) {
      if (!preparation.generationOperationId().equals(generation.operationId())
          || !preparation.generationResult().equals(result)
          || !preparation
              .enrollmentIdentity()
              .sameStablePins(state.enrollmentIdentity().orElseThrow())
          || !generation.binding().equals(binding)
          || !result.binding().equals(binding)
          || !generation.trustFence().equals(trust)
          || !result.trustFence().equals(trust)
          || !state.generationOperationId().equals(Optional.of(generation.operationId()))
          || state.preparedOperationId().isPresent()) {
        throw new PromotionPrerequisitesIncompleteException(
            "Promotion preparation is not bound to the current Account generation and enrollment");
      }
      UUID promotionId = UUID.randomUUID();
      StoredPromotion unsigned =
          new StoredPromotion(
              promotionId,
              binding,
              1,
              "0".repeat(64),
              state.recordVersion(),
              state.durableActive(),
              state.publishedActive(),
              result.targetGeneration(),
              result.targetKid(),
              result.targetAlgorithm(),
              result.publicKeyFingerprint(),
              result.observedResourceVersion(),
              preparation.expectedPublicResourceVersion(),
              trust,
              generation.operationId(),
              generation.operationDigest(),
              result.receiptDigest(),
              preparation.apiBindingDigest(),
              preparation.apiConfigRevision(),
              result.secretUid(),
              preparation.publicConfigMapUid(),
              preparation.prepublicationIntentDigest(),
              preparation.prepublicationReceiptDigest(),
              preparation.mountedObservationDigest(),
              preparation.readinessPlanDigest(),
              preparation.readinessEvidenceDigest(),
              preparation.expectedPublicJwksJson(),
              "{}",
              "PREPARED",
              false,
              null,
              null,
              null,
              null,
              null);
      StoredPromotion signed =
          new StoredPromotion(
              unsigned.operationId(),
              unsigned.binding(),
              unsigned.requestDigestVersion(),
              promotionRequestDigest(unsigned),
              unsigned.expectedRecordVersion(),
              unsigned.expectedPreviousActive(),
              unsigned.expectedPublishedActive(),
              unsigned.targetGeneration(),
              unsigned.targetKid(),
              unsigned.targetAlgorithm(),
              unsigned.targetPublicKeyFingerprint(),
              unsigned.expectedPrivateResourceVersion(),
              unsigned.expectedPublicResourceVersion(),
              unsigned.trustFence(),
              unsigned.generationOperationId(),
              unsigned.generationOperationDigest(),
              unsigned.generationReceiptDigest(),
              unsigned.apiBindingDigest(),
              unsigned.apiConfigRevision(),
              unsigned.secretUid(),
              unsigned.publicConfigMapUid(),
              unsigned.prepublicationIntentDigest(),
              unsigned.prepublicationReceiptDigest(),
              unsigned.mountedObservationDigest(),
              unsigned.readinessPlanDigest(),
              unsigned.readinessEvidenceDigest(),
              unsigned.expectedPublicJwksJson(),
              "{}",
              unsigned.status(),
              unsigned.privatePromotionDispatched(),
              null,
              null,
              null,
              null,
              null);
      return new StoredPromotion(
          signed.operationId(),
          signed.binding(),
          signed.requestDigestVersion(),
          signed.requestDigest(),
          signed.expectedRecordVersion(),
          signed.expectedPreviousActive(),
          signed.expectedPublishedActive(),
          signed.targetGeneration(),
          signed.targetKid(),
          signed.targetAlgorithm(),
          signed.targetPublicKeyFingerprint(),
          signed.expectedPrivateResourceVersion(),
          signed.expectedPublicResourceVersion(),
          signed.trustFence(),
          signed.generationOperationId(),
          signed.generationOperationDigest(),
          signed.generationReceiptDigest(),
          signed.apiBindingDigest(),
          signed.apiConfigRevision(),
          signed.secretUid(),
          signed.publicConfigMapUid(),
          signed.prepublicationIntentDigest(),
          signed.prepublicationReceiptDigest(),
          signed.mountedObservationDigest(),
          signed.readinessPlanDigest(),
          signed.readinessEvidenceDigest(),
          signed.expectedPublicJwksJson(),
          activeMarkerJson(signed),
          signed.status(),
          signed.privatePromotionDispatched(),
          null,
          null,
          null,
          null,
          null);
    }

    private String operationDigestForGeneration() {
      return generationOperationDigest;
    }

    private boolean matchesPreparation(
        PromotionPreparation preparation,
        StoredGenerationOperation generation,
        GenerationResult result,
        EnrollmentIdentity enrollment) {
      return generation.operationId().equals(preparation.generationOperationId())
          && result.equals(preparation.generationResult())
          && preparation.enrollmentIdentity().sameStablePins(enrollment)
          && apiBindingDigest.equals(preparation.apiBindingDigest())
          && apiConfigRevision.equals(preparation.apiConfigRevision())
          && publicConfigMapUid.equals(preparation.publicConfigMapUid())
          && expectedPublicResourceVersion.equals(preparation.expectedPublicResourceVersion())
          && prepublicationIntentDigest.equals(preparation.prepublicationIntentDigest())
          && prepublicationReceiptDigest.equals(preparation.prepublicationReceiptDigest())
          && mountedObservationDigest.equals(preparation.mountedObservationDigest())
          && readinessPlanDigest.equals(preparation.readinessPlanDigest())
          && readinessEvidenceDigest.equals(preparation.readinessEvidenceDigest())
          && expectedPublicJwksJson.equals(preparation.expectedPublicJwksJson())
          && generationOperationId.equals(generation.operationId())
          && generationOperationDigest.equals(generation.operationDigest())
          && generationReceiptDigest.equals(result.receiptDigest())
          && secretUid.equals(result.secretUid())
          && expectedPrivateResourceVersion.equals(result.observedResourceVersion())
          && targetGeneration.equals(result.targetGeneration())
          && targetKid.equals(result.targetKid())
          && targetPublicKeyFingerprint.equals(result.publicKeyFingerprint());
    }

    private PromotionOperationEvidence publicEvidence() {
      return new PromotionOperationEvidence(
          operationId,
          requestDigest,
          expectedRecordVersion,
          binding,
          expectedPreviousActive,
          expectedPreviousActive.map(
              active -> publicFingerprintForKid(expectedPublicJwksJson, active.kid())),
          expectedPublishedActive,
          targetGeneration,
          targetKid,
          targetAlgorithm,
          targetPublicKeyFingerprint,
          trustFence,
          generationOperationId,
          generationOperationDigest,
          generationReceiptDigest,
          secretUid,
          expectedPrivateResourceVersion,
          apiBindingDigest,
          apiConfigRevision,
          publicConfigMapUid,
          expectedPublicResourceVersion,
          prepublicationIntentDigest,
          prepublicationReceiptDigest,
          mountedObservationDigest,
          readinessPlanDigest,
          readinessEvidenceDigest,
          expectedPublicJwksJson,
          expectedActiveMarkerJson,
          status,
          privatePromotionDispatched,
          Optional.ofNullable(privatePromotionObservedResourceVersion),
          Optional.ofNullable(privatePromotionReceiptDigest),
          Optional.ofNullable(activeJwksObservedResourceVersion),
          Optional.ofNullable(activeJwksPublicDataDigest),
          Optional.ofNullable(activeJwksReceiptDigest));
    }
  }

  public record PreparedGenerationEvidence(
      DesiredState desiredState,
      PromotionOperationEvidence promotion,
      GenerationResult generationResult) {
    public PreparedGenerationEvidence {
      Objects.requireNonNull(desiredState);
      Objects.requireNonNull(promotion);
      Objects.requireNonNull(generationResult);
      if (!"PREPARED".equals(promotion.status())
          || !promotion.generationOperationId().equals(generationResult.operationId())) {
        throw new QuarantinedStateException("Prepared generation evidence is inconsistent");
      }
    }
  }

  /** Exact current owner-readback values required to freeze one PREPARED operation. */
  public record PromotionPreparation(
      UUID generationOperationId,
      GenerationResult generationResult,
      EnrollmentIdentity enrollmentIdentity,
      String apiBindingDigest,
      String apiConfigRevision,
      String publicConfigMapUid,
      String expectedPublicResourceVersion,
      String prepublicationIntentDigest,
      String prepublicationReceiptDigest,
      String mountedObservationDigest,
      String readinessPlanDigest,
      String readinessEvidenceDigest,
      String expectedPublicJwksJson) {
    public PromotionPreparation {
      requireOperationId(generationOperationId);
      Objects.requireNonNull(generationResult, "Persisted generation result is required");
      Objects.requireNonNull(enrollmentIdentity, "Pinned Account enrollment is required");
      requireMatch(SHA256_HEX, apiBindingDigest, "promotion API binding digest");
      requireMatch(CLUSTER_ID, apiConfigRevision, "promotion API config revision");
      requireUuid(publicConfigMapUid, "promotion public ConfigMap UID");
      requireMatch(
          RESOURCE_VERSION, expectedPublicResourceVersion, "promotion ConfigMap resourceVersion");
      requireMatch(SHA256_HEX, prepublicationIntentDigest, "prepublication intent digest");
      requireMatch(SHA256_HEX, prepublicationReceiptDigest, "prepublication receipt digest");
      requireMatch(SHA256_HEX, mountedObservationDigest, "mounted observation digest");
      requireMatch(SHA256_HEX, readinessPlanDigest, "readiness plan digest");
      requireMatch(SHA256_HEX, readinessEvidenceDigest, "readiness evidence digest");
      if (expectedPublicJwksJson == null
          || expectedPublicJwksJson.isBlank()
          || expectedPublicJwksJson.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
        throw new PromotionPrerequisitesIncompleteException(
            "Account promotion public projection is missing or oversized");
      }
    }
  }

  public record ActiveJwksPromotionObservation(
      String configMapUid,
      String priorResourceVersion,
      String observedResourceVersion,
      String jwksJson,
      String activeMarkerJson) {
    public ActiveJwksPromotionObservation {
      requireUuid(configMapUid, "active public ConfigMap UID");
      requireMatch(
          RESOURCE_VERSION, priorResourceVersion, "active ConfigMap prior resourceVersion");
      requireMatch(
          RESOURCE_VERSION, observedResourceVersion, "active ConfigMap readback resourceVersion");
      if (priorResourceVersion.equals(observedResourceVersion)) {
        throw new QuarantinedStateException(
            "Account ACTIVE ConfigMap resourceVersion did not advance");
      }
      if (jwksJson == null
          || jwksJson.isBlank()
          || jwksJson.getBytes(StandardCharsets.UTF_8).length > 256 * 1024
          || activeMarkerJson == null
          || activeMarkerJson.isBlank()
          || activeMarkerJson.getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
        throw new QuarantinedStateException(
            "Account ACTIVE ConfigMap projection is missing or oversized");
      }
    }
  }

  public record ActiveJwksPromotionReceipt(
      UUID promotionOperationId,
      String configMapUid,
      String priorResourceVersion,
      String observedResourceVersion,
      String publicDataDigest,
      String receiptDigest) {
    public ActiveJwksPromotionReceipt {
      requireOperationId(promotionOperationId);
      requireUuid(configMapUid, "active public ConfigMap UID");
      requireMatch(
          RESOURCE_VERSION, priorResourceVersion, "active ConfigMap prior resourceVersion");
      requireMatch(
          RESOURCE_VERSION, observedResourceVersion, "active ConfigMap readback resourceVersion");
      requireMatch(SHA256_HEX, publicDataDigest, "active public ConfigMap data digest");
      requireMatch(SHA256_HEX, receiptDigest, "active public ConfigMap receipt digest");
      if (priorResourceVersion.equals(observedResourceVersion)) {
        throw new QuarantinedStateException(
            "Account ACTIVE ConfigMap resourceVersion did not advance");
      }
    }

    private static ActiveJwksPromotionReceipt from(
        StoredPromotion promotion, ActiveJwksPromotionObservation observation) {
      String dataDigest =
          AccountJwtJwksPublicationRepository.publicDataDigest(
              observation.jwksJson(), observation.activeMarkerJson());
      return create(
          promotion,
          observation.configMapUid(),
          observation.priorResourceVersion(),
          observation.observedResourceVersion(),
          dataDigest);
    }

    private static ActiveJwksPromotionReceipt fromStored(StoredPromotion promotion) {
      if (promotion.activeJwksObservedResourceVersion() == null
          || promotion.activeJwksPublicDataDigest() == null
          || promotion.activeJwksReceiptDigest() == null) {
        throw new QuarantinedStateException("Committed signer has no active JWKS receipt");
      }
      ActiveJwksPromotionReceipt calculated =
          create(
              promotion,
              promotion.publicConfigMapUid(),
              promotion.expectedPublicResourceVersion(),
              promotion.activeJwksObservedResourceVersion(),
              promotion.activeJwksPublicDataDigest());
      if (!calculated.receiptDigest().equals(promotion.activeJwksReceiptDigest())) {
        throw new QuarantinedStateException("Committed active JWKS receipt digest is invalid");
      }
      return calculated;
    }

    private static ActiveJwksPromotionReceipt create(
        StoredPromotion promotion,
        String configMapUid,
        String priorResourceVersion,
        String observedResourceVersion,
        String dataDigest) {
      Map<String, Object> preimage = new LinkedHashMap<>();
      preimage.put("receiptVersion", "account-jwt-active-jwks-promotion-receipt/v1");
      preimage.put("promotionOperationId", promotion.operationId().toString());
      preimage.put("promotionRequestDigest", promotion.requestDigest());
      preimage.put("generationOperationId", promotion.generationOperationId().toString());
      preimage.put("generationOperationDigest", promotion.generationOperationDigest());
      preimage.put("generationReceiptDigest", promotion.generationReceiptDigest());
      preimage.put("configMapName", PUBLIC_JWKS_CONFIG_MAP_NAME);
      preimage.put("configMapUid", configMapUid);
      preimage.put("priorResourceVersion", priorResourceVersion);
      preimage.put("observedResourceVersion", observedResourceVersion);
      preimage.put("action", "MARK_ACTIVE");
      preimage.put("publicDataDigest", dataDigest);
      return new ActiveJwksPromotionReceipt(
          promotion.operationId(),
          configMapUid,
          priorResourceVersion,
          observedResourceVersion,
          dataDigest,
          digest(preimage));
    }
  }

  /** Durable non-authorizing result for Account's current committed active signer. */
  public record CommittedSignerEvidence(
      PromotionOperationEvidence promotion,
      GenerationResult generationResult,
      DesiredState desiredState,
      PrivatePromotionReceipt privateReceipt,
      ActiveJwksPromotionReceipt publicReceipt) {
    public CommittedSignerEvidence {
      Objects.requireNonNull(promotion);
      Objects.requireNonNull(generationResult);
      Objects.requireNonNull(desiredState);
      Objects.requireNonNull(privateReceipt);
      Objects.requireNonNull(publicReceipt);
      ActiveSigner target = new ActiveSigner(promotion.targetGeneration(), promotion.targetKid());
      if (!"COMMITTED".equals(promotion.status())
          || !promotion.operationId().equals(privateReceipt.promotionOperationId())
          || !promotion.operationId().equals(publicReceipt.promotionOperationId())
          || !promotion.generationOperationId().equals(generationResult.operationId())
          || desiredState.durableActive().filter(target::equals).isEmpty()
          || desiredState.publishedActive().filter(target::equals).isEmpty()
          || desiredState.preparedOperationId().isPresent()) {
        throw new QuarantinedStateException("Committed signer evidence is inconsistent");
      }
    }
  }

  public record AbortedPromotion(
      UUID operationId,
      long desiredStateVersion,
      Optional<ActiveSigner> durableActive,
      Optional<ActiveSigner> publishedActive) {
    public AbortedPromotion {
      requireOperationId(operationId);
      if (desiredStateVersion <= 0)
        throw new IllegalArgumentException("Aborted state version is invalid");
      durableActive = Objects.requireNonNull(durableActive);
      publishedActive = Objects.requireNonNull(publishedActive);
      if (!durableActive.equals(publishedActive)) {
        throw new QuarantinedStateException("Aborted signer operation changed active fences");
      }
    }
  }

  /** Public-only immutable promotion row needed by the materializer and Account reconciler. */
  public record PromotionOperationEvidence(
      UUID operationId,
      String requestDigest,
      long expectedRecordVersion,
      Binding binding,
      Optional<ActiveSigner> expectedPreviousActive,
      Optional<String> expectedPreviousPublicKeyFingerprint,
      Optional<ActiveSigner> expectedPublishedActive,
      String targetGeneration,
      String targetKid,
      String targetAlgorithm,
      String targetPublicKeyFingerprint,
      TrustFence trustFence,
      UUID generationOperationId,
      String generationOperationDigest,
      String generationReceiptDigest,
      String secretUid,
      String expectedPrivateResourceVersion,
      String apiBindingDigest,
      String apiConfigRevision,
      String publicConfigMapUid,
      String expectedPublicResourceVersion,
      String prepublicationIntentDigest,
      String prepublicationReceiptDigest,
      String mountedObservationDigest,
      String readinessPlanDigest,
      String readinessEvidenceDigest,
      String expectedPublicJwksJson,
      String expectedActiveMarkerJson,
      String status,
      boolean privatePromotionDispatched,
      Optional<String> privatePromotionObservedResourceVersion,
      Optional<String> privatePromotionReceiptDigest,
      Optional<String> activeJwksObservedResourceVersion,
      Optional<String> activeJwksPublicDataDigest,
      Optional<String> activeJwksReceiptDigest) {
    public PromotionOperationEvidence {
      Objects.requireNonNull(operationId);
      requireMatch(SHA256_HEX, requestDigest, "promotion request digest");
      Objects.requireNonNull(binding);
      expectedPreviousActive = Objects.requireNonNull(expectedPreviousActive);
      expectedPreviousPublicKeyFingerprint =
          Objects.requireNonNull(expectedPreviousPublicKeyFingerprint);
      expectedPublishedActive = Objects.requireNonNull(expectedPublishedActive);
      if (expectedPreviousActive.isPresent() != expectedPreviousPublicKeyFingerprint.isPresent()) {
        throw new QuarantinedStateException("Promotion prior public identity is incomplete");
      }
      expectedPreviousPublicKeyFingerprint.ifPresent(
          value -> requireMatch(SHA256_HEX, value, "prior public-key fingerprint"));
      Objects.requireNonNull(trustFence);
      Objects.requireNonNull(generationOperationId);
      requireMatch(SHA256_HEX, generationOperationDigest, "generation operation digest");
      requireMatch(SHA256_HEX, generationReceiptDigest, "generation receipt digest");
      privatePromotionObservedResourceVersion =
          Objects.requireNonNull(privatePromotionObservedResourceVersion);
      privatePromotionReceiptDigest = Objects.requireNonNull(privatePromotionReceiptDigest);
      activeJwksObservedResourceVersion = Objects.requireNonNull(activeJwksObservedResourceVersion);
      activeJwksPublicDataDigest = Objects.requireNonNull(activeJwksPublicDataDigest);
      activeJwksReceiptDigest = Objects.requireNonNull(activeJwksReceiptDigest);
    }
  }

  public record PublicKeyIdentity(String generation, String kid, String publicKeyFingerprint) {
    public PublicKeyIdentity {
      parseGeneration(generation);
      requireMatch(KID, kid, "observed public key kid");
      requireMatch(SHA256_HEX, publicKeyFingerprint, "observed public key fingerprint");
    }
  }

  public record PrivatePromotionObservation(
      UUID promotionOperationId,
      String promotionRequestDigest,
      UUID generationOperationId,
      String generationOperationDigest,
      String secretUid,
      String expectedPriorResourceVersion,
      String observedResourceVersion,
      PublicKeyIdentity current,
      Optional<PublicKeyIdentity> previous,
      List<String> resultingSlots) {
    public PrivatePromotionObservation {
      requireOperationId(promotionOperationId);
      requireMatch(SHA256_HEX, promotionRequestDigest, "promotion request digest");
      requireOperationId(generationOperationId);
      requireMatch(SHA256_HEX, generationOperationDigest, "generation operation digest");
      requireUuid(secretUid, "observed fixed Secret UID");
      requireMatch(RESOURCE_VERSION, expectedPriorResourceVersion, "expected Secret version");
      requireMatch(RESOURCE_VERSION, observedResourceVersion, "observed Secret version");
      Objects.requireNonNull(current);
      previous = Objects.requireNonNull(previous);
      resultingSlots = List.copyOf(resultingSlots);
      if (expectedPriorResourceVersion.equals(observedResourceVersion)
          || !(resultingSlots.equals(List.of("current"))
              || resultingSlots.equals(List.of("current", "previous")))) {
        throw new QuarantinedStateException("Private promotion readback identity is malformed");
      }
    }
  }

  public record PrivatePromotionReceipt(
      UUID promotionOperationId,
      UUID generationOperationId,
      String observedResourceVersion,
      String receiptDigest) {
    public PrivatePromotionReceipt {
      requireOperationId(promotionOperationId);
      requireOperationId(generationOperationId);
      requireMatch(RESOURCE_VERSION, observedResourceVersion, "private promotion readback version");
      requireMatch(SHA256_HEX, receiptDigest, "private promotion receipt digest");
    }

    private static PrivatePromotionReceipt from(
        StoredPromotion promotion, PrivatePromotionObservation observation) {
      Map<String, Object> preimage = new LinkedHashMap<>();
      preimage.put("receiptVersion", 1);
      preimage.put("promotionOperationId", promotion.operationId().toString());
      preimage.put("promotionRequestDigest", promotion.requestDigest());
      preimage.put("generationOperationId", promotion.generationOperationId().toString());
      preimage.put("generationOperationDigest", promotion.generationOperationDigest());
      preimage.put("generationReceiptDigest", promotion.generationReceiptDigest());
      preimage.put("secretResource", Map.of("kind", "Secret", "name", PRIVATE_SECRET_NAME));
      preimage.put("secretUid", observation.secretUid());
      preimage.put("expectedPriorResourceVersion", observation.expectedPriorResourceVersion());
      preimage.put("observedResourceVersion", observation.observedResourceVersion());
      preimage.put("action", "PROMOTE_PENDING");
      preimage.put("resultingSlots", observation.resultingSlots());
      preimage.put("current", publicKeyIdentityMap(observation.current()));
      preimage.put(
          "previous",
          observation
              .previous()
              .<Object>map(AccountJwtSignerDesiredStateRepository::publicKeyIdentityMap)
              .orElseGet(() -> Map.of("present", false)));
      return new PrivatePromotionReceipt(
          promotion.operationId(),
          promotion.generationOperationId(),
          observation.observedResourceVersion(),
          digest(preimage));
    }

    private static PrivatePromotionReceipt fromStored(StoredPromotion promotion) {
      if (promotion.privatePromotionObservedResourceVersion() == null
          || promotion.privatePromotionReceiptDigest() == null) {
        throw new QuarantinedStateException("Committed signer has no private promotion receipt");
      }
      Optional<PublicKeyIdentity> previous =
          promotion
              .expectedPreviousActive()
              .map(
                  active ->
                      new PublicKeyIdentity(
                          active.generation(),
                          active.kid(),
                          publicFingerprintForKid(
                              promotion.expectedPublicJwksJson(), active.kid())));
      PrivatePromotionReceipt calculated =
          from(
              promotion,
              new PrivatePromotionObservation(
                  promotion.operationId(),
                  promotion.requestDigest(),
                  promotion.generationOperationId(),
                  promotion.generationOperationDigest(),
                  promotion.secretUid(),
                  promotion.expectedPrivateResourceVersion(),
                  promotion.privatePromotionObservedResourceVersion(),
                  new PublicKeyIdentity(
                      promotion.targetGeneration(),
                      promotion.targetKid(),
                      promotion.targetPublicKeyFingerprint()),
                  previous,
                  previous.isPresent() ? List.of("current", "previous") : List.of("current")));
      if (!calculated.receiptDigest().equals(promotion.privatePromotionReceiptDigest())) {
        throw new QuarantinedStateException(
            "Committed private promotion receipt digest is invalid");
      }
      return calculated;
    }
  }

  private static Map<String, Object> publicKeyIdentityMap(PublicKeyIdentity identity) {
    return Map.of(
        "generation", identity.generation(),
        "kid", identity.kid(),
        "publicKeyFingerprint", identity.publicKeyFingerprint());
  }

  public record PreparedPromotion(UUID operationId, String requestDigest, String status) {
    public PreparedPromotion {
      requireOperationId(operationId);
      requireMatch(SHA256_HEX, requestDigest, "prepared promotion digest");
      if (!"PREPARED".equals(status)) {
        throw new IllegalArgumentException("Only PREPARED JWT signer operations are represented");
      }
    }
  }

  private static String generationRequestDigest(
      String operationDigest, String observationDigest, String secretUid, String resourceVersion) {
    return digest(
        Map.of(
            "digestVersion", GENERATION_REQUEST_DIGEST_VERSION,
            "operationDigest", operationDigest,
            "observationDigest", observationDigest,
            "secretUid", secretUid,
            "expectedPriorResourceVersion", resourceVersion,
            "target", "pending"));
  }

  public static class MissingDesiredStateException extends IllegalStateException {
    public MissingDesiredStateException(String environmentId) {
      super("Account JWT signer desired state is missing for environment " + environmentId);
    }
  }

  public static class MissingGenerationOperationException extends IllegalStateException {
    public MissingGenerationOperationException(String message) {
      super(message);
    }
  }

  public static class MissingGenerationObservationException extends IllegalStateException {
    public MissingGenerationObservationException(String message) {
      super(message);
    }
  }

  public static class StaleGenerationOperationException extends IllegalStateException {
    public StaleGenerationOperationException(String message) {
      super(message);
    }
  }

  public static class BindingMismatchException extends IllegalStateException {
    public BindingMismatchException(String message) {
      super(message);
    }
  }

  public static class VersionConflictException extends IllegalStateException {
    public VersionConflictException(String message) {
      super(message);
    }
  }

  public static class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(String message) {
      super(message);
    }
  }

  public static class QuarantinedStateException extends IllegalStateException {
    public QuarantinedStateException(String message) {
      super(message);
    }

    public QuarantinedStateException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class PromotionPrerequisitesIncompleteException extends IllegalStateException {
    public PromotionPrerequisitesIncompleteException(String message) {
      super(message);
    }
  }

  public static class NoPreparedPromotionException extends IllegalStateException {
    public NoPreparedPromotionException() {
      super("Account has no PREPARED JWT signer promotion");
    }
  }

  public static class StorageUnavailableException extends IllegalStateException {
    public StorageUnavailableException(String message) {
      super(message);
    }
  }
}
