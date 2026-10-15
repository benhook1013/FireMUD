package net.firedevops.firemud.gamesession.data;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.FixtureCanonicalInstanceLaunch;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.FixtureCanonicalRealmCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounded, internal-only launch preparation for an explicitly claimed run-owned Compose fixture.
 */
@Component
public final class RunOwnedCanonicalLaunchPreparationOwner {
  private final boolean enabled;
  private final String capabilityPath;
  private final String sourceReceiptPath;
  private final String runId;
  private final String composeProjectName;
  private final CommonGrpcClientProperties grpcProperties;
  private final ObjectProvider<GameSessionCanonicalInstanceLaunchRepository> configuredLaunchOwners;
  private final String workloadNamespace;
  private final GameSessionCanonicalInstanceLaunchRepository fixedLaunchOwner;
  private final AtomicReference<RunOwnedInitialAdmissionFixtureCapability> acceptedCapability =
      new AtomicReference<>();
  private final AtomicReference<RunOwnedInitialAdmissionFixtureCapability.SourceReceipt>
      acceptedSourceReceipt = new AtomicReference<>();

  @Autowired
  public RunOwnedCanonicalLaunchPreparationOwner(
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}") boolean enabled,
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH:}") String capabilityPath,
      @Value("${FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_PATH:}") String sourceReceiptPath,
      @Value("${FIREMUD_SMOKE_RUN_ID:}") String runId,
      @Value("${FIREMUD_SMOKE_COMPOSE_PROJECT_NAME:}") String composeProjectName,
      CommonGrpcClientProperties grpcProperties,
      ObjectProvider<GameSessionCanonicalInstanceLaunchRepository> configuredLaunchOwners,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this(
        enabled,
        capabilityPath,
        sourceReceiptPath,
        runId,
        composeProjectName,
        grpcProperties,
        configuredLaunchOwners,
        workloadNamespace,
        null);
  }

  RunOwnedCanonicalLaunchPreparationOwner(
      boolean enabled,
      String capabilityPath,
      String sourceReceiptPath,
      String runId,
      String composeProjectName,
      CommonGrpcClientProperties grpcProperties,
      GameSessionCanonicalInstanceLaunchRepository launchOwner,
      String workloadNamespace) {
    this(
        enabled,
        capabilityPath,
        sourceReceiptPath,
        runId,
        composeProjectName,
        grpcProperties,
        null,
        workloadNamespace,
        Objects.requireNonNull(launchOwner, "launchOwner"));
  }

  private RunOwnedCanonicalLaunchPreparationOwner(
      boolean enabled,
      String capabilityPath,
      String sourceReceiptPath,
      String runId,
      String composeProjectName,
      CommonGrpcClientProperties grpcProperties,
      ObjectProvider<GameSessionCanonicalInstanceLaunchRepository> configuredLaunchOwners,
      String workloadNamespace,
      GameSessionCanonicalInstanceLaunchRepository fixedLaunchOwner) {
    this.enabled = enabled;
    this.capabilityPath = capabilityPath;
    this.sourceReceiptPath = sourceReceiptPath;
    this.runId = runId;
    this.composeProjectName = composeProjectName;
    this.grpcProperties = Objects.requireNonNull(grpcProperties, "grpcProperties");
    this.configuredLaunchOwners = configuredLaunchOwners;
    this.workloadNamespace = workloadNamespace;
    this.fixedLaunchOwner = fixedLaunchOwner;
  }

  /** Explicit source preparation; no scheduler or read RPC invokes this owner write. */
  public CommittedFixtureLaunchOwnerEvidence prepareFixedVerifiedFixtureLaunch() {
    FixedSourceReceipt source = loadFixedVerifiedSourceReceipt();
    return prepareVerifiedFixtureLaunch(source.capability(), source.receipt());
  }

  CommittedFixtureLaunchOwnerEvidence prepareVerifiedFixtureLaunch(
      RunOwnedInitialAdmissionFixtureCapability capability,
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt sourceReceipt) {
    if (!enabled) {
      throw unresolved();
    }
    Objects.requireNonNull(capability, "capability");
    requireExactSourceReceipt(capability, sourceReceipt);
    RunOwnedInitialAdmissionLaunchEvidence launchEvidence =
        RunOwnedInitialAdmissionLaunchEvidence.fromVerifiedSourceReceipt(capability, sourceReceipt);
    launchEvidence.requireVerifiedFixture();

    GameSessionCanonicalInstanceLaunchRepository launchOwner = launchOwner();
    FixtureCanonicalInstanceLaunch committed =
        Objects.requireNonNull(
            launchOwner.createVerifiedFixtureStartingLaunch(launchEvidence),
            "Fixture launch owner returned no committed STARTING allocation");
    FixtureCanonicalInstanceLaunch independent =
        Objects.requireNonNull(
            launchOwner.readVerifiedFixtureLaunch(launchEvidence),
            "Independent fixture launch readback returned no allocation");
    requireExactStartingLaunch(capability, launchEvidence, committed, independent);
    return new CommittedFixtureLaunchOwnerEvidence(launchEvidence, independent);
  }

  /** Loads only the fixed, independently verified fixture source; it performs no owner writes. */
  public RunOwnedInitialAdmissionLaunchEvidence loadFixedVerifiedEvidence() {
    FixedSourceReceipt source = loadFixedVerifiedSourceReceipt();
    return RunOwnedInitialAdmissionLaunchEvidence.fromVerifiedSourceReceipt(
        source.capability(), source.receipt());
  }

  private FixedSourceReceipt loadFixedVerifiedSourceReceipt() {
    if (!enabled) {
      throw unresolved();
    }
    RunOwnedInitialAdmissionFixtureCapability capability =
        acceptCapability(
            RunOwnedInitialAdmissionFixtureCapability.load(
                declaredCapabilityPath(), runId, composeProjectName, grpcProperties));
    RunOwnedInitialAdmissionFixtureCapability.SourceReceipt sourceReceipt =
        acceptSourceReceipt(
            RunOwnedInitialAdmissionFixtureCapability.loadSourceReceipt(
                declaredSourceReceiptPath(),
                runId,
                composeProjectName,
                capability,
                grpcProperties));
    requireExactSourceReceipt(capability, sourceReceipt);
    return new FixedSourceReceipt(capability, sourceReceipt);
  }

  /** Authenticates the fixed World peer before reading any caller-selectable fixture state. */
  public RunOwnedInitialAdmissionLaunchEvidence loadFixedVerifiedEvidenceForWorldPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || !"world-management-service".equals(peer.service())
        || !workloadNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service")
            .equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException(
          "Fixed fixture read requires the exact World workload peer");
    }
    RunOwnedInitialAdmissionLaunchEvidence evidence = loadFixedVerifiedEvidence();
    GrpcPeerCertificateEvidence certificate = GrpcPeerCertificateEvidence.current();
    if (certificate == null
        || !evidence
            .fixtureCapability()
            .worldManagementLeafSha256()
            .equals(certificate.leafSha256())) {
      throw new AdminAuthorizationException(
          "Fixed fixture read requires the retained World recipient leaf");
    }
    return evidence;
  }

  /** Reads the actual allocation through the canonical launch repository. */
  public FixtureCanonicalInstanceLaunch readFixedFixtureLaunch(
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence").requireVerifiedFixture();
    if (!Objects.equals(workloadNamespace, evidence.sourceEvidence().targetNamespace())) {
      throw unresolved();
    }
    return launchOwner().readVerifiedFixtureLaunch(evidence);
  }

  private RunOwnedInitialAdmissionFixtureCapability acceptCapability(
      RunOwnedInitialAdmissionFixtureCapability capability) {
    RunOwnedInitialAdmissionFixtureCapability first =
        acceptedCapability.updateAndGet(existing -> existing == null ? capability : existing);
    if (!Objects.equals(first, capability)) {
      throw unresolved();
    }
    return first;
  }

  private RunOwnedInitialAdmissionFixtureCapability.SourceReceipt acceptSourceReceipt(
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt sourceReceipt) {
    RunOwnedInitialAdmissionFixtureCapability.SourceReceipt first =
        acceptedSourceReceipt.updateAndGet(existing -> existing == null ? sourceReceipt : existing);
    if (!Objects.equals(first, sourceReceipt)) {
      throw unresolved();
    }
    return first;
  }

  private GameSessionCanonicalInstanceLaunchRepository launchOwner() {
    if (fixedLaunchOwner != null) {
      return fixedLaunchOwner;
    }
    GameSessionCanonicalInstanceLaunchRepository configured =
        configuredLaunchOwners == null ? null : configuredLaunchOwners.getIfAvailable();
    if (configured == null) {
      throw unresolved();
    }
    return configured;
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

  private static void requireExactStartingLaunch(
      RunOwnedInitialAdmissionFixtureCapability capability,
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      FixtureCanonicalInstanceLaunch committed,
      FixtureCanonicalInstanceLaunch independent) {
    FixtureCanonicalRealmCatalog catalog = independent.catalog();
    var snapshot = catalog.snapshot();
    var association = catalog.association();
    var source = evidence.sourceEvidence();
    var creation = evidence.creationEvidence();
    if (!committed.equals(independent)
        || independent.status() != CurrentGameInstanceStatus.STARTING
        || independent.gameInstanceId() <= 0L
        || independent.gameInstanceUuid() == null
        || new UUID(0L, 0L).equals(independent.gameInstanceUuid())
        || independent.rowVersion() != 0L
        || !RunOwnedInitialAdmissionFixtureCapability.SOURCE_TARGET_NAMESPACE.equals(
            snapshot.targetNamespace())
        || !source.targetNamespace().equals(snapshot.targetNamespace())
        || !source.canonicalTenantId().equals(snapshot.tenantId())
        || !source.tenantSlug().equals(snapshot.tenantSlug())
        || !capability.worldSlug().equals(snapshot.worldSlug())
        || !capability.realmSlug().equals(snapshot.realmSlug())
        || !capability.realmDisplayName().equals(snapshot.realmDisplayName())
        || capability.visible() != snapshot.visible()
        || capability.publicProductionRealm() != snapshot.publicProduction()
        || !capability.stateScope().equals(snapshot.stateScope())
        || !capability.characterCreationPolicy().equals(snapshot.characterCreationPolicy())
        || snapshot.catalogRevision() <= 0L
        || !source.targetNamespace().equals(association.targetNamespace())
        || !source.canonicalTenantId().equals(association.canonicalTenantId())
        || association.gameSessionTenantId() <= 0L
        || !creation.equals(association.sourceEvidence())
        || !evidence.fixtureCapability().operationId().equals(capability.operationId())
        || evidence.fixturePolicySeal() == null) {
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
        || !receipt
            .creationEvidence()
            .targetNamespace()
            .equals(receipt.sourceEvidence().targetNamespace())
        || !receipt
            .creationEvidence()
            .canonicalTenantId()
            .equals(receipt.sourceEvidence().canonicalTenantId())
        || receipt.creationEvidence().sourceGameRowId()
            != receipt.sourceEvidence().sourceGameRowId()
        || !receipt
            .creationEvidence()
            .sourceGameTenantKey()
            .equals(receipt.sourceEvidence().sourceGameTenantKey())
        || !receipt
            .creationEvidence()
            .provenanceKind()
            .equals(receipt.sourceEvidence().provenanceKind())
        || receipt.launchReadEvidence() == null
        || receipt.launchReadEvidence().launchDescriptor() == null
        || !RunOwnedInitialAdmissionFixtureCapability.LAUNCH_READ_EVIDENCE_SCHEMA.equals(
            receipt.launchReadEvidence().schema())
        || receipt.launchReadEvidence().gameTemplateId() != capability.gameTemplateId()
        || !capability
            .operationId()
            .equals(receipt.sourceEvidence().registrationRequestId().toString())
        || !capability.worldSlug().equals(receipt.sourceEvidence().worldSlug())
        || !capability.worldDisplayName().equals(receipt.sourceEvidence().worldDisplayName())
        || !Long.toString(capability.tenantId())
            .equals(receipt.sourceEvidence().sourceGameTenantKey())) {
      throw unresolved();
    }
  }

  private record FixedSourceReceipt(
      RunOwnedInitialAdmissionFixtureCapability capability,
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt receipt) {}

  private static RuntimeException unresolved() {
    return new IllegalStateException("Run-owned fixture launch preparation remains unresolved");
  }

  public record CommittedFixtureLaunchOwnerEvidence(
      RunOwnedInitialAdmissionLaunchEvidence launchEvidence,
      FixtureCanonicalInstanceLaunch launch) {
    public CommittedFixtureLaunchOwnerEvidence {
      Objects.requireNonNull(launchEvidence, "launchEvidence");
      Objects.requireNonNull(launch, "launch");
    }
  }
}
