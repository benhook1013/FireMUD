package net.firedevops.firemud.gamesession.repository;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/** Narrow current-source port for the stable Redis deployment incarnation used by region prep. */
@FunctionalInterface
interface CanonicalGameplayRegionIncarnationSource {
  JsonMapper JSON = JsonMapper.builder().build();

  /** Reads current typed deployment evidence for exactly this canonical region key scope. */
  StableEvidence readCurrent(RegionScope scope);

  static CanonicalGameplayRegionIncarnationSource unavailable() {
    return scope -> {
      throw new IllegalStateException("Stable region incarnation source is unavailable");
    };
  }

  record RegionScope(UUID tenantId, UUID gameInstanceId, UUID regionId, String tenantRegionTag) {
    public RegionScope {
      requireNonNil(tenantId);
      requireNonNil(gameInstanceId);
      requireNonNil(regionId);
      String derived =
          CanonicalGameplayRegionRedisKeys.tenantRegionTag(tenantId, gameInstanceId, regionId);
      if (!derived.equals(tenantRegionTag)) throw unavailableException();
    }

    static RegionScope from(CanonicalGameplayRegionRuntimeStatusRepository.Source source) {
      Objects.requireNonNull(source, "source");
      return new RegionScope(
          source.tenantId(),
          source.gameInstanceId(),
          source.regionId(),
          CanonicalGameplayRegionRedisKeys.tenantRegionTag(
              source.tenantId(), source.gameInstanceId(), source.regionId()));
    }

