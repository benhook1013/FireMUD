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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controlled transfer-purpose signer custody only. Actual FROZEN or sealed-COMPLETED context and
 * mapping must come from the SQL owners and are independently rechecked by the real receiver.
 * COMPLETED observations supply read-only currentness, never mutation authority. This fixture
 * supplies no transfer outcome, Account settlement, deployment custody or reset proof.
 */
final class CanonicalGameplayControlledOriginTransferObservationFixture {
  private static final String KEY_ID = "controlled-region-transfer-attestor";
  private static final String BINDING = "controlled-region-transfer-attestor-binding";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final KeyPair keys;
  private final Clock clock;
  private final Duration observationLatency;
  private final AtomicInteger observationCount = new AtomicInteger();

  CanonicalGameplayControlledOriginTransferObservationFixture() throws Exception {
    this(Clock.systemUTC(), Duration.ZERO);
  }

  CanonicalGameplayControlledOriginTransferObservationFixture(Clock clock) throws Exception {
    this(clock, Duration.ZERO);
  }

  CanonicalGameplayControlledOriginTransferObservationFixture(
      Clock clock, Duration observationLatency) throws Exception {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.observationLatency = Objects.requireNonNull(observationLatency, "observationLatency");
    if (observationLatency.isNegative()) throw new IllegalArgumentException();
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
  }

  int observationCount() {
    return observationCount.get();
  }

  CanonicalGameplayProtectedIncarnationReceiver receiver(
      CanonicalGameplayCapacityOriginTransferRepository source,
      CanonicalGameplayTenantGameplayTagRepository tags) {
    return receiver(source, tags, "storage-1");
  }

  CanonicalGameplayProtectedIncarnationReceiver receiver(
      CanonicalGameplayCapacityOriginTransferRepository source,
      CanonicalGameplayTenantGameplayTagRepository tags,
      String storageGeneration) {
    var expected =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-transfer-target",
            "generation-1",
            List.of("redis-node-a"),
            "controlled-redis-build",
            "controlled-registry-v1",
            "sha256:" + "d".repeat(64),
            "READY",
            "boot-1",
            "startup-1",
            storageGeneration,
            "aof-1");
    var trust =
        new CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration(
            BINDING,
            "sha256:" + "a".repeat(64),
            Map.of(KEY_ID, (RSAPublicKey) keys.getPublic()),
            expected,
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64),
            16 * 1024,
            1,
            Duration.ofSeconds(30));
    return CanonicalGameplayProtectedIncarnationReceiver.forOriginOwnerTransfer(
        source, tags, this::observe, trust, clock);
  }

  private CanonicalGameplayProtectedIncarnationReceiver.SignedObservation observe(
      CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
    var context = request.context();
    if (context.ownerKind()
        != CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.ORIGIN_OWNER_TRANSFER) {
      throw new IllegalArgumentException("Controlled channel is limited to transfer observations");
    }
    long observedAt = request.issuedAtEpochMillis();
    if (!observationLatency.isZero()) {
      try {
        Thread.sleep(observationLatency);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Controlled origin observation interrupted", interrupted);
      }
      observedAt = clock.millis();
    }
    observationCount.incrementAndGet();
    var transfer = context.originTransferContext();
    Map<String, Object> owner = new LinkedHashMap<>();
    owner.put("ownerKind", context.ownerKind().name());
    owner.put("transferId", transfer.transferId().toString());
    owner.put("transferIntentDigest", transfer.transferIntentDigest());
    owner.put("frozenSourceDigest", transfer.frozenSourceDigest());
    owner.put("oldRegionOwnerId", transfer.oldRegionOwnerId());
    owner.put("oldRegionOwnerFence", transfer.oldRegionOwnerFence());
    owner.put("gameInstanceId", transfer.gameInstanceId().toString());
    owner.put("regionId", transfer.regionId().toString());
    owner.put("regionEpoch", transfer.regionEpoch());
    owner.put("transitionId", transfer.transitionId().toString());
    owner.put("requestId", transfer.requestId().toString());
    owner.put("accountId", transfer.accountId().toString());
    owner.put("originDigest", transfer.originDigest());
    owner.put("state", transfer.state().name());
    owner.put("newOwner", transfer.newOwner());
    owner.put("newFence", transfer.newFence());
    owner.put("newLease", transfer.newLease());
    owner.put("candidateDigest", transfer.candidateDigest());
    owner.put("controlState", transfer.controlState());
    owner.put("completionDigest", transfer.completionDigest());
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
    root.put("schema", "gs-original-new-origin-owner-transfer-observation/v1");
    root.put("attestorBinding", request.attestorBinding());
    root.put("attestorKeyId", KEY_ID);
    root.put("acceptedAttestationDigest", request.acceptedAttestationDigest());
    root.put("observationNonce", request.nonce());
    root.put("observationIssuedAtEpochMillis", request.issuedAtEpochMillis());
    root.put("observationObservedAtEpochMillis", observedAt);
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
      throw new IllegalStateException("Controlled transfer observation signing failed", failure);
    }
  }
}
