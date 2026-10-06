package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PreparedGenerationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable Account-owned evidence for the fixed public JWT ConfigMap projection.
 *
 * <p>Every write re-locks the current desired-state row, generation operation, and exact generation
 * result through {@link AccountJwtSignerDesiredStateRepository} before touching these rows. The
 * repository records intent before external CAS, and its receipts/observations never authorize
 * PREPARED, activation, token issuance, or readiness.
 */
@Repository
public class AccountJwtJwksPublicationRepository {
  public static final String PUBLIC_JWKS_NAME = "jwt-jwks";
  public static final String JWKS_DATA_KEY = "jwks.json";
  public static final String GENERATION_MARKER_DATA_KEY = "jwt-generation.json";
  public static final int MAX_JWKS_BYTES = 256 * 1024;
  public static final int MAX_MARKER_BYTES = 16 * 1024;

  private static final String INTENT_TABLE = "account_jwt_jwks_prepublication_intents";
  private static final String RECEIPT_TABLE = "account_jwt_jwks_publication_receipts";
  private static final String MOUNT_TABLE = "account_jwt_jwks_mount_observations";
  private static final String CUSTODY_MODE = "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK";
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern RESOURCE_VERSION = Pattern.compile("[!-~]{1,256}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DSLContext dsl;
  private final AccountJwtSignerDesiredStateRepository desiredStateRepository;

  @Autowired
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The constructor only validates trusted Spring collaborators; it performs no I/O or resource acquisition and defines no finalizer.")
  public AccountJwtJwksPublicationRepository(
      DSLContext dsl, AccountJwtSignerDesiredStateRepository desiredStateRepository) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.desiredStateRepository =
        Objects.requireNonNull(desiredStateRepository, "Desired-state repository is required");
  }

