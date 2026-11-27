package net.firedevops.firemud.gamesession.repository;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pre-v1 gate waiver (ADR 0185): an in-process attestor for INITIAL account-barrier observations.
 * It holds a per-process RSA key and signs the receiver's own current SQL context with the
 * Coordination incarnation derived from the Redis {@code run_id}. It stands in for the protected
 * operational attestor, owed before v1, and observes nothing outside this process. Other owner
 * kinds are refused.
 */
final class PreV1LocalIncarnationAttestor
    implements CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel {
  static final String KEY_ID = "pre-v1-local-attestor";
  private static final int MAX_SIGNED_BYTES = 16 * 1024;
  private static final Duration OBSERVATION_TTL = Duration.ofSeconds(30);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Supplier<String> redisRunId;
  private final KeyPair keys;

  PreV1LocalIncarnationAttestor(Supplier<String> redisRunId) {
    this.redisRunId = Objects.requireNonNull(redisRunId, "redisRunId");
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      keys = generator.generateKeyPair();
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("Pre-v1 attestor key is unavailable", unavailable);
    }
  }

  /** Pins this process's key to the incarnation of the Redis {@code run_id} read now. */
  CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration trustedConfiguration() {
    var incarnation = PreV1CoordinationIncarnation.forRedisRunId(redisRunId.get());
    return new CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration(
        PreV1CoordinationIncarnation.ATTESTOR_BINDING,
        incarnation.acceptedAttestationDigest(),
        Map.of(KEY_ID, (RSAPublicKey) keys.getPublic()),
        incarnation.deployment(),
        incarnation.coordOpsRoAclDigest(),
        incarnation.coordRedisConfigObservedDigest(),
        MAX_SIGNED_BYTES,
        1,
        OBSERVATION_TTL);
  }

  /** The actual receiver, verifying this attestor's signatures for INITIAL observations. */
  CanonicalGameplayProtectedIncarnationReceiver initialBarrierReceiver(
      CanonicalGameplayAccountInitialBarrierInitializerRepository repository,
      CanonicalGameplayTenantGameplayTagRepository tags,
      Clock clock) {
    return new CanonicalGameplayProtectedIncarnationReceiver(
        repository, tags, this, trustedConfiguration(), clock);
  }

  @Override
  public CanonicalGameplayProtectedIncarnationReceiver.SignedObservation observe(
      CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
    var context = request.context();
    if (context.ownerKind()
        != CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER) {
      throw new IllegalArgumentException("Pre-v1 attestor is limited to INITIAL observations");
    }
    Map<String, Object> owner = new LinkedHashMap<>();
    owner.put("ownerKind", context.ownerKind().name());
    owner.put("initializerId", context.operationId().toString());
    owner.put("originalRequestId", context.requestId().toString());
    owner.put("originalCallerContextId", context.originalCallerContextId().toString());
    owner.put("originalAttemptFence", context.originalAttemptFence());
    owner.put("accountId", context.accountId().toString());
    owner.put("sourceControlState", context.sourceControlState());
    owner.put("sourceControlEpoch", context.sourceControlEpoch());
    owner.put("inventorySnapshotRevision", context.inventorySnapshotRevision());
    owner.put("accountLoginEvidenceDigest", context.accountLoginEvidenceDigest());
    owner.put("sourceControlDigest", context.sourceControlDigest());
    owner.put("targetDigest", context.barrierTargetDigest());
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetOrdinal", context.targetOrdinal());
    target.put("tenantId", context.tenantId().toString());
    target.put("playableStateNamespaceId", context.playableStateNamespaceId().toString());
    target.put("tenantGameplayTag", context.tenantGameplayTag());
    target.put("workloadNamespace", context.workloadNamespace());
    target.put("allocationVersion", context.allocationVersion());
    var evidence = request.expectedEvidence();
    Map<String, Object> deployment = new LinkedHashMap<>();
    deployment.put("deploymentIdentity", evidence.deploymentIdentity());
    deployment.put("deploymentGeneration", evidence.deploymentGeneration());
    deployment.put("canonicalNodeSet", evidence.canonicalNodeSet());
    deployment.put("redisBuildIdentity", evidence.redisBuildIdentity());
    deployment.put("luaScriptRegistryVersion", evidence.luaScriptRegistryVersion());
    deployment.put("luaScriptRegistryDigest", evidence.luaScriptRegistryDigest());
    deployment.put("startupState", evidence.startupState());
    deployment.put("bootGeneration", evidence.bootGeneration());
    deployment.put("startupGeneration", evidence.startupGeneration());
    deployment.put("storageGeneration", evidence.storageGeneration());
    deployment.put("aofGeneration", evidence.aofGeneration());
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", "firemud.gs.protected-initial-account-barrier-read/v1");
    root.put("attestorBinding", request.attestorBinding());
    root.put("attestorKeyId", KEY_ID);
    root.put("acceptedAttestationDigest", request.acceptedAttestationDigest());
    root.put("observationNonce", request.nonce());
    root.put("observationIssuedAtEpochMillis", request.issuedAtEpochMillis());
    root.put("observationObservedAtEpochMillis", request.issuedAtEpochMillis());
    root.put("observationExpiresAtEpochMillis", request.expiresAtEpochMillis());
    root.put("owner", owner);
    root.put("target", target);
    root.put("targetDeploymentEvidence", deployment);
    root.put("coordOpsRoAclDigest", request.coordOpsRoAclDigest());
    root.put("coordRedisConfigObservedDigest", request.coordRedisConfigObservedDigest());
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(keys.getPrivate());
      signer.update(canonical);
      return new CanonicalGameplayProtectedIncarnationReceiver.SignedObservation(
          KEY_ID, canonical, signer.sign());
    } catch (GeneralSecurityException | IOException failure) {
      throw new IllegalStateException("Pre-v1 INITIAL observation signing failed", failure);
    }
  }
}
