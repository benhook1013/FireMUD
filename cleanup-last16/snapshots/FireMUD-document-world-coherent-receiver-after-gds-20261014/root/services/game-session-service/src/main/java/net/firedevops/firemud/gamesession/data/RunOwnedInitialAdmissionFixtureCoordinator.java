package net.firedevops.firemud.gamesession.data;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.AuthorityKind;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionService;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.RunOwnedInitialLaunchResult;
import net.firedevops.firemud.gamesession.service.impl.CanonicalInitialAdmissionHoldPreparation;
import net.firedevops.firemud.gamesession.service.impl.DatabaseCanonicalInitialAdmissionService;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** Bounded, internal-only bootstrap for an explicitly claimed run-owned Compose fixture. */
@Component
public final class RunOwnedInitialAdmissionFixtureCoordinator {
  private static final Logger logger =
      LoggerFactory.getLogger(RunOwnedInitialAdmissionFixtureCoordinator.class);
  private static final int MAX_ATTEMPTS = 12;
  private static final long RETRY_INTERVAL_MS = 5_000L;
  private static final String RUNNING = "RUNNING";

  private final boolean enabled;
  private final String capabilityPath;
  private final String sourceReceiptPath;
  private final String runId;
  private final String composeProjectName;
  private final CommonGrpcClientProperties grpcProperties;
  private final GameInstanceService gameInstanceService;
  private DSLContext canonicalDsl;
  private PlatformTransactionManager canonicalTransactionManager;
  private ServiceEndpointsProperties canonicalEndpoints;
  private GrpcChannelFactory canonicalChannelFactory;
  private String workloadNamespace;
  private FixtureOwners fixedOwners;
  private org.springframework.beans.factory.ObjectProvider<GameSessionCanonicalInstanceLaunchRepository>
      configuredLaunchOwners;
  private final AtomicInteger attempts = new AtomicInteger();
  private final AtomicBoolean inProgress = new AtomicBoolean();
  private final AtomicBoolean finished = new AtomicBoolean();
  private final AtomicReference<RunOwnedInitialAdmissionFixtureCapability> acceptedCapability =
      new AtomicReference<>();
  private final AtomicReference<RunOwnedInitialAdmissionFixtureCapability.SourceReceipt>
      acceptedSourceReceipt = new AtomicReference<>();