  /**
   * Returns the current operation's immutable intent, locking its entire owner chain first. A
   * missing intent stays missing; reads never create a publication request.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<PrepublicationIntent> readCurrentIntent(Binding binding, TrustFence trust) {
    requireAccountTransaction();
    GenerationResult result = readAndLockCurrentResult(binding, trust);
    GenerationRequest request = desiredStateRepository.readCurrentGenerationRequest(binding, trust);
    PrepublicationIntent intent = selectIntent(result.operationId(), true);
    if (intent == null) {
      return Optional.empty();
    }
    verifyIntentMatches(result, request, intent);
    return Optional.of(intent);
  }

  /**
   * Persists the exact Account-constructed public projection before remote ConfigMap CAS. Only an
   * exact same-operation retry can read back the existing row; a changed prior object, public
   * bytes, API trust identity, generation result, or active fence is a conflict.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PrepublicationIntent recordPrepublicationIntent(
      Binding binding,
      TrustFence trust,
      GenerationResult expectedResult,
      GenerationRequest expectedRequest,
      String apiBindingDigest,
      String apiConfigRevision,
      String configMapUid,
      String expectedResourceVersion,
      String expectedSnapshotDigest,
      String jwksJson,
      String generationMarkerJson) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedResult, "Exact current generation result is required");
    Objects.requireNonNull(expectedRequest, "Exact current generation request is required");
    requireDigest(apiBindingDigest, "protected ConfigMap API binding digest");
    requireRevision(apiConfigRevision, "protected ConfigMap API configuration revision");
    requireUuid(configMapUid, "public ConfigMap UID");
    requireResourceVersion(expectedResourceVersion, "expected ConfigMap resourceVersion");
    requireDigest(expectedSnapshotDigest, "expected ConfigMap snapshot digest");
    requireBounded(jwksJson, MAX_JWKS_BYTES, "public JWKS");
    requireBounded(generationMarkerJson, MAX_MARKER_BYTES, "public generation marker");

    GenerationResult currentResult = readAndLockCurrentResult(binding, trust);
    GenerationRequest currentRequest =
        desiredStateRepository.readCurrentGenerationRequest(binding, trust);
    requireExactGeneration(expectedResult, currentResult, expectedRequest, currentRequest);
    PrepublicationIntent candidate =
        PrepublicationIntent.create(
            currentResult,
            currentRequest,
            apiBindingDigest,
            apiConfigRevision,
            configMapUid,
            expectedResourceVersion,
            expectedSnapshotDigest,
            jwksJson,
            generationMarkerJson);

    PrepublicationIntent existing = selectIntent(currentResult.operationId(), true);
    if (existing != null) {
      verifyIntentMatches(currentResult, currentRequest, existing);
      if (!existing.equals(candidate)) {
        throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
            "Account JWT JWKS publication intent changed for the same operation");
      }
      return existing;
    }

    insertIntent(candidate);
    PrepublicationIntent readback = selectIntent(currentResult.operationId(), true);
    if (readback == null || !readback.equals(candidate)) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account JWT JWKS publication intent did not read back exactly");
    }
    return readback;
  }

  /** Returns the current operation's persisted intent and optional exact API readback receipt. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<PublicationEvidence> readCurrentPublication(Binding binding, TrustFence trust) {
    requireAccountTransaction();
    GenerationResult result = readAndLockCurrentResult(binding, trust);
    GenerationRequest request = desiredStateRepository.readCurrentGenerationRequest(binding, trust);
    PrepublicationIntent intent = selectIntent(result.operationId(), true);
    if (intent == null) {
      return Optional.empty();
    }
    verifyIntentMatches(result, request, intent);
    PublicationReceipt receipt = selectReceipt(result.operationId(), true);
    if (receipt != null) {
      verifyReceiptMatches(intent, receipt);
    }
    MountObservation mount = selectMountObservation(result.operationId(), true);
    if (mount != null) {
      if (receipt == null) {
        throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
            "Mounted JWKS observation has no Account publication receipt");
      }
      verifyMountMatches(intent, receipt, mount);
    }
    return Optional.of(
        new PublicationEvidence(intent, Optional.ofNullable(receipt), Optional.ofNullable(mount)));
  }

  /**
   * Recovery-only read of the immutable publication that authorized the exact PREPARED source
   * generation. Ordinary current-publication reads remain unavailable while PREPARED.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public PreparedPublicationEvidence readPreparedPublicationForRecovery(
      Binding binding, TrustFence trust) {
    requireAccountTransaction();
    PreparedGenerationEvidence prepared =
        desiredStateRepository.readPreparedGenerationForRecovery(binding, trust);
    GenerationResult result = prepared.generationResult();
    PrepublicationIntent intent = selectIntent(result.operationId(), false);
    PublicationReceipt receipt = selectReceipt(result.operationId(), false);
    MountObservation mount = selectMountObservation(result.operationId(), false);
    if (intent == null || receipt == null || mount == null) {
      throw new AccountJwtSignerDesiredStateRepository.PromotionPrerequisitesIncompleteException(
          "Prepared generation has no exact public prepublication/readback/mount evidence");
    }
    verifyPreparedIntentMatches(prepared, intent);
    verifyReceiptMatches(intent, receipt);
    verifyMountMatches(intent, receipt, mount);
    PromotionOperationEvidence promotion = prepared.promotion();
    if (!promotion.prepublicationIntentDigest().equals(intent.intentDigest())
        || !promotion.prepublicationReceiptDigest().equals(receipt.receiptDigest())
        || !promotion.mountedObservationDigest().equals(mount.observationDigest())
        || !promotion.publicConfigMapUid().equals(intent.configMapUid())
        || !promotion.expectedPublicResourceVersion().equals(receipt.observedResourceVersion())
        || !promotion.apiBindingDigest().equals(intent.apiBindingDigest())
        || !promotion.apiConfigRevision().equals(intent.apiConfigRevision())
        || !promotion.expectedPublicJwksJson().equals(intent.jwksJson())) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Prepared promotion no longer matches its immutable prepublication evidence");
    }
    return new PreparedPublicationEvidence(intent, receipt, mount);
  }

  /**
   * Persists only an exact GET readback from the protected CAS client. No Kubernetes response body,
   * bearer token, or private key is stored.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PublicationReceipt recordPublicationReceipt(
      Binding binding,
      TrustFence trust,
      UUID operationId,
      String apiBindingDigest,
      String apiConfigRevision,
      String configMapUid,
      String returnedResourceVersion,
      String jwksJson,
      String generationMarkerJson) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(operationId, "Account JWT signer operation ID is required");
    requireDigest(apiBindingDigest, "protected ConfigMap API binding digest");
    requireRevision(apiConfigRevision, "protected ConfigMap API configuration revision");
    requireUuid(configMapUid, "public ConfigMap UID");
    requireResourceVersion(returnedResourceVersion, "observed ConfigMap resourceVersion");
    requireBounded(jwksJson, MAX_JWKS_BYTES, "public JWKS");
    requireBounded(generationMarkerJson, MAX_MARKER_BYTES, "public generation marker");

    GenerationResult result = readAndLockCurrentResult(binding, trust);
    if (!result.operationId().equals(operationId)) {
      throw new AccountJwtSignerDesiredStateRepository.StaleGenerationOperationException(
          "Account JWT JWKS receipt is not for the current generation operation");
    }
    GenerationRequest request = desiredStateRepository.readCurrentGenerationRequest(binding, trust);
    PrepublicationIntent intent = selectIntent(operationId, true);
    if (intent == null) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account JWT JWKS publication intent is missing");
    }
    verifyIntentMatches(result, request, intent);
    if (!intent.apiBindingDigest().equals(apiBindingDigest)
        || !intent.apiConfigRevision().equals(apiConfigRevision)
        || !intent.configMapUid().equals(configMapUid)
        || !intent.jwksJson().equals(jwksJson)
        || !intent.generationMarkerJson().equals(generationMarkerJson)) {
      throw new AccountJwtSignerDesiredStateRepository.BindingMismatchException(
          "Account JWT JWKS readback does not match the protected publication intent");
    }

    PublicationReceipt candidate = PublicationReceipt.create(intent, returnedResourceVersion);
    PublicationReceipt existing = selectReceipt(operationId, true);
    if (existing != null) {
      verifyReceiptMatches(intent, existing);
      if (!existing.equals(candidate)) {
        throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
            "Account JWT JWKS readback changed for the same operation");
      }
      return existing;
    }
    insertReceipt(candidate);
    PublicationReceipt readback = selectReceipt(operationId, true);
    if (readback == null || !readback.equals(candidate)) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account JWT JWKS receipt did not read back exactly");
    }
    return readback;
  }

  /**
   * Records a local mounted private/public correspondence result only after the exact same
   * operation has a persisted API publication receipt. This observation remains non-authorizing.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public MountObservation recordMountedCorrespondence(
      Binding binding,
      TrustFence trust,
      UUID operationId,
      String generationMarkerDigest,
      String mountedPublicDataDigest,
      String localPublicKeyFingerprint) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(operationId, "Account JWT signer operation ID is required");
    requireDigest(generationMarkerDigest, "mounted generation marker digest");
    requireDigest(mountedPublicDataDigest, "mounted public projection digest");
    requireDigest(localPublicKeyFingerprint, "mounted public-key fingerprint");

    GenerationResult result = readAndLockCurrentResult(binding, trust);
    if (!result.operationId().equals(operationId)) {
      throw new AccountJwtSignerDesiredStateRepository.StaleGenerationOperationException(
          "Mounted JWT correspondence is not for the current generation operation");
    }
    PrepublicationIntent intent = selectIntent(operationId, true);
    PublicationReceipt receipt = selectReceipt(operationId, true);
    if (intent == null || receipt == null) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Mounted JWT correspondence has no Account publication receipt");
    }
    GenerationRequest request = desiredStateRepository.readCurrentGenerationRequest(binding, trust);
    verifyIntentMatches(result, request, intent);
    verifyReceiptMatches(intent, receipt);
    if (!intent.generationMarkerDigest().equals(generationMarkerDigest)
        || !intent.publicDataDigest().equals(mountedPublicDataDigest)
        || !result.publicKeyFingerprint().equals(localPublicKeyFingerprint)) {
      throw new AccountJwtSignerDesiredStateRepository.BindingMismatchException(
          "Mounted JWT correspondence does not match Account's exact publication");
    }

    MountObservation candidate = MountObservation.create(intent, receipt);
    MountObservation existing = selectMountObservation(operationId, true);
    if (existing != null) {
      verifyMountMatches(intent, receipt, existing);
      if (!existing.equals(candidate)) {
        throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
            "Mounted JWT correspondence changed for the same operation");
      }
      return existing;
    }
    insertMountObservation(candidate, binding);
    MountObservation readback = selectMountObservation(operationId, true);
    if (readback == null || !readback.equals(candidate)) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Mounted JWT correspondence did not read back exactly");
    }
    return readback;
  }

  private GenerationResult readAndLockCurrentResult(Binding binding, TrustFence trust) {
    GenerationResult result = desiredStateRepository.readCurrentGenerationResult(binding, trust);
    GenerationRequest request = desiredStateRepository.readCurrentGenerationRequest(binding, trust);
    if (request.phase()
            != AccountJwtSignerDesiredStateRepository.GenerationPhase.GENERATION_RECORDED
        || !request.operationId().equals(result.operationId())
        || !request.operationDigest().equals(result.operationDigest())
        || !request.generationReceiptDigest().equals(result.receiptDigest())
        || !request.publicKeyFingerprint().equals(result.publicKeyFingerprint())
        || !request.publicJwkJson().equals(result.publicJwkJson())
        || !request.trustFence().equals(trust)) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account JWT publication is not bound to a complete current generation result");
    }
    return result;
  }

  private static void requireExactGeneration(
      GenerationResult expectedResult,
      GenerationResult currentResult,
      GenerationRequest expectedRequest,
      GenerationRequest currentRequest) {
    if (!expectedResult.equals(currentResult) || !expectedRequest.equals(currentRequest)) {
      throw new AccountJwtSignerDesiredStateRepository.StaleGenerationOperationException(
          "Account JWT publication candidate is not the exact current generation result");
    }
  }

  private PrepublicationIntent selectIntent(UUID operationId, boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "operation_digest, generation_request_digest, generation_receipt_digest, "
                + "desired_state_version, expected_cluster_incarnation_uid, expected_namespace_uid, "
                + "trust_binding_digest, trust_config_revision, api_binding_digest, api_config_revision, "
                + "config_map_name, config_map_uid, expected_resource_version, expected_snapshot_digest, "
                + "target_generation, target_kid, public_key_fingerprint, expected_durable_active_json, "
                + "expected_published_active_json, jwks_json, generation_marker_json, public_data_digest, "
                + "generation_marker_digest, intent_digest FROM "
                + INTENT_TABLE
                + " WHERE operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            operationId);
    return row == null ? null : decodeIntent(row);
  }

  private PublicationReceipt selectReceipt(UUID operationId, boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "intent_digest, config_map_uid, expected_resource_version, "
                + "observed_resource_version, public_data_digest, receipt_digest FROM "
                + RECEIPT_TABLE
                + " WHERE operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            operationId);
    return row == null ? null : decodeReceipt(row);
  }

  private MountObservation selectMountObservation(UUID operationId, boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT operation_id, intent_digest, publication_receipt_digest, "
                + "generation_marker_digest, public_data_digest, public_key_fingerprint, "
                + "observation_digest FROM "
                + MOUNT_TABLE
                + " WHERE operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            operationId);
    return row == null ? null : decodeMountObservation(row);
  }

  private void insertIntent(PrepublicationIntent intent) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + INTENT_TABLE
                + " (operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "operation_digest, generation_request_digest, generation_receipt_digest, "
                + "desired_state_version, expected_cluster_incarnation_uid, expected_namespace_uid, "
                + "trust_binding_digest, trust_config_revision, api_binding_digest, api_config_revision, "
                + "config_map_name, config_map_uid, expected_resource_version, expected_snapshot_digest, "
                + "target_generation, target_kid, public_key_fingerprint, expected_durable_active_json, "
                + "expected_published_active_json, jwks_json, generation_marker_json, public_data_digest, "
                + "generation_marker_digest, intent_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'jwt-jwks', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            intent.operationId(),
            intent.binding().environmentId(),
            intent.binding().clusterId(),
            intent.binding().namespace(),
            CUSTODY_MODE,
            intent.operationDigest(),
            intent.generationRequestDigest(),
            intent.generationReceiptDigest(),
            intent.desiredStateVersion(),
            UUID.fromString(intent.trustFence().expectedClusterIncarnationUid()),
            UUID.fromString(intent.trustFence().expectedNamespaceUid()),
            intent.trustFence().bindingDigest(),
            intent.trustFence().configRevision(),
            intent.apiBindingDigest(),
            intent.apiConfigRevision(),
            UUID.fromString(intent.configMapUid()),
            intent.expectedResourceVersion(),
            intent.expectedSnapshotDigest(),
            Long.parseLong(intent.targetGeneration()),
            intent.targetKid(),
            intent.publicKeyFingerprint(),
            activeJson(intent.expectedDurableActive()),
            activeJson(intent.expectedPublishedActive()),
            intent.jwksJson(),
            intent.generationMarkerJson(),
            intent.publicDataDigest(),
            intent.generationMarkerDigest(),
            intent.intentDigest());
    if (inserted != 1) {
      throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
          "Account JWT JWKS intent insert was ambiguous");
    }
  }

  private void insertReceipt(PublicationReceipt receipt) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + RECEIPT_TABLE
                + " (operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "intent_digest, config_map_uid, expected_resource_version, "
                + "observed_resource_version, public_data_digest, receipt_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            receipt.operationId(),
            receipt.binding().environmentId(),
            receipt.binding().clusterId(),
            receipt.binding().namespace(),
            receipt.binding().mode().value(),
            receipt.intentDigest(),
            UUID.fromString(receipt.configMapUid()),
            receipt.expectedResourceVersion(),
            receipt.observedResourceVersion(),
            receipt.publicDataDigest(),
            receipt.receiptDigest());
    if (inserted != 1) {
      throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
          "Account JWT JWKS receipt insert was ambiguous");
    }
  }

  private void insertMountObservation(MountObservation observation, Binding binding) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + MOUNT_TABLE
                + " (operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "intent_digest, publication_receipt_digest, "
                + "generation_marker_digest, public_data_digest, public_key_fingerprint, "
                + "observation_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            observation.operationId(),
            binding.environmentId(),
            binding.clusterId(),
            binding.namespace(),
            binding.mode().value(),
            observation.intentDigest(),
            observation.publicationReceiptDigest(),
            observation.generationMarkerDigest(),
            observation.publicDataDigest(),
            observation.publicKeyFingerprint(),
            observation.observationDigest());
    if (inserted != 1) {
      throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
          "Account mounted JWT correspondence insert was ambiguous");
    }
  }

  private PrepublicationIntent decodeIntent(Record row) {
    try {
      Binding binding =
          new Binding(
              row.get("environment_id", String.class),
              row.get("cluster_id", String.class),
              row.get("kubernetes_namespace", String.class),
              parseCustodyMode(row.get("custody_mode", String.class)));
      return new PrepublicationIntent(
          row.get("operation_id", UUID.class),
          binding,
          row.get("operation_digest", String.class),
          row.get("generation_request_digest", String.class),
          row.get("generation_receipt_digest", String.class),
          positive(row.get("desired_state_version", Long.class), "desired-state version"),
          new TrustFence(
              row.get("expected_cluster_incarnation_uid", UUID.class).toString(),
              row.get("expected_namespace_uid", UUID.class).toString(),
              row.get("trust_binding_digest", String.class),
              row.get("trust_config_revision", String.class)),
          row.get("api_binding_digest", String.class),
          row.get("api_config_revision", String.class),
          row.get("config_map_name", String.class),
          row.get("config_map_uid", UUID.class).toString(),
          row.get("expected_resource_version", String.class),
          row.get("expected_snapshot_digest", String.class),
          Long.toString(positive(row.get("target_generation", Long.class), "target generation")),
          row.get("target_kid", String.class),
          row.get("public_key_fingerprint", String.class),
          parseActive(row.get("expected_durable_active_json", String.class)),
          parseActive(row.get("expected_published_active_json", String.class)),
          row.get("jwks_json", String.class),
          row.get("generation_marker_json", String.class),
          row.get("public_data_digest", String.class),
          row.get("generation_marker_digest", String.class),
          row.get("intent_digest", String.class));
    } catch (RuntimeException ex) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account JWT JWKS intent is malformed", ex);
    }
  }

  private PublicationReceipt decodeReceipt(Record row) {
    try {
      return new PublicationReceipt(
          row.get("operation_id", UUID.class),
          new Binding(
              row.get("environment_id", String.class),
              row.get("cluster_id", String.class),
              row.get("kubernetes_namespace", String.class),
              parseCustodyMode(row.get("custody_mode", String.class))),
          row.get("intent_digest", String.class),
          row.get("config_map_uid", UUID.class).toString(),
          row.get("expected_resource_version", String.class),
          row.get("observed_resource_version", String.class),
          row.get("public_data_digest", String.class),
          row.get("receipt_digest", String.class));
    } catch (RuntimeException ex) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account JWT JWKS receipt is malformed", ex);
    }
  }

  private MountObservation decodeMountObservation(Record row) {
    try {
      return new MountObservation(
          row.get("operation_id", UUID.class),
          row.get("intent_digest", String.class),
          row.get("publication_receipt_digest", String.class),
          row.get("generation_marker_digest", String.class),
          row.get("public_data_digest", String.class),
          row.get("public_key_fingerprint", String.class),
          row.get("observation_digest", String.class));
    } catch (RuntimeException ex) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account mounted JWT correspondence is malformed", ex);
    }
  }

  private static void verifyIntentMatches(
      GenerationResult result, GenerationRequest request, PrepublicationIntent intent) {
    if (!intent.operationId().equals(result.operationId())
        || !intent.binding().equals(result.binding())
        || !intent.operationDigest().equals(result.operationDigest())
        || !intent.generationRequestDigest().equals(result.generationRequestDigest())
        || !intent.generationReceiptDigest().equals(result.receiptDigest())
        || intent.desiredStateVersion() != result.desiredStateVersion()
        || !intent.trustFence().equals(result.trustFence())
        || !intent.targetGeneration().equals(result.targetGeneration())
        || !intent.targetKid().equals(result.targetKid())
        || !intent.publicKeyFingerprint().equals(result.publicKeyFingerprint())
        || !intent.expectedDurableActive().equals(request.expectedActive())
        || !intent.expectedPublishedActive().equals(request.expectedPublishedActive())
        || !PUBLIC_JWKS_NAME.equals(intent.configMapName())
        || !PrepublicationIntent.digest(intent).equals(intent.intentDigest())) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account JWT JWKS intent no longer matches its current generation result");
    }
  }

  private static void verifyPreparedIntentMatches(
      PreparedGenerationEvidence prepared, PrepublicationIntent intent) {
    GenerationResult result = prepared.generationResult();
    PromotionOperationEvidence promotion = prepared.promotion();
    if (!intent.operationId().equals(result.operationId())
        || !intent.binding().equals(result.binding())
        || !intent.operationDigest().equals(result.operationDigest())
        || !intent.generationRequestDigest().equals(result.generationRequestDigest())
        || !intent.generationReceiptDigest().equals(result.receiptDigest())
        || intent.desiredStateVersion() != result.desiredStateVersion()
        || !intent.trustFence().equals(result.trustFence())
        || !intent.targetGeneration().equals(result.targetGeneration())
        || !intent.targetKid().equals(result.targetKid())
        || !intent.publicKeyFingerprint().equals(result.publicKeyFingerprint())
        || !intent.expectedDurableActive().equals(promotion.expectedPreviousActive())
        || !intent.expectedPublishedActive().equals(promotion.expectedPublishedActive())
        || !PUBLIC_JWKS_NAME.equals(intent.configMapName())
        || !PrepublicationIntent.digest(intent).equals(intent.intentDigest())) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Prepared Account JWKS intent does not match the exact source generation");
    }
  }

  private static void verifyReceiptMatches(
      PrepublicationIntent intent, PublicationReceipt receipt) {
    if (!receipt.operationId().equals(intent.operationId())
        || !receipt.binding().equals(intent.binding())
        || !receipt.intentDigest().equals(intent.intentDigest())
        || !receipt.configMapUid().equals(intent.configMapUid())
        || !receipt.expectedResourceVersion().equals(intent.expectedResourceVersion())
        || !receipt.publicDataDigest().equals(intent.publicDataDigest())
        || !PublicationReceipt.digest(receipt).equals(receipt.receiptDigest())) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account JWT JWKS receipt is not bound to its immutable intent");
    }
  }

  private static void verifyMountMatches(
      PrepublicationIntent intent, PublicationReceipt receipt, MountObservation observation) {
    if (!observation.operationId().equals(intent.operationId())
        || !observation.intentDigest().equals(intent.intentDigest())
        || !observation.publicationReceiptDigest().equals(receipt.receiptDigest())
        || !observation.generationMarkerDigest().equals(intent.generationMarkerDigest())
        || !observation.publicDataDigest().equals(intent.publicDataDigest())
        || !observation.publicKeyFingerprint().equals(intent.publicKeyFingerprint())
        || !MountObservation.digest(observation).equals(observation.observationDigest())) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Mounted JWT correspondence is not bound to its exact Account publication");
    }
  }

  private static void requireAccountTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account JWT JWKS persistence requires the owning Account transaction");
    }
  }

  private static void requireWritableAccountTransaction() {
    requireAccountTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Account JWT JWKS persistence transaction must be writable");
    }
  }

  private static void requireExactCurrentIdentity(
      Binding binding,
      TrustFence trust,
      GenerationResult result,
      String apiBindingDigest,
      String apiConfigRevision) {
    if (!result.binding().equals(binding)
        || !result.trustFence().equals(trust)
        || !result.trustFence().bindingDigest().equals(trust.bindingDigest())
        || !result.trustFence().configRevision().equals(trust.configRevision())) {
      throw new AccountJwtSignerDesiredStateRepository.BindingMismatchException(
          "Account JWT publication trust identity changed");
    }
    requireDigest(apiBindingDigest, "protected ConfigMap API binding digest");
    requireRevision(apiConfigRevision, "protected ConfigMap API configuration revision");
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException ex) {
      throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
          "Account JWT JWKS canonicalization is unavailable");
    }
  }

  private static AccountJwtSignerDesiredStateRepository.CustodyMode parseCustodyMode(String value) {
    for (AccountJwtSignerDesiredStateRepository.CustodyMode candidate :
        AccountJwtSignerDesiredStateRepository.CustodyMode.values()) {
      if (candidate.value().equals(value)) {
        return candidate;
      }
    }
    throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
        "JWT signer custody mode is unsupported");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static void requireDigest(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException("JWT signer " + field + " is malformed");
    }
  }

  private static void requireRevision(String value, String field) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("JWT signer " + field + " is malformed");
    }
  }

  private static void requireUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("JWT signer " + field + " is malformed", ex);
    }
  }

  private static void requireResourceVersion(String value, String field) {
    if (value == null || !RESOURCE_VERSION.matcher(value).matches()) {
      throw new IllegalArgumentException("JWT signer " + field + " is malformed");
    }
  }

  private static void requireBounded(String value, int limit, String field) {
    if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > limit) {
      throw new IllegalArgumentException("JWT signer " + field + " is missing or oversized");
    }
  }

  private static long positive(Long value, String field) {
    if (value == null || value <= 0L) {
      throw new IllegalArgumentException("JWT signer " + field + " is not positive");
    }
    return value;
  }

  private static String activeJson(Optional<ActiveSigner> active) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("present", active.isPresent());
    active.ifPresent(
        signer -> {
          value.put("generation", signer.generation());
          value.put("kid", signer.kid());
        });
    return new String(canonicalJson(value), StandardCharsets.UTF_8);
  }

  private static Optional<ActiveSigner> parseActive(String json) {
    try {
      tools.jackson.databind.JsonNode node = JSON.readTree(json);
      if (node == null || !node.isObject() || !node.has("present")) {
        throw new IllegalArgumentException();
      }
      if (node.size() == 1
          && node.get("present").isBoolean()
          && !node.get("present").booleanValue()) {
        return Optional.empty();
      }
      if (node.size() == 3
          && node.get("present").isBoolean()
          && node.get("present").booleanValue()
          && node.get("generation").isTextual()
          && node.get("kid").isTextual()) {
        return Optional.of(
            new ActiveSigner(node.get("generation").textValue(), node.get("kid").textValue()));
      }
      throw new IllegalArgumentException();
    } catch (Exception ex) {
      throw new IllegalArgumentException("JWT signer active fence is malformed", ex);
    }
  }

  /** Fixed non-secret Account intent recorded before an external ConfigMap CAS. */
  public record PrepublicationIntent(
      UUID operationId,
      Binding binding,
      String operationDigest,
      String generationRequestDigest,
      String generationReceiptDigest,
      long desiredStateVersion,
      TrustFence trustFence,
      String apiBindingDigest,
      String apiConfigRevision,
      String configMapName,
      String configMapUid,
      String expectedResourceVersion,
      String expectedSnapshotDigest,
      String targetGeneration,
      String targetKid,
      String publicKeyFingerprint,
      Optional<ActiveSigner> expectedDurableActive,
      Optional<ActiveSigner> expectedPublishedActive,
      String jwksJson,
      String generationMarkerJson,
      String publicDataDigest,
      String generationMarkerDigest,
      String intentDigest) {
    public PrepublicationIntent {
      Objects.requireNonNull(operationId, "Account JWT signer operation ID is required");
      Objects.requireNonNull(binding, "Account JWT signer binding is required");
      requireDigest(operationDigest, "operation digest");
      requireDigest(generationRequestDigest, "generation request digest");
      requireDigest(generationReceiptDigest, "generation receipt digest");
      if (desiredStateVersion <= 1L) {
        throw new IllegalArgumentException("JWT signer desired-state version is invalid");
      }
      Objects.requireNonNull(trustFence, "JWT signer trust fence is required");
      requireDigest(apiBindingDigest, "protected ConfigMap API binding digest");
      requireRevision(apiConfigRevision, "protected ConfigMap API configuration revision");
      if (!PUBLIC_JWKS_NAME.equals(configMapName)) {
        throw new IllegalArgumentException("JWT signer public ConfigMap name is not fixed");
      }
      requireUuid(configMapUid, "public ConfigMap UID");
      requireResourceVersion(expectedResourceVersion, "expected ConfigMap resourceVersion");
      requireDigest(expectedSnapshotDigest, "expected ConfigMap snapshot digest");
      if (targetGeneration == null || !targetGeneration.matches("[1-9][0-9]{0,18}")) {
        throw new IllegalArgumentException("JWT signer target generation is malformed");
      }
      if (targetKid == null || !targetKid.matches("[A-Za-z0-9_-]{1,64}")) {
        throw new IllegalArgumentException("JWT signer target kid is malformed");
      }
      requireDigest(publicKeyFingerprint, "public-key fingerprint");
      expectedDurableActive = Objects.requireNonNull(expectedDurableActive);
      expectedPublishedActive = Objects.requireNonNull(expectedPublishedActive);
      requireBounded(jwksJson, MAX_JWKS_BYTES, "public JWKS");
      requireBounded(generationMarkerJson, MAX_MARKER_BYTES, "public generation marker");
      requireDigest(publicDataDigest, "public data digest");
      requireDigest(generationMarkerDigest, "generation marker digest");
      requireDigest(intentDigest, "prepublication intent digest");
      if (!publicDataDigest.equals(
              AccountJwtJwksPublicationRepository.publicDataDigest(jwksJson, generationMarkerJson))
          || !generationMarkerDigest.equals(digestUtf8(generationMarkerJson))) {
        throw new IllegalArgumentException("JWT signer public projection digest is invalid");
      }
      if (!intentDigest.equals(
          digest(
              operationId,
              binding,
              operationDigest,
              generationRequestDigest,
              generationReceiptDigest,
              desiredStateVersion,
              trustFence,
              apiBindingDigest,
              apiConfigRevision,
              configMapName,
              configMapUid,
              expectedResourceVersion,
              expectedSnapshotDigest,
              targetGeneration,
              targetKid,
              publicKeyFingerprint,
              expectedDurableActive,
              expectedPublishedActive,
              jwksJson,
              generationMarkerJson,
              publicDataDigest,
              generationMarkerDigest))) {
        throw new IllegalArgumentException("JWT signer prepublication intent digest is invalid");
      }
    }

    public static PrepublicationIntent create(
        GenerationResult result,
        GenerationRequest request,
        String apiBindingDigest,
        String apiConfigRevision,
        String configMapUid,
        String expectedResourceVersion,
        String expectedSnapshotDigest,
        String jwksJson,
        String generationMarkerJson) {
      return create(
          result.operationId(),
          result.binding(),
          result.operationDigest(),
          result.generationRequestDigest(),
          result.receiptDigest(),
          result.desiredStateVersion(),
          result.trustFence(),
          apiBindingDigest,
          apiConfigRevision,
          PUBLIC_JWKS_NAME,
          configMapUid,
          expectedResourceVersion,
          expectedSnapshotDigest,
          result.targetGeneration(),
          result.targetKid(),
          result.publicKeyFingerprint(),
          request.expectedActive(),
          request.expectedPublishedActive(),
          jwksJson,
          generationMarkerJson,
          AccountJwtJwksPublicationRepository.publicDataDigest(jwksJson, generationMarkerJson),
          digestUtf8(generationMarkerJson));
    }

    public static PrepublicationIntent create(
        UUID operationId,
        Binding binding,
        String operationDigest,
        String generationRequestDigest,
        String generationReceiptDigest,
        long desiredStateVersion,
        TrustFence trustFence,
        String apiBindingDigest,
        String apiConfigRevision,
        String configMapName,
        String configMapUid,
        String expectedResourceVersion,
        String expectedSnapshotDigest,
        String targetGeneration,
        String targetKid,
        String publicKeyFingerprint,
        Optional<ActiveSigner> expectedDurableActive,
        Optional<ActiveSigner> expectedPublishedActive,
        String jwksJson,
        String generationMarkerJson,
        String publicDataDigest,
        String generationMarkerDigest) {
      String intentDigest =
          digest(
              operationId,
              binding,
              operationDigest,
              generationRequestDigest,
              generationReceiptDigest,
              desiredStateVersion,
              trustFence,
              apiBindingDigest,
              apiConfigRevision,
              configMapName,
              configMapUid,
              expectedResourceVersion,
              expectedSnapshotDigest,
              targetGeneration,
              targetKid,
              publicKeyFingerprint,
              expectedDurableActive,
              expectedPublishedActive,
              jwksJson,
              generationMarkerJson,
              publicDataDigest,
              generationMarkerDigest);
      return new PrepublicationIntent(
          operationId,
          binding,
          operationDigest,
          generationRequestDigest,
          generationReceiptDigest,
          desiredStateVersion,
          trustFence,
          apiBindingDigest,
          apiConfigRevision,
          configMapName,
          configMapUid,
          expectedResourceVersion,
          expectedSnapshotDigest,
          targetGeneration,
          targetKid,
          publicKeyFingerprint,
          expectedDurableActive,
          expectedPublishedActive,
          jwksJson,
          generationMarkerJson,
          publicDataDigest,
          generationMarkerDigest,
          intentDigest);
    }

    static String digest(PrepublicationIntent value) {
      return digest(
          value.operationId(),
          value.binding(),
          value.operationDigest(),
          value.generationRequestDigest(),
          value.generationReceiptDigest(),
          value.desiredStateVersion(),
          value.trustFence(),
          value.apiBindingDigest(),
          value.apiConfigRevision(),
          value.configMapName(),
          value.configMapUid(),
          value.expectedResourceVersion(),
          value.expectedSnapshotDigest(),
          value.targetGeneration(),
          value.targetKid(),
          value.publicKeyFingerprint(),
          value.expectedDurableActive(),
          value.expectedPublishedActive(),
          value.jwksJson(),
          value.generationMarkerJson(),
          value.publicDataDigest(),
          value.generationMarkerDigest());
    }

    private static String digest(
        UUID operationId,
        Binding binding,
        String operationDigest,
        String generationRequestDigest,
        String generationReceiptDigest,
        long desiredStateVersion,
        TrustFence trustFence,
        String apiBindingDigest,
        String apiConfigRevision,
        String configMapName,
        String configMapUid,
        String expectedResourceVersion,
        String expectedSnapshotDigest,
        String targetGeneration,
        String targetKid,
        String publicKeyFingerprint,
        Optional<ActiveSigner> expectedDurableActive,
        Optional<ActiveSigner> expectedPublishedActive,
        String jwksJson,
        String generationMarkerJson,
        String publicDataDigest,
        String generationMarkerDigest) {
      Map<String, Object> preimage = new LinkedHashMap<>();
      preimage.put("digestVersion", "account-jwt-jwks-prepublication-intent/v1");
      preimage.put("operationId", operationId.toString());
      preimage.put("environmentId", binding.environmentId());
      preimage.put("clusterId", binding.clusterId());
      preimage.put("namespace", binding.namespace());
      preimage.put("custodyMode", CUSTODY_MODE);
      preimage.put("operationDigest", operationDigest);
      preimage.put("generationRequestDigest", generationRequestDigest);
      preimage.put("generationReceiptDigest", generationReceiptDigest);
      preimage.put("desiredStateVersion", desiredStateVersion);
      preimage.put("expectedClusterIncarnationUid", trustFence.expectedClusterIncarnationUid());
      preimage.put("expectedNamespaceUid", trustFence.expectedNamespaceUid());
      preimage.put("trustBindingDigest", trustFence.bindingDigest());
      preimage.put("trustConfigRevision", trustFence.configRevision());
      preimage.put("apiBindingDigest", apiBindingDigest);
      preimage.put("apiConfigRevision", apiConfigRevision);
      preimage.put("configMapName", configMapName);
      preimage.put("configMapUid", configMapUid);
      preimage.put("expectedResourceVersion", expectedResourceVersion);
      preimage.put("expectedSnapshotDigest", expectedSnapshotDigest);
      preimage.put("targetGeneration", targetGeneration);
      preimage.put("targetKid", targetKid);
      preimage.put("publicKeyFingerprint", publicKeyFingerprint);
      preimage.put(
          "expectedDurableActive", expectedDurableActive.map(ActiveSigner::generation).orElse(""));
      preimage.put(
          "expectedDurableActiveKid", expectedDurableActive.map(ActiveSigner::kid).orElse(""));
      preimage.put(
          "expectedPublishedActive",
          expectedPublishedActive.map(ActiveSigner::generation).orElse(""));
      preimage.put(
          "expectedPublishedActiveKid", expectedPublishedActive.map(ActiveSigner::kid).orElse(""));
      preimage.put("jwksJson", jwksJson);
      preimage.put("generationMarkerJson", generationMarkerJson);
      preimage.put("publicDataDigest", publicDataDigest);
      preimage.put("generationMarkerDigest", generationMarkerDigest);
      return sha256(canonicalJson(preimage));
    }
  }

