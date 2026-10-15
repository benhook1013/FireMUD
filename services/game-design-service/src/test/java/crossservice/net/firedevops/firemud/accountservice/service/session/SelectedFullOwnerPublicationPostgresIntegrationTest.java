package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.util.JsonFormat;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationSelectedEmptyPublicationDigestGrpcReceiver;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationSelectedEmptyPublicationDigestService;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.entitymanagement.sourceintake.EntitySelectedEmptyPublicationDigestGrpcReceiver;
import net.firedevops.firemud.entitymanagement.sourceintake.EntitySelectedEmptyPublicationDigestService;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.gamedesign.client.EntityManagementClient;
import net.firedevops.firemud.gamedesign.client.GameLogicClient;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignReviewedBaseRepository;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceipt;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceiptRepository;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationOwner;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftTemplateWorldSourceAssociation;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.impl.PublishGateServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.PublishedReleaseBundleServiceImpl;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.gamelogic.service.impl.GameLogicDraftDesignDigestServiceImpl;
import net.firedevops.firemud.gamelogic.service.impl.GameLogicGrpcService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeRepository;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicPublicationSourceReadService;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.tenant.WorldSelectedFullPublicationEvidenceFixture;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceClosureDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamily;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Actual current-default GDS bundle, Entity, Automation, Game Logic, and World owner storage and
 * readbacks. Account participation/source inputs, original creator authority, World source
 * provenance, Game Logic settlement input, asset manifest, and ephemeral PKI are explicitly
 * controlled. This is a component composition proof; it does not establish genuine Account or
 * creator authority, registered ingress, or runtime activation/admission/delivery.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class SelectedFullOwnerPublicationPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";

  @Container
  static final PostgreSQLContainer<?> GDS_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> WORLD_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> ENTITY_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> AUTOMATION_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> GAME_LOGIC_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @TempDir Path temporary;

  @Test
  void actualSelectedWorldFourAndParticipantOwnersProduceAndReadBackCompleteSchemaFourBundle()
      throws Exception {
    Store gds = store("game-design", "selected_full_gds", GDS_POSTGRES, false);
    Store world = store("world-management", "selected_full_world", WORLD_POSTGRES, false);
    Store entity = store("entity-management", "selected_full_entity", ENTITY_POSTGRES, true);
    Store automation =
        store("automation-scripting", "selected_full_automation", AUTOMATION_POSTGRES, true);
    Store gameLogic = store("game-logic", "selected_full_gl", GAME_LOGIC_POSTGRES, false);

    Draft draft = createDraft(gds);
    var worldSource = registerControlledWorldSource(gds, draft.target());
    DraftCommitBinding commit = selectedCommit(gds, draft.target());
    applyGameDesignCommit(gds, commit);
    var worldGraph =
        WorldSelectedFullPublicationEvidenceFixture.applyGraph(
            world.dsl(), world.transactions(), NAMESPACE, commit, worldSource);
    completeGameDesignCommit(gds, commit, worldGraph.applied());

    String publishRequestId = "selected-full-" + UUID.randomUUID();
    var intent =
        new AuthoredDraftPublishSelection.PublishIntent(
            draft.target().canonicalTenantId(),
            draft.target().canonicalVersionId(),
            publishRequestId,
            "1",
            "controlled selected-full owner composition",
            commit.requestId(),
            commit.commitId(),
            commit.digest());
    var selection =
        gds.write()
            .execute(
                ignored ->
                    new AuthoredDraftPublishSelectionRepository(
                            gds.dsl(), new DraftCommitCoordinatorRepository(gds.dsl()))
                        .reserve(intent)
                        .selection());
    var worldOwners =
        WorldSelectedFullPublicationEvidenceFixture.applyFreezeAndCapture(
            world.dsl(),
            world.transactions(),
            NAMESPACE,
            AuthoredDraftPublishSelectionBinding.fromStored(
                selection.canonicalJson(), selection.digest()),
            worldGraph,
            SelectedFullOwnerPublicationPostgresIntegrationTest::controlledAccountBinding);
    assertThat(worldOwners.selectedWorldDigest().digestSchemaVersion()).isEqualTo(4);
    assertThat(worldOwners.appliedResultBytes()).isNotEmpty();
    assertThat(worldOwners.graphBytes()).isNotEmpty();
    assertThat(worldOwners.inventoryEvidence().freezeEvidence())
        .isEqualTo(worldOwners.freezeEvidence());

    var sourceScopeActor = UUID.randomUUID();
    var entityAuthorization =
        selectedOwnerAuthorization(gds, selection, Owner.ENTITY_MANAGEMENT, sourceScopeActor);
    var entityWorldRead =
        SelectedOwnerWorldInventoryReadEvidence.create(
            NAMESPACE, entityAuthorization, worldOwners.freezeEvidence());
    var entityInputs =
        new SelectedOwnerEmptySourceInputs(
            entityAuthorization,
            new SelectedOwnerWorldInventoryReadEvidence(
                entityWorldRead, worldOwners.inventoryEvidence()));
    var entityRepository = new EntityEmptySelectedSourceIntakeRepository(entity.dsl());
    var entityReceipt =
        entityRepository.retainFresh(
            entityInputs,
            EntityEmptySelectedSourceIntakeReceipt.requestDigest(
                NAMESPACE, entityAuthorization, worldOwners.freezeEvidence()));
    assertThat(
            entityRepository
                .read(entityReceipt.targetNamespace(), entityReceipt.intakeRequestId())
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(entityReceipt.canonicalBytes());
    assertThat(
            entityRepository
                .retainFresh(
                    entityInputs,
                    EntityEmptySelectedSourceIntakeReceipt.requestDigest(
                        NAMESPACE, entityAuthorization, worldOwners.freezeEvidence()))
                .canonicalBytes())
        .containsExactly(entityReceipt.canonicalBytes());

    var automationAuthorization =
        selectedOwnerAuthorization(gds, selection, Owner.AUTOMATION_SCRIPTING, sourceScopeActor);
    var automationWorldRead =
        SelectedOwnerWorldInventoryReadEvidence.create(
            NAMESPACE, automationAuthorization, worldOwners.freezeEvidence());
    var automationInputs =
        new SelectedOwnerEmptySourceInputs(
            automationAuthorization,
            new SelectedOwnerWorldInventoryReadEvidence(
                automationWorldRead, worldOwners.inventoryEvidence()));
    var automationRepository = new AutomationEmptySelectedSourceIntakeRepository(automation.dsl());
    var automationReceipt =
        automationRepository.retainFresh(
            automationInputs,
            net.firedevops.firemud.common.automation.sourceintake
                .AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
                NAMESPACE, automationAuthorization, worldOwners.freezeEvidence()));
    assertThat(
            automationRepository
                .read(automationReceipt.targetNamespace(), automationReceipt.intakeRequestId())
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(automationReceipt.canonicalBytes());
    assertThat(
            automationRepository
                .retainFresh(
                    automationInputs,
                    net.firedevops.firemud.common.automation.sourceintake
                        .AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
                        NAMESPACE, automationAuthorization, worldOwners.freezeEvidence()))
                .canonicalBytes())
        .containsExactly(automationReceipt.canonicalBytes());

    var synchronizedSources =
        new GameDesignSourceRepository(gds.dsl())
            .readSynchronized(draft.target(), commit.commitId())
            .orElseThrow();
    var gameplaySnapshot = synchronizedSources.gameplayEvidence().orElseThrow();
    var gameplaySource = new GameplayRuleSelectedSource(gameplaySnapshot.canonicalJson());
    var glAuthorization =
        new GameLogicIntakeAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            sourceScopeActor,
            gameplaySource,
            controlledAccountSources(sourceScopeActor, draft.target().canonicalTenantId()));
    var glOperation = new GameLogicGameplayRuleIntakeOperation(NAMESPACE, glAuthorization);
    var glTerminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            glOperation,
            gameplaySource.canonicalBytes(),
            gameplaySource.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    var glOwner = new GameLogicGameplayRuleIntakeRepository(gameLogic.dsl());
    gameLogic
        .write()
        .execute(
            ignored -> {
              glOwner.lockOperation(glAuthorization.operationId());
              glOwner.insertTerminal(glTerminal);
              return null;
            });
    var committedGlTerminal = glOwner.findTerminal(glAuthorization.operationId()).orElseThrow();
    assertThat(committedGlTerminal.canonicalBytes()).containsExactly(glTerminal.canonicalBytes());

    var selectedReceiptRepository = new SelectedDraftGameLogicReceiptRepository(gds.dsl());
    var glReceiptCandidate =
        new SelectedDraftGameLogicReceipt(
            selection,
            glAuthorization,
            new AccountGameLogicIntakeSettlementEvidence(committedGlTerminal));
    var glReceipt =
        gds.write().execute(ignored -> selectedReceiptRepository.retain(glReceiptCandidate));
    assertThat(
            selectedReceiptRepository
                .readForPublication(
                    PublicationDigestRequestBinding.full(
                        draft.target().canonicalTenantId().toString(),
                        Long.toString(draft.version().getId()),
                        publishRequestId))
                .orElseThrow()
                .receipt()
                .canonicalBytes())
        .containsExactly(glReceipt.receipt().canonicalBytes());

    var selectionBinding =
        AuthoredDraftPublishSelectionBinding.fromStored(
            selection.canonicalJson(), selection.digest());
    var accountBinding = worldOwners.accountBinding();
    var templateSourceRead =
        new SelectedDraftTemplateWorldSourceAssociation.SourceRead(
            worldOwners.sourceReadRequest(), worldOwners.sourceReadReceipt());
    var selectedOwnerReservation =
        gds.write()
            .execute(
                ignored ->
                    new SelectedDraftPublicationOwner(gds.dsl())
                        .reserve(
                            intent,
                            accountBinding,
                            worldOwners.startLocationEvidence(),
                            worldOwners.inventoryEvidence(),
                            templateSourceRead));
    assertThat(selectedOwnerReservation.selection().canonicalBytes())
        .containsExactly(selectionBinding.canonicalBytes());

    Long gdsBundleBefore =
        gds.dsl()
            .fetchSingle(
                "SELECT count(*) FROM published_release_bundle WHERE tenant_id = ? AND version_id = ?",
                draft.version().getTenantId(),
                draft.version().getId())
            .get(0, Long.class);
    assertThat(gdsBundleBefore).isZero();

    ProofPki pki = new ProofPki(temporary.resolve("selected-full-pki"));
    Server entityServer = null;
    Server automationServer = null;
    Server gameLogicServer = null;
    try {
      entityServer =
          ownerServer(
              pki,
              "entity-management-service",
              new EntitySelectedEmptyPublicationDigestGrpcReceiver(
                  new EntitySelectedEmptyPublicationDigestService(NAMESPACE, entityRepository),
                  NAMESPACE,
                  new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
      automationServer =
          ownerServer(
              pki,
              "automation-scripting-service",
              new AutomationSelectedEmptyPublicationDigestGrpcReceiver(
                  new AutomationSelectedEmptyPublicationDigestService(
                      NAMESPACE, automationRepository),
                  NAMESPACE,
                  new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
      gameLogicServer =
          ownerServer(
              pki,
              "game-logic-service",
              new GameLogicGrpcService(
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  new GameLogicDraftDesignDigestServiceImpl(
                      new GameLogicPublicationSourceReadService(glOwner, NAMESPACE)),
                  null,
                  new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                  new net.firedevops.firemud.common.security.PublicationReadGuard(NAMESPACE)));

      var endpoints = new ServiceEndpointsProperties();
      endpoints.setEntityManagementService(loopback(entityServer));
      endpoints.setAutomationScriptingService(loopback(automationServer));
      endpoints.setGameLogicService(loopback(gameLogicServer));
      var channelFactory = new GrpcChannelFactory();
      try (var entityClient =
              new ReadyEntityManagementClient(
                  endpoints, pki.client("game-design-service"), channelFactory);
          var automationClient =
              new ReadyAutomationScriptingClient(
                  endpoints, pki.client("game-design-service"), channelFactory);
          var gameLogicClient =
              new ReadyGameLogicClient(
                  endpoints, pki.client("game-design-service"), channelFactory)) {
        ReflectionTestUtils.setField(gameLogicClient, "workloadNamespace", NAMESPACE);
        entityClient.connect();
        automationClient.connect();
        gameLogicClient.connect();
        var gate =
            new PublishGateServiceImpl(
                null,
                null,
                entityClient,
                gameLogicClient,
                automationClient,
                gds.dsl(),
                gds.transactions(),
                NAMESPACE);
        VersionDto versionDto = versionDto(draft.version());
        var publicationRequest =
            PublicationDigestRequestBinding.full(
                draft.target().canonicalTenantId().toString(),
                Long.toString(draft.version().getId()),
                publishRequestId);
        var participantDigests =
            gate.collectSelectedFullVersionParticipantDigests(
                versionDto, publicationRequest, publicationRequest.derivedWorkflowIdentity());
        assertThat(participantDigests)
            .extracting(PublishParticipantDigestDto::participantKey)
            .containsExactly(
                "WORLD_MANAGEMENT",
                "ENTITY_MANAGEMENT",
                "GAME_LOGIC",
                "AUTOMATION_SCRIPTING",
                "GAME_DESIGN_CONTROL_PLANE");
        assertThat(participantDigests)
            .extracting(PublishParticipantDigestDto::digestSchemaVersion)
            .containsExactly(4, 3, 1, 6, 2);
        assertThat(participantDigests)
            .filteredOn(digest -> "WORLD_MANAGEMENT".equals(digest.participantKey()))
            .singleElement()
            .satisfies(
                digest ->
                    assertThat(digest.contentDigest())
                        .isEqualTo(worldOwners.selectedWorldDigest().contentDigest()));
        assertThat(participantDigests)
            .filteredOn(digest -> "GAME_LOGIC".equals(digest.participantKey()))
            .singleElement()
            .satisfies(digest -> assertThat(digest.abilitySchemaDigest()).startsWith("sha256:"));
        gate.assertSelectedGatePassed(versionDto, participantDigests);

        var altered = new ArrayList<>(participantDigests);
        int automationIndex = 3;
        var validAutomation = altered.get(automationIndex);
        altered.set(
            automationIndex,
            new PublishParticipantDigestDto(
                validAutomation.participantKey(),
                validAutomation.scopeValue(),
                validAutomation.baseVersionId(),
                validAutomation.appliedCommitId(),
                validAutomation.contentDigest(),
                5,
                validAutomation.abilitySchemaDigest(),
                validAutomation.errorCode(),
                validAutomation.errorMessage()));
        gate.assertSelectedGatePassed(versionDto, participantDigests);
        assertThatThrownBy(() -> gate.assertSelectedGatePassed(versionDto, altered))
            .isInstanceOf(RuntimeException.class);

        var releaseService =
            new PublishedReleaseBundleServiceImpl(
                new PublishedReleaseBundleRepository(gds.dsl()),
                new VersionRepository(gds.dsl()),
                new ObjectMapper());
        var controlledEmptyManifest = controlledEmptyManifest();
        assertThatThrownBy(
                () ->
                    gds.write()
                        .execute(
                            ignored ->
                                releaseService.createFullVersionBundle(
                                    versionDto,
                                    publicationRequest.derivedWorkflowIdentity(),
                                    controlledEmptyManifest,
                                    "controlled-generation-input",
                                    altered,
                                    worldOwners.startLocationEvidence())))
            .isInstanceOf(RuntimeException.class);
        assertThat(
                gds.dsl()
                    .fetchSingle(
                        "SELECT count(*) FROM published_release_bundle WHERE tenant_id = ? AND version_id = ?",
                        draft.version().getTenantId(),
                        draft.version().getId())
                    .get(0, Long.class))
            .isZero();

        PublishedReleaseBundleService releaseOwner = releaseService;
        var written =
            gds.write()
                .execute(
                    ignored ->
                        releaseOwner.createFullVersionBundle(
                            versionDto,
                            publicationRequest.derivedWorkflowIdentity(),
                            controlledEmptyManifest,
                            "controlled-generation-input",
                            participantDigests,
                            worldOwners.startLocationEvidence()));
        assertThat(written.attestationSchemaVersion()).isEqualTo("v4");
        assertThat(written.participantDigests()).containsExactlyElementsOf(participantDigests);
        assertThat(written.worldPublishedStartLocationEvidence().canonicalBytes())
            .containsExactly(worldOwners.startLocationEvidence().canonicalBytes());
        String committedBundleRow = bundleOwnerRow(gds, draft.version());
        var independentRead =
            new PublishedReleaseBundleServiceImpl(
                    new PublishedReleaseBundleRepository(gds.dsl()),
                    new VersionRepository(gds.dsl()),
                    new ObjectMapper())
                .findPublishedReleaseBundle(draft.version().getTenantId(), draft.version().getId())
                .orElseThrow();
        assertThat(independentRead).isEqualTo(written);
        assertThat(independentRead.attestationSchemaVersion()).isEqualTo("v4");
        assertThat(bundleOwnerRow(gds, draft.version())).isEqualTo(committedBundleRow);

        var replay =
            gds.write()
                .execute(
                    ignored ->
                        releaseOwner.createFullVersionBundle(
                            versionDto,
                            publicationRequest.derivedWorkflowIdentity(),
                            controlledEmptyManifest,
                            "controlled-generation-input",
                            participantDigests,
                            worldOwners.startLocationEvidence()));
        assertThat(replay).isEqualTo(written);
        assertThat(bundleOwnerRow(gds, draft.version())).isEqualTo(committedBundleRow);
      }
    } finally {
      stop(entityServer);
      stop(automationServer);
      stop(gameLogicServer);
    }
  }

  private static Draft createDraft(Store gds) {
    Version savedVersion =
        gds.write()
            .execute(
                ignored -> {
                  Game game = new Game();
                  game.setTenantId(UUID.randomUUID().toString());
                  game.setName("Controlled selected full owner proof");
                  Game savedGame = new GameRepository(gds.dsl()).save(game);
                  Version version = new Version();
                  version.setTenantId(savedGame.getTenantId());
                  version.setVersionNumber(1);
                  version.setVersionState(VersionLifecycleState.DRAFT);
                  version.setVersionStateEpoch(1L);
                  return new VersionRepository(gds.dsl()).save(version);
                });
    var target =
        new TargetProof(
            savedVersion.getCanonicalTenantId(),
            savedVersion.getCanonicalVersionId(),
            savedVersion.getId(),
            savedVersion.getTenantId(),
            savedVersion.getIdentitySourceGameRowId(),
            savedVersion.getIdentitySourceGameTenantKey(),
            savedVersion.getIdentitySourceProvenanceKind());
    return new Draft(savedVersion, target);
  }

  private static AuthoredWorldSourceEvidence registerControlledWorldSource(
      Store gds, TargetProof target) {
    var repository = new GameAuthoredWorldSourceRepository(gds.dsl());
    return gds.write()
        .execute(
            ignored ->
                repository.register(
                    NAMESPACE,
                    UUID.randomUUID(),
                    target.canonicalTenantId(),
                    "controlled-selected-tenant",
                    "controlled-selected-world",
                    "Controlled selected World source"));
  }

  private static DraftCommitBinding selectedCommit(Store gds, TargetProof target)
      throws com.google.protobuf.InvalidProtocolBufferException {
    var genesis = new GameDesignSourceRepository(gds.dsl()).readGenesis(target).orElseThrow();
    UUID commitId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    UUID zoneId = UUID.randomUUID();
    UUID roomId = UUID.randomUUID();
    var counts = new ArrayList<WorldFreshGraphFamilyCount>();
    for (var family :
        List.of(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING)) {
      counts.add(
          WorldFreshGraphFamilyCount.newBuilder()
              .setFamily(family)
              .setCount(
                  family == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION
                          || family == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE
                          || family == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM
                      ? 1
                      : 0)
              .build());
    }
    var inbound = WorldInboundSourceClosureDeclaration.newBuilder().setSchemaVersion(1);
    for (WorldInboundSourceFamily family :
        List.of(
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING)) {
      inbound.addFamilyCounts(
          WorldInboundSourceFamilyCount.newBuilder().setFamily(family).setCount(0));
    }
    var declaration =
        WorldFreshGraphDeclaration.newBuilder()
            .setTenantId(target.canonicalTenantId().toString())
            .setVersionId(target.canonicalVersionId().toString())
            .addAllFamilyCounts(counts)
            .setInboundSourceClosure(inbound.build())
            .setStartLocation(
                net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                    .setTenantId(target.canonicalTenantId().toString())
                    .setVersionId(target.canonicalVersionId().toString())
                    .setRoomTemplateId(roomId.toString()))
            .build();
    var mutations =
        List.of(
            mutation(
                    commitId,
                    roomId,
                    regionId,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
                .setRoom(
                    RoomDesignMutation.newBuilder()
                        .setName("Controlled starting room")
                        .setZoneId(zoneId.toString())
                        .setDescription("Controlled World owner input"))
                .build(),
            mutation(
                    commitId,
                    zoneId,
                    regionId,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
                .setZone(
                    ZoneDesignMutation.newBuilder()
                        .setName("Controlled zone")
                        .setRegionId(regionId.toString()))
                .build(),
            mutation(
                    commitId,
                    regionId,
                    regionId,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
                .setRegion(
                    RegionDesignMutation.newBuilder()
                        .setName("Controlled region")
                        .setWeather("clear")
                        .setShardId(1))
                .setFreshGraphDeclaration(declaration)
                .build());

    var revisions = new ArrayList<RevisionPayload>();
    var units = new ArrayList<AffectedUnit>();
    String commandJson =
        "{\"schemaVersion\":1,\"commandId\":\"LOOK\",\"semanticOwner\":\"WORLD\","
            + "\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\","
            + "\"promptPolicy\":\"NEVER\",\"actionCategory\":\"GAMEPLAY\","
            + "\"historyRecordable\":true,\"aliases\":[],\"actionTags\":[\"WORLD_BROWSE\"],"
            + "\"effects\":[]}";
    revisions.add(
        new RevisionPayload(
            "0",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            CommandSource.upsertPayload(commandJson)));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            CommandSource.SCOPE,
            target.canonicalVersionId().toString(),
            CommandSource.SCOPE,
            CommandSource.SCOPE_ID,
            "0"));
    revisions.add(
        new RevisionPayload(
            "1",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.upsertPayload(
                new GameplayRuleManifest.AdmissionTag("selected-world-admission"))));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.SCOPE,
            target.canonicalVersionId().toString(),
            GameplayRuleSource.SCOPE,
            "effective",
            "0"));
    String policyPayload =
        "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\"selected-public-realm\",\"policy\":{"
            + "\"schemaVersion\":1,\"worldSlug\":\"controlled-selected-world\",\"worldDisplayName\":\"World\","
            + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
            + "\"publicProduction\":true,\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}}";
    revisions.add(new RevisionPayload(Integer.toString(revisions.size()), UUID.randomUUID(),
        Owner.GAME_DESIGN_CONTROL_PLANE, policyPayload));
    units.add(new AffectedUnit(Owner.GAME_DESIGN_CONTROL_PLANE,
        net.firedevops.firemud.gamedesign.publication.RealmPolicySource.SCOPE,
        target.canonicalVersionId().toString(),
        net.firedevops.firemud.gamedesign.publication.RealmPolicySource.SCOPE, "effective", "0"));
    // Authored declarations are content only; each real owner independently proves emptiness.
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            net.firedevops.firemud.gamedesign.publication.TemplateConfigSource
                .ownerInventoryPayload(
                    Owner.ENTITY_MANAGEMENT,
                    net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration
                        .parse(entityInventory()))));
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            net.firedevops.firemud.gamedesign.publication.TemplateConfigSource
                .ownerInventoryPayload(
                    Owner.AUTOMATION_SCRIPTING,
                    net.firedevops.firemud.common.automation
                        .AutomationAuthoredSourceInventoryDeclaration.parse(
                        automationInventory()))));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            net.firedevops.firemud.gamedesign.publication.TemplateConfigSource.SCOPE,
            target.canonicalVersionId().toString(),
            net.firedevops.firemud.gamedesign.publication.TemplateConfigSource.SCOPE,
            net.firedevops.firemud.gamedesign.publication.TemplateConfigSource.SCOPE_ID,
            "0"));
    for (var mutation : mutations) {
      revisions.add(
          new RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(mutation.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              JsonFormat.printer().omittingInsignificantWhitespace().print(mutation)));
      String family =
          mutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "AGGREGATE",
              mutation.getAggregateId(),
              "0"));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "REGION_SUBTREE",
              mutation.getScopeId(),
              "0"));
    }
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        commitId,
        "genesis:" + genesis.policy().receiptId(),
        revisions,
        units);
  }

  private static WorldDesignMutationRevision.Builder mutation(
      UUID commitId, UUID aggregateId, UUID regionId, WorldDesignAggregateType aggregateType) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commitId.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateId(aggregateId.toString())
        .setAggregateType(aggregateType)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(regionId.toString());
  }

  private static void applyGameDesignCommit(Store gds, DraftCommitBinding binding) {
    var coordinator = new DraftCommitCoordinatorRepository(gds.dsl());
    var sources = new GameDesignSourceRepository(gds.dsl());
    gds.write()
        .execute(
            ignored -> {
              var reviewed =
                  new GameDesignReviewedBaseRepository(gds.dsl())
                      .resolve(binding.target(), DraftBaseReference.parse(binding.baseCommitId()));
              sources.claimReviewed(binding, reviewed);
              coordinator.claimApplicationSlot(binding);
              for (Owner owner : binding.requiredOwners())
                coordinator.markOwnerInProgress(binding, owner);
              return null;
            });
  }

  private static void completeGameDesignCommit(
      Store gds,
      DraftCommitBinding binding,
      net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphAppliedResult worldApplied) {
    var coordinator = new DraftCommitCoordinatorRepository(gds.dsl());
    var sources = new GameDesignSourceRepository(gds.dsl());
    var gameDesignApplied = gds.write().execute(ignored -> sources.apply(binding));
    var worldOutcome =
        new DraftCommitCoordinatorRepository.OwnerOutcome(
            Owner.WORLD_MANAGEMENT,
            DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
            binding.commitId(),
            binding.digest(),
            worldApplied.resultIdentity(),
            worldApplied.canonicalBytes(),
            worldApplied.appliedEpochs().stream()
                .map(
                    epoch ->
                        new DraftCommitCoordinatorRepository.AppliedEpoch(
                            epoch.aggregateType(),
                            epoch.aggregateId(),
                            epoch.scopeType(),
                            epoch.scopeId(),
                            epoch.expectedEpoch(),
                            epoch.resultingEpoch()))
                .toList());
    gds.write()
        .execute(
            ignored -> {
              coordinator.recordOwnerOutcome(binding, gameDesignApplied.ownerOutcome());
              coordinator.recordOwnerOutcome(binding, worldOutcome);
              List<DraftCommitCoordinatorRepository.OwnerOutcome> outcomes =
                  binding.requiredOwners().stream()
                      .map(
                          owner ->
                              owner == Owner.WORLD_MANAGEMENT
                                  ? worldOutcome
                                  : gameDesignApplied.ownerOutcome())
                      .toList();
              net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup
                  .advanceIsolatedSourceVisibility(gds.dsl(), binding, outcomes);
              coordinator.releaseApplicationSlot(binding);
              return null;
            });
  }

  private static AccountPublicationAuthorizationBinding controlledAccountBinding(
      AuthoredDraftPublishSelectionBinding selection) {
    UUID actor = UUID.randomUUID();
    var input = new AccountPublicationAuthorizationBinding.PreallocationInput(actor, selection);
    return new AccountPublicationAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        input,
        controlledAccountSources(actor, selection.intent().canonicalTenantId()));
  }

  private static SelectedOwnerIntakeAuthorizationBinding selectedOwnerAuthorization(
      Store gds, AuthoredDraftPublishSelection selection, Owner recipient, UUID actor) {
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            recipient,
            NAMESPACE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            actor,
            selection.selectedCommit());
    var export = new GameDesignSourceRepository(gds.dsl()).requireSelectedOwnerIntakeSource(scope);
    var content =
        net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent.fromStored(
            export.canonicalBytes(), scope, export.digest());
    return new SelectedOwnerIntakeAuthorizationBinding(
        content, controlledAccountSources(actor, selection.target().canonicalTenantId()));
  }

  private static List<DraftAuthorizationFenceBinding.SourceEvidence> controlledAccountSources(
      UUID actor, UUID tenant) {
    byte[] evidence =
        "TEST_ONLY controlled upstream Account source input".getBytes(StandardCharsets.UTF_8);
    return List.of(
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            actor.toString(),
            "1",
            "1",
            null,
            null,
            evidence),
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.TENANT,
            tenant.toString(),
            "1",
            "1",
            null,
            null,
            evidence),
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.MEMBERSHIP,
            actor + "/" + tenant,
            "1",
            "1",
            null,
            null,
            evidence),
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.GLOBAL_ROLES,
            actor.toString(),
            "1",
            "1",
            null,
            null,
            evidence));
  }

  private static VersionDto versionDto(Version version) {
    return new VersionDto(
        version.getId(),
        version.getTenantId(),
        version.getVersionNumber(),
        version.getVersionState(),
        version.getVersionStateEpoch(),
        version.getScriptPatchVersion(),
        version.getBaseVersionId(),
        version.isScriptOnly(),
        version.getNotes(),
        version.getCreatedAt(),
        version.getUpdatedAt());
  }

  private static ExportedAssetManifest controlledEmptyManifest() throws Exception {
    String hash =
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("[]".getBytes(StandardCharsets.UTF_8)));
    return new ExportedAssetManifest("sha256:" + hash, 1, List.of(), List.of());
  }

  private static String bundleOwnerRow(Store gds, Version version) {
    return gds.dsl()
        .fetchSingle(
            "SELECT row_to_json(t)::TEXT || ':' || t.xmin::TEXT FROM published_release_bundle t WHERE tenant_id = ? AND version_id = ?",
            version.getTenantId(),
            version.getId())
        .get(0, String.class);
  }

  private static String loopback(Server server) {
    return "127.0.0.1:" + server.getPort();
  }

  private static Server ownerServer(ProofPki pki, String service, io.grpc.BindableService handler)
      throws Exception {
    var identity = pki.server(service);
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(identity.certificate().toFile(), identity.key().toFile())
                .trustManager(pki.ca().toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(handler, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static void stop(Server server) throws InterruptedException {
    if (server == null) return;
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static Store store(
      String service, String schemaPrefix, PostgreSQLContainer<?> postgres, boolean saga) {
    String schema = schemaPrefix + "_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    String history = "flyway_schema_history_" + service.replace('-', '_');
    var flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(history)
            .placeholders(Map.of("serviceSchema", schema))
            .locations(migrationLocation(service));
    if (saga) {
      flyway.locations(migrationLocation(service), sagaMigrationLocation());
    }
    if ("entity-management".equals(service)) {
      flyway
          .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
          .setTransactionalLock(false);
    }
    flyway.load().migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(dataSource);
    return new Store(schema, dsl, manager);
  }

  private static String migrationLocation(String service) {
    return "filesystem:"
        + repositoryRoot()
            .resolve("services")
            .resolve(service + "-service")
            .resolve("src/main/resources/db/migration")
            .toAbsolutePath()
            .normalize();
  }

  private static String sagaMigrationLocation() {
    return "filesystem:"
        + repositoryRoot()
            .resolve("services/common-saga/src/main/resources/db/migration/saga")
            .toAbsolutePath()
            .normalize();
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.isDirectory(current.resolve("services/game-design-service"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException(
        "Repository root is unavailable for current-default migrations");
  }

  private record Draft(Version version, TargetProof target) {}

  private record Store(String schema, DSLContext dsl, DataSourceTransactionManager transactions) {
    TransactionTemplate write() {
      var transaction = new TransactionTemplate(transactions);
      transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      return transaction;
    }
  }

  private static final class ReadyEntityManagementClient extends EntityManagementClient {
    ReadyEntityManagementClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tls,
        GrpcChannelFactory channels) {
      super(endpoints, tls, channels, BlockingGrpcStubCustomizer.noop());
    }

    void connect() throws javax.net.ssl.SSLException, IOException {
      initReloadingClient();
    }
  }

  private static final class ReadyAutomationScriptingClient extends AutomationScriptingClient {
    ReadyAutomationScriptingClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tls,
        GrpcChannelFactory channels) {
      super(endpoints, tls, channels, BlockingGrpcStubCustomizer.noop());
    }

    void connect() throws javax.net.ssl.SSLException, IOException {
      initReloadingClient();
    }
  }

  private static final class ReadyGameLogicClient extends GameLogicClient {
    ReadyGameLogicClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tls,
        GrpcChannelFactory channels) {
      super(endpoints, tls, channels, BlockingGrpcStubCustomizer.noop());
    }

    void connect() throws javax.net.ssl.SSLException, IOException {
      initReloadingClient();
    }
  }

  private static final class ProofPki {
    private final Path ca;
    private final Map<String, Identity> servers = new java.util.HashMap<>();
    private final Map<String, Identity> clients = new java.util.HashMap<>();

    ProofPki(Path root) throws Exception {
      Files.createDirectories(root);
      KeyPair caKeys = keys();
      var now = java.time.Instant.now();
      var caName = new X500Name("CN=Controlled selected full owner proof CA");
      var caBuilder =
          new JcaX509v3CertificateBuilder(
              caName,
              BigInteger.ONE,
              Date.from(now.minusSeconds(60)),
              Date.from(now.plusSeconds(3600)),
              caName,
              caKeys.getPublic());
      caBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
      caBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign));
      X509Certificate certificate =
          new JcaX509CertificateConverter()
              .getCertificate(
                  caBuilder.build(
                      new JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate())));
      ca = pem(root.resolve("ca.pem"), certificate);
      for (String service :
          List.of(
              "game-design-service",
              "entity-management-service",
              "automation-scripting-service",
              "game-logic-service")) {
        servers.put(service, issue(root, service, true, caKeys, certificate));
        clients.put(service, issue(root, service, false, caKeys, certificate));
      }
    }

    Path ca() {
      return ca;
    }

    Identity server(String service) {
      return java.util.Objects.requireNonNull(servers.get(service));
    }

    CommonGrpcClientProperties client(String service) {
      Identity identity = java.util.Objects.requireNonNull(clients.get(service));
      var properties = new CommonGrpcClientProperties();
      properties.setPlaintext(false);
      properties.setCertChain(identity.certificate().toString());
      properties.setPrivateKey(identity.key().toString());
      properties.setCaCert(ca.toString());
      return properties;
    }

    private static Identity issue(
        Path root, String service, boolean server, KeyPair caKeys, X509Certificate caCertificate)
        throws Exception {
      KeyPair pair = keys();
      var now = java.time.Instant.now();
      var builder =
          new JcaX509v3CertificateBuilder(
              caCertificate,
              new BigInteger(120, new SecureRandom()),
              Date.from(now.minusSeconds(60)),
              Date.from(now.plusSeconds(3600)),
              new X500Name("CN=" + service),
              pair.getPublic());
      builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
      builder.addExtension(
          Extension.keyUsage,
          false,
          new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              server ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth));
      builder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(
              new GeneralName[] {
                new GeneralName(
                    GeneralName.uniformResourceIdentifier,
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service),
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")
              }));
      X509Certificate cert =
          new JcaX509CertificateConverter()
              .getCertificate(
                  builder.build(
                      new JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate())));
      String prefix = service + (server ? "-server" : "-client");
      return new Identity(
          pem(root.resolve(prefix + ".crt"), cert),
          pem(root.resolve(prefix + ".key"), pair.getPrivate()));
    }

    private static KeyPair keys() throws Exception {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    }

    private static Path pem(Path path, Object value) throws Exception {
      try (var writer = new JcaPEMWriter(Files.newBufferedWriter(path))) {
        writer.writeObject(value);
      }
      return path;
    }

    record Identity(Path certificate, Path key) {}
  }

  private static String automationInventory() {
    return "{\"schema\":\"automation-authored-source-inventory/v1\","
        + "\"families\":{\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
        + "\"SCRIPT_PATCH_SOURCES\":[]}}";
  }

  private static String entityInventory() {
    return "{\"schema\":\"entity-authored-source-inventory/v1\","
        + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
        + "\"ACTOR_BODY_LAYOUT_ASSIGNMENTS\":[],\"ARCHETYPE_ASSIGNMENTS\":[],"
        + "\"ARCHETYPE_CONSTRAINTS\":[],\"ARCHETYPE_ROOTS\":[],"
        + "\"BALANCE_CURVE_ATTACHMENTS\":[],\"BALANCE_CURVE_ROOTS\":[],"
        + "\"BODY_LAYOUT_MEMBERSHIPS\":[],\"BODY_LAYOUT_ROOTS\":[],"
        + "\"CRAFTING_INGREDIENT_BINDINGS\":[],\"CRAFTING_RECIPE_RESULT_BINDINGS\":[],"
        + "\"CRAFTING_RECIPE_ROOTS\":[],\"EQUIPMENT_ATTACHMENT_RULES\":[],"
        + "\"EQUIPMENT_CAPABILITIES\":[],\"EQUIPMENT_COMPATIBILITY_RULES\":[],"
        + "\"EQUIPMENT_OCCUPANCY_RULES\":[],\"EQUIPMENT_SLOT_GROUPS\":[],"
        + "\"EQUIPMENT_SLOT_ROOTS\":[],\"INBOUND_LOOT_BINDINGS\":[],"
        + "\"ITEM_TEMPLATE_ROOTS\":[],\"LOOT_ITEM_MAPPINGS\":[],"
        + "\"LOOT_TABLE_ROOTS\":[],\"NPC_TEMPLATE_ROOTS\":[],"
        + "\"OTHER_ACTOR_TEMPLATE_ROOTS\":[]}}";
  }
}
