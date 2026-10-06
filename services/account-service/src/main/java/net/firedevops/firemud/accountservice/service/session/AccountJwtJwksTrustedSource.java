package net.firedevops.firemud.accountservice.service.session;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PrepublicationIntent;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PublicationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PublicationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CommittedSignerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.DesiredState;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.BindingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Account-owned authenticated read adapter for the current public JWKS publication.
 *
 * <p>The cache pin is derived from Account's root-protected Kubernetes API binding and exact
 * Account-owned publication evidence. The first receipt-backed PREPUBLISHED key can be served to
 * readiness probes before a signer is committed, but is never represented as active authority.
 * During ordinary prepublication of a next key, the prior committed key remains the only active
 * signer while the exact new public set may be served for validation overlap. PREPARED and
 * unexplained resource changes deny. This adapter exposes only public JWKS bytes and grants no
 * signing, registry, readiness, or admission authority.
 */
public final class AccountJwtJwksTrustedSource
    implements AccountPublicJwksCache.TrustedPublicJwksSource {
  private static final int MAX_JWKS_BYTES = 256 * 1024;
  private static final int MAX_MARKER_BYTES = 16 * 1024;
  private static final String ACCOUNT_SERVICE_USERNAME_PREFIX = "system:serviceaccount:";
  private static final String ACCOUNT_SERVICE_NAME = "account-service";
  private static final int MAX_KEYS = 64;
  private static final java.util.Set<String> JWKS_FIELDS = java.util.Set.of("keys");
  private static final java.util.Set<String> JWK_FIELDS =
      java.util.Set.of("kty", "use", "alg", "kid", "key_ops", "n", "e");
  private static final JsonMapper STRICT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final AccountJwtJwksConfigMapClient configMapClient;
  private final AccountJwtSignerMaterializerTrustBinding materializerTrustBinding;
  private final AccountJwtSignerDesiredStateRepository desiredStateRepository;
  private final AccountJwtJwksPublicationRepository publicationRepository;
  private final TransactionTemplate accountTransaction;
  private final Object pinMonitor = new Object();

  private volatile TrustedPin trustedPin;

  /** Constructs an inactive adapter over Account's real protected API and owner-recorded state. */
  public AccountJwtJwksTrustedSource(
      AccountJwtJwksConfigMapClient configMapClient,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtJwksPublicationRepository publicationRepository,
      PlatformTransactionManager transactionManager) {
    this.configMapClient =
        Objects.requireNonNull(configMapClient, "Account ConfigMap client is required");
    this.materializerTrustBinding =
        Objects.requireNonNull(materializerTrustBinding, "Protected Account trust is required");
    this.desiredStateRepository =
        Objects.requireNonNull(desiredStateRepository, "Account signer repository is required");
    this.publicationRepository =
        Objects.requireNonNull(publicationRepository, "Account publication repository is required");
    this.accountTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
    this.accountTransaction.setReadOnly(false);
  }

  /**
   * Establishes the immutable cache pin from a coherent, protected Account read. A cache owner
   * obtains its expected source identity here; it is not accepted as caller-supplied evidence.
   */
  public SourceIdentity sourceIdentity() {
    TrustedPin current = trustedPin;
    if (current != null) {
      return current.sourceIdentity();
    }
    synchronized (pinMonitor) {
      current = trustedPin;
      if (current == null) {
        VerifiedSource verified = readVerifiedSource();
        current = verified.pin();
        trustedPin = current;
      }
    }
    return current.sourceIdentity();
  }

  /**
   * Derives one key's RFC 7638 fingerprint from a snapshot already loaded through this trusted
   * source. It compares the immutable pin and grants no signer, registry, or token authority.
   */
  static Optional<String> publicKeyFingerprint(
      PublicJwksSnapshot snapshot, SourceIdentity expectedSourceIdentity, String kid) {
    if (snapshot == null
        || expectedSourceIdentity == null
        || !expectedSourceIdentity.equals(snapshot.sourceIdentity())
        || kid == null
        || !kid.matches("[A-Za-z0-9_-]{1,64}")) {
      throw rejected();
    }
    Map<String, String> fingerprints =
        publicKeyFingerprints(new String(snapshot.jwksBytes(), StandardCharsets.UTF_8));
    return Optional.ofNullable(fingerprints.get(kid));
  }

  /**
   * Loads one exact public JWKS snapshot after checking Account trust and receipt readback twice.
   */
  @Override
  public PublicJwksSnapshot load() {
    TrustedPin expected = trustedPin;
    if (expected == null) {
      throw rejected();
    }
    VerifiedSource verified = readVerifiedSource();
    if (!expected.equals(verified.pin())) {
      throw rejected();
    }
    return new PublicJwksSnapshot(expected.sourceIdentity(), verified.jwksBytes());
  }

  private VerifiedSource readVerifiedSource() {
    Binding materializerBefore = currentMaterializerBinding();
    BindingIdentity apiBefore = currentApiIdentity();
    requireBindingsAgree(materializerBefore, apiBefore);
    TrustedPin alreadyPinned = trustedPin;
    if (alreadyPinned != null
        && (!alreadyPinned.materializerBinding().equals(materializerBefore)
            || !alreadyPinned.apiIdentity().equals(apiBefore))) {
      throw rejected();
    }

    OwnerProjection ownerBefore = currentOwnerProjection(materializerBefore, apiBefore);
    SourceIdentity pinnedIdentity =
        sourceIdentity(materializerBefore, apiBefore, ownerBefore.projection().configMapUid());
    if (alreadyPinned != null && !alreadyPinned.sourceIdentity().equals(pinnedIdentity)) {
      throw rejected();
    }

    ConfigMapSnapshot live;
    try {
      live = configMapClient.observe();
    } catch (AccountJwtJwksConfigMapClient.ResourceMissingException unavailable) {
      requireUnchangedProtectedTrust(materializerBefore, apiBefore);
      throw unavailable();
    } catch (AccountJwtJwksConfigMapClient.ApiFailureException failure) {
      requireUnchangedProtectedTrust(materializerBefore, apiBefore);
      if (isTemporaryApiFailure(failure.statusCode())) {
        throw unavailable();
      }
      throw rejected();
    } catch (AccountJwtJwksConfigMapClient.BindingRejectedException trustRejected) {
      throw rejected();
    } catch (RuntimeException unexpected) {
      throw rejected();
    }

    Binding materializerAfter = currentMaterializerBinding();
    BindingIdentity apiAfter = currentApiIdentity();
    if (!materializerBefore.equals(materializerAfter) || !apiBefore.equals(apiAfter)) {
      throw rejected();
    }

    OwnerProjection ownerAfter = currentOwnerProjection(materializerAfter, apiAfter);
    if (!ownerBefore.equals(ownerAfter)) {
      // A concurrent Account lifecycle/publication change is not a coherent source snapshot.
      throw unavailable();
    }
    requireExactReadback(ownerAfter.projection(), live);

    byte[] jwks =
        live.data()
            .get(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY)
            .getBytes(StandardCharsets.UTF_8);
    if (jwks.length == 0 || jwks.length > MAX_JWKS_BYTES) {
      throw rejected();
    }
    TrustedPin pin = new TrustedPin(materializerBefore, apiBefore, pinnedIdentity);
    return new VerifiedSource(pin, jwks);
  }

  private Binding currentMaterializerBinding() {
    return materializerTrustBinding.current().orElseThrow(AccountJwtJwksTrustedSource::rejected);
  }

  private BindingIdentity currentApiIdentity() {
    try {
      return Objects.requireNonNull(configMapClient.identity());
    } catch (RuntimeException rejectedBinding) {
      throw rejected();
    }
  }

  private OwnerProjection currentOwnerProjection(Binding binding, BindingIdentity api) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw rejected();
    }
    try {
      OwnerProjection result =
          accountTransaction.execute(
              status -> {
                DesiredState enrolled =
                    desiredStateRepository
                        .readEnrollmentState(binding.accountBinding())
                        .orElseThrow(AccountJwtJwksTrustedSource::unavailable);
                if (enrolled.durableActive().isEmpty() && enrolled.publishedActive().isEmpty()) {
                  return initialPrepublicationProjection(enrolled, binding, api);
                }
                CommittedSignerEvidence committed =
                    desiredStateRepository
                        .readCurrentCommittedSigner(
                            binding.accountBinding(),
                            trustFence(binding),
                            api.bindingDigest(),
                            api.configRevision())
                        .orElseThrow(AccountJwtJwksTrustedSource::unavailable);
                ActiveSigner currentActive =
                    new ActiveSigner(
                        committed.promotion().targetGeneration(),
                        committed.promotion().targetKid());
                PublishedProjection projection = committedProjection(committed);
                if (committed.desiredState().generationOperationId().isEmpty()) {
                  return new OwnerProjection(committed, projection);
                }

                var request =
                    desiredStateRepository.readCurrentGenerationRequest(
                        binding.accountBinding(), trustFence(binding));
                if (request.phase()
                    != AccountJwtSignerDesiredStateRepository.GenerationPhase.GENERATION_RECORDED) {
                  return new OwnerProjection(committed, projection);
                }
                Optional<PublicationEvidence> currentPublication =
                    publicationRepository.readCurrentPublication(
                        binding.accountBinding(), trustFence(binding));
                if (currentPublication.isEmpty()
                    || currentPublication.orElseThrow().receipt().isEmpty()) {
                  return new OwnerProjection(committed, projection);
                }
                PublicationEvidence evidence = currentPublication.orElseThrow();
                requirePublicationBinding(evidence, binding, api);
                PrepublicationIntent intent = evidence.intent();
                var receipt = evidence.receipt().orElseThrow();
                if (!intent.operationId().equals(request.operationId())
                    || !intent.operationDigest().equals(request.operationDigest())
                    || !intent.generationRequestDigest().equals(request.generationRequestDigest())
                    || !intent.generationReceiptDigest().equals(request.generationReceiptDigest())
                    || !intent.targetGeneration().equals(request.targetGeneration())
                    || !intent.targetKid().equals(request.targetKid())
                    || !intent.publicKeyFingerprint().equals(request.publicKeyFingerprint())
                    || !intent.expectedDurableActive().equals(Optional.of(currentActive))
                    || !intent.expectedPublishedActive().equals(Optional.of(currentActive))
                    || !intent.configMapUid().equals(committed.promotion().publicConfigMapUid())
                    || !receipt.configMapUid().equals(committed.promotion().publicConfigMapUid())
                    || !intent.apiBindingDigest().equals(api.bindingDigest())
                    || !intent.apiConfigRevision().equals(api.configRevision())) {
                  throw rejected();
                }
                requireRetainedPublicKeySet(committed, intent);
                return new OwnerProjection(
                    committed,
                    new PublishedProjection(
                        intent.configMapUid(),
                        receipt.observedResourceVersion(),
                        intent.jwksJson(),
                        intent.generationMarkerJson(),
                        receipt.publicDataDigest()));
              });
      if (result == null) {
        throw unavailable();
      }
      return result;
    } catch (RuntimeException ownerStateUnavailable) {
      if (ownerStateUnavailable instanceof SourceUnavailableException unavailable) {
        throw unavailable;
      }
      if (ownerStateUnavailable instanceof IllegalStateException
          && ownerStateUnavailable.getMessage() != null
          && ownerStateUnavailable.getMessage().contains("Account trusted public JWKS source")) {
        throw ownerStateUnavailable;
      }
      throw rejected();
    }
  }

  /**
   * The first exact Account-receipted PREPUBLISHED set is visible to validator probes before any
   * signer is committed. It is never represented as an active signer and remains unavailable during
   * PREPARED or after any active fence appears.
   */
  private OwnerProjection initialPrepublicationProjection(
      DesiredState enrolled, Binding binding, BindingIdentity api) {
    if (!enrolled.binding().equals(binding.accountBinding())
        || enrolled.preparedOperationId().isPresent()
        || enrolled.generationOperationId().isEmpty()
        || enrolled.enrollmentIdentity().isEmpty()) {
      throw rejected();
    }
    EnrollmentIdentity enrollment = enrolled.enrollmentIdentity().orElseThrow();
    if (!enrollment.expectedClusterIncarnationUid().equals(binding.expectedClusterIncarnationUid())
        || !enrollment.expectedNamespaceUid().equals(binding.expectedNamespaceUid())
        || !enrollment.materializerTrustBindingDigest().equals(binding.bindingDigest())
        || !enrollment.materializerTrustConfigRevision().equals(binding.configRevision())
        || !enrollment.apiBindingDigest().equals(api.bindingDigest())
        || !enrollment.apiConfigRevision().equals(api.configRevision())) {
      throw rejected();
    }

    GenerationRequest request =
        desiredStateRepository.readCurrentGenerationRequest(
            binding.accountBinding(), trustFence(binding));
    GenerationResult result =
        desiredStateRepository.readCurrentGenerationResult(
            binding.accountBinding(), trustFence(binding));
    if (request.phase()
            != AccountJwtSignerDesiredStateRepository.GenerationPhase.GENERATION_RECORDED
        || !request.expectedActive().isEmpty()
        || !request.expectedPublishedActive().isEmpty()
        || !enrolled.generationOperationId().equals(Optional.of(request.operationId()))
        || !request.binding().equals(binding.accountBinding())
        || !request.trustFence().equals(trustFence(binding))
        || !result.binding().equals(binding.accountBinding())
        || !result.trustFence().equals(trustFence(binding))
        || request.desiredStateVersion() != result.desiredStateVersion()
        || !request.operationId().equals(result.operationId())
        || !request.operationDigest().equals(result.operationDigest())
        || !request.generationRequestDigest().equals(result.generationRequestDigest())
        || !request.targetGeneration().equals(result.targetGeneration())
        || !request.targetKid().equals(result.targetKid())
        || !request.publicKeyFingerprint().equals(result.publicKeyFingerprint())
        || !request.publicJwkJson().equals(result.publicJwkJson())
        || !"RS256".equals(result.targetAlgorithm())) {
      throw rejected();
    }

    PublicationEvidence evidence =
        publicationRepository
            .readCurrentPublication(binding.accountBinding(), trustFence(binding))
            .orElseThrow(AccountJwtJwksTrustedSource::unavailable);
    requirePublicationBinding(evidence, binding, api);
    PrepublicationIntent intent = evidence.intent();
    var receipt = evidence.receipt().orElseThrow(AccountJwtJwksTrustedSource::unavailable);
    if (!intent.operationId().equals(result.operationId())
        || !intent.operationDigest().equals(result.operationDigest())
        || !intent.generationRequestDigest().equals(result.generationRequestDigest())
        || !intent.generationReceiptDigest().equals(result.receiptDigest())
        || intent.desiredStateVersion() != result.desiredStateVersion()
        || !intent.expectedDurableActive().isEmpty()
        || !intent.expectedPublishedActive().isEmpty()
        || !intent.configMapUid().equals(enrollment.publicConfigMapUid())
        || !receipt.configMapUid().equals(enrollment.publicConfigMapUid())
        || !intent.apiBindingDigest().equals(enrollment.apiBindingDigest())
        || !intent.apiConfigRevision().equals(enrollment.apiConfigRevision())
        || !intent.targetGeneration().equals(result.targetGeneration())
        || !intent.targetKid().equals(result.targetKid())
        || !intent.publicKeyFingerprint().equals(result.publicKeyFingerprint())) {
      throw rejected();
    }
    Map<String, String> publicKeys = publicKeyFingerprints(intent.jwksJson());
    if (publicKeys.size() != 1
        || !result.publicKeyFingerprint().equals(publicKeys.get(result.targetKid()))) {
      throw rejected();
    }
    requireInitialPrepublicationMarker(intent, result, binding, api);
    return new OwnerProjection(
        null,
        new PublishedProjection(
            intent.configMapUid(),
            receipt.observedResourceVersion(),
            intent.jwksJson(),
            intent.generationMarkerJson(),
            receipt.publicDataDigest()));
  }

  private static void requireInitialPrepublicationMarker(
      PrepublicationIntent intent, GenerationResult result, Binding binding, BindingIdentity api) {
    try {
      JsonNode marker = STRICT_JSON.readTree(intent.generationMarkerJson());
      JsonNode markerBinding = marker == null ? null : marker.get("binding");
      JsonNode publicConfigMap = marker == null ? null : marker.get("publicConfigMap");
      JsonNode pending = marker == null ? null : marker.get("pending");
      JsonNode durable = marker == null ? null : marker.get("expectedDurableActive");
      JsonNode published = marker == null ? null : marker.get("expectedPublishedActive");
      if (marker == null
          || !marker.isObject()
          || !hasExactFields(
              marker,
              java.util.Set.of(
                  "schemaVersion",
                  "phase",
                  "operationId",
                  "operationDigest",
                  "generationRequestDigest",
                  "generationReceiptDigest",
                  "binding",
                  "publicConfigMap",
                  "expectedDurableActive",
                  "expectedPublishedActive",
                  "pending"))
          || !marker.get("schemaVersion").isIntegralNumber()
          || marker.get("schemaVersion").intValue() != 1
          || !"PREPUBLISHED".equals(requiredText(marker, "phase"))
          || !intent.operationId().toString().equals(requiredText(marker, "operationId"))
          || !intent.operationDigest().equals(requiredText(marker, "operationDigest"))
          || !intent
              .generationRequestDigest()
              .equals(requiredText(marker, "generationRequestDigest"))
          || !intent
              .generationReceiptDigest()
              .equals(requiredText(marker, "generationReceiptDigest"))
          || !hasExactFields(
              markerBinding,
              java.util.Set.of(
                  "environmentId",
                  "clusterId",
                  "namespace",
                  "expectedClusterIncarnationUid",
                  "expectedNamespaceUid",
                  "trustBindingDigest",
                  "trustConfigRevision",
                  "apiBindingDigest",
                  "apiConfigRevision"))
          || !binding.environmentId().equals(requiredText(markerBinding, "environmentId"))
          || !binding.clusterId().equals(requiredText(markerBinding, "clusterId"))
          || !binding.namespace().equals(requiredText(markerBinding, "namespace"))
          || !binding
              .expectedClusterIncarnationUid()
              .equals(requiredText(markerBinding, "expectedClusterIncarnationUid"))
          || !binding
              .expectedNamespaceUid()
              .equals(requiredText(markerBinding, "expectedNamespaceUid"))
          || !binding.bindingDigest().equals(requiredText(markerBinding, "trustBindingDigest"))
          || !binding.configRevision().equals(requiredText(markerBinding, "trustConfigRevision"))
          || !api.bindingDigest().equals(requiredText(markerBinding, "apiBindingDigest"))
          || !api.configRevision().equals(requiredText(markerBinding, "apiConfigRevision"))
          || !hasExactFields(publicConfigMap, java.util.Set.of("name"))
          || !AccountJwtJwksConfigMapClient.CONFIG_MAP_NAME.equals(
              requiredText(publicConfigMap, "name"))
          || !hasExactFields(
              pending, java.util.Set.of("generation", "kid", "algorithm", "publicKeyFingerprint"))
          || !result.targetGeneration().equals(requiredText(pending, "generation"))
          || !result.targetKid().equals(requiredText(pending, "kid"))
          || !"RS256".equals(requiredText(pending, "algorithm"))
          || !result.publicKeyFingerprint().equals(requiredText(pending, "publicKeyFingerprint"))
          || !inactiveMarker(durable)
          || !inactiveMarker(published)) {
        throw rejected();
      }
    } catch (RuntimeException malformed) {
      throw rejected();
    }
  }

  private static boolean inactiveMarker(JsonNode marker) {
    return hasExactFields(marker, java.util.Set.of("present"))
        && marker.get("present").isBoolean()
        && !marker.get("present").booleanValue();
  }

  private static void requireBindingsAgree(Binding materializer, BindingIdentity api) {
    if (!materializer.environmentId().equals(api.environmentId())
        || !materializer.clusterId().equals(api.clusterId())
        || !materializer.namespace().equals(api.namespace())
        || !materializer.expectedClusterIncarnationUid().equals(api.expectedClusterIncarnationUid())
        || !materializer.expectedNamespaceUid().equals(api.expectedNamespaceUid())
        || api.bindingDigest() == null
        || api.configRevision() == null
        || api.expectedApiUsername() == null
        || !api.expectedApiUsername()
            .equals(
                ACCOUNT_SERVICE_USERNAME_PREFIX
                    + materializer.namespace()
                    + ":"
                    + ACCOUNT_SERVICE_NAME)) {
      throw rejected();
    }
    URI endpoint;
    try {
      endpoint = URI.create(api.apiServerUrl());
    } catch (RuntimeException malformedEndpoint) {
      throw rejected();
    }
    if (!"https".equalsIgnoreCase(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getPort() <= 0
        || endpoint.getRawUserInfo() != null
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null
        || (endpoint.getRawPath() != null
            && !endpoint.getRawPath().isEmpty()
            && !"/".equals(endpoint.getRawPath()))
        || api.tlsServerName() == null
        || !endpoint.getHost().equalsIgnoreCase(api.tlsServerName())
        || api.servingCaSha256() == null) {
      throw rejected();
    }
  }

  private static void requirePublicationBinding(
      PublicationEvidence evidence, Binding materializer, BindingIdentity api) {
    PrepublicationIntent intent = evidence.intent();
    if (!intent.binding().equals(materializer.accountBinding())
        || !intent.trustFence().equals(trustFence(materializer))
        || !AccountJwtJwksConfigMapClient.CONFIG_MAP_NAME.equals(intent.configMapName())
        || !intent.apiBindingDigest().equals(api.bindingDigest())
        || !intent.apiConfigRevision().equals(api.configRevision())
        || !AccountJwtJwksPublicationRepository.publicDataDigest(
                intent.jwksJson(), intent.generationMarkerJson())
            .equals(intent.publicDataDigest())) {
      throw rejected();
    }
    PublicationReceipt receipt = evidence.receipt().orElse(null);
    if (receipt != null
        && (!receipt.operationId().equals(intent.operationId())
            || !receipt.binding().equals(materializer.accountBinding())
            || !receipt.intentDigest().equals(intent.intentDigest())
            || !receipt.configMapUid().equals(intent.configMapUid())
            || !receipt.publicDataDigest().equals(intent.publicDataDigest())
            || !receipt.expectedResourceVersion().equals(intent.expectedResourceVersion()))) {
      throw rejected();
    }
  }

  private static PublishedProjection committedProjection(CommittedSignerEvidence committed) {
    PromotionOperationEvidence promotion = committed.promotion();
    var receipt = committed.publicReceipt();
    String dataDigest =
        AccountJwtJwksPublicationRepository.publicDataDigest(
            promotion.expectedPublicJwksJson(), promotion.expectedActiveMarkerJson());
    if (!promotion.publicConfigMapUid().equals(receipt.configMapUid())
        || !promotion
            .activeJwksObservedResourceVersion()
            .orElseThrow()
            .equals(receipt.observedResourceVersion())
        || !promotion.activeJwksPublicDataDigest().orElseThrow().equals(dataDigest)
        || !receipt.publicDataDigest().equals(dataDigest)) {
      throw rejected();
    }
    return new PublishedProjection(
        receipt.configMapUid(),
        receipt.observedResourceVersion(),
        promotion.expectedPublicJwksJson(),
        promotion.expectedActiveMarkerJson(),
        dataDigest);
  }

  private static void requireRetainedPublicKeySet(
      CommittedSignerEvidence committed, PrepublicationIntent intent) {
    Map<String, String> previousKeys =
        publicKeyFingerprints(committed.promotion().expectedPublicJwksJson());
    Map<String, String> currentKeys = publicKeyFingerprints(intent.jwksJson());
    if (previousKeys.isEmpty() || currentKeys.size() < previousKeys.size()) {
      throw rejected();
    }
    if (new BigInteger(intent.targetGeneration())
            .compareTo(new BigInteger(committed.promotion().targetGeneration()))
        <= 0) {
      throw rejected();
    }
    for (Map.Entry<String, String> previous : previousKeys.entrySet()) {
      if (!previous.getValue().equals(currentKeys.get(previous.getKey()))) {
        throw rejected();
      }
    }
    if (!intent.publicKeyFingerprint().equals(currentKeys.get(intent.targetKid()))
        || !committed
            .promotion()
            .targetPublicKeyFingerprint()
            .equals(currentKeys.get(committed.promotion().targetKid()))) {
      throw rejected();
    }
  }

  private static Map<String, String> publicKeyFingerprints(String jwksJson) {
    try {
      if (jwksJson == null
          || jwksJson.isBlank()
          || jwksJson.getBytes(StandardCharsets.UTF_8).length > MAX_JWKS_BYTES) {
        throw new IllegalArgumentException();
      }
      JsonNode root = STRICT_JSON.readTree(jwksJson);
      if (root == null || !root.isObject() || !hasExactFields(root, JWKS_FIELDS)) {
        throw new IllegalArgumentException();
      }
      JsonNode keys = root.get("keys");
      if (keys == null || !keys.isArray() || keys.isEmpty() || keys.size() > MAX_KEYS) {
        throw new IllegalArgumentException();
      }
      Map<String, String> fingerprints = new LinkedHashMap<>();
      for (JsonNode key : keys) {
        if (key == null || !key.isObject() || !hasExactFields(key, JWK_FIELDS)) {
          throw new IllegalArgumentException();
        }
        String kty = requiredText(key, "kty");
        String use = requiredText(key, "use");
        String alg = requiredText(key, "alg");
        String kid = requiredText(key, "kid");
        String modulus = requiredText(key, "n");
        String exponent = requiredText(key, "e");
        JsonNode keyOps = key.get("key_ops");
        if (!"RSA".equals(kty)
            || !"sig".equals(use)
            || !"RS256".equals(alg)
            || !kid.matches("[A-Za-z0-9_-]{1,64}")
            || keyOps == null
            || !keyOps.isArray()
            || keyOps.size() != 1
            || !keyOps.get(0).isTextual()
            || !"verify".equals(keyOps.get(0).textValue())) {
          throw new IllegalArgumentException();
        }
        String preimage =
            new String(
                Rfc8785CanonicalJson.canonicalizeUtf8(
                    STRICT_JSON.writeValueAsString(
                        Map.of("e", exponent, "kty", kty, "n", modulus))),
                StandardCharsets.UTF_8);
        String fingerprint =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(preimage.getBytes(StandardCharsets.UTF_8)));
        if (fingerprints.putIfAbsent(kid, fingerprint) != null) {
          throw new IllegalArgumentException();
        }
      }
      return Map.copyOf(fingerprints);
    } catch (IOException | NoSuchAlgorithmException | RuntimeException malformed) {
      throw rejected();
    }
  }

  private static String requiredText(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
      throw new IllegalArgumentException();
    }
    return value.textValue();
  }

  private static boolean hasExactFields(JsonNode object, java.util.Set<String> expected) {
    if (object == null || !object.isObject() || object.size() != expected.size()) {
      return false;
    }
    for (Map.Entry<String, JsonNode> property : object.properties()) {
      if (!expected.contains(property.getKey())) {
        return false;
      }
    }
    return true;
  }

  private static void requireExactReadback(PublishedProjection projection, ConfigMapSnapshot live) {
    String jwks = live.data().get(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY);
    String marker = live.data().get(AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY);
    if (!projection.configMapUid().equals(live.uid())
        || !projection.resourceVersion().equals(live.resourceVersion())
        || !projection.jwksJson().equals(jwks)
        || !projection.markerJson().equals(marker)
        || marker == null
        || marker.getBytes(StandardCharsets.UTF_8).length > MAX_MARKER_BYTES
        || !AccountJwtJwksPublicationRepository.publicDataDigest(jwks, marker)
            .equals(projection.publicDataDigest())) {
      throw rejected();
    }
  }

  private static SourceIdentity sourceIdentity(
      Binding materializer, BindingIdentity api, String configMapUid) {
    try {
      return new SourceIdentity(
          materializer.environmentId(),
          materializer.clusterId(),
          materializer.expectedClusterIncarnationUid(),
          materializer.namespace(),
          materializer.expectedNamespaceUid(),
          configMapUid,
          api.configRevision(),
          api.apiServerUrl(),
          api.servingCaSha256());
    } catch (RuntimeException invalidIdentity) {
      throw rejected();
    }
  }

  private void requireUnchangedProtectedTrust(Binding expected, BindingIdentity expectedApi) {
    Binding currentMaterializer = currentMaterializerBinding();
    BindingIdentity currentApi = currentApiIdentity();
    if (!expected.equals(currentMaterializer) || !expectedApi.equals(currentApi)) {
      throw rejected();
    }
  }

  private static boolean isTemporaryApiFailure(int statusCode) {
    return statusCode == 0 || statusCode == 408 || statusCode == 429 || statusCode >= 500;
  }

  private static TrustFence trustFence(Binding binding) {
    return new TrustFence(
        binding.expectedClusterIncarnationUid(),
        binding.expectedNamespaceUid(),
        binding.bindingDigest(),
        binding.configRevision());
  }

  private static SourceUnavailableException unavailable() {
    return new SourceUnavailableException();
  }

  private static IllegalStateException rejected() {
    return new IllegalStateException("Account trusted public JWKS source was rejected");
  }

  private record TrustedPin(
      Binding materializerBinding, BindingIdentity apiIdentity, SourceIdentity sourceIdentity) {}

  private record OwnerProjection(
      CommittedSignerEvidence committedSigner, PublishedProjection projection) {}

  private record PublishedProjection(
      String configMapUid,
      String resourceVersion,
      String jwksJson,
      String markerJson,
      String publicDataDigest) {}

  private record VerifiedSource(TrustedPin pin, byte[] jwksBytes) {}
}
