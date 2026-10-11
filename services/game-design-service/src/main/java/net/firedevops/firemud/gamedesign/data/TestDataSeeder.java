package net.firedevops.firemud.gamedesign.data;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
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
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
      registerRunOwnedAuthoredWorldSource(game, template, version);
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
            .orElseGet(VersionAssetArtifact::new);
    artifact.setTenantId(DEMO_TENANT_ID);
    artifact.setVersionId(versionId);
    artifact.setExportedVersionNumber(version.getVersionNumber());
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(Math.max(artifact.getStateEpoch(), 1L));
    artifact.setManifestHash(bundle.getManifestHash());
    artifact.setLastWorkflowId(DEMO_PUBLISH_WORKFLOW_ID);
    artifact.setLastErrorCode(null);
    artifact.setLastErrorMessage(null);
    artifact.setExportedManifestAssetKeysJson("[]");
    versionAssetArtifactRepository.save(artifact);
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
      Game game, GameTemplate template, Version version) {
    FixtureCapability capability = loadFixtureCapability();
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
            publishCommittedSourceReceipt(capability, captured);
          }
        });
  }

  private FixtureCapability loadFixtureCapability() {
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

    JsonNode capability = readStrictJson(Path.of(fixtureCapabilityPath));
    Set<String> expectedFields =
        Set.of(
            "schema",
            "runId",
            "composeProjectName",
            "operationId",
            "tenantId",
            "gameTemplateId",
            "ownerAccountId",
            "worldSlug",
            "worldDisplayName",
            "realmSlug",
            "realmDisplayName",
            "visible",
            "publicProductionRealm",
            "requiresCharacterSelection",
            "stateScope",
            "characterCreationPolicy",
            "gameSessionLeafSha256",
            "gameSessionUriSan",
            "worldManagementLeafSha256",
            "worldManagementUriSan",
            "caCertificateSha256");
    requireExactObjectFields(capability, expectedFields, "initial-admission capability");
    requireText(capability, "schema", "firemud.run-owned-initial-admission-fixture.v1");
    requireText(capability, "runId", fixtureRunId);
    requireText(capability, "composeProjectName", fixtureComposeProjectName);
    requireLong(capability, "tenantId", 1L);
    long gameTemplateId = requirePositiveLong(capability, "gameTemplateId");
    requireLong(capability, "ownerAccountId", 1L);
    requireText(capability, "worldSlug", FIXTURE_WORLD_SLUG);
    requireText(capability, "worldDisplayName", FIXTURE_WORLD_DISPLAY_NAME);
    requireText(capability, "realmSlug", "production");
    requireText(capability, "realmDisplayName", "Live Realm");
    requireBoolean(capability, "visible", true);
    requireBoolean(capability, "publicProductionRealm", true);
    requireBoolean(capability, "requiresCharacterSelection", false);
    requireText(capability, "stateScope", "SHARED");
    requireText(capability, "characterCreationPolicy", "ALLOW_NEW");
    requireText(capability, "gameSessionUriSan", FIXTURE_GAME_SESSION_URI_SAN);
    requireText(capability, "worldManagementUriSan", FIXTURE_WORLD_MANAGEMENT_URI_SAN);

    UUID operationId = canonicalUuid(text(capability, "operationId"), "operationId");
    String gameSessionLeafDigest = digestHex(text(capability, "gameSessionLeafSha256"));
    String worldManagementLeafDigest = digestHex(text(capability, "worldManagementLeafSha256"));
    String caDigest = digestHex(text(capability, "caCertificateSha256"));
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
        caDigest);
  }

  private void publishCommittedSourceReceipt(
      FixtureCapability capability, RunOwnedFixtureLaunchReadback.Snapshot captured) {
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
    byte[] receipt = serializeFixtureReceipt(capability, committedAfterResolve, resolvedDescriptor);
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
    byte[] existing = readBoundedRegularReadOnlyFile(destination);
    if (!MessageDigest.isEqual(existing, expected)) {
      throw new IllegalStateException(
          "Existing authored-world source receipt conflicts with committed owner evidence");
    }
  }

  private JsonNode readStrictJson(Path path) {
    try {
      byte[] bytes = readBoundedRegularReadOnlyFile(path);
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      ObjectMapper strictMapper =
          JsonMapper.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .build();
      JsonNode node = strictMapper.readTree(json);
      if (node == null || !node.isObject()) {
        throw new IllegalStateException("Run-owned fixture capability must be one JSON object");
      }
      return node;
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException(
          "Run-owned fixture capability is not strict UTF-8", exception);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Could not read run-owned fixture capability", exception);
    }
  }

  private byte[] readBoundedRegularReadOnlyFile(Path path) throws IOException {
    if (Files.isSymbolicLink(path)) {
      throw new IllegalStateException("Run-owned fixture file must not be a symlink");
    }
    BasicFileAttributes before =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile() || before.size() <= 0 || before.size() > MAX_FIXTURE_FILE_BYTES) {
      throw new IllegalStateException("Run-owned fixture file must be a bounded regular file");
    }
    Set<PosixFilePermission> permissions =
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    if (permissions.stream().anyMatch(permission -> permission.name().endsWith("WRITE"))) {
      throw new IllegalStateException("Run-owned fixture file must be read-only");
    }
    byte[] content;
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (FileChannel channel = FileChannel.open(path, options)) {
      ByteBuffer buffer = ByteBuffer.allocate(MAX_FIXTURE_FILE_BYTES + 1);
      while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
        // The extra byte detects growth beyond the contract's file bound.
      }
      if (buffer.position() > MAX_FIXTURE_FILE_BYTES) {
        throw new IllegalStateException("Run-owned fixture file exceeds its 16 KiB bound");
      }
      content = new byte[buffer.position()];
      buffer.flip();
      buffer.get(content);
      BasicFileAttributes after =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile()
          || !sameFile(before.fileKey(), after.fileKey())
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || content.length != after.size()
          || Files.isSymbolicLink(path)) {
        throw new IllegalStateException("Run-owned fixture file changed while it was read");
      }
      if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream()
          .anyMatch(permission -> permission.name().endsWith("WRITE"))) {
        throw new IllegalStateException(
            "Run-owned fixture file changed to writable while it was read");
      }
    }
    return content;
  }

  private X509Certificate readCertificate(Path path) {
    try {
      byte[] bytes = readBoundedRegularReadOnlyFile(path);
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Could not read run-owned fixture TLS certificate", exception);
    }
  }

  private void requireCertificatePins(
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

  private void requireRecipientCertificatePins(
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

  private void requireExactObjectFields(JsonNode object, Set<String> expectedFields, String label) {
    if (!object.isObject() || object.size() != expectedFields.size()) {
      throw new IllegalStateException(label + " has unknown or missing fields");
    }
    for (String name : object.propertyNames()) {
      if (!expectedFields.contains(name)) {
        throw new IllegalStateException(label + " has unknown or missing fields");
      }
    }
  }

  private void requireText(JsonNode node, String field, String expected) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || !expected.equals(value.textValue())) {
      throw new IllegalStateException("Run-owned fixture capability has an unexpected " + field);
    }
  }

  private String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalStateException("Run-owned fixture capability has an invalid " + field);
    }
    return value.textValue();
  }

  private long requirePositiveLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() <= 0L) {
      throw new IllegalStateException("Run-owned fixture capability has an unexpected " + field);
    }
    return value.longValue();
  }

  private void requireLong(JsonNode node, String field, long expected) {
    if (requirePositiveLong(node, field) != expected) {
      throw new IllegalStateException("Run-owned fixture capability has an unexpected " + field);
    }
  }

  private void requireBoolean(JsonNode node, String field, boolean expected) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean() || value.booleanValue() != expected) {
      throw new IllegalStateException("Run-owned fixture capability has an unexpected " + field);
    }
  }

  private UUID canonicalUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("not canonical");
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Run-owned fixture capability has an invalid " + field, exception);
    }
  }

  private String digestHex(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw new IllegalStateException("Run-owned fixture capability has an invalid SHA-256 pin");
    }
    return value;
  }

  private String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private boolean safeFixtureId(String value) {
    return value != null && value.matches("[a-z0-9][a-z0-9-]{0,127}");
  }

  private boolean absolutePath(String value) {
    return value != null && !value.isBlank() && Path.of(value).isAbsolute();
  }

  private boolean validCapabilityPath(String value) {
    if (!absolutePath(value)) {
      return false;
    }
    Path fileName = Path.of(value).getFileName();
    return fileName != null
        && "run-owned-initial-admission-capability.json".equals(fileName.toString());
  }

  private boolean validReceiptPath(String value) {
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

  private boolean sameFile(Object left, Object right) {
    return left == null || right == null ? left == right : left.equals(right);
  }

  private record FixtureCapability(
      UUID operationId,
      long gameTemplateId,
      String gameSessionLeafSha256,
      String worldManagementUriSan,
      String worldManagementLeafSha256,
      String caCertificateSha256) {}
}
