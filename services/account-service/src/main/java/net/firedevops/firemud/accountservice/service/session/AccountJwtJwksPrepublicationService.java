package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyFactory;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.MountObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PrepublicationIntent;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PublicationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PublicationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.BindingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.CasObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Account-owned producer for the public JWKS prepublication phase and local mounted correspondence
 * observation.
 *
 * <p>It accepts no caller-selected operation, key identity, ConfigMap name, or public bytes. The
 * only source key is the exact current Account generation receipt. Remote CAS is outside SQL
 * transactions and is preceded by a durable immutable intent. Neither publication nor local
 * correspondence establishes PREPARED, COMMITTED, issuance, cache convergence, probes, or
 * readiness.
 */
public final class AccountJwtJwksPrepublicationService {
  public static final int MAX_MOUNTED_MARKER_BYTES = 16 * 1024;
  private static final String PRIVATE_MOUNT = "/var/run/secrets/firemud/jwt";
  private static final String PRIVATE_PENDING_BUNDLE = "pending.key";
  private static final String PUBLIC_MOUNT = "/var/run/secrets/firemud/jwks";
  private static final String PUBLIC_JWKS = "jwks.json";
  private static final String PUBLIC_MARKER = "jwt-generation.json";
  private static final int MAX_KEYS = 64;
  private static final int MIN_RSA_BITS = 3_072;
  private static final int MAX_RSA_BITS = 16_384;
  private static final Set<String> JWKS_ROOT_FIELDS = Set.of("keys");
  private static final Set<String> JWK_FIELDS =
      Set.of("kty", "use", "alg", "kid", "key_ops", "n", "e");
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final JsonMapper STRICT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final AccountJwtSignerDesiredStateRepository desiredStateRepository;
  private final AccountJwtJwksPublicationRepository publicationRepository;
  private final AccountJwtSignerMaterializerTrustBinding materializerTrustBinding;
  private final AccountJwtJwksConfigMapClient configMapClient;
  private final TransactionTemplate accountTransaction;
  private final MountedProjection mountedProjection;