    byte[] canonicalBytes() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("schema", "firemud.gs.region-incarnation-scope/v1");
      value.put("tenantId", tenantId.toString());
      value.put("gameInstanceId", gameInstanceId.toString());
      value.put("regionId", regionId.toString());
      value.put("tenantRegionTag", tenantRegionTag);
      byte[] bytes = CanonicalGameplayRegionIncarnationSource.canonicalBytes(value);
      if (bytes.length > 4096) throw unavailableException();
      return bytes;
    }
  }

  /**
   * Complete stable incarnation value. This carries the established deployment evidence shape; it
   * does not verify a source, create a production provider, or reuse Account intent context.
   */
  record StableEvidence(
      RegionScope scope,
      String attestorBinding,
      String acceptedAttestationDigest,
      CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
          targetDeploymentEvidence,
      String coordOpsRoAclDigest,
      String coordRedisConfigObservedDigest,
      boolean qualifiedNonResetProfile,
      TimelineInitializationQualification timelineInitializationQualification) {
    public StableEvidence {
      Objects.requireNonNull(scope, "scope");
      requireText(attestorBinding);
      requireDigest(acceptedAttestationDigest);
      Objects.requireNonNull(targetDeploymentEvidence, "targetDeploymentEvidence");
      requireDigest(coordOpsRoAclDigest);
      requireDigest(coordRedisConfigObservedDigest);
      if (!qualifiedNonResetProfile) throw unavailableException();
      Objects.requireNonNull(
          timelineInitializationQualification, "timelineInitializationQualification");
      if (targetDeploymentEvidence.canonicalNodeSet().size() > 2048
          || scope.canonicalBytes().length > 4096
          || stableValueBytes(
                      scope,
                      attestorBinding,
                      acceptedAttestationDigest,
                      targetDeploymentEvidence,
                      coordOpsRoAclDigest,
                      coordRedisConfigObservedDigest)
                  .length
              > 65536
          || timelineInitializationQualification.canonicalBytes().length > 8192
          || !timelineInitializationQualification.matches(
              scope,
              stableDigest(
                  scope,
                  attestorBinding,
                  acceptedAttestationDigest,
                  targetDeploymentEvidence,
                  coordOpsRoAclDigest,
                  coordRedisConfigObservedDigest))) {
        throw unavailableException();
      }
      if (timelineInitializationQualification.qualifiedNonResetProfile()
          != qualifiedNonResetProfile) {
        throw unavailableException();
      }
    }

    static StableEvidence fromCurrentSource(
        RegionScope scope,
        String attestorBinding,
        String acceptedAttestationDigest,
        CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
            targetDeploymentEvidence,
        String coordOpsRoAclDigest,
        String coordRedisConfigObservedDigest,
        boolean qualifiedNonResetProfile,
        String qualifiedNonResetProfileId,
        String qualifiedNonResetProfileEvidenceDigest,
        boolean recoveryInactive,
        String recoveryInactiveEvidenceDigest,
        boolean noUnfinishedPriorWork,
        String noUnfinishedPriorWorkEvidenceDigest) {
      String stableDigest =
          CanonicalGameplayRegionIncarnationSource.stableDigest(
              scope,
              attestorBinding,
              acceptedAttestationDigest,
              targetDeploymentEvidence,
              coordOpsRoAclDigest,
              coordRedisConfigObservedDigest);
      return new StableEvidence(
          scope,
          attestorBinding,
          acceptedAttestationDigest,
          targetDeploymentEvidence,
          coordOpsRoAclDigest,
          coordRedisConfigObservedDigest,
          qualifiedNonResetProfile,
          new TimelineInitializationQualification(
              scope,
              stableDigest,
              qualifiedNonResetProfile,
              qualifiedNonResetProfileId,
              qualifiedNonResetProfileEvidenceDigest,
              recoveryInactive,
              recoveryInactiveEvidenceDigest,
              noUnfinishedPriorWork,
              noUnfinishedPriorWorkEvidenceDigest));
    }

    byte[] scopeBytes() {
      return scope.canonicalBytes();
    }

    byte[] stableCanonicalBytes() {
      return CanonicalGameplayRegionIncarnationSource.canonicalBytes(
          stableValue(
              scope,
              attestorBinding,
              acceptedAttestationDigest,
              targetDeploymentEvidence,
              coordOpsRoAclDigest,
              coordRedisConfigObservedDigest));
    }

    byte[] canonicalBytes() {
      Map<String, Object> value =
          stableValue(
              scope,
              attestorBinding,
              acceptedAttestationDigest,
              targetDeploymentEvidence,
              coordOpsRoAclDigest,
              coordRedisConfigObservedDigest);
      value.put("qualifiedNonResetProfile", qualifiedNonResetProfile);
      value.put("timelineInitializationQualification", timelineInitializationQualification.toMap());
      byte[] bytes = CanonicalGameplayRegionIncarnationSource.canonicalBytes(value);
      if (bytes.length > 65536) throw unavailableException();
      return bytes;
    }

    String digest() {
      return digestBytes(stableCanonicalBytes());
    }
  }

  /**
   * Positive exact-scope evidence for all canonical first-metadata initialization prerequisites.
   */
  record TimelineInitializationQualification(
      RegionScope scope,
      String stableIncarnationDigest,
      boolean qualifiedNonResetProfile,
      String qualifiedNonResetProfileId,
      String qualifiedNonResetProfileEvidenceDigest,
      boolean recoveryInactive,
      String recoveryInactiveEvidenceDigest,
      boolean noUnfinishedPriorWork,
      String noUnfinishedPriorWorkEvidenceDigest) {
    public TimelineInitializationQualification {
      Objects.requireNonNull(scope, "scope");
      requireDigest(stableIncarnationDigest);
      if (!qualifiedNonResetProfile || !recoveryInactive || !noUnfinishedPriorWork) {
        throw unavailableException();
      }
      requireText(qualifiedNonResetProfileId);
      requireDigest(qualifiedNonResetProfileEvidenceDigest);
      requireDigest(recoveryInactiveEvidenceDigest);
      requireDigest(noUnfinishedPriorWorkEvidenceDigest);
    }

    boolean matches(RegionScope expectedScope, String expectedStableIncarnationDigest) {
      return scope.equals(expectedScope)
          && stableIncarnationDigest.equals(expectedStableIncarnationDigest)
          && qualifiedNonResetProfile
          && recoveryInactive
          && noUnfinishedPriorWork;
    }

    byte[] canonicalBytes() {
      return CanonicalGameplayRegionIncarnationSource.canonicalBytes(toMap());
    }

    private Map<String, Object> toMap() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("scope", scopeMap(scope));
      value.put("stableIncarnationDigest", stableIncarnationDigest);
      value.put("qualifiedNonResetProfile", qualifiedNonResetProfile);
      value.put("qualifiedNonResetProfileId", qualifiedNonResetProfileId);
      value.put("qualifiedNonResetProfileEvidenceDigest", qualifiedNonResetProfileEvidenceDigest);
      value.put("recoveryInactive", recoveryInactive);
      value.put("recoveryInactiveEvidenceDigest", recoveryInactiveEvidenceDigest);
      value.put("noUnfinishedPriorWork", noUnfinishedPriorWork);
      value.put("noUnfinishedPriorWorkEvidenceDigest", noUnfinishedPriorWorkEvidenceDigest);
      return value;
    }
  }

  private static String stableDigest(
      RegionScope scope,
      String attestorBinding,
      String acceptedAttestationDigest,
      CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence evidence,
      String coordOpsRoAclDigest,
      String coordRedisConfigObservedDigest) {
    return digestBytes(
        stableValueBytes(
            scope,
            attestorBinding,
            acceptedAttestationDigest,
            evidence,
            coordOpsRoAclDigest,
            coordRedisConfigObservedDigest));
  }

  private static byte[] stableValueBytes(
      RegionScope scope,
      String attestorBinding,
      String acceptedAttestationDigest,
      CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence evidence,
      String coordOpsRoAclDigest,
      String coordRedisConfigObservedDigest) {
    return canonicalBytes(
        stableValue(
            scope,
            attestorBinding,
            acceptedAttestationDigest,
            evidence,
            coordOpsRoAclDigest,
            coordRedisConfigObservedDigest));
  }

  private static Map<String, Object> stableValue(
      RegionScope scope,
      String attestorBinding,
      String acceptedAttestationDigest,
      CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence evidence,
      String coordOpsRoAclDigest,
      String coordRedisConfigObservedDigest) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", "firemud.gs.region-stable-incarnation/v1");
    value.put("scope", scopeMap(scope));
    value.put("attestorBinding", attestorBinding);
    value.put("acceptedAttestationDigest", acceptedAttestationDigest);
    value.put("targetDeploymentEvidence", targetDeploymentEvidenceMap(evidence));
    value.put("coordOpsRoAclDigest", coordOpsRoAclDigest);
    value.put("coordRedisConfigObservedDigest", coordRedisConfigObservedDigest);
    return value;
  }

  private static Map<String, Object> scopeMap(RegionScope scope) {
    return Map.of(
        "tenantId", scope.tenantId().toString(),
        "gameInstanceId", scope.gameInstanceId().toString(),
        "regionId", scope.regionId().toString(),
        "tenantRegionTag", scope.tenantRegionTag());
  }

  private static Map<String, Object> targetDeploymentEvidenceMap(
      CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence evidence) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("deploymentIdentity", evidence.deploymentIdentity());
    value.put("deploymentGeneration", evidence.deploymentGeneration());
    value.put("canonicalNodeSet", evidence.canonicalNodeSet());
    value.put("redisBuildIdentity", evidence.redisBuildIdentity());
    value.put("luaScriptRegistryVersion", evidence.luaScriptRegistryVersion());
    value.put("luaScriptRegistryDigest", evidence.luaScriptRegistryDigest());
    value.put("startupState", evidence.startupState());
    value.put("bootGeneration", evidence.bootGeneration());
    value.put("startupGeneration", evidence.startupGeneration());
    value.put("storageGeneration", evidence.storageGeneration());
    value.put("aofGeneration", evidence.aofGeneration());
    return value;
  }

  private static byte[] canonicalBytes(Map<String, Object> value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw unavailableException();
    }
  }

  static String digestBytes(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception impossible) {
      throw unavailableException();
    }
  }

  private static void requireText(String value) {
    if (value == null || value.isBlank() || !value.equals(value.trim()))
      throw unavailableException();
  }

  private static void requireDigest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) throw unavailableException();
  }

  private static void requireNonNil(UUID value) {
    Objects.requireNonNull(value, "scope UUID");
    if (value.equals(new UUID(0L, 0L))) throw unavailableException();
  }

  private static IllegalStateException unavailableException() {
    return new IllegalStateException("Stable region incarnation source is unavailable");
  }
}
