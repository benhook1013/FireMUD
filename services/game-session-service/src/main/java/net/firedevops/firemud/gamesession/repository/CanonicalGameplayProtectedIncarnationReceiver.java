package net.firedevops.firemud.gamesession.repository;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unregistered Game Session adapter that verifies a protected current-incarnation observation.
 *
 * <p>This is a LOCAL current-read proof only. It does not implement the Redis reset challenge,
 * accepted post-reset attestation lifecycle, barrier installation, OPEN authorization, or
 * admission. Production source, custody, and key provisioning are intentionally absent.
 */
final class CanonicalGameplayProtectedIncarnationReceiver {
  private static final String OBSERVATION_SCHEMA = "firemud.gs.protected-incarnation-read/v1";
  private static final String INITIAL_OBSERVATION_SCHEMA =
      "firemud.gs.protected-initial-account-barrier-read/v1";
  private static final String INCARNATION_BINDING_SCHEMA =
      "firemud.gs.protected-incarnation-binding/v1";
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schema",
          "attestorBinding",
          "attestorKeyId",
          "acceptedAttestationDigest",
          "observationNonce",
          "observationIssuedAtEpochMillis",
          "observationObservedAtEpochMillis",
          "observationExpiresAtEpochMillis",
          "owner",
          "target",
          "targetDeploymentEvidence",
          "coordOpsRoAclDigest",
          "coordRedisConfigObservedDigest");
  private static final Set<String> OWNER_FIELDS =
      Set.of(
          "operationId",
          "requestId",
          "accountId",
          "operationFence",
          "plannedAccountAdmissionFence",
          "inventorySnapshotRevision",
          "cohortDigest",
          "barrierTargetDigest");
  private static final Set<String> INITIAL_OWNER_FIELDS =
      Set.of(
          "ownerKind",
          "initializerId",
          "originalRequestId",
          "originalCallerContextId",
          "originalAttemptFence",
          "accountId",
          "sourceControlState",
          "sourceControlEpoch",
          "inventorySnapshotRevision",
          "accountLoginEvidenceDigest",
          "sourceControlDigest",
          "targetDigest");
  private static final Set<String> TARGET_FIELDS =
      Set.of(
          "targetOrdinal",
          "tenantId",
          "playableStateNamespaceId",
          "tenantGameplayTag",
          "workloadNamespace",
          "allocationVersion");
  private static final Set<String> EVIDENCE_FIELDS =
      Set.of(
          "deploymentIdentity",
          "deploymentGeneration",
          "canonicalNodeSet",
          "redisBuildIdentity",
          "luaScriptRegistryVersion",
          "luaScriptRegistryDigest",
          "startupState",
          "bootGeneration",
          "startupGeneration",
          "storageGeneration",
          "aofGeneration");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final CanonicalGameplayAccountAdmissionBarrierRepository intentRepository;
  private final CanonicalGameplayAccountInitialBarrierInitializerRepository initialRepository;
  private final CanonicalGameplayTenantGameplayTagRepository tenantTagRepository;
  private final ProtectedObservationChannel observationChannel;
  private final TrustedConfiguration trustedConfiguration;
  private final Clock clock;
  private final SecureRandom secureRandom;

  CanonicalGameplayProtectedIncarnationReceiver(
      CanonicalGameplayAccountAdmissionBarrierRepository intentRepository,
      CanonicalGameplayTenantGameplayTagRepository tenantTagRepository,
      ProtectedObservationChannel observationChannel,
      TrustedConfiguration trustedConfiguration,
      Clock clock) {
    this(
        intentRepository,
        null,
        tenantTagRepository,
        observationChannel,
        trustedConfiguration,
        clock,
        new SecureRandom());
  }

  CanonicalGameplayProtectedIncarnationReceiver(
      CanonicalGameplayAccountAdmissionBarrierRepository intentRepository,
      CanonicalGameplayTenantGameplayTagRepository tenantTagRepository,
      ProtectedObservationChannel observationChannel,
      TrustedConfiguration trustedConfiguration,
      Clock clock,
      SecureRandom secureRandom) {
    this(
        intentRepository,
        null,
        tenantTagRepository,
        observationChannel,
        trustedConfiguration,
        clock,
        secureRandom);
  }

  CanonicalGameplayProtectedIncarnationReceiver(
      CanonicalGameplayAccountInitialBarrierInitializerRepository initialRepository,
      CanonicalGameplayTenantGameplayTagRepository tenantTagRepository,
      ProtectedObservationChannel observationChannel,
      TrustedConfiguration trustedConfiguration,
      Clock clock) {
    this(
        null,
        initialRepository,
        tenantTagRepository,
        observationChannel,
        trustedConfiguration,
        clock,
        new SecureRandom());
  }

  private CanonicalGameplayProtectedIncarnationReceiver(
      CanonicalGameplayAccountAdmissionBarrierRepository intentRepository,
      CanonicalGameplayAccountInitialBarrierInitializerRepository initialRepository,
      CanonicalGameplayTenantGameplayTagRepository tenantTagRepository,
      ProtectedObservationChannel observationChannel,
      TrustedConfiguration trustedConfiguration,
      Clock clock,
      SecureRandom secureRandom) {
    this.intentRepository = intentRepository;
    this.initialRepository = initialRepository;
    this.tenantTagRepository = tenantTagRepository;
    this.observationChannel = observationChannel;
    this.trustedConfiguration = trustedConfiguration;
    this.clock = clock;
    this.secureRandom = secureRandom;
  }

  /**
   * Reads current evidence for one REQUIRED frozen fence target.
   *
   * <p>The correlation nonce below is local to this bounded current observation. It is not the
   * replacementVerificationChallenge nonce and never renews, consumes, or replaces that challenge.
   */
  VerifiedIncarnation observeForFenceTarget(UUID operationId, long targetOrdinal) {
    requireNoAmbientTransaction();
    requireNonNil(operationId, "operationId");
    if (targetOrdinal < 0) {
      throw new IllegalArgumentException("targetOrdinal must not be negative");
    }
    requireAvailable(OwnerKind.ACCOUNT_FENCE);
    CurrentContext before = loadCurrentContext(operationId, targetOrdinal);
    return observeCurrentContext(before, () -> loadCurrentContext(operationId, targetOrdinal));
  }

  /**
   * Reads a separate INITIAL original-attempt context under the same trusted signature verifier.
   */
  VerifiedIncarnation observeForInitialAccountBarrier(UUID originalRequestId) {
    requireNoAmbientTransaction();
    requireNonNil(originalRequestId, "originalRequestId");
    requireAvailable(OwnerKind.INITIAL_ACCOUNT_BARRIER);
    CurrentContext before = loadInitialCurrentContext(originalRequestId);
    return observeCurrentContext(before, () -> loadInitialCurrentContext(originalRequestId));
  }

  private VerifiedIncarnation observeCurrentContext(
      CurrentContext before, java.util.function.Supplier<CurrentContext> reload) {
    long issuedAt = clock.millis();
    if (issuedAt <= 0L) {
      throw new ObservationUnavailableException("Protected observation clock is unavailable");
    }
    long expiresAt;
    try {
      expiresAt = Math.addExact(issuedAt, trustedConfiguration.observationTtlMillis());
    } catch (ArithmeticException overflow) {
      throw new ObservationUnavailableException("Protected observation time window is invalid");
    }
    byte[] nonceBytes = new byte[32];
    secureRandom.nextBytes(nonceBytes);
    String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
    ObservationRequest request =
        new ObservationRequest(
            nonce,
            issuedAt,
            expiresAt,
            trustedConfiguration.attestorBinding(),
            trustedConfiguration.acceptedAttestationDigest(),
            before,
            trustedConfiguration.expectedEvidence(),
            trustedConfiguration.coordOpsRoAclDigest(),
            trustedConfiguration.coordRedisConfigObservedDigest());

    requireNoAmbientTransaction();
    SignedObservation signed;
    try {
      signed = observationChannel.observe(request);
    } catch (ObservationUnavailableException unavailable) {
      throw unavailable;
    } catch (RuntimeException unavailable) {
      throw new ObservationUnavailableException("Protected observation source is unavailable");
    }
    requireNoAmbientTransaction();
    if (signed == null) {
      throw new ObservationUnavailableException("Protected observation source returned no bytes");
    }
    VerifiedPayload payload = verifyObservation(signed, request, before);
    CurrentContext after = reload.get();
    if (!before.equals(after)) {
      throw new ObservationRejectedException("Protected observation owner context changed");
    }
    long now = clock.millis();
    if (now < issuedAt
        || now >= expiresAt
        || payload.observedAtEpochMillis() < issuedAt
        || payload.observedAtEpochMillis() > now
        || payload.expiresAtEpochMillis() != expiresAt) {
      throw new ObservationRejectedException("Protected observation is expired or out of window");
    }
    return new VerifiedIncarnation(
        before,
        trustedConfiguration.acceptedAttestationDigest(),
        trustedConfiguration.expectedEvidence(),
        payload.observationNonce(),
        issuedAt,
        payload.observedAtEpochMillis(),
        expiresAt,
        payload.payloadDigest(),
        incarnationDigest(trustedConfiguration));
  }

  private void requireAvailable(OwnerKind ownerKind) {
    if ((ownerKind == OwnerKind.ACCOUNT_FENCE && intentRepository == null)
        || (ownerKind == OwnerKind.INITIAL_ACCOUNT_BARRIER && initialRepository == null)
        || tenantTagRepository == null
        || observationChannel == null
        || trustedConfiguration == null
        || clock == null
        || secureRandom == null
        || trustedConfiguration.attestorBinding() == null
        || trustedConfiguration.attestorBinding().isBlank()
        || trustedConfiguration.trustedAttestorKeys().isEmpty()
        || trustedConfiguration.expectedEvidence() == null
        || trustedConfiguration.acceptedAttestationDigest() == null
        || trustedConfiguration.coordOpsRoAclDigest() == null
        || trustedConfiguration.coordRedisConfigObservedDigest() == null) {
      throw new ObservationUnavailableException(
          "Protected current-incarnation source or trust configuration is unavailable");
    }
  }

  private CurrentContext loadCurrentContext(UUID operationId, long targetOrdinal) {
    requireNoAmbientTransaction();
    CanonicalGameplayAccountAdmissionFenceIntent intent =
        intentRepository.readCurrentFenceIntent(operationId);
    if (!operationId.equals(intent.operationId())
        || intent.state()
            != CanonicalGameplayAccountAdmissionFenceIntent.State.ACQUISITION_INTENT) {
      throw new ObservationRejectedException("Protected observation intent is not current");
    }
    List<CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget> matches =
        intent.barrierTargets().stream()
            .filter(target -> target.ordinal() == targetOrdinal)
            .toList();
    if (matches.size() != 1) {
      throw new ObservationRejectedException("Protected observation target is not unique");
    }
    CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget target = matches.get(0);
    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
        tenantTagRepository.assignmentForBarrierTarget(operationId, targetOrdinal);
    if (assignment.isBindingTransition()
        || !operationId.equals(assignment.admissionFenceIntentOperationId())
        || !Long.valueOf(targetOrdinal).equals(assignment.barrierTargetOrdinal())
        || !intent.accountId().equals(assignment.accountId())
        || !target.tenantId().equals(assignment.tenantId())
        || !target.playableStateNamespaceId().equals(assignment.playableStateNamespaceId())) {
      throw new ObservationRejectedException(
          "Protected observation target differs from current SQL and tenant mapping");
    }
    CanonicalGameplaySessionRedisKeys.accountAdmissionBarrier(assignment);
    return new CurrentContext(
        OwnerKind.ACCOUNT_FENCE,
        intent.operationId(),
        intent.requestId(),
        intent.accountId(),
        intent.operationFence().toString(),
        intent.plannedAccountAdmissionFence(),
        intent.inventorySnapshotRevision().toString(),
        intent.cohortDigest(),
        intent.barrierTargetDigest(),
        targetOrdinal,
        target.tenantId(),
        target.playableStateNamespaceId(),
        assignment.tenantGameplayTag(),
        assignment.workloadNamespace(),
        assignment.allocationVersion(),
        null,
        0L,
        null,
        null,
        null,
        null);
  }

  private CurrentContext loadInitialCurrentContext(UUID originalRequestId) {
    requireNoAmbientTransaction();
    CanonicalGameplayAccountInitialBarrierInitializerRepository.InitialObservationContext context =
        initialRepository.readCurrentObservationContext(originalRequestId);
    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
        tenantTagRepository.assignmentForInitialBarrierTarget(originalRequestId);
    CanonicalGameplayAccountInitialBarrierKey.forTarget(
        assignment, context.initializerId(), context.originalRequestId());
    if (!assignment.isInitialBarrierTarget()
        || !context.initializerId().equals(assignment.initialBarrierInitializerId())
        || !context.originalRequestId().equals(assignment.initialBarrierOriginalRequestId())
        || !context.accountId().equals(assignment.accountId())
        || !context.tenantId().equals(assignment.tenantId())
        || !context.playableStateNamespaceId().equals(assignment.playableStateNamespaceId())) {
      throw new ObservationRejectedException(
          "Protected INITIAL observation target differs from current SQL source");
    }
    return new CurrentContext(
        OwnerKind.INITIAL_ACCOUNT_BARRIER,
        context.initializerId(),
        context.originalRequestId(),
        context.accountId(),
        null,
        null,
        context.inventorySnapshotRevision().toString(),
        null,
        context.targetDigest(),
        0L,
        context.tenantId(),
        context.playableStateNamespaceId(),
        assignment.tenantGameplayTag(),
        assignment.workloadNamespace(),
        assignment.allocationVersion(),
        context.originalCallerContextId(),
        context.originalAttemptFence(),
        context.sourceControlState(),
        context.sourceControlEpoch().toString(),
        context.accountLoginEvidenceDigest(),
        context.sourceControlDigest());
  }

  private VerifiedPayload verifyObservation(
      SignedObservation signed, ObservationRequest request, CurrentContext expected) {
    validateObservationBounds(signed);
    byte[] payloadBytes = signed.payloadBytes();
    byte[] signatureBytes = signed.signatureBytes();
    String keyId = signed.keyId();
    if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
      throw new ObservationRejectedException("Protected observation key id is invalid");
    }
    java.security.interfaces.RSAPublicKey publicKey =
        trustedConfiguration.trustedAttestorKeys().get(keyId);
    if (publicKey == null) {
      throw new ObservationRejectedException("Protected observation key is not pinned");
    }
    requireRsaKey(publicKey);
    String payloadText = strictUtf8(payloadBytes);
    JsonNode root;
    byte[] canonical;
    try {
      root = JSON.readTree(payloadText);
      canonical = Rfc8785CanonicalJson.canonicalizeUtf8(payloadText);
    } catch (IOException | RuntimeException invalid) {
      throw new ObservationRejectedException("Protected observation JSON is invalid");
    }
    if (root == null || !root.isObject() || !Arrays.equals(payloadBytes, canonical)) {
      throw new ObservationRejectedException("Protected observation JSON is not canonical");
    }
    verifyDetachedSignature(publicKey, canonical, signatureBytes);

    requireExactFields(root, ROOT_FIELDS);
    String expectedSchema =
        expected.ownerKind() == OwnerKind.ACCOUNT_FENCE
            ? OBSERVATION_SCHEMA
            : INITIAL_OBSERVATION_SCHEMA;
    requireText(root, "schema", expectedSchema);
    requireText(root, "attestorBinding", trustedConfiguration.attestorBinding());
    requireText(root, "attestorKeyId", keyId);
    requireText(root, "acceptedAttestationDigest", request.acceptedAttestationDigest());
    requireText(root, "observationNonce", request.nonce());
    long issuedAt = requiredPositiveLong(root, "observationIssuedAtEpochMillis");
    long observedAt = requiredPositiveLong(root, "observationObservedAtEpochMillis");
    long expiresAt = requiredPositiveLong(root, "observationExpiresAtEpochMillis");
    if (issuedAt != request.issuedAtEpochMillis()
        || expiresAt != request.expiresAtEpochMillis()
        || observedAt < issuedAt
        || observedAt > expiresAt) {
      throw new ObservationRejectedException("Protected observation timing differs from request");
    }

    JsonNode owner = requiredObject(root, "owner");
    if (expected.ownerKind() == OwnerKind.ACCOUNT_FENCE) {
      requireExactFields(owner, OWNER_FIELDS);
      requireText(owner, "operationId", expected.operationId().toString());
      requireText(owner, "requestId", expected.requestId().toString());
      requireText(owner, "accountId", expected.accountId().toString());
      requireText(owner, "operationFence", expected.operationFence());
      requireText(
          owner,
          "plannedAccountAdmissionFence",
          expected.plannedAccountAdmissionFence().toString());
      requireText(owner, "inventorySnapshotRevision", expected.inventorySnapshotRevision());
      requireText(owner, "cohortDigest", expected.cohortDigest());
      requireText(owner, "barrierTargetDigest", expected.barrierTargetDigest());
    } else {
      requireExactFields(owner, INITIAL_OWNER_FIELDS);
      requireText(owner, "ownerKind", OwnerKind.INITIAL_ACCOUNT_BARRIER.name());
      requireText(owner, "initializerId", expected.operationId().toString());
      requireText(owner, "originalRequestId", expected.requestId().toString());
      requireText(owner, "originalCallerContextId", expected.originalCallerContextId().toString());
      if (requiredPositiveLong(owner, "originalAttemptFence") != expected.originalAttemptFence()) {
        throw new ObservationRejectedException("Protected INITIAL attempt fence differs");
      }
      requireText(owner, "accountId", expected.accountId().toString());
      requireText(owner, "sourceControlState", expected.sourceControlState());
      requireText(owner, "sourceControlEpoch", expected.sourceControlEpoch());
      requireText(owner, "inventorySnapshotRevision", expected.inventorySnapshotRevision());
      requireText(owner, "accountLoginEvidenceDigest", expected.accountLoginEvidenceDigest());
      requireText(owner, "sourceControlDigest", expected.sourceControlDigest());
      requireText(owner, "targetDigest", expected.barrierTargetDigest());
    }

    JsonNode target = requiredObject(root, "target");
    requireExactFields(target, TARGET_FIELDS);
    if (requiredNonNegativeLong(target, "targetOrdinal") != expected.targetOrdinal()
        || requiredPositiveLong(target, "allocationVersion") != expected.allocationVersion()) {
      throw new ObservationRejectedException("Protected observation target ordinal differs");
    }
    requireText(target, "tenantId", expected.tenantId().toString());
    requireText(target, "playableStateNamespaceId", expected.playableStateNamespaceId().toString());
    requireText(target, "tenantGameplayTag", expected.tenantGameplayTag());
    requireText(target, "workloadNamespace", expected.workloadNamespace());

    requireEvidence(requiredObject(root, "targetDeploymentEvidence"));
    requireText(root, "coordOpsRoAclDigest", trustedConfiguration.coordOpsRoAclDigest());
    requireText(
        root,
        "coordRedisConfigObservedDigest",
        trustedConfiguration.coordRedisConfigObservedDigest());
    return new VerifiedPayload(request.nonce(), observedAt, expiresAt, digest(payloadBytes));
  }

  private void validateObservationBounds(SignedObservation signed) {
    if (signed == null
        || signed.payloadBytes == null
        || signed.signatureBytes == null
        || signed.payloadBytes.length == 0
        || signed.signatureBytes.length == 0
        || (long) signed.payloadBytes.length + signed.signatureBytes.length
            > trustedConfiguration.maxSignedBytes()) {
      throw new ObservationRejectedException("Protected observation exceeds configured bounds");
    }
  }

  private void requireEvidence(JsonNode evidence) {
    requireExactFields(evidence, EVIDENCE_FIELDS);
    ExpectedTargetDeploymentEvidence expected = trustedConfiguration.expectedEvidence();
    requireText(evidence, "deploymentIdentity", expected.deploymentIdentity());
    requireText(evidence, "deploymentGeneration", expected.deploymentGeneration());
    requireText(evidence, "redisBuildIdentity", expected.redisBuildIdentity());
    requireText(evidence, "luaScriptRegistryVersion", expected.luaScriptRegistryVersion());
    requireText(evidence, "luaScriptRegistryDigest", expected.luaScriptRegistryDigest());
    requireText(evidence, "startupState", expected.startupState());
    requireText(evidence, "bootGeneration", expected.bootGeneration());
    requireText(evidence, "startupGeneration", expected.startupGeneration());
    requireText(evidence, "storageGeneration", expected.storageGeneration());
    requireText(evidence, "aofGeneration", expected.aofGeneration());
    JsonNode nodes = evidence.get("canonicalNodeSet");
    if (nodes == null || !nodes.isArray() || nodes.size() > trustedConfiguration.maxNodes()) {
      throw new ObservationRejectedException("Protected observation node set exceeds bounds");
    }
    List<String> observedNodes = new ArrayList<>();
    for (JsonNode node : nodes) {
      if (!node.isTextual() || node.textValue().isBlank()) {
        throw new ObservationRejectedException("Protected observation node set is malformed");
      }
      observedNodes.add(node.textValue());
    }
    if (observedNodes.isEmpty()
        || new HashSet<>(observedNodes).size() != observedNodes.size()
        || !isCanonicalNodeOrder(observedNodes)
        || !expected.canonicalNodeSet().equals(observedNodes)) {
      throw new ObservationRejectedException("Protected observation node set differs");
    }
  }

  private static boolean isCanonicalNodeOrder(List<String> nodes) {
    List<String> sorted = new ArrayList<>(nodes);
    Collections.sort(sorted);
    return sorted.equals(nodes);
  }

  private void requireNoAmbientTransaction() {
    if (initialRepository != null) {
      initialRepository.requireNoAmbientTransaction();
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Protected observation requires bounded SQL and remote phases");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requireRsaKey(java.security.interfaces.RSAPublicKey key) {
    if (!"RSA".equalsIgnoreCase(key.getAlgorithm()) || key.getModulus().bitLength() < 2048) {
      throw new ObservationRejectedException("Pinned attestor key is not RSA-2048 or stronger");
    }
  }

  private static void verifyDetachedSignature(
      java.security.interfaces.RSAPublicKey key, byte[] payload, byte[] signatureBytes) {
    try {
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);
      verifier.update(payload);
      if (!verifier.verify(signatureBytes)) {
        throw new ObservationRejectedException("Protected observation signature is invalid");
      }
    } catch (ObservationRejectedException rejected) {
      throw rejected;
    } catch (Exception invalid) {
      throw new ObservationRejectedException("Protected observation signature is invalid");
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
    } catch (CharacterCodingException invalid) {
      throw new ObservationRejectedException("Protected observation is not valid UTF-8");
    }
  }

  private static JsonNode requiredObject(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isObject()) {
      throw new ObservationRejectedException("Protected observation object is missing");
    }
    return value;
  }

  private static void requireExactFields(JsonNode object, Set<String> expected) {
    Set<String> actual = new HashSet<>();
    object.propertyNames().forEach(actual::add);
    if (!actual.equals(expected)) {
      throw new ObservationRejectedException("Protected observation fields are not exact");
    }
  }

  private static void requireText(JsonNode object, String field, String expected) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual() || !expected.equals(value.textValue())) {
      throw new ObservationRejectedException("Protected observation context differs");
    }
  }

  private static long requiredPositiveLong(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() <= 0L) {
      throw new ObservationRejectedException("Protected observation integer is invalid");
    }
    return value.longValue();
  }

  private static long requiredNonNegativeLong(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0L) {
      throw new ObservationRejectedException("Protected observation integer is invalid");
    }
    return value.longValue();
  }

  private static String digest(byte[] payload) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String incarnationDigest(TrustedConfiguration configuration) {
    ExpectedTargetDeploymentEvidence evidence = configuration.expectedEvidence();
    Map<String, Object> targetEvidence = new LinkedHashMap<>();
    targetEvidence.put("deploymentIdentity", evidence.deploymentIdentity());
    targetEvidence.put("deploymentGeneration", evidence.deploymentGeneration());
    targetEvidence.put("canonicalNodeSet", evidence.canonicalNodeSet());
    targetEvidence.put("redisBuildIdentity", evidence.redisBuildIdentity());
    targetEvidence.put("luaScriptRegistryVersion", evidence.luaScriptRegistryVersion());
    targetEvidence.put("luaScriptRegistryDigest", evidence.luaScriptRegistryDigest());
    targetEvidence.put("startupState", evidence.startupState());
    targetEvidence.put("bootGeneration", evidence.bootGeneration());
    targetEvidence.put("startupGeneration", evidence.startupGeneration());
    targetEvidence.put("storageGeneration", evidence.storageGeneration());
    targetEvidence.put("aofGeneration", evidence.aofGeneration());

    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("schema", INCARNATION_BINDING_SCHEMA);
    binding.put("attestorBinding", configuration.attestorBinding());
    binding.put("acceptedAttestationDigest", configuration.acceptedAttestationDigest());
    binding.put("targetDeploymentEvidence", targetEvidence);
    binding.put("coordOpsRoAclDigest", configuration.coordOpsRoAclDigest());
    binding.put("coordRedisConfigObservedDigest", configuration.coordRedisConfigObservedDigest());
    try {
      String json = JSON.writeValueAsString(binding);
      return digest(Rfc8785CanonicalJson.canonicalizeUtf8(json));
    } catch (IOException invalid) {
      throw new IllegalStateException(
          "Could not canonicalize trusted incarnation binding", invalid);
    }
  }

  /** Raw source output only. Constructing this value does not assert signature validity. */
  static final class SignedObservation {
    private final String keyId;
    private final byte[] payloadBytes;
    private final byte[] signatureBytes;

    SignedObservation(String keyId, byte[] payloadBytes, byte[] signatureBytes) {
      this.keyId = keyId;
      this.payloadBytes = payloadBytes;
      this.signatureBytes = signatureBytes;
    }

    String keyId() {
      return keyId;
    }

    byte[] payloadBytes() {
      return payloadBytes == null ? new byte[0] : payloadBytes.clone();
    }

    byte[] signatureBytes() {
      return signatureBytes == null ? new byte[0] : signatureBytes.clone();
    }
  }

  /** Trusted protected source abstraction; implementations return only signed bytes and key id. */
  @FunctionalInterface
  interface ProtectedObservationChannel {
    SignedObservation observe(ObservationRequest request);
  }

  /** Receiver-created request; its nonce and times belong only to this current read. */
  static final class ObservationRequest {
    private final String nonce;
    private final long issuedAtEpochMillis;
    private final long expiresAtEpochMillis;
    private final String attestorBinding;
    private final String acceptedAttestationDigest;
    private final CurrentContext context;
    private final ExpectedTargetDeploymentEvidence expectedEvidence;
    private final String coordOpsRoAclDigest;
    private final String coordRedisConfigObservedDigest;

    private ObservationRequest(
        String nonce,
        long issuedAtEpochMillis,
        long expiresAtEpochMillis,
        String attestorBinding,
        String acceptedAttestationDigest,
        CurrentContext context,
        ExpectedTargetDeploymentEvidence expectedEvidence,
        String coordOpsRoAclDigest,
        String coordRedisConfigObservedDigest) {
      this.nonce = nonce;
      this.issuedAtEpochMillis = issuedAtEpochMillis;
      this.expiresAtEpochMillis = expiresAtEpochMillis;
      this.attestorBinding = attestorBinding;
      this.acceptedAttestationDigest = acceptedAttestationDigest;
      this.context = context;
      this.expectedEvidence = expectedEvidence;
      this.coordOpsRoAclDigest = coordOpsRoAclDigest;
      this.coordRedisConfigObservedDigest = coordRedisConfigObservedDigest;
    }

    String nonce() {
      return nonce;
    }

    long issuedAtEpochMillis() {
      return issuedAtEpochMillis;
    }

    long expiresAtEpochMillis() {
      return expiresAtEpochMillis;
    }

    String attestorBinding() {
      return attestorBinding;
    }

    String acceptedAttestationDigest() {
      return acceptedAttestationDigest;
    }

    CurrentContext context() {
      return context;
    }

    ExpectedTargetDeploymentEvidence expectedEvidence() {
      return expectedEvidence;
    }

    String coordOpsRoAclDigest() {
      return coordOpsRoAclDigest;
    }

    String coordRedisConfigObservedDigest() {
      return coordRedisConfigObservedDigest;
    }
  }

  /** Local immutable, manually configured verifier trust. No production provisioner is provided. */
  static final class TrustedConfiguration {
    private final String attestorBinding;
    private final String acceptedAttestationDigest;
    private final Map<String, java.security.interfaces.RSAPublicKey> trustedAttestorKeys;
    private final ExpectedTargetDeploymentEvidence expectedEvidence;
    private final String coordOpsRoAclDigest;
    private final String coordRedisConfigObservedDigest;
    private final int maxSignedBytes;
    private final int maxNodes;
    private final long observationTtlMillis;

    TrustedConfiguration(
        String attestorBinding,
        String acceptedAttestationDigest,
        Map<String, java.security.interfaces.RSAPublicKey> trustedAttestorKeys,
        ExpectedTargetDeploymentEvidence expectedEvidence,
        String coordOpsRoAclDigest,
        String coordRedisConfigObservedDigest,
        int maxSignedBytes,
        int maxNodes,
        Duration observationTtl) {
      this.attestorBinding = attestorBinding;
      this.acceptedAttestationDigest = acceptedAttestationDigest;
      this.trustedAttestorKeys =
          trustedAttestorKeys == null
              ? Map.of()
              : Collections.unmodifiableMap(new LinkedHashMap<>(trustedAttestorKeys));
      this.expectedEvidence = expectedEvidence;
      this.coordOpsRoAclDigest = coordOpsRoAclDigest;
      this.coordRedisConfigObservedDigest = coordRedisConfigObservedDigest;
      if (attestorBinding == null
          || attestorBinding.isBlank()
          || acceptedAttestationDigest == null
          || expectedEvidence == null
          || coordOpsRoAclDigest == null
          || coordRedisConfigObservedDigest == null) {
        throw new ObservationUnavailableException(
            "Protected observation trust binding is unavailable");
      }
      if (!DIGEST.matcher(acceptedAttestationDigest).matches()
          || !DIGEST.matcher(coordOpsRoAclDigest).matches()
          || !DIGEST.matcher(coordRedisConfigObservedDigest).matches()
          || maxSignedBytes <= 0
          || maxNodes <= 0
          || observationTtl == null
          || observationTtl.isNegative()
          || observationTtl.isZero()) {
        throw new IllegalArgumentException("Protected observation trust configuration is invalid");
      }
      try {
        this.observationTtlMillis = observationTtl.toMillis();
      } catch (ArithmeticException overflow) {
        throw new IllegalArgumentException("Observation TTL is outside finite millisecond bounds");
      }
      if (this.observationTtlMillis <= 0) {
        throw new IllegalArgumentException("Observation TTL must be positive in milliseconds");
      }
      for (Map.Entry<String, java.security.interfaces.RSAPublicKey> entry :
          this.trustedAttestorKeys.entrySet()) {
        if (entry.getKey() == null || !KEY_ID.matcher(entry.getKey()).matches()) {
          throw new IllegalArgumentException("Pinned attestor key id is invalid");
        }
        if (entry.getValue() == null) {
          throw new IllegalArgumentException("Pinned attestor public key is missing");
        }
        requireRsaKey(entry.getValue());
      }
      if (expectedEvidence.canonicalNodeSet().size() > maxNodes) {
        throw new IllegalArgumentException("Expected node set exceeds configured local bound");
      }
      this.maxSignedBytes = maxSignedBytes;
      this.maxNodes = maxNodes;
    }

    String attestorBinding() {
      return attestorBinding;
    }

    String acceptedAttestationDigest() {
      return acceptedAttestationDigest;
    }

    Map<String, java.security.interfaces.RSAPublicKey> trustedAttestorKeys() {
      return trustedAttestorKeys;
    }

    ExpectedTargetDeploymentEvidence expectedEvidence() {
      return expectedEvidence;
    }

    String coordOpsRoAclDigest() {
      return coordOpsRoAclDigest;
    }

    String coordRedisConfigObservedDigest() {
      return coordRedisConfigObservedDigest;
    }

    int maxSignedBytes() {
      return maxSignedBytes;
    }

    int maxNodes() {
      return maxNodes;
    }

    long observationTtlMillis() {
      return observationTtlMillis;
    }
  }

  /** Exact independently configured target binding; never learned from the response. */
  record ExpectedTargetDeploymentEvidence(
      String deploymentIdentity,
      String deploymentGeneration,
      List<String> canonicalNodeSet,
      String redisBuildIdentity,
      String luaScriptRegistryVersion,
      String luaScriptRegistryDigest,
      String startupState,
      String bootGeneration,
      String startupGeneration,
      String storageGeneration,
      String aofGeneration) {
    ExpectedTargetDeploymentEvidence {
      requireConfiguredText(deploymentIdentity, "deploymentIdentity");
      requireConfiguredText(deploymentGeneration, "deploymentGeneration");
      canonicalNodeSet = List.copyOf(Objects.requireNonNull(canonicalNodeSet, "canonicalNodeSet"));
      requireConfiguredText(redisBuildIdentity, "redisBuildIdentity");
      requireConfiguredText(luaScriptRegistryVersion, "luaScriptRegistryVersion");
      requireDigestValue(luaScriptRegistryDigest, "luaScriptRegistryDigest");
      requireConfiguredText(startupState, "startupState");
      requireConfiguredText(bootGeneration, "bootGeneration");
      requireConfiguredText(startupGeneration, "startupGeneration");
      requireConfiguredText(storageGeneration, "storageGeneration");
      requireConfiguredText(aofGeneration, "aofGeneration");
      if (canonicalNodeSet.isEmpty()
          || canonicalNodeSet.stream().anyMatch(node -> node == null || node.isBlank())
          || new HashSet<>(canonicalNodeSet).size() != canonicalNodeSet.size()
          || !isCanonicalNodeOrder(canonicalNodeSet)) {
        throw new IllegalArgumentException("Expected node set must be nonempty and canonical");
      }
    }
  }

  private static void requireConfiguredText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required in trusted configuration");
    }
  }

  private static void requireDigestValue(String value, String field) {
    if (!DIGEST.matcher(value == null ? "" : value).matches()) {
      throw new IllegalArgumentException(field + " must be canonical SHA-256");
    }
  }

  enum OwnerKind {
    ACCOUNT_FENCE,
    INITIAL_ACCOUNT_BARRIER
  }

  /** Discriminated current owner and exact target tuple obtained from the actual SQL owners. */
  record CurrentContext(
      OwnerKind ownerKind,
      UUID operationId,
      UUID requestId,
      UUID accountId,
      String operationFence,
      UUID plannedAccountAdmissionFence,
      String inventorySnapshotRevision,
      String cohortDigest,
      String barrierTargetDigest,
      long targetOrdinal,
      UUID tenantId,
      UUID playableStateNamespaceId,
      String tenantGameplayTag,
      String workloadNamespace,
      int allocationVersion,
      UUID originalCallerContextId,
      long originalAttemptFence,
      String sourceControlState,
      String sourceControlEpoch,
      String accountLoginEvidenceDigest,
      String sourceControlDigest) {
    CurrentContext {
      Objects.requireNonNull(ownerKind, "ownerKind");
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(tenantGameplayTag, "tenantGameplayTag");
      Objects.requireNonNull(workloadNamespace, "workloadNamespace");
      if (ownerKind == OwnerKind.ACCOUNT_FENCE) {
        Objects.requireNonNull(plannedAccountAdmissionFence, "plannedAccountAdmissionFence");
        Objects.requireNonNull(operationFence, "operationFence");
        Objects.requireNonNull(cohortDigest, "cohortDigest");
        Objects.requireNonNull(barrierTargetDigest, "barrierTargetDigest");
      } else {
        Objects.requireNonNull(originalCallerContextId, "originalCallerContextId");
        Objects.requireNonNull(sourceControlState, "sourceControlState");
        Objects.requireNonNull(accountLoginEvidenceDigest, "accountLoginEvidenceDigest");
        Objects.requireNonNull(sourceControlDigest, "sourceControlDigest");
        if (originalAttemptFence <= 0L
            || operationFence != null
            || plannedAccountAdmissionFence != null
            || !"NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(sourceControlState)
            || sourceControlEpoch == null
            || !sourceControlEpoch.matches("0|[1-9][0-9]{0,255}")) {
          throw new IllegalArgumentException(
              "INITIAL owner context is not the positively read NO_ACTIVE arm");
        }
      }
    }
  }

  private record VerifiedPayload(
      String observationNonce,
      long observedAtEpochMillis,
      long expiresAtEpochMillis,
      String payloadDigest) {}

  /** Opaque exact-context evidence produced only after signature and two current-owner reads. */
  static final class VerifiedIncarnation {
    private final CurrentContext context;
    private final String acceptedAttestationDigest;
    private final ExpectedTargetDeploymentEvidence evidence;
    private final String observationNonce;
    private final long issuedAtEpochMillis;
    private final long observedAtEpochMillis;
    private final long expiresAtEpochMillis;
    private final String payloadDigest;
    private final String incarnationDigest;

    private VerifiedIncarnation(
        CurrentContext context,
        String acceptedAttestationDigest,
        ExpectedTargetDeploymentEvidence evidence,
        String observationNonce,
        long issuedAtEpochMillis,
        long observedAtEpochMillis,
        long expiresAtEpochMillis,
        String payloadDigest,
        String incarnationDigest) {
      this.context = Objects.requireNonNull(context);
      this.acceptedAttestationDigest = Objects.requireNonNull(acceptedAttestationDigest);
      this.evidence = Objects.requireNonNull(evidence);
      this.observationNonce = Objects.requireNonNull(observationNonce);
      this.issuedAtEpochMillis = issuedAtEpochMillis;
      this.observedAtEpochMillis = observedAtEpochMillis;
      this.expiresAtEpochMillis = expiresAtEpochMillis;
      this.payloadDigest = Objects.requireNonNull(payloadDigest);
      this.incarnationDigest = Objects.requireNonNull(incarnationDigest);
    }

    UUID operationId() {
      return context.operationId();
    }

    OwnerKind ownerKind() {
      return context.ownerKind();
    }

    UUID requestId() {
      return context.requestId();
    }

    UUID originalCallerContextId() {
      return context.originalCallerContextId();
    }

    long originalAttemptFence() {
      return context.originalAttemptFence();
    }

    String sourceControlState() {
      return context.sourceControlState();
    }

    String sourceControlEpoch() {
      return context.sourceControlEpoch();
    }

    String inventorySnapshotRevision() {
      return context.inventorySnapshotRevision();
    }

    String accountLoginEvidenceDigest() {
      return context.accountLoginEvidenceDigest();
    }

    String sourceControlDigest() {
      return context.sourceControlDigest();
    }

    long targetOrdinal() {
      return context.targetOrdinal();
    }

    UUID accountId() {
      return context.accountId();
    }

    UUID tenantId() {
      return context.tenantId();
    }

    UUID playableStateNamespaceId() {
      return context.playableStateNamespaceId();
    }

    UUID plannedAccountAdmissionFence() {
      return context.plannedAccountAdmissionFence();
    }

    String cohortDigest() {
      return context.cohortDigest();
    }

    String operationFence() {
      return context.operationFence();
    }

    String barrierTargetDigest() {
      return context.barrierTargetDigest();
    }

    String tenantGameplayTag() {
      return context.tenantGameplayTag();
    }

    String acceptedAttestationDigest() {
      return acceptedAttestationDigest;
    }

    ExpectedTargetDeploymentEvidence evidence() {
      return evidence;
    }

    String observationNonce() {
      return observationNonce;
    }

    long issuedAtEpochMillis() {
      return issuedAtEpochMillis;
    }

    long observedAtEpochMillis() {
      return observedAtEpochMillis;
    }

    long expiresAtEpochMillis() {
      return expiresAtEpochMillis;
    }

    String payloadDigest() {
      return payloadDigest;
    }

    String incarnationDigest() {
      return incarnationDigest;
    }
  }

  static final class ObservationUnavailableException extends RuntimeException {
    ObservationUnavailableException(String message) {
      super(message);
    }
  }

  static final class ObservationRejectedException extends RuntimeException {
    ObservationRejectedException(String message) {
      super(message);
    }
  }
}