  /** Exact ConfigMap readback; it proves publication only, never activation/readiness. */
  public record PublicationReceipt(
      UUID operationId,
      Binding binding,
      String intentDigest,
      String configMapUid,
      String expectedResourceVersion,
      String observedResourceVersion,
      String publicDataDigest,
      String receiptDigest) {
    public PublicationReceipt {
      Objects.requireNonNull(operationId, "Account JWT signer operation ID is required");
      Objects.requireNonNull(binding, "Account JWT signer binding is required");
      requireDigest(intentDigest, "prepublication intent digest");
      requireUuid(configMapUid, "public ConfigMap UID");
      requireResourceVersion(expectedResourceVersion, "expected ConfigMap resourceVersion");
      requireResourceVersion(observedResourceVersion, "observed ConfigMap resourceVersion");
      requireDigest(publicDataDigest, "published public data digest");
      requireDigest(receiptDigest, "publication receipt digest");
      if (expectedResourceVersion.equals(observedResourceVersion)) {
        throw new IllegalArgumentException("Account JWT ConfigMap resourceVersion did not advance");
      }
      if (!receiptDigest.equals(
          digest(
              operationId,
              binding,
              intentDigest,
              configMapUid,
              expectedResourceVersion,
              observedResourceVersion,
              publicDataDigest))) {
        throw new IllegalArgumentException("Account JWT publication receipt digest is invalid");
      }
    }

    public static PublicationReceipt create(
        PrepublicationIntent intent, String observedResourceVersion) {
      String receiptDigest =
          digest(
              intent.operationId(),
              intent.binding(),
              intent.intentDigest(),
              intent.configMapUid(),
              intent.expectedResourceVersion(),
              observedResourceVersion,
              intent.publicDataDigest());
      return new PublicationReceipt(
          intent.operationId(),
          intent.binding(),
          intent.intentDigest(),
          intent.configMapUid(),
          intent.expectedResourceVersion(),
          observedResourceVersion,
          intent.publicDataDigest(),
          receiptDigest);
    }

    static String digest(PublicationReceipt value) {
      return digest(
          value.operationId(),
          value.binding(),
          value.intentDigest(),
          value.configMapUid(),
          value.expectedResourceVersion(),
          value.observedResourceVersion(),
          value.publicDataDigest());
    }

    private static String digest(
        UUID operationId,
        Binding binding,
        String intentDigest,
        String configMapUid,
        String expectedResourceVersion,
        String observedResourceVersion,
        String publicDataDigest) {
      Map<String, Object> preimage = new LinkedHashMap<>();
      preimage.put("digestVersion", "account-jwt-jwks-publication-receipt/v1");
      preimage.put("operationId", operationId.toString());
      preimage.put("environmentId", binding.environmentId());
      preimage.put("clusterId", binding.clusterId());
      preimage.put("namespace", binding.namespace());
      preimage.put("custodyMode", CUSTODY_MODE);
      preimage.put("intentDigest", intentDigest);
      preimage.put("configMapUid", configMapUid);
      preimage.put("expectedResourceVersion", expectedResourceVersion);
      preimage.put("observedResourceVersion", observedResourceVersion);
      preimage.put("publicDataDigest", publicDataDigest);
      return sha256(canonicalJson(preimage));
    }
  }

