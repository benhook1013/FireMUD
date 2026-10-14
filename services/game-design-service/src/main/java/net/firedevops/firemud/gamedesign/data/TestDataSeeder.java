package net.firedevops.firemud.gamedesign.data;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.smoke.RunOwnedFixtureImmutableFileReader;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(
    prefix = "firemud.smoke.seed-demo-runtime",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class TestDataSeeder implements ApplicationRunner {
  private static final String FIXTURE_RECEIPT_SCHEMA =
      "firemud.run-owned-authored-world-source-receipt.v1";
  private static final String FIXTURE_OWNER_SERVICE = "game-design-service";
  private static final String FIXTURE_NAMESPACE = "dev";
  private static final String FIXTURE_TENANT_SLUG = "demo";
  private static final String FIXTURE_WORLD_SLUG = "demo";
  private static final String FIXTURE_WORLD_DISPLAY_NAME = "Demo World";
  private static final String FIXTURE_GAME_SESSION_URI_SAN =
      "spiffe://firemud/ns/dev/sa/game-session-service";
  private static final String FIXTURE_WORLD_MANAGEMENT_URI_SAN =
      "spiffe://firemud/ns/dev/sa/world-management-service";
  private static final int MAX_FIXTURE_FILE_BYTES = 16 * 1024;

  private final GameRepository gameRepository;
  private final GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  private final GameTemplateRepository templateRepository;
  private final RevisionRepository revisionRepository;
  private final VersionRepository versionRepository;
  private final PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  private final VersionAssetArtifactRepository versionAssetArtifactRepository;
  private final RunOwnedFixtureLaunchReadback launchReadback;
  private final ObjectMapper objectMapper;

  @org.springframework.beans.factory.annotation.Autowired
  private net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository
      creationRepository;

  @org.springframework.beans.factory.annotation.Autowired
  private RunOwnedFixturePolicyProducer fixturePolicyProducer;

  @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}")
  private boolean initialAdmissionFixtureEnabled;

  @Value("${FIREMUD_SMOKE_RUN_ID:}")
  private String fixtureRunId;

  @Value("${FIREMUD_SMOKE_COMPOSE_PROJECT_NAME:}")
  private String fixtureComposeProjectName;

  @Value(
      "${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH:/app/run-owned-initial-admission-capability.json}")
  private String fixtureCapabilityPath;

  @Value(
      "${FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_PATH:/app/run-owned-authored-world-source/source-receipt.json}")
  private String fixtureReceiptPath;

  @Value("${FIREMUD_SMOKE_GAME_SESSION_LEAF_CERT_PATH:/app/game-session-fixture-client.crt}")
  private String gameSessionLeafCertPath;

  @Value(
      "${FIREMUD_SMOKE_WORLD_MANAGEMENT_LEAF_CERT_PATH:/app/world-management-fixture-client.crt}")
  private String worldManagementLeafCertPath;

  @Value("${firemud.grpc.workload-namespace:}")
  private String workloadNamespace;

  @Value("${firemud.grpc.ca-cert:certs/ca.crt}")
  private String caCertPath;

  private static final String DEMO_TENANT_ID = "1";
  private static final String DEMO_MANIFEST_HASH = "demo-manifest-hash";
  private static final String DEMO_GENERATION_CONFIG_REVISION = "genrev:demo";
  private static final String DEMO_PUBLISH_WORKFLOW_ID = "demo-seed-publish";
  private static final String DEMO_REVISION_KIND = "GENERIC";
  private static final int DEMO_VERSION_NUMBER = 1;
  private static final String DEMO_TEMPLATE_NAME = "Default Template";

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!initialAdmissionFixtureEnabled
        && versionRepository
            .findByTenantIdAndVersionNumber(DEMO_TENANT_ID, DEMO_VERSION_NUMBER)
            .filter(
                version ->
                    version.getCanonicalVersionId() == null
                        && version.getCanonicalTenantId() == null
                        && version.getIdentitySourceGameRowId() == null
                        && version.getIdentitySourceGameTenantKey() == null
                        && version.getIdentitySourceProvenanceKind() == null)
            .isPresent()) {
      seedRetainedBaselineRuntime();
      return;
    }
    FixtureCapability verifiedCapability =
        initialAdmissionFixtureEnabled ? loadFixtureCapability() : null;
    net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence creation =
        verifiedCapability == null
            ? null
            : creationRepository.createCandidate(
                FIXTURE_NAMESPACE,
                verifiedCapability.operationId(),
                DEMO_TENANT_ID,
                "Demo Game",
                "Seed game");
    Game game = gameRepository.findByTenantId(DEMO_TENANT_ID);
    if (game == null) {
      game = new Game();
      game.setTenantId(DEMO_TENANT_ID);
      game.setName("Demo Game");
      game.setDescription("Seed game");
      game = gameRepository.save(game);
    } else if (!DEMO_TENANT_ID.equals(game.getTenantId())
        || !"Demo Game".equals(game.getName())
        || !"Seed game".equals(game.getDescription())
        || game.getId() == null
        || game.getCanonicalTenantId() == null) {
      throw new IllegalStateException("Existing demo game does not exactly match its seed");
    }

    Version version =
        versionRepository
            .findByTenantIdAndVersionNumber(DEMO_TENANT_ID, DEMO_VERSION_NUMBER)
            .orElse(null);
    if (version == null) {
      version = new Version();
      version.setTenantId(DEMO_TENANT_ID);
      version.setVersionNumber(DEMO_VERSION_NUMBER);
      version.setVersionState(VersionLifecycleState.PUBLISHED);
      version.setVersionStateEpoch(1L);
      version.setScriptPatchVersion(null);
      version.setBaseVersionId(null);
      version.setScriptOnly(false);
      version.setNotes("Seed published version");
      version = versionRepository.save(version);
    } else {
      GameTenantIdentity gameIdentity =
          gameRepository
              .findRuntimeTenantIdentityByTenantKey(game.getTenantId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Existing demo game tenant identity cannot be read"));
      if (!matchesDemoVersion(version, game, gameIdentity)) {
        throw new IllegalStateException(
            "Existing published version does not exactly match the demo seed; refusing to reuse it");
      }
    }
    Long versionId = version.getId();

    GameTemplate template =
        templateRepository.findByTenantIdAndName(DEMO_TENANT_ID, DEMO_TEMPLATE_NAME).orElse(null);
    if (template == null) {
      template = new GameTemplate();
      template.setTenantId(DEMO_TENANT_ID);
      template.setName(DEMO_TEMPLATE_NAME);
      template.setDescription("Demo template");
      template.setConfig("{}");
      template.setDefaultVersionId(version.getId());
      template.setDefaultScriptPatchVersion(null);
      template.setDefaultRuntimeFlagsJson("{}");
      template.setTemplateReferencePhase(
          net.firedevops.firemud.gamedesign.model.TemplateReferencePhase.ENFORCED);
      template = templateRepository.save(template);
    } else if (!matchesDemoTemplate(template, version)) {
      throw new IllegalStateException(
          "Existing default template does not exactly match the demo seed; refusing to reuse it");
    }

    Revision revision =
        revisionRepository
            .findByTenantIdAndVersionIdAndRevisionKind(
                DEMO_TENANT_ID, versionId, DEMO_REVISION_KIND)
            .orElse(null);
    if (revision == null) {
      revision = new Revision();
      revision.setTenantId(DEMO_TENANT_ID);
      revision.setVersionId(versionId);
      revision.setRevisionKind(DEMO_REVISION_KIND);
    }
    revision.setAuthorAccountId(1L);
    revision.setData("{}");
    revisionRepository.save(revision);

    PublishedReleaseBundle bundle = seedPublishedReleaseBundle(game, version);

    VersionAssetArtifact artifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(DEMO_TENANT_ID, versionId)
            .orElse(null);
    if (artifact == null) {
      artifact = new VersionAssetArtifact();
      artifact.setTenantId(DEMO_TENANT_ID);
      artifact.setVersionId(versionId);
      artifact.setExportedVersionNumber(version.getVersionNumber());
      artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
      artifact.setStateEpoch(1L);
      artifact.setManifestHash(bundle.getManifestHash());
      artifact.setLastWorkflowId(DEMO_PUBLISH_WORKFLOW_ID);
      artifact.setLastErrorCode(null);
      artifact.setLastErrorMessage(null);
      artifact.setExportedManifestAssetKeysJson("[]");
      versionAssetArtifactRepository.save(artifact);
    } else if (!matchesDemoArtifact(artifact, version, bundle)) {
      throw new IllegalStateException(
          "Existing version artifact does not exactly match the demo seed; refusing to reuse it");
    }

    if (initialAdmissionFixtureEnabled) {
      registerRunOwnedAuthoredWorldSource(game, template, version, verifiedCapability, creation);
    }
  }

  /** Preserves the ordinary retained-v1 seed boundary without granting fixture source authority. */
  private void seedRetainedBaselineRuntime() {
    Game game = gameRepository.findByTenantId(DEMO_TENANT_ID);
    if (game == null) {
      game = new Game();
    }
    game.setTenantId(DEMO_TENANT_ID);
    game.setName("Demo Game");
    game.setDescription("Seed game");
    gameRepository.save(game);

    Version version =
        versionRepository
            .findByTenantIdAndVersionNumber(DEMO_TENANT_ID, DEMO_VERSION_NUMBER)
            .orElse(null);
    if (version == null) {
      version = new Version();
      version.setTenantId(DEMO_TENANT_ID);
      version.setVersionNumber(DEMO_VERSION_NUMBER);
    }
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(1L);
    version.setScriptPatchVersion(null);
    version.setBaseVersionId(null);
    version.setScriptOnly(false);
    version.setNotes("Seed published version");
    version = versionRepository.save(version);
    Long versionId = version.getId();

    GameTemplate template =
        templateRepository.findByTenantIdAndName(DEMO_TENANT_ID, DEMO_TEMPLATE_NAME).orElse(null);
    if (template == null) {
      template = new GameTemplate();
      template.setTenantId(DEMO_TENANT_ID);
      template.setName(DEMO_TEMPLATE_NAME);
    }
    template.setDescription("Demo template");
    template.setConfig("{}");
    template.setDefaultVersionId(version.getId());
    template.setDefaultScriptPatchVersion(null);
    template.setDefaultRuntimeFlagsJson("{}");
    templateRepository.save(template);

    Revision revision =
        revisionRepository
            .findByTenantIdAndVersionIdAndRevisionKind(
                DEMO_TENANT_ID, versionId, DEMO_REVISION_KIND)
            .orElse(null);
    if (revision == null) {
      revision = new Revision();
      revision.setTenantId(DEMO_TENANT_ID);
      revision.setVersionId(versionId);
      revision.setRevisionKind(DEMO_REVISION_KIND);
    }
    revision.setAuthorAccountId(1L);
    revision.setData("{}");
    revisionRepository.save(revision);

    PublishedReleaseBundle bundle =
        publishedReleaseBundleRepository
            .findByTenantIdAndVersionId(DEMO_TENANT_ID, versionId)
            .orElse(null);
    if (bundle == null) {
      bundle = new PublishedReleaseBundle();
      bundle.setTenantId(DEMO_TENANT_ID);
      bundle.setVersionId(versionId);
      bundle.setVersionNumber(version.getVersionNumber());
      bundle.setAttestationSchemaVersion("v1");
      bundle.setPublishWorkflowId(DEMO_PUBLISH_WORKFLOW_ID);
      bundle.setManifestHash(DEMO_MANIFEST_HASH);
      bundle.setGenerationConfigRevision(DEMO_GENERATION_CONFIG_REVISION);
      bundle.setRequiredManifestAssetKeysJson("[]");
      bundle.setParticipantDigestsJson("[]");
      bundle.setCommandDefinitionsJson("[]");
      bundle.setScriptOnly(false);
      bundle.setScriptPatchVersion(null);
      bundle = publishedReleaseBundleRepository.save(bundle);
    }

    VersionAssetArtifact artifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(DEMO_TENANT_ID, versionId)
            .orElse(null);
    if (artifact == null) {
      artifact = new VersionAssetArtifact();
      artifact.setTenantId(DEMO_TENANT_ID);
      artifact.setVersionId(versionId);
      artifact.setExportedVersionNumber(version.getVersionNumber());
      artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
      artifact.setStateEpoch(1L);
      artifact.setManifestHash(bundle.getManifestHash());
      artifact.setLastWorkflowId(DEMO_PUBLISH_WORKFLOW_ID);
      artifact.setLastErrorCode(null);
      artifact.setLastErrorMessage(null);
      artifact.setExportedManifestAssetKeysJson("[]");
      versionAssetArtifactRepository.save(artifact);
    } else if (!matchesDemoArtifact(artifact, version, bundle)) {
      throw new IllegalStateException(
          "Existing version artifact does not exactly match the demo seed; refusing to reuse it");
    }
  }

  private boolean matchesDemoVersion(Version version, Game game, GameTenantIdentity gameIdentity) {
    return version.getId() != null
        && version.getId() > 0L
        && DEMO_TENANT_ID.equals(version.getTenantId())
        && version.getVersionNumber() == DEMO_VERSION_NUMBER
        && version.getVersionState() == VersionLifecycleState.PUBLISHED
        && Objects.equals(version.getVersionStateEpoch(), 1L)
        && version.getScriptPatchVersion() == null
        && version.getBaseVersionId() == null
        && !version.isScriptOnly()
        && "Seed published version".equals(version.getNotes())
        && version.getCanonicalVersionId() != null
        && !version.getCanonicalVersionId().equals(new UUID(0L, 0L))
        && Objects.equals(version.getCanonicalTenantId(), game.getCanonicalTenantId())
        && Objects.equals(version.getIdentitySourceGameRowId(), game.getId())
        && Objects.equals(version.getIdentitySourceGameTenantKey(), game.getTenantId())
        && Objects.equals(version.getIdentitySourceGameRowId(), gameIdentity.sourceGameId())
        && Objects.equals(
            version.getIdentitySourceGameTenantKey(), gameIdentity.sourceLegacyTenantId())
        && gameIdentity.provenanceKind() != null
        && Objects.equals(
            version.getIdentitySourceProvenanceKind(), gameIdentity.provenanceKind().name())
        && Objects.equals(gameIdentity.canonicalTenantId(), game.getCanonicalTenantId())
        && version.getCreatedAt() != null
        && version.getUpdatedAt() != null;
  }

  private boolean matchesDemoTemplate(GameTemplate template, Version version) {
    return template.getId() != null
        && template.getId() > 0L
        && DEMO_TENANT_ID.equals(template.getTenantId())
        && DEMO_TEMPLATE_NAME.equals(template.getName())
        && "Demo template".equals(template.getDescription())
        && "{}".equals(template.getConfig())
        && Objects.equals(template.getDefaultVersionId(), version.getId())
        && template.getDefaultScriptPatchVersion() == null
        && "{}".equals(template.getDefaultRuntimeFlagsJson())
        && template.getTemplateReferencePhase()
            == net.firedevops.firemud.gamedesign.model.TemplateReferencePhase.ENFORCED
        && template.getCreatedAt() != null;
  }

  private boolean matchesDemoArtifact(
      VersionAssetArtifact artifact, Version version, PublishedReleaseBundle bundle) {
    return artifact.getId() != null
        && artifact.getId() > 0L
        && DEMO_TENANT_ID.equals(artifact.getTenantId())
        && Objects.equals(artifact.getVersionId(), version.getId())
        && artifact.getExportedVersionNumber() == version.getVersionNumber()
        && artifact.getArtifactState() == VersionAssetArtifactState.PUBLISHED
        && artifact.getStateEpoch() == 1L
        && Objects.equals(artifact.getManifestHash(), bundle.getManifestHash())
        && DEMO_PUBLISH_WORKFLOW_ID.equals(artifact.getLastWorkflowId())
        && artifact.getLastErrorCode() == null
        && artifact.getLastErrorMessage() == null
        && "[]".equals(artifact.getExportedManifestAssetKeysJson())
        && artifact.getUpdatedAt() != null;
  }

  private PublishedReleaseBundle seedPublishedReleaseBundle(Game game, Version version) {
    PublishedReleaseBundle expected = new PublishedReleaseBundle();
    expected.setTenantId(DEMO_TENANT_ID);
    expected.setVersionId(version.getId());
    expected.setCanonicalTenantId(game.getCanonicalTenantId());
    expected.setCanonicalVersionId(version.getCanonicalVersionId());
    expected.setVersionNumber(version.getVersionNumber());
    expected.setAttestationSchemaVersion("v1");
    expected.setPublishWorkflowId(DEMO_PUBLISH_WORKFLOW_ID);
    expected.setManifestHash(DEMO_MANIFEST_HASH);
    expected.setGenerationConfigRevision(DEMO_GENERATION_CONFIG_REVISION);
    expected.setRequiredManifestAssetKeysJson("[]");
    expected.setParticipantDigestsJson("[]");
    expected.setCommandDefinitionsJson("[]");
    expected.setScriptOnly(false);
    expected.setScriptPatchVersion(null);

    PublishedReleaseBundle stored =
        publishedReleaseBundleRepository
            .findByTenantIdAndVersionId(DEMO_TENANT_ID, version.getId())
            .orElse(null);
    if (stored == null) {
      return publishedReleaseBundleRepository.save(expected);
    }

    if (!matchesDemoReleaseBundle(stored, expected)
        || stored.getId() == null
        || stored.getPublishedReleaseBundleRef() == null
        || stored.getPublishedReleaseBundleRef().isBlank()
        || stored.getPublishedAt() == null) {
      throw new IllegalStateException(
          "Existing published release bundle does not exactly match the demo seed; "
              + "refusing to reuse it");
    }
    return stored;
  }

  private boolean matchesDemoReleaseBundle(
      PublishedReleaseBundle stored, PublishedReleaseBundle expected) {
    return Objects.equals(stored.getTenantId(), expected.getTenantId())
        && Objects.equals(stored.getVersionId(), expected.getVersionId())
        && Objects.equals(stored.getCanonicalTenantId(), expected.getCanonicalTenantId())
        && Objects.equals(stored.getCanonicalVersionId(), expected.getCanonicalVersionId())
        && stored.getVersionNumber() == expected.getVersionNumber()
        && Objects.equals(
            stored.getAttestationSchemaVersion(), expected.getAttestationSchemaVersion())
        && Objects.equals(stored.getPublishWorkflowId(), expected.getPublishWorkflowId())
        && Objects.equals(stored.getManifestHash(), expected.getManifestHash())
        && Objects.equals(stored.getManifestSchemaVersion(), expected.getManifestSchemaVersion())
        && Objects.equals(stored.getArtifactDigestsJson(), expected.getArtifactDigestsJson())
        && Objects.equals(
            stored.getGenerationConfigRevision(), expected.getGenerationConfigRevision())
        && Objects.equals(
            stored.getRequiredManifestAssetKeysJson(), expected.getRequiredManifestAssetKeysJson())
        && Objects.equals(stored.getParticipantDigestsJson(), expected.getParticipantDigestsJson())
        && Objects.equals(stored.getCommandDefinitionsJson(), expected.getCommandDefinitionsJson())
        && stored.isScriptOnly() == expected.isScriptOnly()
        && Objects.equals(stored.getScriptPatchVersion(), expected.getScriptPatchVersion());
  }

  private void registerRunOwnedAuthoredWorldSource(
      Game game,
      GameTemplate template,
      Version version,
      FixtureCapability capability,
      net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence creation) {
    if (!Objects.equals(template.getId(), capability.gameTemplateId())) {
      throw new IllegalStateException(
          "Run-owned fixture capability does not name the persisted default template");
    }
    if (game.getCanonicalTenantId() == null
        || game.getCanonicalTenantId().equals(new UUID(0L, 0L))) {
      throw new IllegalStateException("Persisted demo game has no canonical tenant identity");
    }
    AuthoredWorldSourceEvidence registered =
        authoredWorldSourceRepository.register(
            FIXTURE_NAMESPACE,
            capability.operationId(),
            game.getCanonicalTenantId(),
            FIXTURE_TENANT_SLUG,
            FIXTURE_WORLD_SLUG,
            FIXTURE_WORLD_DISPLAY_NAME);
    if (!capability.operationId().equals(registered.registrationRequestId())) {
      throw new IllegalStateException(
          "Run-owned source registration does not match the fixture capability request ID");
    }
    RunOwnedFixtureLaunchReadback.Snapshot captured =
        launchReadback.captureCommittedTransaction(
            game.getId(), capability.gameTemplateId(), version.getId(), registered);
    if (!TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Run-owned authored-world source registration requires the seeder transaction");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            publishCommittedSourceReceipt(capability, creation, captured);
          }
        });
  }

  private FixtureCapability loadFixtureCapability() {
    return verifyFixtureConfiguration(
        workloadNamespace,
        fixtureRunId,
        fixtureComposeProjectName,
        fixtureCapabilityPath,
        fixtureReceiptPath,
        gameSessionLeafCertPath,
        worldManagementLeafCertPath,
        caCertPath);
  }

  /** The producer and fixed read adapters share the exact immutable configuration verifier. */
  static FixtureCapability verifyFixtureConfiguration(
      String workloadNamespace,
      String fixtureRunId,
      String fixtureComposeProjectName,
      String fixtureCapabilityPath,
      String fixtureReceiptPath,
      String gameSessionLeafCertPath,
      String worldManagementLeafCertPath,
      String caCertPath) {
    if (!FIXTURE_NAMESPACE.equals(workloadNamespace)
        || !safeFixtureId(fixtureRunId)
        || !safeFixtureId(fixtureComposeProjectName)
        || !validCapabilityPath(fixtureCapabilityPath)
        || !validReceiptPath(fixtureReceiptPath)
        || !absolutePath(gameSessionLeafCertPath)
        || !absolutePath(worldManagementLeafCertPath)
        || !absolutePath(caCertPath)) {
      throw new IllegalStateException(
          "Run-owned authored-world fixture configuration is incomplete");
    }

    RunOwnedInitialAdmissionFixtureWireCodec.Capability capability;
    try {
      capability =
          RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(
              readBoundedImmutableFile(Path.of(fixtureCapabilityPath), "capability"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Could not read run-owned fixture capability", exception);
    }
    if (!fixtureRunId.equals(capability.runId())
        || !fixtureComposeProjectName.equals(capability.composeProjectName())
        || capability.tenantId() != 1L) {
      throw new IllegalStateException(
          "Run-owned fixture capability does not match the configured Game Design scope");
    }
    UUID operationId = UUID.fromString(capability.operationId());
    long gameTemplateId = capability.gameTemplateId();
    String gameSessionLeafDigest = capability.gameSessionLeafSha256();
    String worldManagementLeafDigest = capability.worldManagementLeafSha256();
    String caDigest = capability.caCertificateSha256();
    X509Certificate gameSessionCertificate = readCertificate(Path.of(gameSessionLeafCertPath));
    X509Certificate worldManagementCertificate =
        readCertificate(Path.of(worldManagementLeafCertPath));
    X509Certificate caCertificate = readCertificate(Path.of(caCertPath));
    requireCertificatePins(
        gameSessionCertificate,
        worldManagementCertificate,
        caCertificate,
        gameSessionLeafDigest,
        worldManagementLeafDigest,
        caDigest);
    return new FixtureCapability(
        operationId,
        gameTemplateId,
        gameSessionLeafDigest,
        FIXTURE_WORLD_MANAGEMENT_URI_SAN,
        worldManagementLeafDigest,
        caDigest,
        capability);
  }

  private void publishCommittedSourceReceipt(
      FixtureCapability capability,
      net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence creation,
      RunOwnedFixtureLaunchReadback.Snapshot captured) {
    var committedCreation = launchReadback.readCommittedCreation(creation);
    RunOwnedFixtureLaunchReadback.Snapshot verifiedBeforeResolve =
        launchReadback.readCommittedOutcome(captured);
    AuthoredWorldSourceEvidence capturedSource = verifiedBeforeResolve.sourceEvidence();
    AuthoredWorldLaunchDescriptorEvidence.Request launchRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            FIXTURE_NAMESPACE,
            capability.operationId().toString(),
            capturedSource.canonicalTenantId(),
            capturedSource.worldSlug(),
            capturedSource.operationId(),
            capturedSource.evidenceDigest(),
            capability.gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    ResolvedLaunchDescriptorDto resolvedDescriptor =
        launchReadback.resolveAndReadLaunchDescriptor(launchRequest);
    RunOwnedFixtureLaunchReadback.Snapshot committedAfterResolve =
        launchReadback.readCommittedOutcome(captured);
    launchReadback.requireDescriptorMatchesSnapshot(
        resolvedDescriptor, launchRequest, committedAfterResolve);
    var fixturePolicySeal =
        fixturePolicyProducer.sealAndRead(
            capability, committedCreation, committedAfterResolve, resolvedDescriptor);
    byte[] receipt =
        serializeFixtureReceipt(
            capability,
            committedCreation,
            fixturePolicySeal,
            committedAfterResolve,
            resolvedDescriptor);
    if (receipt.length == 0 || receipt.length > MAX_FIXTURE_FILE_BYTES) {
      throw new IllegalStateException("Run-owned authored-world receipt exceeds its 16 KiB bound");
    }
    Path destination = Path.of(fixtureReceiptPath);
    Path directory = destination.getParent();
    if (directory == null
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(directory)) {
      throw new IllegalStateException("Run-owned authored-world receipt directory is unavailable");
    }
    try {
      if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
        requireExistingReceipt(destination, receipt);
        return;
      }
      Path temporary = Files.createTempFile(directory, ".source-receipt-", ".tmp");
      try {
        Files.write(
            temporary, receipt, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
          channel.force(true);
        }
        Files.setPosixFilePermissions(
            temporary,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
        try {
          Files.createLink(destination, temporary);
        } catch (FileAlreadyExistsException exception) {
          requireExistingReceipt(destination, receipt);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
      requireExistingReceipt(destination, receipt);
    } catch (IOException | UnsupportedOperationException exception) {
      throw new IllegalStateException(
          "Could not atomically publish authored-world source receipt", exception);
    }
  }

  private byte[] serializeFixtureReceipt(
      FixtureCapability capability,
      net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence creation,
      RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal fixturePolicySeal,
      RunOwnedFixtureLaunchReadback.Snapshot snapshot,
      ResolvedLaunchDescriptorDto launchDescriptor) {
    AuthoredWorldSourceEvidence source = snapshot.sourceEvidence();
    Map<String, Object> sourceEvidence = new LinkedHashMap<>();
    sourceEvidence.put("schemaVersion", source.schemaVersion());
    sourceEvidence.put("targetNamespace", source.targetNamespace());
    sourceEvidence.put("registrationRequestId", source.registrationRequestId().toString());
    sourceEvidence.put("operationId", source.operationId().toString());
    sourceEvidence.put("requestDigest", source.requestDigest());
    sourceEvidence.put("canonicalTenantId", source.canonicalTenantId().toString());
    sourceEvidence.put("tenantSlug", source.tenantSlug());
    sourceEvidence.put("worldSlug", source.worldSlug());
    sourceEvidence.put("worldDisplayName", source.worldDisplayName());
    sourceEvidence.put("sourceGameRowId", source.sourceGameRowId());
    sourceEvidence.put("sourceGameTenantKey", source.sourceGameTenantKey());
    sourceEvidence.put("provenanceKind", source.provenanceKind());
    sourceEvidence.put("evidenceDigest", source.evidenceDigest());

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schema", FIXTURE_RECEIPT_SCHEMA);
    envelope.put("runId", fixtureRunId);
    envelope.put("composeProjectName", fixtureComposeProjectName);
    envelope.put("fixtureOperationId", capability.operationId().toString());
    envelope.put("ownerService", FIXTURE_OWNER_SERVICE);
    envelope.put("gameSessionUriSan", FIXTURE_GAME_SESSION_URI_SAN);
    envelope.put("gameSessionLeafSha256", capability.gameSessionLeafSha256());
    envelope.put("worldManagementUriSan", capability.worldManagementUriSan());
    envelope.put("worldManagementLeafSha256", capability.worldManagementLeafSha256());
    envelope.put("caCertificateSha256", capability.caCertificateSha256());
    envelope.put("sourceEvidence", sourceEvidence);
    envelope.put("creationEvidence", creation);
    envelope.put("fixturePolicySeal", fixturePolicySeal);
    Map<String, Object> launchReadEvidence = new LinkedHashMap<>();
    launchReadEvidence.put("schema", "firemud.run-owned-v1-launch-read-evidence.v1");
    launchReadEvidence.put("gameTemplateId", snapshot.gameTemplateId());
    launchReadEvidence.put(
        "publishedReleaseBundleBase64",
        Base64.getEncoder().encodeToString(snapshot.bundleProto().toByteArray()));
    launchReadEvidence.put(
        "versionAssetArtifactStateBase64",
        Base64.getEncoder().encodeToString(snapshot.artifactProto().toByteArray()));
    launchReadEvidence.put(
        "versionStateBase64",
        Base64.getEncoder().encodeToString(snapshot.versionProto().toByteArray()));
    launchReadEvidence.put(
        "launchDescriptorBase64",
        Base64.getEncoder()
            .encodeToString(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
                        launchDescriptor.authoredWorldBinding())
                    .toByteArray()));
    envelope.put("launchReadEvidence", launchReadEvidence);
    try {
      return objectMapper.writeValueAsBytes(envelope);
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "Could not serialize authored-world source receipt", exception);
    }
  }

  private void requireExistingReceipt(Path destination, byte[] expected) throws IOException {
    byte[] existing = readBoundedImmutableFile(destination, "receipt");
    if (!MessageDigest.isEqual(existing, expected)) {
      throw new IllegalStateException(
          "Existing authored-world source receipt conflicts with committed owner evidence");
    }
  }

  private static byte[] readBoundedImmutableFile(Path path, String fileDescription) {
    return RunOwnedFixtureImmutableFileReader.readBoundedImmutableFile(
        path,
        MAX_FIXTURE_FILE_BYTES,
        () ->
            new IllegalStateException("Run-owned fixture " + fileDescription + " file is invalid"));
  }

  private static X509Certificate readCertificate(Path path) {
    try {
      byte[] bytes = readBoundedImmutableFile(path, "TLS certificate");
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Could not read run-owned fixture TLS certificate", exception);
    }
  }

  private static void requireCertificatePins(
      X509Certificate gameSessionCertificate,
      X509Certificate worldManagementCertificate,
      X509Certificate caCertificate,
      String expectedGameSessionLeafDigest,
      String expectedWorldManagementLeafDigest,
      String expectedCaDigest) {
    requireRecipientCertificatePins(
        gameSessionCertificate,
        caCertificate,
        expectedGameSessionLeafDigest,
        expectedCaDigest,
        FIXTURE_GAME_SESSION_URI_SAN,
        "Game Session");
    requireRecipientCertificatePins(
        worldManagementCertificate,
        caCertificate,
        expectedWorldManagementLeafDigest,
        expectedCaDigest,
        FIXTURE_WORLD_MANAGEMENT_URI_SAN,
        "World Management");
  }

  private static void requireRecipientCertificatePins(
      X509Certificate recipientCertificate,
      X509Certificate caCertificate,
      String expectedLeafDigest,
      String expectedCaDigest,
      String expectedUriSan,
      String recipientName) {
    try {
      recipientCertificate.checkValidity();
      caCertificate.checkValidity();
      recipientCertificate.verify(caCertificate.getPublicKey());
      if (caCertificate.getBasicConstraints() < 0
          || !recipientCertificate
              .getIssuerX500Principal()
              .equals(caCertificate.getSubjectX500Principal())
          || !expectedLeafDigest.equals(sha256(recipientCertificate.getEncoded()))
          || !expectedCaDigest.equals(sha256(caCertificate.getEncoded()))) {
        throw new IllegalStateException(
            "Run-owned fixture TLS certificates do not match capability pins");
      }
      if (GrpcPeerIdentity.fromCertificate(recipientCertificate)
          .map(GrpcPeerIdentity::uri)
          .filter(expectedUriSan::equals)
          .isEmpty()) {
        throw new IllegalStateException(
            "Run-owned " + recipientName + " certificate has the wrong workload URI SAN");
      }
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException illegalStateException) {
        throw illegalStateException;
      }
      throw new IllegalStateException(
          "Run-owned fixture " + recipientName + " certificate is not trusted", exception);
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static boolean safeFixtureId(String value) {
    return value != null && value.matches("[a-z0-9][a-z0-9-]{0,127}");
  }

  private static boolean absolutePath(String value) {
    return value != null && !value.isBlank() && Path.of(value).isAbsolute();
  }

  private static boolean validCapabilityPath(String value) {
    if (!absolutePath(value)) {
      return false;
    }
    Path fileName = Path.of(value).getFileName();
    return fileName != null
        && "run-owned-initial-admission-capability.json".equals(fileName.toString());
  }

  private static boolean validReceiptPath(String value) {
    if (!absolutePath(value)) {
      return false;
    }
    Path path = Path.of(value);
    Path parent = path.getParent();
    Path fileName = path.getFileName();
    Path parentName = parent == null ? null : parent.getFileName();
    return fileName != null
        && "source-receipt.json".equals(fileName.toString())
        && parent != null
        && parentName != null
        && "run-owned-authored-world-source".equals(parentName.toString());
  }

  /** Created only after local fixed-capability and mounted TLS validation. */
  static final class FixtureCapability {
    private final UUID operationId;
    private final long gameTemplateId;
    private final String gameSessionLeafSha256;
    private final String worldManagementUriSan;
    private final String worldManagementLeafSha256;
    private final String caCertificateSha256;
    private final RunOwnedInitialAdmissionFixtureWireCodec.Capability wire;

    private FixtureCapability(
        UUID operationId,
        long gameTemplateId,
        String gameSessionLeafSha256,
        String worldManagementUriSan,
        String worldManagementLeafSha256,
        String caCertificateSha256,
        RunOwnedInitialAdmissionFixtureWireCodec.Capability wire) {
      this.operationId = operationId;
      this.gameTemplateId = gameTemplateId;
      this.gameSessionLeafSha256 = gameSessionLeafSha256;
      this.worldManagementUriSan = worldManagementUriSan;
      this.worldManagementLeafSha256 = worldManagementLeafSha256;
      this.caCertificateSha256 = caCertificateSha256;
      this.wire = wire;
    }

    UUID operationId() {
      return operationId;
    }

    long gameTemplateId() {
      return gameTemplateId;
    }

    String gameSessionLeafSha256() {
      return gameSessionLeafSha256;
    }

    String worldManagementUriSan() {
      return worldManagementUriSan;
    }

    String worldManagementLeafSha256() {
      return worldManagementLeafSha256;
    }

    String caCertificateSha256() {
      return caCertificateSha256;
    }

    RunOwnedInitialAdmissionFixtureWireCodec.Capability wire() {
      return wire;
    }
  }
}