  /** Production instance uses only the fixed Account signer/JWKS mount paths. */
  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification =
          "The production constructor intentionally fixes Account's protected private-signer and read-only public-JWKS mount roots.")
  public AccountJwtJwksPrepublicationService(
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtJwksPublicationRepository publicationRepository,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtJwksConfigMapClient configMapClient,
      PlatformTransactionManager transactionManager) {
    this(
        desiredStateRepository,
        publicationRepository,
        materializerTrustBinding,
        configMapClient,
        transactionManager,
        new MountedProjection(
            Path.of(PRIVATE_MOUNT),
            Path.of(PRIVATE_PENDING_BUNDLE),
            Path.of(PUBLIC_MOUNT),
            Path.of(PUBLIC_JWKS),
            Path.of(PUBLIC_MARKER)));
  }

  AccountJwtJwksPrepublicationService(
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtJwksPublicationRepository publicationRepository,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtJwksConfigMapClient configMapClient,
      PlatformTransactionManager transactionManager,
      MountedProjection mountedProjection) {
    this.desiredStateRepository =
        Objects.requireNonNull(desiredStateRepository, "Desired-state repository is required");
    this.publicationRepository =
        Objects.requireNonNull(publicationRepository, "JWKS publication repository is required");
    this.materializerTrustBinding =
        Objects.requireNonNull(
            materializerTrustBinding, "Protected materializer trust is required");
    this.configMapClient =
        Objects.requireNonNull(configMapClient, "Protected ConfigMap client is required");
    this.accountTransaction = new TransactionTemplate(transactionManager);
    this.accountTransaction.setReadOnly(false);
    this.mountedProjection = Objects.requireNonNull(mountedProjection);
  }

  /**
   * Publishes exactly Account's current pending public JWK alongside all retained public keys.
   * Exact retries reconcile a previously persisted intent; changed/stale ConfigMap data fail before
   * another write.
   */
  public PublicationReceipt prepublishCurrentGeneration() {
    Binding binding = requireCurrentMaterializerBinding();
    BindingIdentity apiIdentity = requireMatchingApiIdentity(binding, configMapClient.identity());
    CurrentGeneration current = loadCurrentGeneration(binding, apiIdentity);
    if (current.publication().isPresent()) {
      PublicationEvidence evidence = current.publication().orElseThrow();
      PrepublicationIntent intent = evidence.intent();
      requireIntentApiIdentity(intent, apiIdentity);
      ConfigMapSnapshot snapshot = configMapClient.observe();
      if (evidence.receipt().isPresent()) {
        requirePublishedProjection(intent, snapshot);
        requireUnchangedBindings(binding, apiIdentity);
        return evidence.receipt().orElseThrow();
      }
      requireRetrySnapshot(intent, snapshot);
      return publishAndRecord(binding, apiIdentity, intent, snapshot);
    }

    ConfigMapSnapshot snapshot = configMapClient.observe();
    String sourceJwks = snapshot.data().get(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY);
    if (sourceJwks == null || sourceJwks.isBlank()) {
      throw new PublicationRejectedException(
          "Pre-created Account public JWKS ConfigMap has no bounded jwks.json value");
    }
    String desiredJwks = appendPendingPublicKey(sourceJwks, current.result());
    String marker = generationMarker(current.result(), current.request(), apiIdentity);
    PrepublicationIntent intent =
        accountTransaction.execute(
            status -> {
              PrepublicationIntent persisted =
                  publicationRepository.recordPrepublicationIntent(
                      binding.accountBinding(),
                      trustFence(binding),
                      current.result(),
                      current.request(),
                      apiIdentity.bindingDigest(),
                      apiIdentity.configRevision(),
                      snapshot.uid(),
                      snapshot.resourceVersion(),
                      AccountJwtJwksPublicationRepository.snapshotDigest(snapshot.data()),
                      desiredJwks,
                      marker);
              requireUnchangedBindings(binding, apiIdentity);
              return persisted;
            });
    if (intent == null) {
      throw new PublicationRejectedException("Account JWT JWKS intent was not persisted");
    }
    requireIntentApiIdentity(intent, apiIdentity);
    requireRetrySnapshot(intent, snapshot);
    return publishAndRecord(binding, apiIdentity, intent, snapshot);
  }

  /**
   * Observes exact mounted {@code pending.key}, {@code jwks.json}, and publication marker files.
   * The operation must already have an exact persisted Account API readback receipt.
   */
  public MountObservation observeCurrentMountedCorrespondence() {
    Binding binding = requireCurrentMaterializerBinding();
    BindingIdentity apiIdentity = requireMatchingApiIdentity(binding, configMapClient.identity());
    CurrentGeneration current = loadCurrentGeneration(binding, apiIdentity);
    PublicationEvidence evidence =
        current
            .publication()
            .orElseThrow(
                () ->
                    new PublicationRejectedException(
                        "Account JWT JWKS publication evidence is not available"));
    PrepublicationIntent intent = evidence.intent();
    evidence
        .receipt()
        .orElseThrow(
            () ->
                new PublicationRejectedException("Account JWT JWKS API readback is not available"));
    requireIntentApiIdentity(intent, apiIdentity);

    byte[] markerBytes =
        readMountedFile(
            mountedProjection.publicMountRoot(),
            mountedProjection.markerPath(),
            MAX_MOUNTED_MARKER_BYTES);
    byte[] jwksBytes =
        readMountedFile(
            mountedProjection.publicMountRoot(),
            mountedProjection.jwksPath(),
            AccountMountedJwtSignerBundle.MAX_PUBLIC_JWKS_BYTES);
    String marker = strictUtf8(markerBytes);
    String mountedJwks = strictUtf8(jwksBytes);
    if (!intent.generationMarkerJson().equals(marker)
        || !intent.jwksJson().equals(mountedJwks)
        || !intent
            .publicDataDigest()
            .equals(AccountJwtJwksPublicationRepository.publicDataDigest(mountedJwks, marker))) {
      throw new PublicationRejectedException(
          "Mounted Account public JWT projection does not match Account's exact publication");
    }

    AccountMountedJwtSignerBundle.ExpectedIdentity expectedIdentity =
        new AccountMountedJwtSignerBundle.ExpectedIdentity(
            binding.environmentId(),
            binding.clusterId(),
            binding.namespace(),
            current.result().operationId().toString(),
            current.result().targetGeneration(),
            current.result().targetKid(),
            current.result().publicKeyFingerprint());
    AccountMountedJwtSignerBundle.LocalObservation localObservation =
        AccountMountedJwtSignerBundle.observeMounted(
            mountedProjection.privateMountRoot(),
            mountedProjection.pendingBundlePath(),
            mountedProjection.publicMountRoot(),
            mountedProjection.jwksPath(),
            expectedIdentity);

    byte[] markerAfter =
        readMountedFile(
            mountedProjection.publicMountRoot(),
            mountedProjection.markerPath(),
            MAX_MOUNTED_MARKER_BYTES);
    byte[] jwksAfter =
        readMountedFile(
            mountedProjection.publicMountRoot(),
            mountedProjection.jwksPath(),
            AccountMountedJwtSignerBundle.MAX_PUBLIC_JWKS_BYTES);
    if (!Arrays.equals(markerBytes, markerAfter) || !Arrays.equals(jwksBytes, jwksAfter)) {
      throw new PublicationRejectedException(
          "Mounted Account public JWT projection changed during correspondence observation");
    }
    MountObservation observation =
        accountTransaction.execute(
            status -> {
              MountObservation persisted =
                  publicationRepository.recordMountedCorrespondence(
                      binding.accountBinding(),
                      trustFence(binding),
                      current.result().operationId(),
                      sha256(markerBytes),
                      AccountJwtJwksPublicationRepository.publicDataDigest(mountedJwks, marker),
                      localObservation.publicKeyFingerprint());
              requireUnchangedBindings(binding, apiIdentity);
              return persisted;
            });
    if (observation == null) {
      throw new PublicationRejectedException(
          "Mounted Account JWT correspondence was not persisted");
    }
    Arrays.fill(markerBytes, (byte) 0);
    Arrays.fill(jwksBytes, (byte) 0);
    Arrays.fill(markerAfter, (byte) 0);
    Arrays.fill(jwksAfter, (byte) 0);
    return observation;
  }

  private PublicationReceipt publishAndRecord(
      Binding binding,
      BindingIdentity apiIdentity,
      PrepublicationIntent intent,
      ConfigMapSnapshot expectedSnapshot) {
    requireUnchangedBindings(binding, apiIdentity);
    Map<String, String> desiredData = desiredData(intent);
    ConfigMapSnapshot intentCasSnapshot =
        new ConfigMapSnapshot(
            intent.configMapUid(), intent.expectedResourceVersion(), expectedSnapshot.data());
    CasObservation observation = configMapClient.publish(intentCasSnapshot, desiredData);
    if (!intent.configMapUid().equals(observation.uid())
        || !intent.expectedResourceVersion().equals(observation.priorResourceVersion())
        || !observation.publishedData().equals(desiredData)
        || !desiredData.entrySet().stream()
            .allMatch(
                entry -> entry.getValue().equals(observation.readbackData().get(entry.getKey())))
        || intent.expectedResourceVersion().equals(observation.resourceVersion())) {
      throw new PublicationRejectedException(
          "Protected Account public JWKS client returned a non-exact readback");
    }
    requireUnchangedBindings(binding, apiIdentity);
    PublicationReceipt persisted =
        accountTransaction.execute(
            status -> {
              PublicationReceipt receipt =
                  publicationRepository.recordPublicationReceipt(
                      binding.accountBinding(),
                      trustFence(binding),
                      intent.operationId(),
                      apiIdentity.bindingDigest(),
                      apiIdentity.configRevision(),
                      observation.uid(),
                      observation.resourceVersion(),
                      intent.jwksJson(),
                      intent.generationMarkerJson());
              requireUnchangedBindings(binding, apiIdentity);
              return receipt;
            });
    if (persisted == null) {
      throw new PublicationRejectedException("Account JWT JWKS API receipt was not persisted");
    }
    return persisted;
  }

  private CurrentGeneration loadCurrentGeneration(Binding binding, BindingIdentity apiIdentity) {
    CurrentGeneration current =
        accountTransaction.execute(
            status -> {
              GenerationResult result =
                  desiredStateRepository.readCurrentGenerationResult(
                      binding.accountBinding(), trustFence(binding));
              GenerationRequest request =
                  desiredStateRepository.readCurrentGenerationRequest(
                      binding.accountBinding(), trustFence(binding));
              Optional<PublicationEvidence> publication =
                  publicationRepository.readCurrentPublication(
                      binding.accountBinding(), trustFence(binding));
              if (!result.binding().equals(binding.accountBinding())
                  || !result.trustFence().equals(trustFence(binding))
                  || !request.operationId().equals(result.operationId())
                  || !request.generationReceiptDigest().equals(result.receiptDigest())) {
                throw new PublicationRejectedException(
                    "Account JWT generation receipt does not match the current protected binding");
              }
              requireUnchangedBindings(binding, apiIdentity);
              return new CurrentGeneration(result, request, publication);
            });
    if (current == null) {
      throw new PublicationRejectedException("Current Account JWT generation is unavailable");
    }
    return current;
  }

  private Binding requireCurrentMaterializerBinding() {
    Binding current = materializerTrustBinding.current().orElse(null);
    if (current == null) {
      throw new PublicationRejectedException(
          "Protected Account JWT materializer binding is unavailable");
    }
    return current;
  }

  private static BindingIdentity requireMatchingApiIdentity(
      Binding materializerBinding, BindingIdentity apiIdentity) {
    if (apiIdentity == null
        || !materializerBinding.environmentId().equals(apiIdentity.environmentId())
        || !materializerBinding.clusterId().equals(apiIdentity.clusterId())
        || !materializerBinding.namespace().equals(apiIdentity.namespace())
        || !materializerBinding
            .expectedClusterIncarnationUid()
            .equals(apiIdentity.expectedClusterIncarnationUid())
        || !materializerBinding.expectedNamespaceUid().equals(apiIdentity.expectedNamespaceUid())
        || apiIdentity.bindingDigest() == null
        || apiIdentity.configRevision() == null) {
      throw new PublicationRejectedException(
          "Protected Account materializer and ConfigMap API bindings disagree");
    }
    return apiIdentity;
  }

  private void requireUnchangedBindings(Binding expected, BindingIdentity expectedApiIdentity) {
    if (materializerTrustBinding.current().filter(expected::equals).isEmpty()
        || !expectedApiIdentity.equals(configMapClient.identity())) {
      throw new PublicationRejectedException(
          "Protected Account JWT publication trust changed during the operation");
    }
  }

  private static void requireIntentApiIdentity(
      PrepublicationIntent intent, BindingIdentity apiIdentity) {
    if (!intent.apiBindingDigest().equals(apiIdentity.bindingDigest())
        || !intent.apiConfigRevision().equals(apiIdentity.configRevision())) {
      throw new PublicationRejectedException(
          "Protected Account ConfigMap API trust changed for the current JWT operation");
    }
  }

  private static void requirePublishedProjection(
      PrepublicationIntent intent, ConfigMapSnapshot snapshot) {
    if (!intent.configMapUid().equals(snapshot.uid())
        || !intent
            .jwksJson()
            .equals(snapshot.data().get(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY))
        || !intent
            .generationMarkerJson()
            .equals(
                snapshot
                    .data()
                    .get(AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY))) {
      throw new PublicationRejectedException(
          "Current public JWT ConfigMap no longer matches Account's immutable receipt");
    }
  }

  private static void requireRetrySnapshot(
      PrepublicationIntent intent, ConfigMapSnapshot snapshot) {
    if (!intent.configMapUid().equals(snapshot.uid())) {
      throw new PublicationRejectedException(
          "Account public JWKS ConfigMap UID changed after intent creation");
    }
    if (intent
            .jwksJson()
            .equals(snapshot.data().get(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY))
        && intent
            .generationMarkerJson()
            .equals(
                snapshot
                    .data()
                    .get(AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY))) {
      return;
    }
    if (!intent.expectedResourceVersion().equals(snapshot.resourceVersion())
        || !intent
            .expectedSnapshotDigest()
            .equals(AccountJwtJwksPublicationRepository.snapshotDigest(snapshot.data()))) {
      throw new PublicationRejectedException(
          "Account public JWKS ConfigMap changed after immutable intent creation");
    }
  }

  private static Map<String, String> desiredData(PrepublicationIntent intent) {
    Map<String, String> data = new LinkedHashMap<>();
    data.put(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY, intent.jwksJson());
    data.put(
        AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
        intent.generationMarkerJson());
    return Map.copyOf(data);
  }

  private static String appendPendingPublicKey(String sourceJwks, GenerationResult result) {
    JsonNode root = parseStrictJson(sourceJwks, AccountJwtJwksPublicationRepository.MAX_JWKS_BYTES);
    boolean supportedRoot = root.size() == JWKS_ROOT_FIELDS.size();
    for (Map.Entry<String, JsonNode> field : root.properties()) {
      supportedRoot &= JWKS_ROOT_FIELDS.contains(field.getKey());
    }
    if (!supportedRoot) {
      throw new PublicationRejectedException("Existing Account JWKS has an unsupported shape");
    }
    JsonNode keys = root.get("keys");
    if (keys == null || !keys.isArray() || keys.size() >= MAX_KEYS) {
      throw new PublicationRejectedException(
          "Existing Account JWKS is missing or at its key limit");
    }
    Set<String> kids = new HashSet<>();
    List<String> canonicalKeys = new ArrayList<>(keys.size() + 1);
    for (JsonNode jwk : keys) {
      validateRetainedJwk(jwk);
      String kid = jwk.get("kid").textValue();
      if (!kids.add(kid) || kid.equals(result.targetKid())) {
        throw new PublicationRejectedException(
            "Existing Account JWKS has a duplicate or conflicting key identifier");
      }
      canonicalKeys.add(canonicalJson(jwk.toString()));
    }
    JsonNode pending = parseStrictJson(result.publicJwkJson(), 8 * 1024);
    validateRetainedJwk(pending);
    if (!result.targetKid().equals(pending.get("kid").textValue())
        || !kids.add(result.targetKid())) {
      throw new PublicationRejectedException(
          "Account generation receipt has a conflicting public key identifier");
    }
    canonicalKeys.add(canonicalJson(result.publicJwkJson()));
    String jwks = "{\"keys\":[" + String.join(",", canonicalKeys) + "]}";
    if (jwks.getBytes(StandardCharsets.UTF_8).length
        > AccountJwtJwksPublicationRepository.MAX_JWKS_BYTES) {
      throw new PublicationRejectedException("Account public JWKS exceeds its storage bound");
    }
    return jwks;
  }

  private static void validateRetainedJwk(JsonNode jwk) {
    if (jwk == null || !jwk.isObject() || jwk.size() < 6 || jwk.size() > 7) {
      throw new PublicationRejectedException("Existing Account JWKS contains a malformed key");
    }
    for (Map.Entry<String, JsonNode> field : jwk.properties()) {
      if (!JWK_FIELDS.contains(field.getKey())) {
        throw new PublicationRejectedException(
            "Existing Account JWKS contains an unsupported or private key field");
      }
    }
    String kty = requiredText(jwk, "kty", 8);
    String use = requiredText(jwk, "use", 8);
    String alg = requiredText(jwk, "alg", 8);
    String kid = requiredText(jwk, "kid", 64);
    String n = requiredText(jwk, "n", 4096);
    String e = requiredText(jwk, "e", 16);
    if (!"RSA".equals(kty)
        || !"sig".equals(use)
        || !"RS256".equals(alg)
        || !KID.matcher(kid).matches()) {
      throw new PublicationRejectedException("Existing Account JWKS contains an unsupported key");
    }
    JsonNode keyOps = jwk.get("key_ops");
    if (keyOps != null
        && (!keyOps.isArray()
            || keyOps.size() != 1
            || !keyOps.get(0).isTextual()
            || !"verify".equals(keyOps.get(0).textValue()))) {
      throw new PublicationRejectedException("Existing Account JWKS key operations are malformed");
    }
    byte[] modulus = decodeBase64Url(n, 2048);
    byte[] exponent = decodeBase64Url(e, 8);
    try {
      if (modulus.length == 0 || modulus[0] == 0 || exponent.length == 0 || exponent[0] == 0) {
        throw new PublicationRejectedException(
            "Existing Account JWKS RSA parameters are malformed");
      }
      BigInteger modulusValue = new BigInteger(1, modulus);
      BigInteger exponentValue = new BigInteger(1, exponent);
      if (modulusValue.bitLength() < MIN_RSA_BITS
          || modulusValue.bitLength() > MAX_RSA_BITS
          || !modulusValue.testBit(0)
          || !BigInteger.valueOf(65_537).equals(exponentValue)) {
        throw new PublicationRejectedException(
            "Existing Account JWKS RSA parameters are unsupported");
      }
      KeyFactory.getInstance("RSA")
          .generatePublic(new RSAPublicKeySpec(modulusValue, exponentValue));
    } catch (Exception ex) {
      if (ex instanceof PublicationRejectedException rejected) {
        throw rejected;
      }
      throw new PublicationRejectedException("Existing Account JWKS RSA parameters are invalid");
    } finally {
      Arrays.fill(modulus, (byte) 0);
      Arrays.fill(exponent, (byte) 0);
    }
  }

  private static String generationMarker(
      GenerationResult result, GenerationRequest request, BindingIdentity apiIdentity) {
    Map<String, Object> marker = new LinkedHashMap<>();
    marker.put("schemaVersion", 1);
    marker.put("phase", "PREPUBLISHED");
    marker.put("operationId", result.operationId().toString());
    marker.put("operationDigest", result.operationDigest());
    marker.put("generationRequestDigest", result.generationRequestDigest());
    marker.put("generationReceiptDigest", result.receiptDigest());
    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("environmentId", result.binding().environmentId());
    binding.put("clusterId", result.binding().clusterId());
    binding.put("namespace", result.binding().namespace());
    binding.put(
        "expectedClusterIncarnationUid", result.trustFence().expectedClusterIncarnationUid());
    binding.put("expectedNamespaceUid", result.trustFence().expectedNamespaceUid());
    binding.put("trustBindingDigest", result.trustFence().bindingDigest());
    binding.put("trustConfigRevision", result.trustFence().configRevision());
    binding.put("apiBindingDigest", apiIdentity.bindingDigest());
    binding.put("apiConfigRevision", apiIdentity.configRevision());
    marker.put("binding", binding);
    marker.put("publicConfigMap", Map.of("name", "jwt-jwks"));
    marker.put("expectedDurableActive", activeMarker(request.expectedActive()));
    marker.put("expectedPublishedActive", activeMarker(request.expectedPublishedActive()));
    Map<String, Object> pending = new LinkedHashMap<>();
    pending.put("generation", result.targetGeneration());
    pending.put("kid", result.targetKid());
    pending.put("algorithm", result.targetAlgorithm());
    pending.put("publicKeyFingerprint", result.publicKeyFingerprint());
    marker.put("pending", pending);
    return canonicalJson(marker);
  }

  private static Map<String, Object> activeMarker(
      Optional<AccountJwtSignerDesiredStateRepository.ActiveSigner> active) {
    Map<String, Object> marker = new LinkedHashMap<>();
    marker.put("present", active.isPresent());
    active.ifPresent(
        signer -> {
          marker.put("generation", signer.generation());
          marker.put("kid", signer.kid());
        });
    return marker;
  }

  private static JsonNode parseStrictJson(String json, int maxBytes) {
    if (json == null || json.isBlank() || json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw new PublicationRejectedException("Account public JWT JSON is missing or oversized");
    }
    try {
      JsonNode node = STRICT_JSON.readTree(json);
      if (node == null || !node.isObject()) {
        throw new PublicationRejectedException("Account public JWT JSON has an unsupported shape");
      }
      return node;
    } catch (PublicationRejectedException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new PublicationRejectedException("Account public JWT JSON is malformed");
    }
  }

  private static String requiredText(JsonNode object, String field, int maxLength) {
    JsonNode value = object.get(field);
    if (value == null
        || !value.isTextual()
        || value.textValue().isEmpty()
        || value.textValue().length() > maxLength) {
      throw new PublicationRejectedException("Existing Account JWKS key is malformed");
    }
    return value.textValue();
  }

  private static byte[] decodeBase64Url(String value, int maxBytes) {
    if (value == null
        || value.isEmpty()
        || value.contains("=")
        || !value.matches("[A-Za-z0-9_-]+")) {
      throw new PublicationRejectedException("Existing Account JWKS RSA parameters are malformed");
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(value);
      if (decoded.length > maxBytes
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
        Arrays.fill(decoded, (byte) 0);
        throw new PublicationRejectedException(
            "Existing Account JWKS RSA parameters are malformed");
      }
      return decoded;
    } catch (IllegalArgumentException ex) {
      throw new PublicationRejectedException("Existing Account JWKS RSA parameters are malformed");
    }
  }

  private static String canonicalJson(String json) {
    try {
      return new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new PublicationRejectedException("Account public JWT JSON canonicalization failed");
    }
  }

  private static String canonicalJson(Object value) {
    try {
      return canonicalJson(STRICT_JSON.writeValueAsString(value));
    } catch (Exception ex) {
      throw new PublicationRejectedException("Account public JWT marker encoding failed");
    }
  }

  private static byte[] readMountedFile(Path configuredRoot, Path relativePath, int maxBytes) {
    try {
      if (configuredRoot == null
          || relativePath == null
          || relativePath.isAbsolute()
          || relativePath.normalize().startsWith("..")) {
        throw new IOException("Mounted file path is invalid");
      }
      Path root = configuredRoot.toRealPath();
      if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Mounted root is unavailable");
      }
      Path unresolved = root.resolve(relativePath).normalize();
      if (!unresolved.startsWith(root)) {
        throw new IOException("Mounted file escapes its projection");
      }
      Path file = unresolved.toRealPath();
      if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Mounted file is unavailable");
      }
      BasicFileAttributes before =
          Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (before.size() <= 0 || before.size() > maxBytes) {
        throw new IOException("Mounted file size is invalid");
      }
      byte[] bytes = new byte[(int) before.size()];
      try (SeekableByteChannel channel =
          Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          if (channel.read(buffer) < 0) {
            throw new IOException("Mounted file changed while being read");
          }
        }
        if (channel.read(ByteBuffer.allocate(1)) != -1) {
          throw new IOException("Mounted file changed while being read");
        }
      }
      BasicFileAttributes after =
          Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile()
          || !Objects.equals(before.fileKey(), after.fileKey())
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
        Arrays.fill(bytes, (byte) 0);
        throw new IOException("Mounted file changed while being read");
      }
      return bytes;
    } catch (IOException | RuntimeException ex) {
      throw new PublicationRejectedException(
          "Mounted Account JWT public projection is unavailable or invalid");
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException ex) {
      throw new PublicationRejectedException("Mounted Account JWT public projection is not UTF-8");
    }
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static TrustFence trustFence(Binding binding) {
    return new TrustFence(
        binding.expectedClusterIncarnationUid(),
        binding.expectedNamespaceUid(),
        binding.bindingDigest(),
        binding.configRevision());
  }

  private record CurrentGeneration(
      GenerationResult result,
      GenerationRequest request,
      Optional<PublicationEvidence> publication) {}

  /** Fixed filesystem projections; production paths are not request inputs. */
  record MountedProjection(
      Path privateMountRoot,
      Path pendingBundlePath,
      Path publicMountRoot,
      Path jwksPath,
      Path markerPath) {
    MountedProjection {
      Objects.requireNonNull(privateMountRoot);
      Objects.requireNonNull(pendingBundlePath);
      Objects.requireNonNull(publicMountRoot);
      Objects.requireNonNull(jwksPath);
      Objects.requireNonNull(markerPath);
      if (pendingBundlePath.isAbsolute()
          || jwksPath.isAbsolute()
          || markerPath.isAbsolute()
          || pendingBundlePath.normalize().startsWith("..")
          || jwksPath.normalize().startsWith("..")
          || markerPath.normalize().startsWith("..")) {
        throw new IllegalArgumentException("JWT mounted projection paths must be relative");
      }
    }
  }

  /** Safe generic failure; never includes a key, ConfigMap response body, or mounted path. */
  public static final class PublicationRejectedException extends IllegalStateException {
    public PublicationRejectedException(String message) {
      super(message);
    }
  }
}
