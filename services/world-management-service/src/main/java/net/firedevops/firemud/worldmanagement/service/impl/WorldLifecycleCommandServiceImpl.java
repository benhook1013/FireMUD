package net.firedevops.firemud.worldmanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldFixedPresetResources;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
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
import net.firedevops.firemud.worldmanagement.service.WorldLifecycleCommandService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorldLifecycleCommandServiceImpl implements WorldLifecycleCommandService {
  private static final Logger logger =
      LoggingUtil.getLogger(WorldLifecycleCommandServiceImpl.class);

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
  private final TransactionOperations transactionOperations;
  private final RunOwnedWorldLaunchEvidenceProvider runOwnedWorldLaunchEvidenceProvider;
  private final RunOwnedCanonicalWorldLaunchEvidenceProvider
      runOwnedCanonicalWorldLaunchEvidenceProvider;

  private final DSLContext dsl;
  private final PlatformTransactionManager fixtureTransactionManager;
  private final ServiceEndpointsProperties serviceEndpoints;
  private final CommonGrpcClientProperties grpcClientProperties;
  private final GrpcChannelFactory grpcChannelFactory;
  private final String workloadNamespace;

  private volatile RunOwnedWorldFixtureOwners runOwnedWorldFixtureOwners;

  private final TransactionOperations fixtureTransactionOperations;
  private final TransactionOperations prepareTransactionOperations;
  private boolean fixtureOwnersClosed;

  private Counter prepareCounter;
  private Counter activateCounter;
  private Counter failCounter;

  public WorldLifecycleCommandServiceImpl(
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
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager,
      RunOwnedWorldLaunchEvidenceProvider runOwnedWorldLaunchEvidenceProvider) {
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
        entityManagementClient,
        meterRegistry,
        transactionTemplate(transactionManager),
        runOwnedWorldLaunchEvidenceProvider);
  }

  @Autowired
  public WorldLifecycleCommandServiceImpl(
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
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager,
      RunOwnedWorldLaunchEvidenceProvider runOwnedWorldLaunchEvidenceProvider,
      RunOwnedCanonicalWorldLaunchEvidenceProvider runOwnedCanonicalWorldLaunchEvidenceProvider,
      DSLContext dsl,
      ServiceEndpointsProperties serviceEndpoints,
      CommonGrpcClientProperties grpcClientProperties,
      GrpcChannelFactory grpcChannelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
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
        entityManagementClient,
        meterRegistry,
        transactionTemplate(transactionManager),
        runOwnedWorldLaunchEvidenceProvider,
        runOwnedCanonicalWorldLaunchEvidenceProvider,
        dsl,
        transactionManager,
        serviceEndpoints,
        grpcClientProperties,
        grpcChannelFactory,
        workloadNamespace);
  }

  WorldLifecycleCommandServiceImpl(
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
      MeterRegistry meterRegistry,
      TransactionOperations transactionOperations) {
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
        entityManagementClient,
        meterRegistry,
        transactionOperations,
        null);
  }

  WorldLifecycleCommandServiceImpl(
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
      MeterRegistry meterRegistry,
      TransactionOperations transactionOperations,
      RunOwnedWorldLaunchEvidenceProvider runOwnedWorldLaunchEvidenceProvider) {
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
        entityManagementClient,
        meterRegistry,
        transactionOperations,
        runOwnedWorldLaunchEvidenceProvider,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The injected owner bundle is an internal collaborator; construction only initializes fields, acquires no resources, and this service has no finalizer.")
  WorldLifecycleCommandServiceImpl(
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
      MeterRegistry meterRegistry,
      TransactionOperations transactionOperations,
      RunOwnedWorldLaunchEvidenceProvider runOwnedWorldLaunchEvidenceProvider,
      DSLContext dsl,
      RunOwnedWorldFixtureOwners runOwnedWorldFixtureOwners) {
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
        entityManagementClient,
        meterRegistry,
        transactionOperations,
        runOwnedWorldLaunchEvidenceProvider,
        null,
        dsl,
        null,
        null,
        null,
        null,
        null);
    this.runOwnedWorldFixtureOwners =
        Objects.requireNonNull(runOwnedWorldFixtureOwners, "runOwnedWorldFixtureOwners");
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The injected owner bundle is an internal collaborator; construction only initializes fields, acquires no resources, and this service has no finalizer.")
  WorldLifecycleCommandServiceImpl(
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
      MeterRegistry meterRegistry,
      TransactionOperations transactionOperations,
      RunOwnedCanonicalWorldLaunchEvidenceProvider runOwnedCanonicalWorldLaunchEvidenceProvider,
      DSLContext dsl,
      RunOwnedWorldFixtureOwners runOwnedWorldFixtureOwners) {
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
        entityManagementClient,
        meterRegistry,
        transactionOperations,
        null,
        runOwnedCanonicalWorldLaunchEvidenceProvider,
        dsl,
        null,
        null,
        null,
        null,
        null);
    this.runOwnedWorldFixtureOwners =
        Objects.requireNonNull(runOwnedWorldFixtureOwners, "runOwnedWorldFixtureOwners");
  }

  private WorldLifecycleCommandServiceImpl(
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
      MeterRegistry meterRegistry,
      TransactionOperations transactionOperations,
      RunOwnedWorldLaunchEvidenceProvider runOwnedWorldLaunchEvidenceProvider,
      RunOwnedCanonicalWorldLaunchEvidenceProvider runOwnedCanonicalWorldLaunchEvidenceProvider,
      DSLContext dsl,
      PlatformTransactionManager fixtureTransactionManager,
      ServiceEndpointsProperties serviceEndpoints,
      CommonGrpcClientProperties grpcClientProperties,
      GrpcChannelFactory grpcChannelFactory,
      String workloadNamespace) {
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
    this.transactionOperations = transactionOperations;
    this.runOwnedWorldLaunchEvidenceProvider = runOwnedWorldLaunchEvidenceProvider;
    this.runOwnedCanonicalWorldLaunchEvidenceProvider =
        runOwnedCanonicalWorldLaunchEvidenceProvider;
    this.dsl = dsl;
    this.fixtureTransactionManager = fixtureTransactionManager;
    this.serviceEndpoints = serviceEndpoints;
    this.grpcClientProperties = grpcClientProperties;
    this.grpcChannelFactory = grpcChannelFactory;
    this.workloadNamespace = workloadNamespace;
    PlatformTransactionManager prepareTransactionManager = fixtureTransactionManager;
    if (prepareTransactionManager == null
        && transactionOperations instanceof TransactionTemplate template) {
      prepareTransactionManager = template.getTransactionManager();
    }
    // Ordinary preparation retains its original REQUIRED boundary. Fixture reads execute before
    // any transaction, then its owners use their separate short READ_COMMITTED transactions.
    this.prepareTransactionOperations =
        prepareTransactionManager == null
            ? transactionOperations
            : new TransactionTemplate(prepareTransactionManager);
    this.fixtureTransactionOperations =
        fixtureTransactionManager == null
            ? transactionOperations
            : fixtureTransactionTemplate(fixtureTransactionManager);
  }

  WorldLifecycleCommandServiceImpl(
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
        meterRegistry,
        (TransactionOperations) null);
  }

  private static TransactionTemplate transactionTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transactionTemplate;
  }

  private static TransactionTemplate fixtureTransactionTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
    return transaction;
  }

  @PostConstruct
  void initMetrics() {
    prepareCounter = meterRegistry.counter("world_instance_prepare_total");
    activateCounter = meterRegistry.counter("world_instance_activate_total");
    failCounter = meterRegistry.counter("world_instance_fail_total");
  }

  /**
   * Runs the explicit canonical source-preparation stage through the original PREPARING allocation.
   * Generic lifecycle calls retain the published runtime-evidence contract.
   */
  WorldInstanceLifecycleSnapshotDto prepareRunOwnedFixture(
      PreparedWorldInstanceRequest originalGameSessionRequest,
      RunOwnedCanonicalWorldLaunchEvidence fixtureEvidence) {
    Objects.requireNonNull(fixtureEvidence, "fixtureEvidence").requireFixedGameSessionPeer();
    requireNoAmbientFixtureReadTransaction();
    if (!canonicalFixtureModeEnabled()) throw fixtureWritesUnavailable();
    fixtureEvidence.requireMatches(originalGameSessionRequest);
    RunOwnedWorldFixtureOwners owners = requireRunOwnedWorldFixtureOwners();
    var sourceReceipt = owners.sourceIntakeService().intakeRunOwnedFixture(fixtureEvidence);
    var versionIdentity =
        owners.versionIdentityService().associateRunOwnedFixture(fixtureEvidence, sourceReceipt);
    // Check the current owner observation before resource writes. Allocation retains the original
    // GS response bytes; that retained response, rather than a fresh read nonce, seals the plan.
    RunOwnedWorldFixedPresetResources.plan(fixtureEvidence, sourceReceipt, versionIdentity);
    WorldCanonicalInstanceAssociation allocated =
        executeFixtureOwnerTransaction(
            () -> {
              owners
                  .associationRepository()
                  .lockRunOwnedFixtureInOwnerTransaction(
                      fixtureEvidence, originalGameSessionRequest);
              owners
                  .publicationFenceRepository()
                  .lockOpen(
                      new OwnerBinding(
                          sourceReceipt.targetNamespace(),
                          sourceReceipt.canonicalTenantId(),
                          versionIdentity.canonicalVersionId(),
                          versionIdentity.operationId(),
                          versionIdentity.gameDesignVersionId(),
                          sourceReceipt.intakeRequestId(),
                          sourceReceipt.operationId(),
                          sourceReceipt.requestDigest(),
                          sourceReceipt.sourceOperationId(),
                          sourceReceipt.sourceEvidenceDigest(),
                          sourceReceipt.receiptDigest()));
              WorldCanonicalInstanceAssociation association =
                  owners
                      .associationRepository()
                      .allocateRunOwnedFixtureInOwnerTransaction(
                          fixtureEvidence,
                          originalGameSessionRequest,
                          sourceReceipt,
                          versionIdentity);
              var retainedPlan =
                  RunOwnedWorldFixedPresetResources.plan(
                      association, sourceReceipt, versionIdentity);
              RunOwnedWorldFixedPresetResources.materialize(
                  dsl,
                  fixtureEvidence,
                  retainedPlan,
                  association.worldInstanceId(),
                  association.worldPrivateKeys().gameInstanceId());
              return association;
            });
    WorldCanonicalInstanceAssociation committed =
        owners
            .associationRepository()
            .readRunOwnedFixtureAssociation(fixtureEvidence, originalGameSessionRequest);
    requireSameFixtureAllocation(allocated, committed);
    RunOwnedWorldFixedPresetResources.readCommitted(
        dsl,
        committed,
        committed.runOwnedFixture().sourceIntakeReceipt(),
        committed.versionIdentity());
    WorldInstance materialized =
        worldInstanceRepository
            .findById(committed.worldInstanceId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "RUN_OWNED_WORLD_INSTANCE_MISSING: committed fixture World row is missing"));
    var keys = committed.worldPrivateKeys();
    PreparedWorldInstanceRequest worldRequest =
        new PreparedWorldInstanceRequest(
            keys.tenantId(),
            keys.gameInstanceId(),
            originalGameSessionRequest.gameTemplateId(),
            originalGameSessionRequest.controlPlaneRequestId(),
            originalGameSessionRequest.launchDescriptorId(),
            keys.versionId(),
            originalGameSessionRequest.scriptPatchVersion(),
            originalGameSessionRequest.runtimeFlagsJson(),
            originalGameSessionRequest.generationConfigRevision(),
            originalGameSessionRequest.releaseBundleId(),
            originalGameSessionRequest.publishedReleaseBundleRef(),
            originalGameSessionRequest.versionStateEpoch(),
            originalGameSessionRequest.remapSetId());
    if (!Objects.equals(materialized.getId(), committed.worldInstanceId())
        || !Objects.equals(materialized.getTenantId(), keys.tenantId())
        || !Objects.equals(materialized.getGameInstanceId(), keys.gameInstanceId())
        || !Objects.equals(materialized.getVersionId(), keys.versionId())
        || !STATUS_PREPARING.equals(materialized.getStatus())
        || !Long.valueOf(1L).equals(materialized.getLifecycleEpoch())
        || !Long.valueOf(0L).equals(materialized.getRowVersion())
        || !Long.valueOf(1L).equals(materialized.getRunOwnedFixturePreparingLifecycleEpoch())
        || !committed
            .runOwnedFixture()
            .preparationInputDigest()
            .equals("sha256:" + materialized.getRunOwnedFixtureBindingSha256())
        || !materialized
            .getRunOwnedFixtureBindingSha256()
            .equals(fixtureEvidence.bindingDigest(originalGameSessionRequest, 1L))
        || !samePreparedRequest(materialized, worldRequest)
        || materialized.getFailureReason() != null
        || materialized.getTerminationRequestId() != null
        || materialized.getTerminatedAt() != null) {
      throw fixtureEvidenceMismatch();
    }
    prepareCounter.increment();
    WorldInstanceLifecycleSnapshotDto materializedSnapshot = snapshot(materialized);
    return new WorldInstanceLifecycleSnapshotDto(
        originalGameSessionRequest.tenantId(),
        originalGameSessionRequest.gameInstanceId(),
        materializedSnapshot.gameTemplateId(),
        materializedSnapshot.controlPlaneRequestId(),
        materializedSnapshot.launchDescriptorId(),
        originalGameSessionRequest.versionId(),
        materializedSnapshot.releaseBundleId(),
        materializedSnapshot.generationConfigRevision(),
        materializedSnapshot.publishedReleaseBundleRef(),
        materializedSnapshot.versionStateEpoch(),
        materializedSnapshot.lifecycleEpoch(),
        materializedSnapshot.status(),
        materializedSnapshot.remapSetId(),
        materializedSnapshot.workflowId(),
        materializedSnapshot.workflowFamily(),
        materializedSnapshot.workflowRunId(),
        materializedSnapshot.workflowStatus());
  }

  private <T> T executeFixtureOwnerTransaction(Supplier<T> operation) {
    if (fixtureTransactionOperations == null) throw fixtureEvidenceUnavailable();
    return Objects.requireNonNull(
        fixtureTransactionOperations.execute(status -> operation.get()),
        "Fixture owner transaction returned no retained allocation");
  }

  @Override
  @Timed(value = "world.prepareInstance")
  public WorldInstanceLifecycleSnapshotDto prepareWorldInstance(
      PreparedWorldInstanceRequest request) {
    if (prepareTransactionOperations == null) return preparePublishedWorldInstance(request);
    return Objects.requireNonNull(
        prepareTransactionOperations.execute(status -> preparePublishedWorldInstance(request)),
        "Ordinary preparation transaction returned no World snapshot");
  }

  private WorldInstanceLifecycleSnapshotDto preparePublishedWorldInstance(
      PreparedWorldInstanceRequest request) {
    validatePrepareRequest(request);
    RunOwnedWorldLaunchEvidence fixtureEvidence = configuredFixtureEvidence();
    if (fixtureEvidence != null) {
      fixtureEvidence.requireMatches(request);
      worldInstanceRepository.lockRunOwnedFixtureOperation(
          request.tenantId(), request.controlPlaneRequestId());
      WorldInstance operationInstance =
          worldInstanceRepository
              .findRunOwnedFixtureByTenantIdAndControlPlaneRequestIdForUpdate(
                  request.tenantId(), request.controlPlaneRequestId())
              .orElse(null);
      if (operationInstance != null
          && !operationInstance.getGameInstanceId().equals(request.gameInstanceId())) {
        throw fixtureIdentityConflict();
      }
    }
    WorldInstance existing =
        fixtureEvidence == null
            ? worldInstanceRepository
                .findByTenantIdAndGameInstanceId(request.tenantId(), request.gameInstanceId())
                .orElse(null)
            : worldInstanceRepository
                .findByTenantIdAndGameInstanceIdForUpdate(
                    request.tenantId(), request.gameInstanceId())
                .orElse(null);
    if (existing != null) {
      if (existing.getRunOwnedFixtureBindingSha256() != null) {
        if (fixtureEvidence == null) throw fixtureEvidenceUnavailable();
        requireRunOwnedFixtureBinding(existing, fixtureEvidence, request);
        return snapshot(existing);
      }
      if (fixtureEvidence != null) throw fixtureIdentityConflict();
      if (!samePreparedRequest(existing, request)) {
        throw new IllegalArgumentException(
            "INVALID_ARGUMENT: gameInstanceId already exists with different activation inputs");
      }
      return snapshot(existing);
    }
    if (fixtureEvidence == null) validateAttestedLaunchInputs(request);
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
    if (fixtureEvidence != null) {
      worldInstance.setRunOwnedFixtureBindingSha256(fixtureEvidence.bindingDigest(request, 1L));
      worldInstance.setRunOwnedFixturePreparingLifecycleEpoch(1L);
    }
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
    if (worldInstance.getRunOwnedFixtureBindingSha256() != null) {
      if (!fixtureModeEnabled()) throw fixtureEvidenceUnavailable();
      RunOwnedWorldLaunchEvidence fixtureEvidence =
          runOwnedWorldLaunchEvidenceProvider.requireAuthenticatedEvidence();
      worldInstance = requireLockedWorldInstance(tenantId, gameInstanceId);
      PreparedWorldInstanceRequest existingRequest = preparedRequest(worldInstance);
      requireRunOwnedFixtureBinding(worldInstance, fixtureEvidence, existingRequest);
      if (STATUS_ACTIVE.equals(worldInstance.getStatus())) {
        long preparingEpoch = worldInstance.getRunOwnedFixturePreparingLifecycleEpoch();
        if (worldInstance.getLifecycleEpoch() != preparingEpoch + 1L
            || (expectedLifecycleEpoch != preparingEpoch
                && expectedLifecycleEpoch != worldInstance.getLifecycleEpoch())) {
          throw lifecycleEpochMismatch();
        }
        return snapshot(worldInstance);
      }
      requireLifecycleEpoch(worldInstance, expectedLifecycleEpoch);
    } else {
      if (fixtureModeEnabled()) throw fixtureIdentityConflict();
      requireLifecycleEpoch(worldInstance, expectedLifecycleEpoch);
      if (STATUS_ACTIVE.equals(worldInstance.getStatus())) return snapshot(worldInstance);
    }
    if (!STATUS_PREPARING.equals(worldInstance.getStatus())) {
      throw new IllegalArgumentException(
          "INVALID_WORLD_INSTANCE_STATE: world instance is not in PREPARING state");
    }
    if (worldInstance.getRunOwnedFixtureBindingSha256() == null) {
      validateAttestedLaunchInputs(preparedRequest(worldInstance));
    }
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

  private PreparedWorldInstanceRequest preparedRequest(WorldInstance worldInstance) {
    return new PreparedWorldInstanceRequest(
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
        worldInstance.getRemapSetId());
  }

  private void requireRunOwnedFixtureBinding(
      WorldInstance instance,
      RunOwnedWorldLaunchEvidence fixtureEvidence,
      PreparedWorldInstanceRequest request) {
    Long preparingEpoch = instance.getRunOwnedFixturePreparingLifecycleEpoch();
    if (instance.getRunOwnedFixtureBindingSha256() == null
        || preparingEpoch == null
        || preparingEpoch <= 0L
        || preparingEpoch != 1L
        || !samePreparedRequest(instance, request)
        || !instance
            .getRunOwnedFixtureBindingSha256()
            .equals(fixtureEvidence.bindingDigest(request, preparingEpoch))) {
      throw fixtureEvidenceMismatch();
    }
  }

  private RunOwnedWorldLaunchEvidence configuredFixtureEvidence() {
    if (!fixtureModeEnabled()) return null;
    return runOwnedWorldLaunchEvidenceProvider.requireAuthenticatedEvidence();
  }

  private static void requireSameFixtureAllocation(
      WorldCanonicalInstanceAssociation expected, WorldCanonicalInstanceAssociation actual) {
    if (!expected.isRunOwnedFixture()
        || !actual.isRunOwnedFixture()
        || expected.worldInstanceId() != actual.worldInstanceId()
        || !expected.identity().equals(actual.identity())
        || !expected.worldPrivateKeys().equals(actual.worldPrivateKeys())
        || !expected.versionIdentity().equals(actual.versionIdentity())
        || !expected
            .runOwnedFixture()
            .sourceIntakeReceipt()
            .equals(actual.runOwnedFixture().sourceIntakeReceipt())
        || !expected
            .runOwnedFixture()
            .preparationInputDigest()
            .equals(actual.runOwnedFixture().preparationInputDigest())
        || !expected
            .runOwnedFixture()
            .fixtureProvenanceDigest()
            .equals(actual.runOwnedFixture().fixtureProvenanceDigest())
        || !java.util.Arrays.equals(
            expected.runOwnedFixture().gameSessionAssociationResponseBytes(),
            actual.runOwnedFixture().gameSessionAssociationResponseBytes())) {
      throw fixtureEvidenceMismatch();
    }
  }

  private synchronized RunOwnedWorldFixtureOwners requireRunOwnedWorldFixtureOwners() {
    if (fixtureOwnersClosed) throw fixtureEvidenceUnavailable();
    if (runOwnedWorldFixtureOwners != null) {
      return runOwnedWorldFixtureOwners;
    }
    if (!canonicalFixtureModeEnabled()
        || dsl == null
        || fixtureTransactionManager == null
        || serviceEndpoints == null
        || grpcClientProperties == null
        || grpcChannelFactory == null
        || workloadNamespace == null
        || !"dev".equals(workloadNamespace)) {
      throw fixtureEvidenceUnavailable();
    }

    AuthoredWorldSourceClient sourceClient =
        new AuthoredWorldSourceClient(
            serviceEndpoints, grpcClientProperties, grpcChannelFactory, workloadNamespace);
    AuthoredWorldVersionStateClient versionStateClient =
        new AuthoredWorldVersionStateClient(
            serviceEndpoints, grpcClientProperties, grpcChannelFactory, workloadNamespace);
    try {
      sourceClient.init();
      versionStateClient.init();
      WorldAuthoredSourceIntakeRepository sourceRepository =
          new WorldAuthoredSourceIntakeRepository(dsl);
      WorldAuthoredVersionIdentityRepository versionRepository =
          new WorldAuthoredVersionIdentityRepository(dsl);
      WorldAuthoredSourceIntakeService sourceIntakeService =
          new WorldAuthoredSourceIntakeService(
              sourceClient, sourceRepository, fixtureTransactionManager, workloadNamespace);
      WorldAuthoredVersionIdentityService versionIdentityService =
          new WorldAuthoredVersionIdentityService(
              versionStateClient,
              versionRepository,
              sourceRepository,
              fixtureTransactionManager,
              workloadNamespace);
      WorldCanonicalInstanceAssociationRepository associationRepository =
          new WorldCanonicalInstanceAssociationRepository(
              dsl,
              new WorldCompleteLaunchBindingRepository(dsl),
              sourceRepository,
              versionRepository);
      runOwnedWorldFixtureOwners =
          new RunOwnedWorldFixtureOwners(
              sourceClient,
              versionStateClient,
              sourceIntakeService,
              versionIdentityService,
              associationRepository,
              new WorldDesignPublicationFenceRepository(dsl, sourceRepository));
      return runOwnedWorldFixtureOwners;
    } catch (IOException | RuntimeException failure) {
      closeFixtureClient(sourceClient, "authored-source");
      closeFixtureClient(versionStateClient, "authored-Version");
      throw new IllegalStateException(
          "RUN_OWNED_WORLD_FIXTURE_OWNER_UNAVAILABLE: authenticated fixture owner clients could not initialize",
          failure);
    }
  }

  @PreDestroy
  synchronized void closeRunOwnedWorldFixtureOwners() {
    fixtureOwnersClosed = true;
    RunOwnedWorldFixtureOwners owners = runOwnedWorldFixtureOwners;
    runOwnedWorldFixtureOwners = null;
    if (owners != null) {
      owners.close();
    }
  }

  private static void closeFixtureClient(AutoCloseable client, String label) {
    try {
      client.close();
    } catch (Exception closeFailure) {
      logger.warn("Run-owned World {} owner client did not close cleanly", label);
    }
  }

  private static void requireNoAmbientFixtureReadTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Run-owned World evidence reads require no ambient transaction");
    }
  }

  private static IllegalArgumentException fixtureEvidenceMismatch() {
    return new IllegalArgumentException(
        "RUN_OWNED_WORLD_LAUNCH_EVIDENCE_MISMATCH: stored fixture binding or prepared identity changed");
  }

  private boolean fixtureModeEnabled() {
    return runOwnedWorldLaunchEvidenceProvider != null
        && runOwnedWorldLaunchEvidenceProvider.isEnabled();
  }

  private boolean canonicalFixtureModeEnabled() {
    return runOwnedCanonicalWorldLaunchEvidenceProvider != null
        && runOwnedCanonicalWorldLaunchEvidenceProvider.isEnabled();
  }

  private static IllegalArgumentException fixtureEvidenceUnavailable() {
    return new IllegalArgumentException(
        "RUN_OWNED_WORLD_LAUNCH_EVIDENCE_UNAVAILABLE: fixture-bound lifecycle requires verified run evidence");
  }

  private static IllegalArgumentException fixtureWritesUnavailable() {
    return new IllegalArgumentException(
        "RUN_OWNED_WORLD_MATERIALIZATION_UNAVAILABLE: fixture mutation requires its verified owner");
  }

  private static IllegalArgumentException fixtureIdentityConflict() {
    return new IllegalArgumentException(
        "RUN_OWNED_WORLD_LAUNCH_IDENTITY_CONFLICT: fixture operation is bound to another instance");
  }

  private static IllegalArgumentException lifecycleEpochMismatch() {
    return new IllegalArgumentException(
        "WORLD_INSTANCE_LIFECYCLE_STALE: world instance lifecycle epoch mismatch");
  }

  @Override
  @Timed(value = "world.failPreparedInstance")
  public WorldInstanceLifecycleSnapshotDto failPreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch, String reason) {
    if (fixtureModeEnabled()) {
      requireNoAmbientFixtureReadTransaction();
      RunOwnedWorldLaunchEvidence fixtureEvidence =
          runOwnedWorldLaunchEvidenceProvider.requireAuthenticatedEvidence();
      return executeOwnedLifecycleTransaction(
          () ->
              failPreparedWorldInstanceOwned(
                  tenantId, gameInstanceId, expectedLifecycleEpoch, reason, fixtureEvidence));
    }
    if (prepareTransactionOperations == null) {
      return failPreparedWorldInstanceOwned(
          tenantId, gameInstanceId, expectedLifecycleEpoch, reason, null);
    }
    return Objects.requireNonNull(
        prepareTransactionOperations.execute(
            status ->
                failPreparedWorldInstanceOwned(
                    tenantId, gameInstanceId, expectedLifecycleEpoch, reason, null)),
        "Ordinary failure transaction returned no World snapshot");
  }

  private WorldInstanceLifecycleSnapshotDto failPreparedWorldInstanceOwned(
      long tenantId,
      long gameInstanceId,
      long expectedLifecycleEpoch,
      String reason,
      RunOwnedWorldLaunchEvidence fixtureEvidence) {
    WorldInstance worldInstance = requireLockedWorldInstance(tenantId, gameInstanceId);
    requireLegacyFixtureLifecycleOwner(worldInstance, fixtureEvidence);
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
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
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
    RunOwnedWorldLaunchEvidence fixtureEvidence = configuredFixtureEvidence();
    WorldInstance worldInstance =
        executeOwnedLifecycleTransaction(
            () ->
                beginOrResumeTermination(
                    tenantId,
                    gameInstanceId,
                    expectedLifecycleEpoch,
                    terminationRequestId,
                    reason,
                    fixtureEvidence));
    if (STATUS_TERMINATED.equals(worldInstance.getStatus())) {
      return snapshot(worldInstance);
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
    WorldInstanceLifecycleSnapshotDto terminatedSnapshot =
        executeOwnedLifecycleTransaction(
            () -> {
              WorldInstance finalWorldInstance =
                  requireLockedWorldInstance(tenantId, gameInstanceId);
              requireLegacyFixtureLifecycleOwner(finalWorldInstance, fixtureEvidence);
              if (!terminationRequestId.equals(finalWorldInstance.getTerminationRequestId())) {
                throw new IllegalArgumentException(
                    "INVALID_WORLD_INSTANCE_STATE: world instance termination request changed before finalization");
              }
              if (STATUS_TERMINATED.equals(finalWorldInstance.getStatus())) {
                return snapshot(finalWorldInstance);
              }
              if (!STATUS_TERMINATING.equals(finalWorldInstance.getStatus())) {
                throw new IllegalArgumentException(
                    "INVALID_WORLD_INSTANCE_STATE: world instance is not terminating during finalization");
              }
              cleanupWorldRuntimeState(tenantId, gameInstanceId);
              finalWorldInstance.setStatus(STATUS_TERMINATED);
              finalWorldInstance.setLifecycleEpoch(finalWorldInstance.getLifecycleEpoch() + 1L);
              finalWorldInstance.setTerminatedAt(Instant.now());
              return snapshot(worldInstanceRepository.save(finalWorldInstance));
            });
    logger.info(
        "Terminated world instance tenant={} gameInstanceId={} terminationRequestId={}",
        tenantId,
        gameInstanceId,
        terminationRequestId);
    return terminatedSnapshot;
  }

  private WorldInstance beginOrResumeTermination(
      long tenantId,
      long gameInstanceId,
      long expectedLifecycleEpoch,
      String terminationRequestId,
      String reason,
      RunOwnedWorldLaunchEvidence fixtureEvidence) {
    WorldInstance worldInstance = requireLockedWorldInstance(tenantId, gameInstanceId);
    requireLegacyFixtureLifecycleOwner(worldInstance, fixtureEvidence);
    if (STATUS_TERMINATED.equals(worldInstance.getStatus())) {
      if (!terminationRequestId.equals(worldInstance.getTerminationRequestId())) {
        throw new IllegalArgumentException(
            "INVALID_WORLD_INSTANCE_STATE: world instance terminated under a different request id");
      }
      return worldInstance;
    }
    if (STATUS_ACTIVE.equals(worldInstance.getStatus())) {
      requireLifecycleEpoch(worldInstance, expectedLifecycleEpoch);
      if (worldInstanceRepository.hasNonterminalInitialAdmissionBindHold(
          tenantId, gameInstanceId)) {
        throw new IllegalArgumentException(
            "INITIAL_ADMISSION_BIND_HOLD_ACTIVE: initial admission binding is unresolved");
      }
      worldInstance.setStatus(STATUS_TERMINATING);
      worldInstance.setTerminationRequestId(terminationRequestId);
      worldInstance.setFailureReason(normalizeBlank(reason));
      worldInstance.setLifecycleEpoch(worldInstance.getLifecycleEpoch() + 1L);
      return worldInstanceRepository.save(worldInstance);
    }
    if (STATUS_TERMINATING.equals(worldInstance.getStatus())) {
      if (!terminationRequestId.equals(worldInstance.getTerminationRequestId())) {
        throw new IllegalArgumentException(
            "INVALID_WORLD_INSTANCE_STATE: world instance is terminating under a different request id");
      }
      return worldInstance;
    }
    throw new IllegalArgumentException(
        "INVALID_WORLD_INSTANCE_STATE: world instance is not in ACTIVE or TERMINATING state");
  }

  private <T> T executeOwnedLifecycleTransaction(java.util.function.Supplier<T> operation) {
    if (transactionOperations == null) {
      return operation.get();
    }
    return transactionOperations.execute(status -> operation.get());
  }

  private WorldInstance requireLockedWorldInstance(long tenantId, long gameInstanceId) {
    return worldInstanceRepository
        .findByTenantIdAndGameInstanceIdForUpdate(tenantId, gameInstanceId)
        .orElseThrow(
            () ->
                new IllegalArgumentException("WORLD_INSTANCE_NOT_FOUND: world instance not found"));
  }

  private void cleanupWorldRuntimeState(long tenantId, long gameInstanceId) {
    worldEventRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    roomInstanceExitRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    roomInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    zoneInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    regionInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
  }

  private void requireLegacyFixtureLifecycleOwner(
      WorldInstance worldInstance, RunOwnedWorldLaunchEvidence fixtureEvidence) {
    boolean fixtureBound = worldInstance.getRunOwnedFixtureBindingSha256() != null;
    if (fixtureModeEnabled()) {
      if (!fixtureBound) throw fixtureIdentityConflict();
      if (fixtureEvidence == null) throw fixtureEvidenceUnavailable();
      requireRunOwnedFixtureBinding(
          worldInstance, fixtureEvidence, preparedRequest(worldInstance));
    } else if (fixtureBound) {
      throw fixtureEvidenceUnavailable();
    }
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
    if (bundle.getPublishedReleaseBundleRef().isBlank()
        || !bundle.getPublishedReleaseBundleRef().equals(request.publishedReleaseBundleRef())) {
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
      roomInstance.setRoomInstanceRowId(templateRoom.getId());
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

  record RunOwnedWorldFixtureOwners(
      AuthoredWorldSourceClient sourceClient,
      AuthoredWorldVersionStateClient versionStateClient,
      WorldAuthoredSourceIntakeService sourceIntakeService,
      WorldAuthoredVersionIdentityService versionIdentityService,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      WorldDesignPublicationFenceRepository publicationFenceRepository)
      implements AutoCloseable {
    @Override
    public void close() {
      closeFixtureClient(sourceClient, "authored-source");
      closeFixtureClient(versionStateClient, "authored-Version");
    }
  }

  private void requireSupportedReleaseAttestationSchema(String schemaVersion) {
    if (!SUPPORTED_RELEASE_ATTESTATION_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "SCHEMA_VERSION_UNSUPPORTED: unsupported published release bundle attestation schema "
              + schemaVersion);
    }
  }
}
