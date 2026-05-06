package net.firedevops.firemud.worldmanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.entity.RoomInstance;
import net.firedevops.firemud.worldmanagement.entity.RoomInstanceExit;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.entity.ZoneInstance;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEventRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.WorldInstanceActivationService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are stored internally for activation flows")
public class WorldInstanceActivationServiceImpl implements WorldInstanceActivationService {
  private static final Logger logger =
      LoggingUtil.getLogger(WorldInstanceActivationServiceImpl.class);

  static final String STATUS_PREPARING = "PREPARING";
  static final String STATUS_ACTIVE = "ACTIVE";
  static final String STATUS_FAILED_PRE_ACTIVATION = "FAILED_PRE_ACTIVATION";
  static final String STATUS_TERMINATING = "TERMINATING";
  static final String STATUS_TERMINATED = "TERMINATED";
  private static final String SUPPORTED_RELEASE_ATTESTATION_SCHEMA_VERSION = "v1";

  private final WorldInstanceRepository worldInstanceRepository;
  private final RegionInstanceRepository regionInstanceRepository;
  private final ZoneRepository zoneRepository;
  private final ZoneInstanceRepository zoneInstanceRepository;
  private final RoomRepository roomRepository;
  private final RoomExitRepository roomExitRepository;
  private final RoomInstanceRepository roomInstanceRepository;
  private final RoomInstanceExitRepository roomInstanceExitRepository;
  private final WorldEventRepository worldEventRepository;
  private final WorldProperties worldProperties;
  private final GameDesignClient gameDesignClient;
  private final EntityManagementClient entityManagementClient;
  private final MeterRegistry meterRegistry;

  private Counter prepareCounter;
  private Counter activateCounter;
  private Counter failCounter;

  @Autowired
  public WorldInstanceActivationServiceImpl(
      WorldInstanceRepository worldInstanceRepository,
      RegionInstanceRepository regionInstanceRepository,
      ZoneRepository zoneRepository,
      ZoneInstanceRepository zoneInstanceRepository,
      RoomRepository roomRepository,
      RoomExitRepository roomExitRepository,
      RoomInstanceRepository roomInstanceRepository,
      RoomInstanceExitRepository roomInstanceExitRepository,
      WorldEventRepository worldEventRepository,
      WorldProperties worldProperties,
      GameDesignClient gameDesignClient,
      EntityManagementClient entityManagementClient,
      MeterRegistry meterRegistry) {
    this.worldInstanceRepository = worldInstanceRepository;
    this.regionInstanceRepository = regionInstanceRepository;
    this.zoneRepository = zoneRepository;
    this.zoneInstanceRepository = zoneInstanceRepository;
    this.roomRepository = roomRepository;
    this.roomExitRepository = roomExitRepository;
    this.roomInstanceRepository = roomInstanceRepository;
    this.roomInstanceExitRepository = roomInstanceExitRepository;
    this.worldEventRepository = worldEventRepository;
    this.worldProperties = worldProperties;
    this.gameDesignClient = gameDesignClient;
    this.entityManagementClient = entityManagementClient;
    this.meterRegistry = meterRegistry;
  }

  WorldInstanceActivationServiceImpl(
      WorldInstanceRepository worldInstanceRepository,
      RegionInstanceRepository regionInstanceRepository,
      ZoneRepository zoneRepository,
      ZoneInstanceRepository zoneInstanceRepository,
      RoomRepository roomRepository,
      RoomExitRepository roomExitRepository,
      RoomInstanceRepository roomInstanceRepository,
      RoomInstanceExitRepository roomInstanceExitRepository,
      WorldEventRepository worldEventRepository,
      WorldProperties worldProperties,
      GameDesignClient gameDesignClient,
      MeterRegistry meterRegistry) {
    this(
        worldInstanceRepository,
        regionInstanceRepository,
        zoneRepository,
        zoneInstanceRepository,
        roomRepository,
        roomExitRepository,
        roomInstanceRepository,
        roomInstanceExitRepository,
        worldEventRepository,
        worldProperties,
        gameDesignClient,
        null,
        meterRegistry);
  }

