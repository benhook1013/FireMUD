package net.firedevops.firemud.gamesession.repository;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controlled INITIAL attestor key/channel only. The receiver independently supplies and rechecks
 * actual SQL context. This fixture supplies no LOGIN result, journal row, grant, or acknowledgement,
 * and is not operational deployment/incarnation custody.
 */
final class CanonicalGameplayControlledInitialObservationFixture {
  private static final String KEY_ID = "controlled-original-final-attestor";
  private static final String BINDING = "controlled-original-final-attestor-binding";
  private static final String ACCEPTED = "sha256:" + "a".repeat(64);
  private static final String ACL = "sha256:" + "b".repeat(64);
  private static final String CONFIG = "sha256:" + "c".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final KeyPair keys;

  CanonicalGameplayControlledInitialObservationFixture() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
  }

  CanonicalGameplayProtectedIncarnationReceiver receiver(
      CanonicalGameplayAccountInitialBarrierInitializerRepository repository,
      CanonicalGameplayTenantGameplayTagRepository tags) {
    var expected = new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
        "controlled-final-target", "generation-1", List.of("redis-node-a"),
        "controlled-redis-build", "controlled-registry-v1", "sha256:" + "d".repeat(64),
        "READY", "boot-1", "startup-1", "storage-1", "aof-1");
    var configuration = new CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration(
        BINDING, ACCEPTED, Map.of(KEY_ID, (RSAPublicKey) keys.getPublic()), expected,
        ACL, CONFIG, 16 * 1024, 1, Duration.ofSeconds(30));
    return new CanonicalGameplayProtectedIncarnationReceiver(
        repository, tags, this::observe, configuration, Clock.systemUTC());
  }

  private CanonicalGameplayProtectedIncarnationReceiver.SignedObservation observe(
      CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
    var context = request.context();
    if (context.ownerKind()
        != CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER) {
      throw new IllegalArgumentException("Controlled channel is limited to INITIAL observations");
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
    } catch (java.security.GeneralSecurityException | java.io.IOException failure) {
      throw new IllegalStateException("Controlled INITIAL observation signing failed", failure);
    }
  }
}