  /** Non-secret Account-local observation of the published private/public mounted projection. */
  public record MountObservation(
      UUID operationId,
      String intentDigest,
      String publicationReceiptDigest,
      String generationMarkerDigest,
      String publicDataDigest,
      String publicKeyFingerprint,
      String observationDigest) {
    public MountObservation {
      Objects.requireNonNull(operationId, "Account JWT signer operation ID is required");
      requireDigest(intentDigest, "prepublication intent digest");
      requireDigest(publicationReceiptDigest, "publication receipt digest");
      requireDigest(generationMarkerDigest, "generation marker digest");
      requireDigest(publicDataDigest, "mounted public data digest");
      requireDigest(publicKeyFingerprint, "mounted public-key fingerprint");
      requireDigest(observationDigest, "mounted observation digest");
      if (!observationDigest.equals(
          digest(
              operationId,
              intentDigest,
              publicationReceiptDigest,
              generationMarkerDigest,
              publicDataDigest,
              publicKeyFingerprint))) {
        throw new IllegalArgumentException("Mounted JWT observation digest is invalid");
      }
    }

    public static MountObservation create(PrepublicationIntent intent, PublicationReceipt receipt) {
      String observationDigest =
          digest(
              intent.operationId(),
              intent.intentDigest(),
              receipt.receiptDigest(),
              intent.generationMarkerDigest(),
              intent.publicDataDigest(),
              intent.publicKeyFingerprint());
      return new MountObservation(
          intent.operationId(),
          intent.intentDigest(),
          receipt.receiptDigest(),
          intent.generationMarkerDigest(),
          intent.publicDataDigest(),
          intent.publicKeyFingerprint(),
          observationDigest);
    }

    static String digest(MountObservation value) {
      return digest(
          value.operationId(),
          value.intentDigest(),
          value.publicationReceiptDigest(),
          value.generationMarkerDigest(),
          value.publicDataDigest(),
          value.publicKeyFingerprint());
    }

    private static String digest(
        UUID operationId,
        String intentDigest,
        String publicationReceiptDigest,
        String generationMarkerDigest,
        String publicDataDigest,
        String publicKeyFingerprint) {
      Map<String, Object> preimage = new LinkedHashMap<>();
      preimage.put("digestVersion", "account-jwt-jwks-mounted-correspondence/v1");
      preimage.put("operationId", operationId.toString());
      preimage.put("intentDigest", intentDigest);
      preimage.put("publicationReceiptDigest", publicationReceiptDigest);
      preimage.put("generationMarkerDigest", generationMarkerDigest);
      preimage.put("publicDataDigest", publicDataDigest);
      preimage.put("publicKeyFingerprint", publicKeyFingerprint);
      return sha256(canonicalJson(preimage));
    }
  }