  @PostConstruct
  void initMetrics() {
    prepareCounter = meterRegistry.counter("world_instance_prepare_total");
    activateCounter = meterRegistry.counter("world_instance_activate_total");
    failCounter = meterRegistry.counter("world_instance_fail_total");
  }

  @Override
  @Transactional
  @Timed(value = "world.prepareInstance")
  public WorldInstanceLifecycleSnapshotDto prepareWorldInstance(
      PreparedWorldInstanceRequest request) {
    validatePrepareRequest(request);
    WorldInstance existing =
        worldInstanceRepository
            .findByTenantIdAndGameInstanceId(request.tenantId(), request.gameInstanceId())
            .orElse(null);
    if (existing != null) {
      if (!samePreparedRequest(existing, request)) {
        throw new IllegalArgumentException(
            "INVALID_ARGUMENT: gameInstanceId already exists with different activation inputs");
      }
      return snapshot(existing);
    }
    validateAttestedLaunchInputs(request);
    WorldInstance worldInstance = new WorldInstance();
    worldInstance.setTenantId(request.tenantId());
    worldInstance.setGameInstanceId(request.gameInstanceId());
    worldInstance.setGameTemplateId(request.gameTemplateId());
    worldInstance.setControlPlaneRequestId(request.controlPlaneRequestId());
    worldInstance.setLaunchDescriptorId(request.launchDescriptorId());
    worldInstance.setVersionId(request.versionId());
    worldInstance.setScriptPatchVersion(normalizeBlank(request.scriptPatchVersion()));
    worldInstance.setRuntimeFlagsJson(
        normalizeBlank(request.runtimeFlagsJson()) == null ? "{}" : request.runtimeFlagsJson());
    worldInstance.setGenerationConfigRevision(request.generationConfigRevision());
    worldInstance.setReleaseBundleId(request.releaseBundleId());
    worldInstance.setPublishedReleaseBundleRef(request.publishedReleaseBundleRef());
    worldInstance.setVersionStateEpoch(request.versionStateEpoch());
    worldInstance.setRemapSetId(normalizeBlank(request.remapSetId()));
    worldInstance.setLifecycleEpoch(1L);
    worldInstance.setStatus(STATUS_PREPARING);
    worldInstance.setCreatedAt(Instant.now());
    worldInstance.setUpdatedAt(Instant.now());
    WorldInstance saved = worldInstanceRepository.save(worldInstance);

    RegionInstance region = new RegionInstance();
    region.setTenantId(request.tenantId());
    region.setGameInstanceId(request.gameInstanceId());
    region.setWorldInstance(saved);
    region.setShardId(worldProperties.getLocalShardId());
    region.setName("Starter Region");
    region.setGenerationSeed(request.gameInstanceId());
    region.setGeneratorType("SimpleDungeonGenerator");
    region.setGeneratorParams("{}");
    region.setSpacingMultiplier(1.0);
    RegionInstance savedRegion = regionInstanceRepository.save(region);
    materializeRoomTopology(
        savedRegion, request.tenantId(), request.versionId(), request.gameInstanceId());

    logger.info(
        "Prepared world instance tenant={} gameInstanceId={} launchDescriptorId={} versionId={}",
        request.tenantId(),
        request.gameInstanceId(),
        request.launchDescriptorId(),
        request.versionId());
    prepareCounter.increment();
    return snapshot(saved);
  }

