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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
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

  private static final org.testcontainers.containers.Network ACCOUNT_NETWORK =
      org.testcontainers.containers.Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> ACCOUNT_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final org.testcontainers.containers.GenericContainer<?> ACCOUNT_REDIS =
      new org.testcontainers.containers.GenericContainer<>(TestContainerImages.redis())
          .withNetwork(ACCOUNT_NETWORK)
          .withNetworkAliases("selected-reservation-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final org.testcontainers.containers.GenericContainer<?> ACCOUNT_REPLICA =
      new org.testcontainers.containers.GenericContainer<>(TestContainerImages.redis())
          .withNetwork(ACCOUNT_NETWORK)
          .dependsOn(ACCOUNT_REDIS)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "selected-reservation-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void actualSelectedWorldFourAndParticipantOwnersProduceAndReadBackCompleteSchemaFourBundle()
      throws Exception {
    var diagnostics = new SettlementDiagnostics();
    diagnostics.logPostgres("game-design", GDS_POSTGRES);
    diagnostics.logPostgres("world-management", WORLD_POSTGRES);
    diagnostics.logPostgres("entity-management", ENTITY_POSTGRES);
    diagnostics.logPostgres("automation-scripting", AUTOMATION_POSTGRES);
    diagnostics.logPostgres("game-logic", GAME_LOGIC_POSTGRES);
    diagnostics.logPostgres("account", ACCOUNT_POSTGRES);
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
    var delivery =
        new net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository(
                gds.dsl())
            .read(worldSource.operationId())
            .orElseThrow();
    var worldGraph =
        WorldSelectedFullPublicationEvidenceFixture.applyGraph(
            world.dsl(),
            world.transactions(),
            NAMESPACE,
            commit,
            worldSource,
            delivery.request().intakeRequestId());
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
    try (var accountFixture =
        new AccountControlUiOriginalOrderFixture(
            ACCOUNT_POSTGRES.getJdbcUrl(),
            ACCOUNT_POSTGRES.getUsername(),
            ACCOUNT_POSTGRES.getPassword(),
            ACCOUNT_REDIS.getHost(),
            ACCOUNT_REDIS.getMappedPort(6379),
            temporary.resolve("account-original"),
            draft.target().canonicalTenantId())) {
      var issuedCreator = accountFixture.issueCreator();
      var accountSources = issuedCreator.sources();
      var publicationPki = new ProofPki(temporary.resolve("fresh-world-begin-pki"));
      var publicationAuthorization =
          authenticatedAccountPublicationOrder(
              gds,
              issuedCreator,
              AuthoredDraftPublishSelectionBinding.fromStored(
                  selection.canonicalJson(), selection.digest()),
              publicationPki);
      var worldOwners =
          freshAuthenticatedWorldFreeze(
              gds,
              world,
              accountSources.dsl,
              accountSources.manager,
              AuthoredDraftPublishSelectionBinding.fromStored(
                  selection.canonicalJson(), selection.digest()),
              worldGraph,
              publicationAuthorization,
              publicationPki);
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
      var automationRepository =
          new AutomationEmptySelectedSourceIntakeRepository(automation.dsl());
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

      Long gdsBundleBefore =
          gds.dsl()
              .fetchSingle(
                  "SELECT count(*) FROM published_release_bundle WHERE tenant_id = ? AND version_id = ?",
                  draft.version().getTenantId(),
                  draft.version().getId())
              .get(0, Long.class);
      assertThat(gdsBundleBefore).isZero();

      ProofPki pki = new ProofPki(temporary.resolve("selected-full-pki"));
      Server accountServer = null;
      Server worldServer = null;
      Server entityServer = null;
      Server automationServer = null;
      Server gameLogicServer = null;
      // This case recovers the exact already-committed World freeze. Mutable owner reads must not
      // repeat.
      var replaySelection =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient
                  .class);
      var replayVersion =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient.class);
      var replayAccount =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient
                  .class);
      var replaySource =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient.class);
      try {
        accountServer =
            ownerServer(
                pki,
                "account-service",
                new AccountPublicationAuthorizationReadGrpcService(
                    new AccountPublicationAuthorizationReadService(
                        new AccountPublicationAuthorizationRepository(accountSources.dsl),
                        accountSources.manager,
                        NAMESPACE),
                    NAMESPACE));
        var worldReaders =
            WorldSelectedFullPublicationEvidenceFixture.reservationReadEndpoints(
                world.dsl(),
                world.transactions(),
                NAMESPACE,
                replaySelection,
                replayVersion,
                replayAccount,
                replaySource);
        worldServer =
            ownerServer(
                pki,
                "world-management-service",
                worldReaders.toArray(io.grpc.BindableService[]::new));
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
        endpoints.setAccountService(loopback(accountServer));
        endpoints.setWorldManagementService(loopback(worldServer));
        endpoints.setEntityManagementService(loopback(entityServer));
        endpoints.setAutomationScriptingService(loopback(automationServer));
        endpoints.setGameLogicService(loopback(gameLogicServer));
        var channelFactory = new GrpcChannelFactory();
        try (var accountClient =
                new net.firedevops.firemud.common.publication
                    .AccountPublicationAuthorizationReadClient(
                    endpoints, pki.client("game-design-service"), channelFactory, NAMESPACE);
            var freezeClient =
                new net.firedevops.firemud.common.publication
                    .WorldSelectedDraftPublicationFreezeClient(
                    endpoints, pki.client("game-design-service"), channelFactory, NAMESPACE);
            var inventoryClient =
                new net.firedevops.firemud.common.publication
                    .WorldSelectedPublicationArtifactInventoryClient(
                    endpoints, pki.client("game-design-service"), channelFactory, NAMESPACE);
            var selectorClient =
                new net.firedevops.firemud.common.world.WorldPublishedStartLocationClient(
                    endpoints, pki.client("game-design-service"), channelFactory, NAMESPACE);
            var sourceClient =
                new net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient(
                    endpoints, pki.client("game-design-service"), channelFactory, NAMESPACE);
            var entityClient =
                new ReadyEntityManagementClient(
                    endpoints, pki.client("game-design-service"), channelFactory);
            var automationClient =
                new ReadyAutomationScriptingClient(
                    endpoints, pki.client("game-design-service"), channelFactory);
            var gameLogicClient =
                new ReadyGameLogicClient(
                    endpoints, pki.client("game-design-service"), channelFactory)) {
          accountClient.init();
          freezeClient.init();
          inventoryClient.init();
          selectorClient.init();
          sourceClient.init();
          var admission =
              new net.firedevops.firemud.gamedesign.publication
                  .SelectedDraftPublicationAdmissionService(
                  gds.dsl(),
                  gds.transactions(),
                  accountClient,
                  freezeClient,
                  inventoryClient,
                  selectorClient,
                  sourceClient,
                  NAMESPACE);
          var selectedOwnerReservation = admission.admitAndReserve(intent, accountBinding);
          assertThat(selectedOwnerReservation.selection().canonicalBytes())
              .containsExactly(selectionBinding.canonicalBytes());
          var reservationReplay = admission.admitAndReserve(intent, accountBinding);
          assertThat(reservationReplay.operation().canonicalBytes())
              .containsExactly(selectedOwnerReservation.operation().canonicalBytes());
          org.mockito.Mockito.verifyNoInteractions(
              replaySelection, replayVersion, replayAccount, replaySource);
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
          var artifactRepository =
              new net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository(
                  gds.dsl());
          var versions = new VersionRepository(gds.dsl());
          var artifactService =
              new net.firedevops.firemud.gamedesign.service.impl.VersionAssetArtifactServiceImpl(
                  artifactRepository,
                  null,
                  versions,
                  null,
                  null,
                  null,
                  null,
                  null,
                  new ObjectMapper());
          var assetPublication =
              new net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository(
                  gds.dsl());
          var exportSnapshot =
              gds.write()
                  .execute(
                      ignored ->
                          assetPublication.freezeOrReadSnapshot(
                              versionDto.tenantId(), versionDto.versionNumber()));
          assertThat(exportSnapshot.items()).isEmpty();
          gds.write()
              .execute(
                  ignored ->
                      artifactService.stageExport(
                          versionDto.tenantId(),
                          versionDto.id(),
                          versionDto.versionNumber(),
                          publicationRequest.derivedWorkflowIdentity()));
          var candidateService =
              new net.firedevops.firemud.gamedesign.service.impl
                  .VersionAssetExportCandidateServiceImpl(
                  versions,
                  new GameRepository(gds.dsl()),
                  new net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository(
                      gds.dsl()),
                  artifactRepository,
                  assetPublication,
                  gds.transactions(),
                  new ObjectMapper());
          // Controlled delivery origin and manifest metadata; no object-store or public delivery
          // proof.
          var candidate =
              new net.firedevops.firemud.gamedesign.service.FrozenAssetExportCandidate(
                  controlledEmptyManifest, "https://controlled.invalid/assets");
          assertThat(
                  candidateService.recordExportCandidate(
                      versionDto.tenantId(), versionDto.versionNumber(), candidate))
              .isEqualTo(candidate);
          assertThat(
                  candidateService.readExportCandidate(
                      versionDto.tenantId(), versionDto.versionNumber()))
              .isEqualTo(candidate);
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
                      ignored -> {
                        String workflow = publicationRequest.derivedWorkflowIdentity();
                        var exported =
                            artifactService.markExportedUnattested(
                                versionDto.tenantId(),
                                versionDto.id(),
                                versionDto.versionNumber(),
                                workflow,
                                controlledEmptyManifest);
                        var result =
                            releaseOwner.createFullVersionBundle(
                                versionDto,
                                workflow,
                                controlledEmptyManifest,
                                "controlled-generation-input",
                                participantDigests,
                                worldOwners.startLocationEvidence());
                        var version =
                            versions
                                .findByTenantIdAndIdForUpdate(
                                    versionDto.tenantId(), versionDto.id())
                                .orElseThrow();
                        version.setVersionState(VersionLifecycleState.PUBLISHED);
                        version.setVersionStateEpoch(
                            Math.addExact(version.getVersionStateEpoch(), 1L));
                        versions.save(version);
                        artifactService.markPublished(
                            versionDto.tenantId(),
                            versionDto.id(),
                            exported.stateEpoch(),
                            workflow,
                            controlledEmptyManifest.manifestHash());
                        var attempts =
                            new net.firedevops.firemud.gamedesign.repository
                                .PublishAttemptRepository(gds.dsl());
                        var attempt =
                            attempts.findByPublishWorkflowIdForUpdate(workflow).orElseThrow();
                        attempt.setStatus(
                            net.firedevops.firemud.gamedesign.model.PublishAttemptStatus.SUCCEEDED);
                        attempts.save(attempt);
                        var operation = selectedOwnerReservation.operation();
                        new net.firedevops.firemud.gamedesign.publication
                                .GameDesignPublicationOperationRepository(gds.dsl())
                            .seal(
                                operation.tenantKey(),
                                workflow,
                                operation.versionId(),
                                operation.selectionDigest(),
                                true);
                        return result;
                      });
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
                  .findPublishedReleaseBundle(
                      draft.version().getTenantId(), draft.version().getId())
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
          completeActualWorldAndAccountTerminals(
              gds,
              world,
              accountSources.dsl,
              accountSources.manager,
              pki,
              selectedOwnerReservation.operation(),
              diagnostics);
          assertThat(bundleOwnerRow(gds, draft.version())).isEqualTo(committedBundleRow);
        }
      } finally {
        stop(accountServer);
        stop(worldServer);
        stop(entityServer);
        stop(automationServer);
        stop(gameLogicServer);
      }
    }
  }

  /** Actual authenticated Account ordering consumes the real GDS immutable selection owner. */
  private static net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding
      authenticatedAccountPublicationOrder(
          Store gds,
          AccountControlUiOriginalOrderFixture.IssuedCreator issued,
          AuthoredDraftPublishSelectionBinding selection,
          ProofPki pki)
          throws Exception {
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var unavailable = new java.util.concurrent.atomic.AtomicBoolean(true);
    var selectionOwner =
        new net.firedevops.firemud.gamedesign.draft
            .GameDesignSelectedDraftPublicationReadGrpcService(
            new AuthoredDraftPublishSelectionRepository(
                gds.dsl(), new DraftCommitCoordinatorRepository(gds.dsl())),
            NAMESPACE);
    Server gdsServer =
        ownerServer(pki, "game-design-service", observedRead(selectionOwner, reads, unavailable));
    Server accountServer = null;
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService(loopback(gdsServer));
    var channels = new GrpcChannelFactory();
    var sources = issued.sources();
    try (var selectionClient =
        new net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient(
            endpoints, pki.client("account-service"), channels, NAMESPACE)) {
      selectionClient.init();
      var repository = new AccountPublicationAuthorizationRepository(sources.dsl);
      var owner =
          new AccountSelectedDraftPublicationOrderService(
              selectionClient,
              new AccountPublicationAuthorizationService(
                  issued.actors(), sources.fences, repository),
              NAMESPACE);
      accountServer =
          ownerServer(
              pki,
              "account-service",
              new AccountSelectedDraftPublicationOrderGrpcService(owner, sources.terms, NAMESPACE));
      endpoints.setAccountService(loopback(accountServer));
      try (var client =
          new net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderClient(
              endpoints, pki.client("game-design-service"), channels, NAMESPACE)) {
        client.init();
        var request =
            net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderGrpcCodec
                .Request.create(NAMESPACE, selection, issued.compact());
        var before = publicationOrderRows(sources.dsl);
        assertThat(before.get("account_selected_publication_authorizations")).isEmpty();
        assertThat(before.get("account_selected_publication_sources")).isEmpty();
        assertThatThrownBy(() -> client.authorize(request)).isInstanceOf(RuntimeException.class);
        assertThat(publicationOrderRows(sources.dsl)).isEqualTo(before);
        assertThat(reads.get()).isEqualTo(1);
        unavailable.set(false);
        var created = client.authorize(request);
        assertThat(created.input().selection().canonicalBytes())
            .containsExactly(selection.canonicalBytes());
        var committed = publicationOrderRows(sources.dsl);
        assertThat(committed.get("account_selected_publication_authorizations")).hasSize(1);
        assertThat(committed.get("account_selected_publication_sources")).isNotEmpty();
        assertThat(client.authorize(request).canonicalBytes())
            .containsExactly(created.canonicalBytes());
        assertThat(publicationOrderRows(sources.dsl)).isEqualTo(committed);
        assertThat(reads.get()).isEqualTo(3);
        assertThat(
                sources
                    .dsl
                    .fetchSingle(
                        "SELECT count(*) FROM account_control_ui_issuance_operations WHERE status = 'COMMITTED'")
                    .get(0, Long.class))
            .isEqualTo(1L);
        return created;
      }
    } finally {
      stop(accountServer);
      stop(gdsServer);
    }
  }

  private static java.util.Map<String, java.util.List<String>> publicationOrderRows(
      org.jooq.DSLContext dsl) {
    var result = new java.util.LinkedHashMap<String, java.util.List<String>>();
    for (String table :
        List.of(
            "account_selected_publication_authorizations",
            "account_selected_publication_sources",
            "account_selected_publication_settlements",
            "account_control_ui_issuance_operations")) {
      result.put(
          table,
          dsl.fetch(
                  "SELECT to_jsonb(r)::text || ':' || r.xmin::text FROM " + table + " r ORDER BY 1")
              .getValues(0, String.class));
    }
    return result;
  }

  /** Fresh World begin uses real owner reads before any World freeze attempt exists. */
  private static WorldSelectedFullPublicationEvidenceFixture.Result freshAuthenticatedWorldFreeze(
      Store gds,
      Store world,
      org.jooq.DSLContext accountDsl,
      org.springframework.transaction.PlatformTransactionManager accountTransactions,
      AuthoredDraftPublishSelectionBinding selection,
      WorldSelectedFullPublicationEvidenceFixture.AppliedGraph graph,
      net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding account,
      ProofPki pki)
      throws Exception {
    var selectionCalls = new java.util.concurrent.atomic.AtomicInteger();
    var versionCalls = new java.util.concurrent.atomic.AtomicInteger();
    var heldCalls = new java.util.concurrent.atomic.AtomicInteger();
    var unavailableAccount = new java.util.concurrent.atomic.AtomicBoolean(true);
    var unavailableGds = new java.util.concurrent.atomic.AtomicBoolean();
    var sources =
        new net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository(
            gds.dsl());
    Server gdsServer =
        ownerServer(
            pki,
            "game-design-service",
            observedRead(
                new net.firedevops.firemud.gamedesign.draft
                    .GameDesignSelectedDraftPublicationReadGrpcService(
                    new AuthoredDraftPublishSelectionRepository(
                        gds.dsl(), new DraftCommitCoordinatorRepository(gds.dsl())),
                    NAMESPACE),
                selectionCalls,
                unavailableGds),
            observedRead(
                new net.firedevops.firemud.gamedesign.service.impl
                    .AuthoredWorldVersionStateGrpcService(
                    new net.firedevops.firemud.gamedesign.service.impl
                        .AuthoredWorldVersionStateService(gds.transactions(), sources),
                    NAMESPACE),
                versionCalls,
                unavailableGds),
            new net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldSourceReadGrpcService(
                sources, NAMESPACE));
    Server accountServer = null;
    Server worldServer = null;
    try {
      accountServer =
          ownerServer(
              pki,
              "account-service",
              observedRead(
                  new AccountPublicationAuthorizationReadGrpcService(
                      new AccountPublicationAuthorizationReadService(
                          new AccountPublicationAuthorizationRepository(accountDsl),
                          accountTransactions,
                          NAMESPACE),
                      NAMESPACE),
                  heldCalls,
                  unavailableAccount));
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameDesignService(loopback(gdsServer));
      endpoints.setAccountService(loopback(accountServer));
      var channels = new GrpcChannelFactory();
      try (var selectionClient =
              new net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var versionClient =
              new net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var accountClient =
              new net.firedevops.firemud.common.publication
                  .AccountPublicationAuthorizationReadClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var sourceClient =
              new net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE)) {
        selectionClient.init();
        versionClient.init();
        accountClient.init();
        sourceClient.init();
        var handlers =
            WorldSelectedFullPublicationEvidenceFixture.reservationReadEndpoints(
                world.dsl(),
                world.transactions(),
                NAMESPACE,
                selectionClient,
                versionClient,
                accountClient,
                sourceClient);
        worldServer =
            ownerServer(
                pki, "world-management-service", handlers.toArray(io.grpc.BindableService[]::new));
        endpoints.setWorldManagementService(loopback(worldServer));
        try (var freeze =
            new net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeClient(
                endpoints, pki.client("game-design-service"), channels, NAMESPACE)) {
          freeze.init();
          var intent = selection.intent();
          var request =
              net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence
                  .Request.create(
                  NAMESPACE,
                  intent.canonicalTenantId(),
                  intent.canonicalVersionId(),
                  intent.publishRequestId(),
                  Long.parseLong(intent.expectedVersionStateEpoch()),
                  selection.digest().substring("sha256:".length()),
                  account);
          var before = freezeOwnerRows(world.dsl());
          for (String empty :
              List.of(
                  "world_design_publication_fence_attempt",
                  "world_design_publication_account_binding",
                  "world_selected_publication_artifact_inventory",
                  "world_canonical_frozen_topology")) {
            assertThat(before.get(empty)).as("no prior %s", empty).isEmpty();
          }
          assertThat(
                  world
                      .dsl()
                      .fetchSingle(
                          "SELECT owner_freeze_phase FROM world_design_publication_fence_owner")
                      .get(0, String.class))
              .isEqualTo("OPEN");
          assertThatThrownBy(() -> freeze.begin(request)).isInstanceOf(RuntimeException.class);
          assertThat(freezeOwnerRows(world.dsl())).isEqualTo(before);
          assertThat(selectionCalls.get()).isEqualTo(1);
          assertThat(versionCalls.get()).isEqualTo(1);
          assertThat(heldCalls.get()).isEqualTo(1);
          unavailableAccount.set(false);
          var created = freeze.begin(request);
          assertThat(created.request()).isEqualTo(request);
          assertThat(created.acknowledgement().ownerFreezePhase())
              .isEqualTo(
                  net.firedevops.firemud.common.publication
                      .WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase.FROZEN);
          assertThat(selectionCalls.get()).isEqualTo(2);
          assertThat(versionCalls.get()).isEqualTo(2);
          assertThat(heldCalls.get()).isEqualTo(2);
          var retained =
              WorldSelectedFullPublicationEvidenceFixture.readCommittedFreeze(
                  world.dsl(), NAMESPACE, selection, graph, account, created);
          var committed = freezeOwnerRows(world.dsl());
          for (String written :
              List.of(
                  "world_design_publication_fence_attempt",
                  "world_design_publication_account_binding",
                  "world_selected_publication_artifact_inventory",
                  "world_canonical_frozen_topology")) {
            assertThat(committed.get(written)).as("one committed %s", written).hasSize(1);
          }
          unavailableAccount.set(true);
          unavailableGds.set(true);
          assertThat(freeze.begin(request)).isEqualTo(created);
          assertThat(freezeOwnerRows(world.dsl())).isEqualTo(committed);
          assertThat(selectionCalls.get()).isEqualTo(2);
          assertThat(versionCalls.get()).isEqualTo(2);
          assertThat(heldCalls.get()).isEqualTo(2);
          return retained;
        }
      }
    } finally {
      stop(worldServer);
      stop(accountServer);
      stop(gdsServer);
    }
  }

  private static java.util.Map<String, java.util.List<String>> freezeOwnerRows(
      org.jooq.DSLContext dsl) {
    var result = new java.util.LinkedHashMap<String, java.util.List<String>>();
    for (String table :
        List.of(
            "world_design_publication_fence_owner",
            "world_design_publication_fence_attempt",
            "world_design_publication_account_binding",
            "world_selected_publication_artifact_inventory",
            "world_canonical_frozen_topology")) {
      result.put(
          table,
          dsl.fetch(
                  "SELECT to_jsonb(r)::text || ':' || r.xmin::text FROM " + table + " r ORDER BY 1")
              .getValues(0, String.class));
    }
    return result;
  }

  private static io.grpc.BindableService observedRead(
      io.grpc.BindableService owner,
      java.util.concurrent.atomic.AtomicInteger calls,
      java.util.concurrent.atomic.AtomicBoolean unavailable) {
    return () ->
        ServerInterceptors.intercept(
            owner,
            new io.grpc.ServerInterceptor() {
              @Override
              public <Q, A> io.grpc.ServerCall.Listener<Q> interceptCall(
                  io.grpc.ServerCall<Q, A> call,
                  io.grpc.Metadata metadata,
                  io.grpc.ServerCallHandler<Q, A> next) {
                calls.incrementAndGet();
                if (unavailable.get()) {
                  call.close(
                      io.grpc.Status.UNAVAILABLE.withDescription(
                          "test-only owner transport outage"),
                      new io.grpc.Metadata());
                  return new io.grpc.ServerCall.Listener<>() {};
                }
                return next.startCall(call, metadata);
              }
            });
  }

  /**
   * Actual immutable owner terminals and Account ingress over controlled loopback workload mTLS.
   */
  private static void completeActualWorldAndAccountTerminals(
      Store gds,
      Store world,
      org.jooq.DSLContext accountDsl,
      org.springframework.transaction.PlatformTransactionManager accountTransactions,
      ProofPki pki,
      net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation operation,
      SettlementDiagnostics diagnostics)
      throws Exception {
    requireAccountClosureInventoryRefusals(accountDsl, operation);
    var gdsOwner =
        new net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationService(
            new net.firedevops.firemud.gamedesign.publication
                .GameDesignPublicationOperationRepository(gds.dsl()),
            gds.transactions());
    Server gdsTerminalServer =
        ownerServer(
            pki,
            "game-design-service",
            diagnostics.timedGrpc(
                "gds-terminal-read",
                new net.firedevops.firemud.gamedesign.publication
                    .GameDesignPublicationTerminalReadGrpcService(gdsOwner, NAMESPACE)));
    Server worldTerminalServer = null;
    Server accountSettlementServer = null;
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService(loopback(gdsTerminalServer));
    var channels = new GrpcChannelFactory();
    try (var worldGdsRead =
        new net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient(
            endpoints, pki.client("world-management-service"), channels, NAMESPACE)) {
      worldGdsRead.init();
      var worldRepository =
          new net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalRepository(
              world.dsl(), world.transactions());
      var worldOwner =
          net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalService
              .authenticated(worldRepository, worldGdsRead, NAMESPACE);
      worldTerminalServer =
          ownerServer(
              pki,
              "world-management-service",
              new net.firedevops.firemud.worldmanagement.tenant
                  .WorldPublicationTerminalCompletionGrpcService(worldOwner, NAMESPACE),
              diagnostics.timedGrpc(
                  "world-terminal-read",
                  new net.firedevops.firemud.worldmanagement.tenant
                      .WorldPublicationTerminalReadGrpcService(
                      new net.firedevops.firemud.worldmanagement.tenant
                          .WorldPublicationTerminalReadService(worldRepository, NAMESPACE),
                      NAMESPACE)));
      endpoints.setWorldManagementService(loopback(worldTerminalServer));
      try (var accountGdsRead =
              new net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var accountWorldRead =
              new net.firedevops.firemud.common.publication.WorldPublicationTerminalReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var producer =
              net.firedevops.firemud.gamedesign.publication
                  .GameDesignWorldPublicationTerminalProducer.usingFileBackedMtls(
                  gdsOwner, endpoints, pki.client("game-design-service"), channels, NAMESPACE)) {
        accountGdsRead.init();
        accountWorldRead.init();
        var accountOwner =
            new AccountSelectedDraftPublicationSettlementService(
                new AccountPublicationAuthorizationRepository(accountDsl),
                accountGdsRead,
                accountWorldRead,
                diagnostics.timeTransactions(accountTransactions),
                NAMESPACE);
        var binding =
            net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding
                .fromStored(operation.canonicalBytes());
        accountSettlementServer =
            ownerServer(
                pki,
                "account-service",
                diagnostics.timedGrpc(
                    "account-settlement",
                    new AccountSelectedPublicationSettlementGrpcService(accountOwner, NAMESPACE)));
        endpoints.setAccountService(loopback(accountSettlementServer));
        try (var settlementClient =
            new net.firedevops.firemud.common.publication
                .AccountSelectedPublicationSettlementClient(
                endpoints, pki.client("game-design-service"), channels, NAMESPACE)) {
          settlementClient.init();
          var settlementRequest =
              net.firedevops.firemud.common.publication
                  .AccountSelectedPublicationSettlementGrpcCodec.Request.create(
                  NAMESPACE, operation.canonicalBytes());
          try (var wrongCaller =
              new net.firedevops.firemud.common.publication
                  .AccountSelectedPublicationSettlementClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE)) {
            wrongCaller.init();
            assertThatThrownBy(() -> wrongCaller.settle(settlementRequest))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .satisfies(
                    error ->
                        assertThat(io.grpc.Status.fromThrowable(error).getCode())
                            .isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
          }
          diagnostics.phase(
              "account-settlement-before-world-terminal",
              () -> {
                assertThatThrownBy(
                        () ->
                            settlementClient.settle(
                                net.firedevops.firemud.common.publication
                                    .AccountSelectedPublicationSettlementGrpcCodec.Request.create(
                                    NAMESPACE, operation.canonicalBytes())))
                    .isInstanceOf(RuntimeException.class);
                return null;
              });
          assertThat(
                  accountDsl
                      .fetchSingle(
                          "SELECT count(*) FROM account_selected_publication_settlements WHERE operation_id = ?",
                          binding.account().operationId())
                      .get(0, Long.class))
              .isZero();
          var published =
              diagnostics.phase(
                  "world-producer-reconcile",
                  () ->
                      producer.reconcile(
                          operation.tenantKey(),
                          operation.workflowId(),
                          operation.versionId(),
                          operation.selectionDigest(),
                          net.firedevops.firemud.common.gamedesign
                              .GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED));
          assertThat(published.operationBytes()).containsExactly(operation.canonicalBytes());
          assertThat(
                  diagnostics.phase(
                      "world-producer-exact-retry",
                      () ->
                          producer
                              .reconcile(
                                  operation.tenantKey(),
                                  operation.workflowId(),
                                  operation.versionId(),
                                  operation.selectionDigest(),
                                  published.outcome())
                              .canonicalBytes()))
              .containsExactly(published.canonicalBytes());
          byte[] receipt =
              diagnostics.phase(
                  "account-settlement-positive",
                  () ->
                      settlementClient.settle(
                          net.firedevops.firemud.common.publication
                              .AccountSelectedPublicationSettlementGrpcCodec.Request.create(
                              NAMESPACE, operation.canonicalBytes())));
          assertThat(receipt).isNotEmpty();
          String retained =
              accountDsl
                  .fetchSingle(
                      "SELECT to_jsonb(s)::text || ':' || xmin::text FROM account_selected_publication_settlements s WHERE operation_id = ?",
                      binding.account().operationId())
                  .get(0, String.class);
          assertThat(
                  diagnostics.phase(
                      "account-settlement-exact-retry",
                      () ->
                          settlementClient.settle(
                              net.firedevops.firemud.common.publication
                                  .AccountSelectedPublicationSettlementGrpcCodec.Request.create(
                                  NAMESPACE, operation.canonicalBytes()))))
              .containsExactly(receipt);
          assertThat(
                  accountDsl
                      .fetchSingle(
                          "SELECT to_jsonb(s)::text || ':' || xmin::text FROM account_selected_publication_settlements s WHERE operation_id = ?",
                          binding.account().operationId())
                      .get(0, String.class))
              .isEqualTo(retained);
          assertThat(
                  accountDsl
                      .fetchSingle(
                          "SELECT game_design_terminal FROM account_selected_publication_settlements WHERE operation_id = ?",
                          binding.account().operationId())
                      .get(0, byte[].class))
              .containsExactly(published.canonicalBytes());
          assertThat(
                  accountDsl
                      .fetchSingle(
                          "SELECT world_terminal FROM account_selected_publication_settlements WHERE operation_id = ?",
                          binding.account().operationId())
                      .get(0, byte[].class))
              .containsExactly(published.canonicalBytes());
        }
      }
    } finally {
      stop(accountSettlementServer);
      stop(worldTerminalServer);
      stop(gdsTerminalServer);
    }
  }

  private static void requireAccountClosureInventoryRefusals(
      org.jooq.DSLContext dsl,
      net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation operation)
      throws Exception {
    dsl.fetch(
        "SELECT require_selected_inventory_operation_v2(?::bytea)", operation.canonicalBytes());
    var mapper = new ObjectMapper();
    java.util.List<java.util.function.Consumer<tools.jackson.databind.node.ObjectNode>> changes =
        java.util.List.of(
            tree ->
                ((tools.jackson.databind.node.ObjectNode) tree.get("sourceModel"))
                    .remove("inboundSourceClosure"),
            tree ->
                ((tools.jackson.databind.node.ObjectNode)
                        tree.at("/sourceModel/inboundSourceClosure/familyCounts/0"))
                    .put("count", 1),
            tree ->
                ((tools.jackson.databind.node.ArrayNode)
                        tree.at("/sourceModel/inboundSourceClosure/familyCounts"))
                    .remove(6),
            tree -> {
              var families =
                  (tools.jackson.databind.node.ArrayNode)
                      tree.at("/sourceModel/inboundSourceClosure/familyCounts");
              families.add(families.remove(0));
            },
            tree ->
                ((tools.jackson.databind.node.ObjectNode)
                        tree.at("/sourceModel/inboundSourceClosure"))
                    .put("unknown", 1),
            tree ->
                ((tools.jackson.databind.node.ObjectNode) tree.get("sourceModel"))
                    .put("unknown", 1),
            tree ->
                ((tools.jackson.databind.node.ObjectNode) tree.get("sourceModel"))
                    .put("graphSchemaVersion", 2),
            tree -> tree.put("schemaVersion", 1),
            tree ->
                ((tools.jackson.databind.node.ObjectNode) tree.get("checkpoint"))
                    .put("digestSchemaVersion", 3));
    var before =
        dsl.fetch(
                "SELECT to_jsonb(r)::text || ':' || r.xmin::text FROM account_selected_publication_authorizations r ORDER BY 1")
            .getValues(0, String.class);
    for (var change : changes) {
      var tree =
          (tools.jackson.databind.node.ObjectNode)
              mapper.readTree(operation.inventory().canonicalBytes());
      change.accept(tree);
      byte[] inventory = mapper.writeValueAsBytes(tree);
      var raw = new java.io.ByteArrayOutputStream();
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          raw,
          net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding.SCHEMA);
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          raw, operation.account().canonicalBytes());
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          raw, operation.world().canonicalBytes());
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(raw, inventory);
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          raw,
          "sha256:"
              + java.util.HexFormat.of()
                  .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(inventory)));
      assertThatThrownBy(
              () ->
                  dsl.fetch(
                      "SELECT require_selected_inventory_operation_v2(?::bytea)",
                      raw.toByteArray()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure -> {
                var cause = failure;
                while (cause != null && !(cause instanceof java.sql.SQLException))
                  cause = cause.getCause();
                assertThat(cause).isInstanceOf(java.sql.SQLException.class);
                assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("23514");
              });
    }
    assertThat(
            dsl.fetch(
                    "SELECT to_jsonb(r)::text || ':' || r.xmin::text FROM account_selected_publication_authorizations r ORDER BY 1")
                .getValues(0, String.class))
        .isEqualTo(before);
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
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            policyPayload));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            net.firedevops.firemud.gamedesign.publication.RealmPolicySource.SCOPE,
            target.canonicalVersionId().toString(),
            net.firedevops.firemud.gamedesign.publication.RealmPolicySource.SCOPE,
            "effective",
            "0"));
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
                    .digest(
                        "{\"assets\":{},\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8)));
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

  private static final class SettlementDiagnostics {
    private final AtomicLong sequence = new AtomicLong();
    private final long diagnosticOriginNanos = System.nanoTime();

    void logPostgres(String role, PostgreSQLContainer<?> postgres) {
      try {
        System.out.printf(
            "SETTLEMENT_DIAGNOSTIC event=postgres-container role=%s elapsed_since_diagnostic_start_nanos=%d container=%s host=%s mapped_port=%d running=%s%n",
            role,
            System.nanoTime() - diagnosticOriginNanos,
            postgres.getContainerId(),
            postgres.getHost(),
            postgres.getMappedPort(5432),
            postgres.isRunning());
      } catch (RuntimeException unavailable) {
        System.out.printf(
            "SETTLEMENT_DIAGNOSTIC event=postgres-container role=%s elapsed_since_diagnostic_start_nanos=%d status=unavailable error=%s%n",
            role,
            System.nanoTime() - diagnosticOriginNanos,
            unavailable.getClass().getSimpleName());
      }
    }

    <T> T phase(String stage, Supplier<T> action) {
      long call = sequence.incrementAndGet();
      long started = System.nanoTime();
      event(call, stage, "start", 0L, "none");
      try {
        T result = action.get();
        event(call, stage, "complete", System.nanoTime() - started, "OK");
        return result;
      } catch (RuntimeException | Error failure) {
        event(
            call, stage, "failed", System.nanoTime() - started, failure.getClass().getSimpleName());
        throw failure;
      }
    }

    io.grpc.BindableService timedGrpc(String stage, io.grpc.BindableService owner) {
      return () ->
          ServerInterceptors.intercept(
              owner,
              new io.grpc.ServerInterceptor() {
                @Override
                public <RequestT, ResponseT> io.grpc.ServerCall.Listener<RequestT> interceptCall(
                    io.grpc.ServerCall<RequestT, ResponseT> call,
                    io.grpc.Metadata headers,
                    io.grpc.ServerCallHandler<RequestT, ResponseT> next) {
                  long callSequence = sequence.incrementAndGet();
                  long started = System.nanoTime();
                  String method = call.getMethodDescriptor().getFullMethodName();
                  var recorded = new java.util.concurrent.atomic.AtomicBoolean();
                  var timedCall =
                      new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                        @Override
                        public void sendMessage(ResponseT message) {
                          long sendStarted = System.nanoTime();
                          String serializedSize = serializedSize(message);
                          event(
                              callSequence,
                              stage + "/" + method,
                              "send-message-before",
                              sendStarted - started,
                              serializedSize);
                          try {
                            super.sendMessage(message);
                            long sendFinished = System.nanoTime();
                            event(
                                callSequence,
                                stage + "/" + method,
                                "send-message-after",
                                sendFinished - started,
                                serializedSize
                                    + ",delegate_elapsed_nanos="
                                    + (sendFinished - sendStarted));
                          } catch (RuntimeException | Error failure) {
                            long sendFailed = System.nanoTime();
                            event(
                                callSequence,
                                stage + "/" + method,
                                "send-message-failed",
                                sendFailed - started,
                                serializedSize
                                    + ",delegate_elapsed_nanos="
                                    + (sendFailed - sendStarted)
                                    + ",failure="
                                    + failure.getClass().getSimpleName());
                            throw failure;
                          }
                        }

                        @Override
                        public void close(io.grpc.Status status, io.grpc.Metadata trailers) {
                          if (recorded.compareAndSet(false, true)) {
                            event(
                                callSequence,
                                stage + "/" + method,
                                "closed",
                                System.nanoTime() - started,
                                status.getCode().name());
                          }
                          super.close(status, trailers);
                        }
                      };
                  event(callSequence, stage + "/" + method, "start", 0L, "STARTED");
                  var listener = next.startCall(timedCall, headers);
                  return new io.grpc.ForwardingServerCallListener
                      .SimpleForwardingServerCallListener<>(listener) {
                    @Override
                    public void onMessage(RequestT message) {
                      event(
                          callSequence,
                          stage + "/" + method,
                          "request-message",
                          System.nanoTime() - started,
                          serializedSize(message));
                      super.onMessage(message);
                    }

                    @Override
                    public void onHalfClose() {
                      long handlerStarted = System.nanoTime();
                      event(
                          callSequence,
                          stage + "/" + method,
                          "handler-enter",
                          handlerStarted - started,
                          "ENTERED");
                      try {
                        super.onHalfClose();
                        long handlerFinished = System.nanoTime();
                        event(
                            callSequence,
                            stage + "/" + method,
                            "handler-return",
                            handlerFinished - started,
                            "handler_elapsed_nanos=" + (handlerFinished - handlerStarted));
                      } catch (RuntimeException | Error failure) {
                        long handlerFailed = System.nanoTime();
                        event(
                            callSequence,
                            stage + "/" + method,
                            "handler-failed",
                            handlerFailed - started,
                            "handler_elapsed_nanos="
                                + (handlerFailed - handlerStarted)
                                + ",failure="
                                + failure.getClass().getSimpleName());
                        throw failure;
                      }
                    }

                    @Override
                    public void onCancel() {
                      if (recorded.compareAndSet(false, true)) {
                        event(
                            callSequence,
                            stage + "/" + method,
                            "cancelled",
                            System.nanoTime() - started,
                            "CANCELLED");
                      }
                      super.onCancel();
                    }
                  };
                }
              });
    }

    PlatformTransactionManager timeTransactions(PlatformTransactionManager delegate) {
      return new PlatformTransactionManager() {
        private final Map<TransactionStatus, TransactionTiming> active = new IdentityHashMap<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
          long call = sequence.incrementAndGet();
          long started = System.nanoTime();
          event(call, "account-transaction", "acquire-start", 0L, "STARTED");
          try {
            TransactionStatus status = delegate.getTransaction(definition);
            synchronized (active) {
              active.put(status, new TransactionTiming(call, started));
            }
            event(call, "account-transaction", "acquired", System.nanoTime() - started, "OK");
            return status;
          } catch (RuntimeException | Error failure) {
            event(
                call,
                "account-transaction",
                "acquire-failed",
                System.nanoTime() - started,
                failure.getClass().getSimpleName());
            throw failure;
          }
        }

        @Override
        public void commit(TransactionStatus status) {
          finish(status, true);
        }

        @Override
        public void rollback(TransactionStatus status) {
          finish(status, false);
        }

        private void finish(TransactionStatus status, boolean commit) {
          TransactionTiming timing;
          synchronized (active) {
            timing = active.remove(status);
          }
          long completionStarted = System.nanoTime();
          try {
            if (commit) {
              delegate.commit(status);
            } else {
              delegate.rollback(status);
            }
            if (timing != null) {
              event(
                  timing.sequence(),
                  "account-transaction",
                  commit ? "committed" : "rolled-back",
                  System.nanoTime() - timing.startedNanos(),
                  "completion_ns=" + (System.nanoTime() - completionStarted));
            }
          } catch (RuntimeException | Error failure) {
            if (timing != null) {
              event(
                  timing.sequence(),
                  "account-transaction",
                  commit ? "commit-failed" : "rollback-failed",
                  System.nanoTime() - timing.startedNanos(),
                  failure.getClass().getSimpleName());
            }
            throw failure;
          }
        }
      };
    }

    private String serializedSize(Object message) {
      if (message instanceof com.google.protobuf.MessageLite protobufMessage) {
        try {
          return "serialized_size_bytes=" + protobufMessage.getSerializedSize();
        } catch (RuntimeException unavailable) {
          return "serialized_size_bytes=unavailable";
        }
      }
      return "serialized_size_bytes=unavailable";
    }

    private void event(long call, String stage, String event, long elapsedNanos, String result) {
      long elapsedSinceDiagnosticStartNanos = System.nanoTime() - diagnosticOriginNanos;
      System.out.printf(
          "SETTLEMENT_DIAGNOSTIC call=%d stage=%s event=%s elapsed_nanos=%d elapsed_since_diagnostic_start_nanos=%d result=%s%n",
          call, stage, event, elapsedNanos, elapsedSinceDiagnosticStartNanos, result);
    }

    private record TransactionTiming(long sequence, long startedNanos) {}
  }

  private static Server ownerServer(
      ProofPki pki, String service, io.grpc.BindableService... handlers) throws Exception {
    var identity = pki.server(service);
    var builder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.forServer(identity.certificate().toFile(), identity.key().toFile())
                    .trustManager(pki.ca().toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build());
    for (var handler : handlers)
      builder.addService(ServerInterceptors.intercept(handler, new GrpcPeerIdentityInterceptor()));
    return builder.build().start();
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
    var manager = new DataSourceTransactionManager(dataSource);
    var configuration = new org.jooq.impl.DefaultConfiguration();
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(
        new org.jooq.impl.DataSourceConnectionProvider(
            new TransactionAwareDataSourceProxy(dataSource)));
    configuration.set(
        new org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider(manager));
    DSLContext dsl = DSL.using(configuration);
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
              "account-service",
              "world-management-service",
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