  public record PublicationEvidence(
      PrepublicationIntent intent,
      Optional<PublicationReceipt> receipt,
      Optional<MountObservation> mountedCorrespondence) {
    public PublicationEvidence {
      Objects.requireNonNull(intent);
      receipt = Objects.requireNonNull(receipt);
      mountedCorrespondence = Objects.requireNonNull(mountedCorrespondence);
    }
  }

  public record PreparedPublicationEvidence(
      PrepublicationIntent intent,
      PublicationReceipt receipt,
      MountObservation mountedCorrespondence) {
    public PreparedPublicationEvidence {
      Objects.requireNonNull(intent);
      Objects.requireNonNull(receipt);
      Objects.requireNonNull(mountedCorrespondence);
      verifyReceiptMatches(intent, receipt);
      verifyMountMatches(intent, receipt, mountedCorrespondence);
    }
  }

  /** Canonical digest of all observed ConfigMap data used as the pre-CAS compare fence. */
  public static String snapshotDigest(Map<String, String> data) {
    Objects.requireNonNull(data, "ConfigMap data is required");
    if (data.size() > 128) {
      throw new IllegalArgumentException("JWT public ConfigMap data has too many entries");
    }
    long totalBytes = 0L;
    Map<String, Object> bounded = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : data.entrySet()) {
      if (entry.getKey() == null
          || entry.getKey().isBlank()
          || entry.getKey().length() > 253
          || entry.getValue() == null) {
        throw new IllegalArgumentException("JWT public ConfigMap data is malformed");
      }
      totalBytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
      totalBytes += entry.getValue().getBytes(StandardCharsets.UTF_8).length;
      if (totalBytes > 1024L * 1024L) {
        throw new IllegalArgumentException("JWT public ConfigMap data is oversized");
      }
      bounded.put(entry.getKey(), entry.getValue());
    }
    return sha256(canonicalJson(bounded));
  }

  public static String publicDataDigest(String jwksJson, String generationMarkerJson) {
    requireBounded(jwksJson, MAX_JWKS_BYTES, "public JWKS");
    requireBounded(generationMarkerJson, MAX_MARKER_BYTES, "public generation marker");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put(JWKS_DATA_KEY, jwksJson);
    data.put(GENERATION_MARKER_DATA_KEY, generationMarkerJson);
    return sha256(canonicalJson(data));
  }

  private static String digestUtf8(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