  @Override
  @Transactional
  @Timed(value = "world.activateInstance")
  public WorldInstanceLifecycleSnapshotDto activatePreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch) {
    WorldInstance worldInstance = requireWorldInstance(tenantId, gameInstanceId);
    requireLifecycleEpoch(worldInstance, expectedLifecycleEpoch);
    if (STATUS_ACTIVE.equals(worldInstance.getStatus())) {
      return snapshot(worldInstance);
    }
    if (!STATUS_PREPARING.equals(worldInstance.getStatus())) {
      throw new IllegalArgumentException(
          "INVALID_WORLD_INSTANCE_STATE: world instance is not in PREPARING state");
    }
    validateAttestedLaunchInputs(
        new PreparedWorldInstanceRequest(
            worldInstance.getTenantId(),
            worldInstance.getGameInstanceId(),
            worldInstance.getGameTemplateId(),
            worldInstance.getControlPlaneRequestId(),
            worldInstance.getLaunchDescriptorId(),
            worldInstance.getVersionId(),
            worldInstance.getScriptPatchVersion(),
            worldInstance.getRuntimeFlagsJson(),
            worldInstance.getGenerationConfigRevision(),
            worldInstance.getReleaseBundleId(),
            worldInstance.getPublishedReleaseBundleRef(),
            worldInstance.getVersionStateEpoch(),
            worldInstance.getRemapSetId()));
    worldInstance.setStatus(STATUS_ACTIVE);
    worldInstance.setLifecycleEpoch(worldInstance.getLifecycleEpoch() + 1L);
    worldInstance.setFailureReason(null);
    WorldInstance saved = worldInstanceRepository.save(worldInstance);
    activateCounter.increment();
    logger.info(
        "Activated world instance tenant={} gameInstanceId={} lifecycleEpoch={}",
        tenantId,
        gameInstanceId,
        saved.getLifecycleEpoch());
    return snapshot(saved);
  }

  @Override
  @Transactional
  @Timed(value = "world.failPreparedInstance")
  public WorldInstanceLifecycleSnapshotDto failPreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch, String reason) {
    WorldInstance worldInstance = requireWorldInstance(tenantId, gameInstanceId);
    requireLifecycleEpoch(worldInstance, expectedLifecycleEpoch);
    if (STATUS_FAILED_PRE_ACTIVATION.equals(worldInstance.getStatus())) {
      return snapshot(worldInstance);
    }
    if (STATUS_ACTIVE.equals(worldInstance.getStatus())) {
      throw new IllegalArgumentException(
          "INVALID_WORLD_INSTANCE_STATE: world instance is already ACTIVE");
    }
    if (!STATUS_PREPARING.equals(worldInstance.getStatus())) {
      throw new IllegalArgumentException(
          "INVALID_WORLD_INSTANCE_STATE: world instance is not in PREPARING state");
    }
    worldInstance.setStatus(STATUS_FAILED_PRE_ACTIVATION);
    worldInstance.setFailureReason(normalizeBlank(reason));
    worldInstance.setLifecycleEpoch(worldInstance.getLifecycleEpoch() + 1L);
    WorldInstance saved = worldInstanceRepository.save(worldInstance);
    failCounter.increment();
    logger.info(
        "Marked prepared world instance failed tenant={} gameInstanceId={} reason={}",
        tenantId,
        gameInstanceId,
        normalizeBlank(reason));
    return snapshot(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public WorldInstanceLifecycleSnapshotDto getWorldInstanceLifecycle(
      long tenantId, long gameInstanceId) {
    return snapshot(requireWorldInstance(tenantId, gameInstanceId));
  }

  @Override
  @Timed(value = "world.terminateInstance")
  public WorldInstanceLifecycleSnapshotDto terminateWorldInstance(
      long tenantId,
      long gameInstanceId,
      long expectedLifecycleEpoch,
      String terminationRequestId,
      String reason) {
    if (terminationRequestId == null || terminationRequestId.isBlank()) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: terminationRequestId is required");
    }
    WorldInstance worldInstance = requireWorldInstance(tenantId, gameInstanceId);
    if (STATUS_TERMINATED.equals(worldInstance.getStatus())) {
      return snapshot(worldInstance);
    }
    if (STATUS_ACTIVE.equals(worldInstance.getStatus())) {
      requireLifecycleEpoch(worldInstance, expectedLifecycleEpoch);
      worldInstance.setStatus(STATUS_TERMINATING);
      worldInstance.setTerminationRequestId(terminationRequestId);
      worldInstance.setFailureReason(normalizeBlank(reason));
      worldInstance.setLifecycleEpoch(worldInstance.getLifecycleEpoch() + 1L);
      worldInstance = worldInstanceRepository.save(worldInstance);
    } else if (STATUS_TERMINATING.equals(worldInstance.getStatus())) {
      if (!terminationRequestId.equals(worldInstance.getTerminationRequestId())) {
        throw new IllegalArgumentException(
            "INVALID_WORLD_INSTANCE_STATE: world instance is terminating under a different request id");
      }
    } else {
      throw new IllegalArgumentException(
          "INVALID_WORLD_INSTANCE_STATE: world instance is not in ACTIVE or TERMINATING state");
    }
    var cleanupResponse =
        entityManagementClient == null
            ? null
            : entityManagementClient.cleanupRuntimeInstance(
                tenantId, gameInstanceId, terminationRequestId);
    if (cleanupResponse != null && cleanupResponse.hasError()) {
      throw new IllegalArgumentException(
          cleanupResponse.getError().getCode() + ": " + cleanupResponse.getError().getMessage());
    }
    cleanupWorldRuntimeState(tenantId, gameInstanceId);
    worldInstance.setStatus(STATUS_TERMINATED);
    worldInstance.setLifecycleEpoch(worldInstance.getLifecycleEpoch() + 1L);
    worldInstance.setTerminatedAt(Instant.now());
    WorldInstance saved = worldInstanceRepository.save(worldInstance);
    logger.info(
        "Terminated world instance tenant={} gameInstanceId={} terminationRequestId={}",
        tenantId,
        gameInstanceId,
        terminationRequestId);
    return snapshot(saved);
  }

  private void cleanupWorldRuntimeState(long tenantId, long gameInstanceId) {
    worldEventRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    roomInstanceExitRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    roomInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    zoneInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    regionInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
  }

  private void validatePrepareRequest(PreparedWorldInstanceRequest request) {
    if (request.tenantId() <= 0L
        || request.gameInstanceId() <= 0L
        || request.gameTemplateId() <= 0L
        || request.versionId() <= 0L
        || request.releaseBundleId() <= 0L
        || request.versionStateEpoch() <= 0L
        || request.controlPlaneRequestId() == null
        || request.controlPlaneRequestId().isBlank()
        || request.launchDescriptorId() == null
        || request.launchDescriptorId().isBlank()
        || request.generationConfigRevision() == null
        || request.generationConfigRevision().isBlank()
        || request.publishedReleaseBundleRef() == null
        || request.publishedReleaseBundleRef().isBlank()) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: incomplete world instance activation request");
    }
  }

  private void validateAttestedLaunchInputs(PreparedWorldInstanceRequest request) {
    var bundleResponse =
        gameDesignClient.getPublishedReleaseBundle(request.tenantId(), request.versionId());
    if (bundleResponse.hasError()) {
      throw new IllegalArgumentException(
          bundleResponse.getError().getCode() + ": " + bundleResponse.getError().getMessage());
    }
    var bundle = bundleResponse.getBundle();
    requireSupportedReleaseAttestationSchema(bundle.getAttestationSchemaVersion());
    if (bundle.getId() != request.releaseBundleId()
        || bundle.getVersionId() != request.versionId()
        || !bundle.getGenerationConfigRevision().equals(request.generationConfigRevision())) {
      throw new IllegalArgumentException(
          "RELEASE_ATTESTATION_MISMATCH: world activation request does not match the published release bundle");
    }
    if (!releaseBundleRef(request.tenantId(), request.versionId(), request.releaseBundleId())
        .equals(request.publishedReleaseBundleRef())) {
      throw new IllegalArgumentException(
          "RELEASE_ATTESTATION_MISMATCH: published release bundle ref mismatch");
    }
    var artifactStateResponse =
        gameDesignClient.getVersionAssetArtifactState(request.tenantId(), request.versionId());
    if (artifactStateResponse.hasError()) {
      throw new IllegalArgumentException(
          artifactStateResponse.getError().getCode()
              + ": "
              + artifactStateResponse.getError().getMessage());
    }
    var artifactState = artifactStateResponse.getArtifactState();
    if (artifactState.getArtifactState()
            != net.firedevops.firemud.gamedesign.v1.ArtifactState.ARTIFACT_STATE_PUBLISHED
        || !artifactState.getManifestHash().equals(bundle.getManifestHash())) {
      throw new IllegalArgumentException(
          "RELEASE_ATTESTATION_MISMATCH: published asset artifact state does not match the published release bundle");
    }
    var exportedKeys = new HashSet<>(artifactState.getExportedManifestAssetKeysList());
    if (!exportedKeys.containsAll(bundle.getRequiredManifestAssetKeysList())) {
      throw new IllegalArgumentException(
          "RELEASE_ATTESTATION_MISMATCH: published asset artifact state is missing required manifest asset keys");
    }
    var versionStateResponse =
        gameDesignClient.getVersionState(request.tenantId(), request.versionId());
    if (versionStateResponse.hasError()) {
      throw new IllegalArgumentException(
          versionStateResponse.getError().getCode()
              + ": "
              + versionStateResponse.getError().getMessage());
    }
    var versionState = versionStateResponse.getVersionState();
    if (versionState.getVersionState() != VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED
        && versionState.getVersionState() != VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE) {
      throw new IllegalArgumentException(
          "VERSION_STATE_EPOCH_STALE: resolved version is not activation-eligible");
    }
    if (versionState.getVersionStateEpoch() != request.versionStateEpoch()) {
      throw new IllegalArgumentException(
          "VERSION_STATE_EPOCH_STALE: world activation request epoch does not match current version state");
    }
  }

  private WorldInstance requireWorldInstance(long tenantId, long gameInstanceId) {
    return worldInstanceRepository
        .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
        .orElseThrow(
            () ->
                new IllegalArgumentException("WORLD_INSTANCE_NOT_FOUND: world instance not found"));
  }

  private void materializeRoomTopology(
      RegionInstance regionInstance, long tenantId, long versionId, long gameInstanceId) {
    Map<Long, ZoneInstance> zoneInstancesByTemplateId = new LinkedHashMap<>();
    for (Zone templateZone :
        zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(tenantId, versionId)) {
      ZoneInstance zoneInstance = new ZoneInstance();
      zoneInstance.setTenantId(tenantId);
      zoneInstance.setGameInstanceId(gameInstanceId);
      zoneInstance.setZoneInstanceId(templateZone.getId());
      zoneInstance.setTemplateZoneId(templateZone.getId());
      zoneInstance.setRegionInstance(regionInstance);
      zoneInstance.setName(templateZone.getName());
      ZoneInstance savedZoneInstance = zoneInstanceRepository.save(zoneInstance);
      zoneInstancesByTemplateId.put(templateZone.getId(), savedZoneInstance);
    }
    List<Room> templateRooms =
        roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(tenantId, versionId);
    Map<Long, RoomInstance> roomInstancesByTemplateId = new LinkedHashMap<>();
    for (Room templateRoom : templateRooms) {
      ZoneInstance zoneInstance = zoneInstancesByTemplateId.get(templateRoom.getZone().getId());
      if (zoneInstance == null) {
        continue;
      }
      RoomInstance roomInstance = new RoomInstance();
      roomInstance.setTenantId(tenantId);
      roomInstance.setGameInstanceId(gameInstanceId);
      roomInstance.setRoomInstanceId(templateRoom.getId());
      roomInstance.setTemplateRoomId(templateRoom.getId());
      roomInstance.setRegionInstance(regionInstance);
      roomInstance.setZoneInstance(zoneInstance);
      roomInstance.setName(templateRoom.getName());
      roomInstance.setDescription(templateRoom.getDescription());
      roomInstance.setNameLocalizedVariantsJson(templateRoom.getNameLocalizedVariantsJson());
      roomInstance.setDescriptionLocalizedVariantsJson(
          templateRoom.getDescriptionLocalizedVariantsJson());
      RoomInstance savedRoomInstance = roomInstanceRepository.save(roomInstance);
      roomInstancesByTemplateId.put(templateRoom.getId(), savedRoomInstance);
    }
    for (RoomExit templateExit :
        roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(tenantId, versionId)) {
      RoomInstance fromRoomInstance =
          roomInstancesByTemplateId.get(templateExit.getFromRoom().getId());
      RoomInstance toRoomInstance = roomInstancesByTemplateId.get(templateExit.getToRoom().getId());
      if (fromRoomInstance == null || toRoomInstance == null) {
        continue;
      }
      RoomInstanceExit roomInstanceExit = new RoomInstanceExit();
      roomInstanceExit.setTenantId(tenantId);
      roomInstanceExit.setGameInstanceId(gameInstanceId);
      roomInstanceExit.setFromRoomInstance(fromRoomInstance);
      roomInstanceExit.setToRoomInstance(toRoomInstance);
      roomInstanceExit.setDirection(templateExit.getDirection());
      roomInstanceExit.setCost(templateExit.getCost());
      roomInstanceExitRepository.save(roomInstanceExit);
    }
  }

  private void requireLifecycleEpoch(WorldInstance worldInstance, long expectedLifecycleEpoch) {
    if (expectedLifecycleEpoch <= 0L) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: expectedLifecycleEpoch is required");
    }
    if (!Long.valueOf(expectedLifecycleEpoch).equals(worldInstance.getLifecycleEpoch())) {
      throw new IllegalArgumentException(
          "WORLD_INSTANCE_LIFECYCLE_STALE: world instance lifecycle epoch mismatch");
    }
  }

  private boolean samePreparedRequest(
      WorldInstance existing, PreparedWorldInstanceRequest request) {
    return existing.getGameTemplateId().equals(request.gameTemplateId())
        && existing.getControlPlaneRequestId().equals(request.controlPlaneRequestId())
        && existing.getLaunchDescriptorId().equals(request.launchDescriptorId())
        && existing.getVersionId().equals(request.versionId())
        && equalsNullable(
            existing.getScriptPatchVersion(), normalizeBlank(request.scriptPatchVersion()))
        && equalsNullable(
            existing.getRuntimeFlagsJson(),
            normalizeBlank(request.runtimeFlagsJson()) == null ? "{}" : request.runtimeFlagsJson())
        && existing.getGenerationConfigRevision().equals(request.generationConfigRevision())
        && existing.getReleaseBundleId().equals(request.releaseBundleId())
        && existing.getPublishedReleaseBundleRef().equals(request.publishedReleaseBundleRef())
        && existing.getVersionStateEpoch().equals(request.versionStateEpoch())
        && equalsNullable(existing.getRemapSetId(), normalizeBlank(request.remapSetId()));
  }

  private WorldInstanceLifecycleSnapshotDto snapshot(WorldInstance worldInstance) {
    return new WorldInstanceLifecycleSnapshotDto(
        worldInstance.getTenantId(),
        worldInstance.getGameInstanceId(),
        worldInstance.getGameTemplateId(),
        worldInstance.getControlPlaneRequestId(),
        worldInstance.getLaunchDescriptorId(),
        worldInstance.getVersionId(),
        worldInstance.getReleaseBundleId(),
        worldInstance.getGenerationConfigRevision(),
        worldInstance.getPublishedReleaseBundleRef(),
        worldInstance.getVersionStateEpoch(),
        worldInstance.getLifecycleEpoch(),
        worldInstance.getStatus(),
        worldInstance.getRemapSetId());
  }

  private boolean equalsNullable(String left, String right) {
    return left == null ? right == null : left.equals(right);
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String releaseBundleRef(long tenantId, long versionId, long releaseBundleId) {
    return "prb:" + tenantId + ":" + versionId + ":" + releaseBundleId;
  }

  private void requireSupportedReleaseAttestationSchema(String schemaVersion) {
    if (!SUPPORTED_RELEASE_ATTESTATION_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "SCHEMA_VERSION_UNSUPPORTED: unsupported published release bundle attestation schema "
              + schemaVersion);
    }
  }
}