  @Autowired
  public RunOwnedInitialAdmissionFixtureCoordinator(
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}") boolean enabled,
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH:}") String capabilityPath,
      @Value("${FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_PATH:}") String sourceReceiptPath,
      @Value("${FIREMUD_SMOKE_RUN_ID:}") String runId,
      @Value("${FIREMUD_SMOKE_COMPOSE_PROJECT_NAME:}") String composeProjectName,
      CommonGrpcClientProperties grpcProperties,
      GameInstanceService gameInstanceService) {
    this.enabled = enabled;
    this.capabilityPath = capabilityPath;
    this.sourceReceiptPath = sourceReceiptPath;
    this.runId = runId;
    this.composeProjectName = composeProjectName;
    this.grpcProperties = Objects.requireNonNull(grpcProperties, "grpcProperties");
    this.gameInstanceService = Objects.requireNonNull(gameInstanceService, "gameInstanceService");

  }

  @Autowired
  void configureCanonicalFixtureOwners(DSLContext dsl, PlatformTransactionManager transactionManager,
      ServiceEndpointsProperties endpoints, GrpcChannelFactory channelFactory,
      org.springframework.beans.factory.ObjectProvider<GameSessionCanonicalInstanceLaunchRepository> launchOwners,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    canonicalDsl = Objects.requireNonNull(dsl);
    canonicalTransactionManager = Objects.requireNonNull(transactionManager);
    canonicalEndpoints = Objects.requireNonNull(endpoints);
    canonicalChannelFactory = Objects.requireNonNull(channelFactory);
    workloadNamespace = namespace;
    configuredLaunchOwners = Objects.requireNonNull(launchOwners);
  }

  /** Controlled owner doubles may be supplied by package tests; production uses the lazy owner graph. */
  RunOwnedInitialAdmissionFixtureCoordinator(boolean enabled, String capabilityPath,
      String sourceReceiptPath, String runId, String project,
      CommonGrpcClientProperties properties, GameInstanceService gameInstances, FixtureOwners owners) {
    this(enabled, capabilityPath, sourceReceiptPath, runId, project, properties, gameInstances);
    fixedOwners = Objects.requireNonNull(owners);
  }

  @Scheduled(initialDelay = RETRY_INTERVAL_MS, fixedDelay = RETRY_INTERVAL_MS)
  void retryRunOwnedFixtureBootstrap() {
    if (!enabled || finished.get() || !inProgress.compareAndSet(false, true)) {
      return;
    }
    int attempt = attempts.incrementAndGet();
    if (attempt > MAX_ATTEMPTS) {
      finished.set(true);
      inProgress.set(false);
      return;
    }

    try {
      RunOwnedInitialAdmissionFixtureCapability capability =
          RunOwnedInitialAdmissionFixtureCapability.load(
              declaredCapabilityPath(), runId, composeProjectName, grpcProperties);
      RunOwnedInitialAdmissionFixtureCapability firstCapability =
          acceptedCapability.updateAndGet(existing -> existing == null ? capability : existing);
      if (!firstCapability.equals(capability)) {
        throw unresolved();
      }
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt sourceReceipt =
          RunOwnedInitialAdmissionFixtureCapability.loadSourceReceipt(
              declaredSourceReceiptPath(), runId, composeProjectName, capability, grpcProperties);
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt firstSourceReceipt =
          acceptedSourceReceipt.updateAndGet(
              existing -> existing == null ? sourceReceipt : existing);
      if (!firstSourceReceipt.equals(sourceReceipt)) {
        throw unresolved();
      }
      BootstrapResult result = coordinate(capability, sourceReceipt);
      if (result == BootstrapResult.COMMITTED) {
        finished.set(true);
        logger.info(
            "Run-owned initial-admission fixture committed; World hold reconciliation remains"
                + " asynchronous.");
      } else if (result == BootstrapResult.ABORTED) {
        finished.set(true);
        logger.warn(
            "Run-owned initial-admission fixture has a durable abort; World retains ownership of"
                + " hold reconciliation.");
      }
    } catch (RuntimeException exception) {
      SafeDiagnostic diagnostic = safeDiagnostic(exception);
      if (attempt >= MAX_ATTEMPTS) {
        finished.set(true);
        logger.error(
            "Run-owned initial-admission fixture did not reach a verified terminal result after"
                + " the bounded retry budget; World hold state remains owner-controlled."
                + " exceptionType={} errorCode={} message={}",
            diagnostic.exceptionType(),
            diagnostic.errorCode(),
            diagnostic.message());
      } else {
        logger.warn(
            "Run-owned initial-admission fixture attempt {}/{} remains unresolved; the same"
                + " stable operation identity will be retried. exceptionType={} errorCode={}"
                + " message={}",
            attempt,
            MAX_ATTEMPTS,
            diagnostic.exceptionType(),
            diagnostic.errorCode(),
            diagnostic.message());
      }
    } finally {
      inProgress.set(false);
    }
  }

  BootstrapResult coordinate(
      RunOwnedInitialAdmissionFixtureCapability capability,
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt sourceReceipt) {
    Objects.requireNonNull(capability, "capability");
    requireExactSourceReceipt(capability, sourceReceipt);
    RunOwnedInitialAdmissionLaunchEvidence launchEvidence =
        RunOwnedInitialAdmissionLaunchEvidence.fromVerifiedSourceReceipt(capability, sourceReceipt);
    RunOwnedInitialLaunchResult launch =
        gameInstanceService.startRunOwnedInitialLaunch(
            new StartSessionRequest(
                capability.tenantId(),
                capability.gameTemplateId(),
                capability.operationId(),
                capability.ownerAccountId()),
            launchEvidence);
    GameInstanceDto target = requireExactRunningTarget(launch, capability);

    FixtureOwners owners = fixtureOwners(launchEvidence);
    var allocated = owners.launchOwner().readVerifiedFixtureLaunch(launchEvidence);
    var catalog = allocated.catalog().snapshot();
    if (allocated.gameInstanceId() != target.id()
        || allocated.catalog().association().gameSessionTenantId() != target.tenantId()
        || allocated.status() != net.firedevops.firemud.common.gamesession
            .CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.RUNNING) {
      throw unresolved();
    }
    UUID canonicalVersion = UUID.fromString(launchEvidence.publishedReleaseBundle().getCanonicalVersionId());
    String seal = launchEvidence.fixturePolicySeal().evidenceDigest();
    String digest = CanonicalInitialAdmissionRequest.computeRequestDigest(
        catalog.targetNamespace(), catalog.tenantId(), catalog.worldSlug(), catalog.realmId(),
        catalog.playableStateNamespaceId(), "SHARED", allocated.gameInstanceUuid(), canonicalVersion,
        launch.activeLifecycleEpoch(), catalog.catalogRevision(), OriginKind.NO_PRIOR_POINTER, null,
        capability.operationId(), AuthorityKind.RUN_OWNED_FIXTURE, seal);
    var holdRequest = new WorldCanonicalInitialAdmissionHold.Request(
        catalog.targetNamespace(), catalog.tenantId(), catalog.worldSlug(), catalog.realmId(),
        catalog.playableStateNamespaceId(), "SHARED", allocated.gameInstanceUuid(), canonicalVersion,
        launch.activeLifecycleEpoch(), capability.operationId(), digest,
        InitialAdmissionOrigin.NO_PRIOR_POINTER, catalog.catalogRevision(), null,
        AuthorityKind.RUN_OWNED_FIXTURE, seal);
    var descriptor = launchEvidence.launchDescriptor().getAuthoredWorldBinding();
    var lifecycleRequest = new WorldCanonicalInstanceLifecycleEvidence.Request(
        1, UUID.fromString(capability.operationId()), catalog.targetNamespace(), catalog.tenantId(),
        catalog.worldSlug(), allocated.gameInstanceUuid(), catalog.playableStateNamespaceId(), "SHARED",
        capability.publicProductionRealm(), capability.operationId(), canonicalVersion,
        descriptor.getRequestDigest(), descriptor.getResultDigest(), null,
        AuthorityKind.RUN_OWNED_FIXTURE, seal);
    var intent = owners.holdPreparation().prepareFixture(launchEvidence, holdRequest, lifecycleRequest);
    var identity = Objects.requireNonNull(intent.holdIdentity(), "Fixture World hold identity is unavailable");
    var request = new CanonicalInitialAdmissionRequest(
        catalog.targetNamespace(), catalog.tenantId(), catalog.worldSlug(), catalog.realmId(),
        catalog.playableStateNamespaceId(), "SHARED", allocated.gameInstanceUuid(), canonicalVersion,
        launch.activeLifecycleEpoch(), catalog.catalogRevision(), OriginKind.NO_PRIOR_POINTER, null,
        capability.operationId(), digest, identity.holdId(), identity.holdFence(),
        identity.holdBindingDigest(), AuthorityKind.RUN_OWNED_FIXTURE, seal, false);
    var proof = owners.admissionService().bindFixture(launchEvidence, request);
    var independent = Objects.requireNonNull(owners.admissionService().readFixture(
        launchEvidence, request.targetNamespace(), request.initialAdmissionRequestId()),
        "Independent fixture owner readback is unavailable");
    if (!independent.equals(proof) || !request.equals(new CanonicalInitialAdmissionRequest(
        proof.targetNamespace(), proof.canonicalTenantId(), proof.worldSlug(), proof.realmId(),
        proof.playableStateNamespaceId(), proof.playableStateScope(), proof.canonicalGameInstanceId(),
        proof.canonicalVersionId(), proof.activeLifecycleEpoch(), proof.expectedCatalogRevision(),
        proof.originKind(), proof.expectedPriorPointerVersion(), proof.initialAdmissionRequestId(),
        proof.requestDigest(), proof.holdId(), proof.holdFence(), proof.holdBindingDigest(),
        proof.authorityKind(), proof.fixtureProvenanceDigest(), proof.requiresCharacterSelection()))) {
      throw unresolved();
    }
    var terminalIntent = owners.intentRepository().read(
        request.targetNamespace(), request.initialAdmissionRequestId()).orElseThrow(
            RunOwnedInitialAdmissionFixtureCoordinator::unresolved);
    if (terminalIntent.state() != net.firedevops.firemud.gamesession.dto
        .CanonicalInitialAdmissionIntentSnapshot.IntentState.TERMINAL
        || !identity.equals(terminalIntent.holdIdentity())) {
      throw unresolved();
    }
    if (proof.provesCommit()) return BootstrapResult.COMMITTED;
    if (proof.provesAbort()) return BootstrapResult.ABORTED;
    throw unresolved();
  }

  private synchronized FixtureOwners fixtureOwners(RunOwnedInitialAdmissionLaunchEvidence evidence) {
    evidence.requireVerifiedFixture();
    if (fixedOwners != null) return fixedOwners;
    if (canonicalDsl == null || canonicalTransactionManager == null
        || !evidence.sourceEvidence().targetNamespace().equals(workloadNamespace)) throw unresolved();
    var catalog = new GameSessionCanonicalRealmCatalogRepository(
        canonicalDsl, canonicalTransactionManager, workloadNamespace);
    var launch = configuredLaunchOwners == null ? null : configuredLaunchOwners.getIfAvailable();
    if (launch == null) throw unresolved();
    var humanLaunch = new CanonicalGameInstanceLaunchAssociationRepository(canonicalDsl, workloadNamespace);
    var events = new GameplayAdmissionPointerEventRepository(canonicalDsl);
    var pointer = new GameSessionCanonicalAdmissionPointerRepository(canonicalDsl, catalog, events);
    var intent = new CanonicalInitialAdmissionIntentRepository(canonicalDsl, catalog, humanLaunch, launch);
    var attempt = new CanonicalInitialAdmissionRepository(
        canonicalDsl, catalog, pointer, humanLaunch, launch, events);
    var world = new WorldCanonicalInitialAdmissionHoldClient(canonicalEndpoints, grpcProperties,
        canonicalChannelFactory, workloadNamespace, evidence.fixtureCapability().worldManagementLeafSha256());
    try { world.init(); }
    catch (java.io.IOException failure) {
      try { world.close(); } catch (java.io.IOException closeFailure) { failure.addSuppressed(closeFailure); }
      throw new IllegalStateException("Fixture World owner transport is unavailable", failure);
    }
    var preparation = new CanonicalInitialAdmissionHoldPreparation(intent, world, canonicalTransactionManager);
    var admission = new DatabaseCanonicalInitialAdmissionService(attempt, pointer, request -> {
      var retained = intent.read(request.targetNamespace(), request.initialAdmissionRequestId())
          .orElseThrow(RunOwnedInitialAdmissionFixtureCoordinator::unresolved);
      var original = retained.lifecycleRequest();
      var currentRequest = new WorldCanonicalInstanceLifecycleEvidence.Request(
          original.schemaVersion(), UUID.randomUUID(), original.targetNamespace(),
          original.canonicalTenantId(), original.worldSlug(), original.canonicalGameInstanceId(),
          original.playableStateNamespaceId(), original.playableStateScope(), original.publicProduction(),
          original.controlPlaneRequestId(), original.canonicalVersionId(),
          original.expectedDescriptorRequestDigest(), original.expectedDescriptorResultDigest(), null,
          AuthorityKind.RUN_OWNED_FIXTURE, original.fixtureProvenanceDigest());
      var state = world.readState(Objects.requireNonNull(retained.holdIdentity()), currentRequest);
      if (!state.isPendingAtExpectedActiveEpoch()) throw unresolved();
      var life = state.lifecycleEvidence();
      return new CanonicalInitialAdmissionWorldProof(request.initialAdmissionRequestId(),
          request.requestDigest(), request.targetNamespace(), request.canonicalTenantId(),
          request.worldSlug(), request.realmId(), request.playableStateNamespaceId(),
          request.playableStateScope(), request.canonicalGameInstanceId(), request.canonicalVersionId(),
          life.lifecycleStatus(), life.lifecycleEpoch(), request.originKind(), request.expectedCatalogRevision(),
          request.expectedPriorPointerVersion(), request.holdId(), request.holdFence(),
          request.holdBindingDigest(), AuthorityKind.RUN_OWNED_FIXTURE,
          request.fixtureProvenanceDigest(), life);
    }, canonicalTransactionManager);
    fixedOwners = new FixtureOwners(launch, preparation, admission, intent, attempt, world);
    return fixedOwners;
  }

  @PreDestroy
  void closeFixtureOwnerClient() throws java.io.IOException {
    if (fixedOwners != null) fixedOwners.worldClient().close();
  }

  record FixtureOwners(GameSessionCanonicalInstanceLaunchRepository launchOwner,
      CanonicalInitialAdmissionHoldPreparation holdPreparation,
      CanonicalInitialAdmissionService admissionService,
      CanonicalInitialAdmissionIntentRepository intentRepository,
      CanonicalInitialAdmissionRepository admissionRepository,
      WorldCanonicalInitialAdmissionHoldClient worldClient) {
    FixtureOwners {
      Objects.requireNonNull(launchOwner);
      Objects.requireNonNull(holdPreparation);
      Objects.requireNonNull(admissionService);
      Objects.requireNonNull(intentRepository);
      Objects.requireNonNull(admissionRepository);
      Objects.requireNonNull(worldClient);
    }
  }

  /** Loads only the fixed, independently verified fixture source; it performs no owner writes. */
  public RunOwnedInitialAdmissionLaunchEvidence loadFixedVerifiedEvidence() {
    if (!enabled) {
      throw unresolved();
    }
    RunOwnedInitialAdmissionFixtureCapability capability =
        RunOwnedInitialAdmissionFixtureCapability.load(
            declaredCapabilityPath(), runId, composeProjectName, grpcProperties);
    RunOwnedInitialAdmissionFixtureCapability firstCapability =
        acceptedCapability.updateAndGet(existing -> existing == null ? capability : existing);
    if (!firstCapability.equals(capability)) {
      throw unresolved();
    }

    RunOwnedInitialAdmissionFixtureCapability.SourceReceipt sourceReceipt =
        RunOwnedInitialAdmissionFixtureCapability.loadSourceReceipt(
            declaredSourceReceiptPath(), runId, composeProjectName, capability, grpcProperties);
    RunOwnedInitialAdmissionFixtureCapability.SourceReceipt firstSourceReceipt =
        acceptedSourceReceipt.updateAndGet(existing -> existing == null ? sourceReceipt : existing);
    if (!firstSourceReceipt.equals(sourceReceipt)) {
      throw unresolved();
    }
    return RunOwnedInitialAdmissionLaunchEvidence.fromVerifiedSourceReceipt(
        capability, sourceReceipt);
  }

  /** Authenticates the fixed World peer before any caller-controlled fixture selector is decoded. */
  public RunOwnedInitialAdmissionLaunchEvidence loadFixedVerifiedEvidenceForWorldPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null || !GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || !"world-management-service".equals(peer.service())
        || !workloadNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service").equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException("Fixed fixture read requires the exact World workload peer");
    }
    RunOwnedInitialAdmissionLaunchEvidence evidence = loadFixedVerifiedEvidence();
    GrpcPeerCertificateEvidence certificate = GrpcPeerCertificateEvidence.current();
    if (certificate == null || !evidence.fixtureCapability().worldManagementLeafSha256()
        .equals(certificate.leafSha256())) {
      throw new AdminAuthorizationException("Fixed fixture read requires the retained World recipient leaf");
    }
    return evidence;
  }

  /** Reads the actual coordinator-owned launch graph; no second durability graph is created. */
  public GameSessionCanonicalInstanceLaunchRepository.FixtureCanonicalInstanceLaunch readFixedFixtureLaunch(
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    return fixtureOwners(evidence).launchOwner().readVerifiedFixtureLaunch(evidence);
  }

  /** Reconstructs terminal evidence from the coordinator's durable owner, never current status alone. */
  public java.util.Optional<net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof>
      readFixedFixtureAdmission(RunOwnedInitialAdmissionLaunchEvidence evidence,
          String targetNamespace, String requestId) {
    return java.util.Optional.of(
        fixtureOwners(evidence).admissionService().readFixture(evidence, targetNamespace, requestId));
  }

  /** Exact current pointer plus immutable intent/attempt/audit proof from this actual owner graph. */
  public CanonicalInitialAdmissionRepository.CurrentOpenSnapshot readFixedFixtureCurrentOpenSnapshot(
      RunOwnedInitialAdmissionLaunchEvidence evidence, String namespace,
      UUID tenantId, UUID realmId) {
    evidence.requireVerifiedFixture();
    if (!Objects.equals(workloadNamespace, namespace)
        || !evidence.sourceEvidence().targetNamespace().equals(namespace)
        || !evidence.sourceEvidence().canonicalTenantId().equals(tenantId)) throw unresolved();
    return fixtureOwners(evidence).admissionRepository().readFixtureCurrentOpenSnapshotForRealm(
        evidence, namespace, tenantId, realmId);
  }

  private Path declaredCapabilityPath() {
    if (capabilityPath == null || capabilityPath.isBlank()) {
      throw unresolved();
    }
    try {
      return Path.of(capabilityPath);
    } catch (RuntimeException exception) {
      throw unresolved();
    }
  }

  private Path declaredSourceReceiptPath() {
    if (sourceReceiptPath == null || sourceReceiptPath.isBlank()) {
      throw unresolved();
    }
    try {
      return Path.of(sourceReceiptPath);
    } catch (RuntimeException exception) {
      throw unresolved();
    }
  }

  static void requireExactSourceReceipt(
      RunOwnedInitialAdmissionFixtureCapability capability,
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt receipt) {
    if (receipt == null
        || !RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_SCHEMA.equals(receipt.schema())
        || !capability.runId().equals(receipt.runId())
        || !capability.composeProjectName().equals(receipt.composeProjectName())
        || !capability.operationId().equals(receipt.fixtureOperationId())
        || !RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_OWNER_SERVICE.equals(
            receipt.ownerService())
        || !capability.gameSessionUriSan().equals(receipt.gameSessionUriSan())
        || !capability.gameSessionLeafSha256().equals(receipt.gameSessionLeafSha256())
        || !capability.caCertificateSha256().equals(receipt.caCertificateSha256())
        || !capability.worldManagementUriSan().equals(receipt.worldManagementUriSan())
        || !capability.worldManagementLeafSha256().equals(receipt.worldManagementLeafSha256())
        || receipt.creationEvidence() == null
        || receipt.fixturePolicySeal() == null
        || receipt.sourceEvidence() == null
        || !RunOwnedInitialAdmissionFixtureCapability.SOURCE_TARGET_NAMESPACE.equals(
            receipt.sourceEvidence().targetNamespace())
        || !receipt.creationEvidence().targetNamespace().equals(receipt.sourceEvidence().targetNamespace())
        || !receipt.creationEvidence().canonicalTenantId().equals(receipt.sourceEvidence().canonicalTenantId())
        || receipt.creationEvidence().sourceGameRowId() != receipt.sourceEvidence().sourceGameRowId()
        || !receipt.creationEvidence().sourceGameTenantKey().equals(receipt.sourceEvidence().sourceGameTenantKey())
        || !receipt.creationEvidence().provenanceKind().equals(receipt.sourceEvidence().provenanceKind())
        || receipt.launchReadEvidence() == null
        || receipt.launchReadEvidence().launchDescriptor() == null
        || !RunOwnedInitialAdmissionFixtureCapability.LAUNCH_READ_EVIDENCE_SCHEMA.equals(
            receipt.launchReadEvidence().schema())
        || receipt.launchReadEvidence().gameTemplateId() != capability.gameTemplateId()
        || !capability.operationId().equals(receipt.sourceEvidence().registrationRequestId().toString())
        || !capability.worldSlug().equals(receipt.sourceEvidence().worldSlug())
        || !capability.worldDisplayName().equals(receipt.sourceEvidence().worldDisplayName())
        || !Long.toString(capability.tenantId())
            .equals(receipt.sourceEvidence().sourceGameTenantKey())) {
      throw unresolved();
    }
  }

  private static GameInstanceDto requireExactRunningTarget(
      RunOwnedInitialLaunchResult launch, RunOwnedInitialAdmissionFixtureCapability capability) {
    if (launch == null || launch.gameInstance() == null || launch.activeLifecycleEpoch() <= 0L) {
      throw unresolved();
    }
    GameInstanceDto target = launch.gameInstance();
    if (target.id() == null
        || target.id() <= 0L
        || target.tenantId() == null
        || target.tenantId() <= 0L
        || target.gameTemplateId() == null
        || target.gameTemplateId() != capability.gameTemplateId()
        || target.ownerAccountId() == null
        || target.ownerAccountId() != capability.ownerAccountId()
        || target.versionId() == null
        || target.versionId() <= 0L
        || target.launchDescriptorId() == null
        || target.launchDescriptorId().isBlank()
        || !RUNNING.equals(target.status())) {
      throw unresolved();
    }
    return target;
  }

  private static RuntimeException unresolved() {
    return new IllegalStateException("Run-owned initial-admission fixture remains unresolved");
  }

  private static SafeDiagnostic safeDiagnostic(RuntimeException exception) {
    if (exception instanceof IllegalArgumentException) {
      return new SafeDiagnostic(
          exception.getClass().getSimpleName(),
          "FIXTURE_INPUT_REJECTED",
          "Fixture input or owner response was rejected.");
    }
    if (exception instanceof IllegalStateException) {
      return new SafeDiagnostic(
          exception.getClass().getSimpleName(),
          "FIXTURE_STATE_UNRESOLVED",
          "Fixture state remains unresolved.");
    }
    return new SafeDiagnostic(
        exception.getClass().getSimpleName(),
        "FIXTURE_ATTEMPT_FAILED",
        "Fixture dependency attempt failed.");
  }

  private record SafeDiagnostic(String exceptionType, String errorCode, String message) {}

  enum BootstrapResult {
    COMMITTED,
    ABORTED
  }
}
