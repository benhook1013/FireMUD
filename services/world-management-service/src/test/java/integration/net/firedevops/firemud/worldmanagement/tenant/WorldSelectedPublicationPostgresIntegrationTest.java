package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.util.JsonFormat;
import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Participant;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.ReleaseContent;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadEvidence;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadClient;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.impl.WorldDraftDesignDigestServiceImpl;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.worldmanagement.v1.GenerationRuleDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldEntitySpawnBindingDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceClosureDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamily;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Current-default PostgreSQL component proof. The source, Version, synchronized selection, Account
 * source vector, COMMIT_ORDER verifier, and Game Design terminal read are controlled fixtures: this
 * proves owner-local World storage and required-mTLS owner readback, not a genuine Account actor,
 * source hold, producer provenance, public publication, selector activation, or runtime admission.
 * The paired Game Design Version/DRAFT revision and affected unit are controlled original-order
 * binding inputs only; this test does not claim a physical Game Design mutation. The graph declares
 * explicit counts for all six families (including zero generation rules) and all seven empty
 * inbound families. A separate opaque nonempty generation-rule fixture proves that selected
 * freeze/inventory failure leaves the APPLIED graph and World owner unchanged.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldSelectedPublicationPostgresIntegrationTest {
  private static final String HISTORY = "flyway_schema_history_world_management_service";
  private static final String NAMESPACE = "firemud";
  private static final List<String> MUTATION_TABLES =
      List.of(
          "region",
          "zone",
          "room",
          "room_exit",
          "generation_rule",
          "world_entity_spawn_binding",
          "world_authored_topology_identity",
          "world_design_aggregate_epoch",
          "world_design_scope_epoch",
          "world_authored_source_intake",
          "world_authored_version_identity",
          "world_topology_draft_commit",
          "world_draft_graph_application",
          "world_authored_graph_snapshot",
          "world_canonical_frozen_topology",
          "world_draft_start_location_receipt",
          "world_draft_graph_terminal_identity",
          "world_draft_terminal_outcome",
          "world_design_publication_fence_owner",
          "world_design_publication_fence_attempt",
          "world_design_publication_account_binding",
          "world_selected_publication_artifact_inventory",
          "world_design_publication_terminal");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @TempDir Path temporaryDirectory;

  @Test
  void currentDefaultAppliedFreezeInventoryTerminalAndIndependentOwnerReadback() throws Exception {
    String schema = "world_selected_postgres_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var transactionManager = new DataSourceTransactionManager(dataSource);
    var write = new TransactionTemplate(transactionManager);
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    Path root = repositoryRoot();
    try {
      Flyway flyway =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(schema)
              .defaultSchema(schema)
              .table(HISTORY)
              .placeholders(Map.of("serviceSchema", schema))
              .locations(
                  "filesystem:"
                      + root.resolve(
                          "services/world-management-service/src/main/resources/db/migration"),
                  "filesystem:"
                      + root.resolve("services/common-saga/src/main/resources/db/migration/saga"))
              .load();
      flyway.migrate();
      flyway.validate();
      assertThat(
              dsl.fetch("SELECT version FROM " + HISTORY + " WHERE version IS NOT NULL")
                  .getValues(0, String.class))
          .contains("1012", "1014", "1017", "1028", "1029", "1032", "1033", "1034", "1042");

      var intakeRepository = new WorldAuthoredSourceIntakeRepository(dsl);
      var versionRepository = new WorldAuthoredVersionIdentityRepository(dsl);
      var fence = new WorldDesignPublicationFenceRepository(dsl, intakeRepository);
      var mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
      var topology = new WorldDraftTopologyCommitRepository(dsl, fence, mapper);
      var applications = new WorldDraftGraphApplicationRepository(dsl, fence, mapper);
      var applicationService =
          new WorldDraftGraphApplicationService(
              applications,
              transactionManager,
              operation -> new WorldDraftGraphApplicationService.CommitOrderProof(operation));

      OwnerFixture appliedOwner =
          createOwner(intakeRepository, versionRepository, write, "selected-applied");
      // A complete six-family vector includes the owner-proved zero generation-rule count.
      WorldDraftTopologyCommitPlan appliedPlan = topologyPlan(appliedOwner, true);
      WorldDraftGraphApplication applied = application(appliedPlan);
      WorldDraftGraphAppliedResult appliedResult = applicationService.apply(applied);
      assertThat(appliedResult.status()).isEqualTo("APPLIED");

      WorldDraftGraphAppliedResult directReadback =
          applications.readCommitted(applied).orElseThrow();
      WorldDraftGraphAppliedResult accountSelectorReadback =
          applications
              .readCommitted(NAMESPACE, applied.operation().accountBindingBytes())
              .orElseThrow();
      WorldDraftTopologyCommitEvidence topologyReadback =
          topology.readCommitted(appliedPlan).orElseThrow();
      byte[] committedTopologyResultBytes = topologyReadback.resultBytes();
      assertThat(directReadback.canonicalBytes()).containsExactly(appliedResult.canonicalBytes());
      assertThat(accountSelectorReadback.canonicalBytes())
          .containsExactly(appliedResult.canonicalBytes());
      assertThat(topologyReadback.graphBytes()).containsExactly(appliedResult.graphBytes());
      var canonicalGraph =
          new WorldCanonicalAuthoredGraphReader().read(appliedPlan, appliedResult.graphBytes());
      assertThat(
              new tools.jackson.databind.ObjectMapper()
                  .readTree(appliedResult.graphBytes())
                  .get("schemaVersion")
                  .asText())
          .isEqualTo("3");
      assertThat(canonicalGraph.inboundSourceClosure()).isPresent();
      assertThat(
              canonicalGraph.inboundSourceClosure().orElseThrow().familyCounts().stream()
                  .map(WorldDraftTopologyInputGraph.InboundSourceFamilyCount::count)
                  .toList())
          .containsExactly(0, 0, 0, 0, 0, 0, 0);
      assertThat(canonicalGraph.family(WorldCanonicalAuthoredGraph.Family.REGION)).hasSize(1);
      assertThat(canonicalGraph.family(WorldCanonicalAuthoredGraph.Family.ZONE)).hasSize(1);
      assertThat(canonicalGraph.family(WorldCanonicalAuthoredGraph.Family.ROOM)).hasSize(2);
      assertThat(canonicalGraph.family(WorldCanonicalAuthoredGraph.Family.ROOM_EXIT)).hasSize(1);
      assertThat(canonicalGraph.family(WorldCanonicalAuthoredGraph.Family.GENERATION_RULE))
          .isEmpty();
      assertThat(
              canonicalGraph.family(WorldCanonicalAuthoredGraph.Family.WORLD_ENTITY_SPAWN_BINDING))
          .hasSize(1);
      var entityReference =
          canonicalGraph
              .family(WorldCanonicalAuthoredGraph.Family.WORLD_ENTITY_SPAWN_BINDING)
              .getFirst()
              .entityReference();
      assertThat(entityReference.kind())
          .isEqualTo(EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC);
      assertThat(entityReference.tenantId())
          .isEqualTo(appliedPlan.binding().target().canonicalTenantId());
      assertThat(entityReference.versionId())
          .isEqualTo(appliedPlan.binding().target().canonicalVersionId());
      assertThat(entityReference.templateId()).isNotEqualTo(new UUID(0L, 0L));

      assertThat(appliedResult.appliedEpochs())
          .hasSize(12)
          .allSatisfy(
              epoch -> {
                assertThat(epoch.expectedEpoch()).isEqualTo("0");
                assertThat(epoch.resultingEpoch()).isEqualTo("1");
              });
      assertThat(appliedPlan.graph().freshGraphDeclaration().orElseThrow().familyCounts())
          .extracting(WorldDraftTopologyInputGraph.FamilyCount::count)
          .containsExactly(1, 1, 2, 1, 0, 1);

      assertFamilyCount(dsl, "region", appliedOwner, 1);
      assertFamilyCount(dsl, "zone", appliedOwner, 1);
      assertFamilyCount(dsl, "room", appliedOwner, 2);
      assertFamilyCount(dsl, "room_exit", appliedOwner, 1);
      assertFamilyCount(dsl, "generation_rule", appliedOwner, 0);
      assertFamilyCount(dsl, "world_entity_spawn_binding", appliedOwner, 1);
      assertFamilyCount(dsl, "world_authored_topology_identity", appliedOwner, 6);
      assertFamilyCount(dsl, "world_design_aggregate_epoch", appliedOwner, 6);
      assertFamilyCount(dsl, "world_design_scope_epoch", appliedOwner, 1);

      Map<String, List<String>> beforeMalformedEvidence = snapshotMutationRows(dsl);
      for (PlanFault fault :
          List.of(
              PlanFault.OMITTED_FAMILY_COUNT,
              PlanFault.INCONSISTENT_FAMILY_COUNT,
              PlanFault.REPEATED_FAMILY_COUNT,
              PlanFault.REPEATED_DECLARATION,
              PlanFault.FABRICATED_MAPPING)) {
        assertThatThrownBy(() -> topologyPlan(appliedOwner, true, fault))
            .as("malformed selected application evidence: %s", fault)
            .isInstanceOf(IllegalArgumentException.class);
      }
      assertThat(snapshotMutationRows(dsl)).isEqualTo(beforeMalformedEvidence);

      AccountPublicationAuthorizationBinding publicationBinding = publicationBinding(appliedPlan);
      WorldSelectedDraftPublicationFreezeEvidence.Request freezeRequest =
          freezeRequest(publicationBinding);
      FreezeFixture freezeFixture =
          freezeFixture(
              dsl,
              intakeRepository,
              fence,
              topology,
              applications,
              transactionManager,
              appliedOwner);
      GrpcPeerIdentity gameDesignPeer =
          new GrpcPeerIdentity(
              "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-design-service",
              NAMESPACE,
              "game-design-service");
      Context authenticatedGameDesign =
          Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, gameDesignPeer);
      var acknowledgement =
          authenticatedGameDesign.call(() -> freezeFixture.freezeService().begin(freezeRequest));
      assertThat(acknowledgement.ownerFreezePhase())
          .isEqualTo(WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase.FROZEN);
      assertThat(acknowledgement.digestSchemaVersion()).isEqualTo(4);
      WorldSelectedDraftPublicationFreezeEvidence committedFreeze =
          WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
              freezeRequest,
              WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
      var retainedAttempt =
          dsl.fetchSingle(
              "SELECT digest_schema_version, applied_commit_id, content_digest "
                  + "FROM world_design_publication_fence_attempt WHERE publication_fence=?",
              acknowledgement.publicationFence());
      assertThat(retainedAttempt.get("digest_schema_version", Integer.class)).isEqualTo(4);
      assertThat(retainedAttempt.get("applied_commit_id", String.class))
          .isEqualTo(applied.operation().commitId().toString());
      assertThat(retainedAttempt.get("content_digest", String.class))
          .isEqualTo(acknowledgement.contentDigest());

      WorldSelectedPublicationArtifactInventoryEvidence inventoryEvidence =
          authenticatedGameDesign.call(
              () -> freezeFixture.inventoryReadService().read(committedFreeze));
      assertThat(inventoryEvidence.publicEvidence().schemaVersion()).isEqualTo(2);
      assertThat(inventoryEvidence.publicEvidence().completeness()).isEqualTo("COMPLETE");
      assertThat(inventoryEvidence.publicEvidence().checkpoint().digestSchemaVersion())
          .isEqualTo(4);
      assertThat(
              inventoryEvidence.publicEvidence().sourceModel().familyCounts().stream()
                  .map(WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount::rowCount)
                  .toList())
          .containsExactly(1, 1, 2, 1, 0, 1);
      assertThat(inventoryEvidence.publicEvidence().sourceModel().generationRuleInputCount())
          .isZero();
      assertThat(
              dsl.fetchSingle(
                      "SELECT inventory_schema_version FROM "
                          + "world_selected_publication_artifact_inventory WHERE publication_fence=?",
                      acknowledgement.publicationFence())
                  .get("inventory_schema_version", Short.class))
          .isEqualTo((short) 2);
      assertThat(inventoryEvidence.canonicalBytes())
          .containsExactly(inventoryEvidence.publicEvidence().canonicalBytes());

      WorldAuthoredGraphSnapshot.CaptureRequest selectorRequest =
          selectorRequest(appliedPlan, acknowledgement);
      WorldPublishedStartLocationSource selectorSource =
          freezeFixture.selectorRepository().readCommitted(selectorRequest).orElseThrow();
      assertThat(selectorSource.frozenTopology().status())
          .isEqualTo(WorldCanonicalFrozenTopology.STATUS);
      assertThat(selectorSource.frozenTopology().graphBytes())
          .containsExactly(appliedResult.graphBytes());
      assertThat(selectorSource.frozenTopology().storageResultBytes())
          .containsExactly(committedTopologyResultBytes);
      assertThat(selectorSource.appliedResult().canonicalBytes())
          .containsExactly(appliedResult.canonicalBytes());
      assertThat(selectorSource.selectorReceipt().canonicalBytes())
          .containsExactly(appliedResult.startLocationReceipt().orElseThrow().canonicalBytes());
      assertThat(selectorSource.frozenTopology().graph().inboundSourceClosure()).isPresent();
      assertThat(
              selectorSource
                  .frozenTopology()
                  .graph()
                  .inboundSourceClosure()
                  .orElseThrow()
                  .familyCounts()
                  .stream()
                  .map(WorldDraftTopologyInputGraph.InboundSourceFamilyCount::count)
                  .toList())
          .containsExactly(0, 0, 0, 0, 0, 0, 0);
      assertThat(
              dsl.fetchSingle(
                      "SELECT graph_bytes FROM world_canonical_frozen_topology "
                          + "WHERE publication_fence=?",
                      acknowledgement.publicationFence())
                  .get("graph_bytes", byte[].class))
          .containsExactly(appliedResult.graphBytes());

      WorldPublishedStartLocationEvidence worldEvidence =
          publishedStartLocationEvidence(selectorSource);
      assertThat(WorldPublishedStartLocationEvidence.fromStored(worldEvidence.canonicalBytes()))
          .isEqualTo(worldEvidence);
      GameDesignPublicationOperationBinding publicationOperation =
          new GameDesignPublicationOperationBinding(
              publicationBinding, worldEvidence, inventoryEvidence);
      GameDesignPublicationTerminalEvidence terminal =
          publishedTerminal(publicationOperation, worldEvidence);
      var terminalClient = mock(GameDesignPublicationTerminalClient.class);
      var terminalStatus =
          new AtomicReference<GameDesignPublicationTerminalReadEvidence.Status>(
              GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN);
      when(terminalClient.read(any()))
          .thenAnswer(
              invocation -> {
                var request =
                    (GameDesignPublicationTerminalReadEvidence.ReadRequest)
                        invocation.getArgument(0);
                var status = terminalStatus.get();
                return new GameDesignPublicationTerminalReadEvidence.ReadResult(
                    request,
                    status,
                    status == GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN
                        ? Optional.empty()
                        : Optional.of(terminal));
              });
      var terminalRepository = new WorldPublicationTerminalRepository(dsl, transactionManager);
      WorldPublicationTerminalService terminalService =
          WorldPublicationTerminalService.authenticated(
              terminalRepository, terminalClient, NAMESPACE);
      Map<String, List<String>> beforeUnknownTerminal = snapshotMutationRows(dsl);
      assertThatThrownBy(
              () ->
                  authenticatedGameDesign.call(
                      () ->
                          terminalService.complete(
                              publicationOperation.canonicalBytes(), terminal.canonicalBytes())))
          .isInstanceOf(SecurityException.class)
          .hasMessageContaining("outcome is unknown");
      assertThat(snapshotMutationRows(dsl)).isEqualTo(beforeUnknownTerminal);
      assertThat(publicationOwnerPhase(dsl, acknowledgement.publicationFence()))
          .isEqualTo("FROZEN");
      assertThat(publicationTerminalCount(dsl, acknowledgement.publicationFence())).isZero();
      terminalStatus.set(GameDesignPublicationTerminalReadEvidence.Status.PUBLISHED);
      GameDesignPublicationTerminalEvidence completed =
          authenticatedGameDesign.call(
              () ->
                  terminalService.complete(
                      publicationOperation.canonicalBytes(), terminal.canonicalBytes()));
      assertThat(completed.canonicalBytes()).containsExactly(terminal.canonicalBytes());
      assertThat(publicationOwnerPhase(dsl, acknowledgement.publicationFence()))
          .isEqualTo("PUBLISHED");
      assertThat(publicationTerminalCount(dsl, acknowledgement.publicationFence())).isEqualTo(1L);
      assertThat(publicationTerminalBytes(dsl, acknowledgement.publicationFence()))
          .containsExactly(terminal.canonicalBytes());
      String expectedWorldRequest =
          WorldPublicationTerminal.request(terminal.canonicalBytes()).worldRequestJson();
      String reformattedWorldRequest =
          dsl.fetchSingle("SELECT jsonb_pretty(?::jsonb)", expectedWorldRequest)
              .get(0, String.class);
      assertThat(
              WorldPublicationTerminalRepository.sameWorldRequestJson(
                  expectedWorldRequest, reformattedWorldRequest))
          .isTrue();
      String changedWorldRequest =
          expectedWorldRequest.replace(
              "\"versionStateEpoch\":\"" + worldEvidence.request().versionStateEpoch() + "\"",
              "\"versionStateEpoch\":\""
                  + (worldEvidence.request().versionStateEpoch() + 1L)
                  + "\"");
      assertThat(changedWorldRequest).isNotEqualTo(expectedWorldRequest);
      assertThat(
              WorldPublicationTerminalRepository.sameWorldRequestJson(
                  expectedWorldRequest, changedWorldRequest))
          .isFalse();
      assertThat(
              WorldPublicationTerminalRepository.sameWorldRequestJson(
                  expectedWorldRequest, "{malformed"))
          .isFalse();
      assertThat(
              WorldPublicationTerminalRepository.sameWorldRequestJson(expectedWorldRequest, "{}"))
          .isFalse();
      assertThat(
              WorldPublicationTerminalRepository.sameWorldRequestJson(expectedWorldRequest, "[]"))
          .isFalse();
      assertThat(
              WorldPublicationTerminalRepository.sameWorldRequestJson(
                  expectedWorldRequest, "{\"value\":1,\"value\":1}"))
          .isFalse();
      assertThat(
              WorldPublicationTerminalRepository.sameWorldRequestJson(
                  expectedWorldRequest, expectedWorldRequest + " {}"))
          .isFalse();
      var terminalReadRequests =
          org.mockito.ArgumentCaptor.forClass(
              GameDesignPublicationTerminalReadEvidence.ReadRequest.class);
      verify(terminalClient, org.mockito.Mockito.times(2)).read(terminalReadRequests.capture());
      assertThat(terminalReadRequests.getAllValues())
          .allSatisfy(
              request -> {
                assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
                assertThat(request.operationBytes())
                    .containsExactly(publicationOperation.canonicalBytes());
              });
      assertThat(terminalReadRequests.getAllValues().get(0).readRequestId())
          .isNotEqualTo(terminalReadRequests.getAllValues().get(1).readRequestId());

      var pki = TerminalReadTestPki.create(temporaryDirectory.resolve("terminal-read-pki"));
      var readService = new WorldPublicationTerminalReadService(terminalRepository, NAMESPACE);
      Server readServer =
          startWorldTerminalReadTransport(
              new WorldPublicationTerminalReadGrpcService(readService, NAMESPACE), pki);
      try (WorldPublicationTerminalReadClient client =
          worldTerminalReadClient(
              readServer, pki.accountClient().clientProperties(pki.caCertificate()))) {
        client.init();
        var ownerRequest =
            WorldPublicationTerminalReadEvidence.Request.create(
                NAMESPACE, publicationOperation.canonicalBytes(), terminal.canonicalBytes());
        var ownerRead = client.read(ownerRequest);
        var exactRetry = client.read(ownerRequest);
        assertThat(ownerRead.request()).isEqualTo(ownerRequest);
        assertThat(ownerRead.worldOutcome())
            .isEqualTo(WorldPublicationTerminalReadEvidence.WorldOutcome.PUBLISHED);
        assertThat(ownerRead.worldTerminalEvidence()).containsExactly(terminal.canonicalBytes());
        assertThat(exactRetry.worldTerminalEvidence()).containsExactly(terminal.canonicalBytes());
      } finally {
        readServer.shutdownNow();
        assertThat(readServer.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      }

      OwnerFixture unsupportedRuleOwner =
          createOwner(intakeRepository, versionRepository, write, "selected-unsupported-rule");
      WorldDraftTopologyCommitPlan unsupportedRulePlan = topologyPlan(unsupportedRuleOwner, false);
      WorldDraftGraphApplication unsupportedRuleApplication = application(unsupportedRulePlan);
      WorldDraftGraphAppliedResult unsupportedRuleResult =
          applicationService.apply(unsupportedRuleApplication);
      AccountPublicationAuthorizationBinding unsupportedRuleBinding =
          publicationBinding(unsupportedRulePlan);
      WorldSelectedDraftPublicationFreezeEvidence.Request unsupportedRuleFreezeRequest =
          freezeRequest(unsupportedRuleBinding);
      FreezeFixture unsupportedRuleFreeze =
          freezeFixture(
              dsl,
              intakeRepository,
              fence,
              topology,
              applications,
              transactionManager,
              unsupportedRuleOwner);
      Map<String, List<String>> beforeUnsupportedRuleFreeze = snapshotMutationRows(dsl);
      assertThatThrownBy(
              () ->
                  authenticatedGameDesign.call(
                      () ->
                          unsupportedRuleFreeze
                              .freezeService()
                              .begin(unsupportedRuleFreezeRequest)))
          .satisfies(
              failure ->
                  assertThat(allThrowableText(failure))
                      .contains(
                          "GENERATION_RULE name/scope/value inputs without a closed requiredness registry"));
      assertThat(snapshotMutationRows(dsl)).isEqualTo(beforeUnsupportedRuleFreeze);
      assertThat(publicationOwnerPhase(dsl, unsupportedRuleOwner)).isEqualTo("OPEN");
      assertThat(
              applications.readCommitted(unsupportedRuleApplication).orElseThrow().canonicalBytes())
          .containsExactly(unsupportedRuleResult.canonicalBytes());
      assertThat(topology.readCommitted(unsupportedRulePlan).orElseThrow().graphBytes())
          .containsExactly(unsupportedRuleResult.graphBytes());

      OwnerFixture storedOwner =
          createOwner(intakeRepository, versionRepository, write, "selected-stored");
      WorldDraftTopologyCommitPlan storedPlan = topologyPlan(storedOwner, true);
      WorldDraftGraphApplication storedApplication = application(storedPlan);
      Objects.requireNonNull(write.execute(status -> topology.store(storedPlan)));
      assertThat(topology.readCommitted(storedPlan).orElseThrow().status())
          .isEqualTo("STORED_PERMISSION_UNVERIFIED");
      Map<String, List<String>> afterStoredWrite = snapshotMutationRows(dsl);
      assertThatThrownBy(() -> applicationService.apply(storedApplication))
          .hasMessageContaining(
              "Existing permission-unverified history cannot be promoted to APPLIED");
      assertThat(snapshotMutationRows(dsl)).isEqualTo(afterStoredWrite);
      assertThat(applications.readCommitted(storedApplication)).isEmpty();
    } finally {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static FreezeFixture freezeFixture(
      DSLContext dsl,
      WorldAuthoredSourceIntakeRepository intakeRepository,
      WorldDesignPublicationFenceRepository fence,
      WorldDraftTopologyCommitRepository topology,
      WorldDraftGraphApplicationRepository applications,
      DataSourceTransactionManager transactionManager,
      OwnerFixture owner) {
    var selectionClient = mock(AuthoredDraftPublishSelectionReadClient.class);
    when(selectionClient.read(any()))
        .thenAnswer(
            invocation -> {
              var request =
                  (AuthoredDraftPublishSelectionReadEvidence.Request) invocation.getArgument(0);
              var evidence = mock(AuthoredDraftPublishSelectionReadEvidence.class);
              when(evidence.request()).thenReturn(request);
              return evidence;
            });
    var versionStateClient = mock(AuthoredWorldVersionStateClient.class);
    when(versionStateClient.read(any()))
        .thenAnswer(
            invocation -> {
              var request = (AuthoredWorldVersionStateEvidence.Request) invocation.getArgument(0);
              return AuthoredWorldVersionStateEvidence.create(
                  request,
                  owner.source(),
                  owner.version().canonicalVersionId(),
                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                  1L);
            });
    var accountClient = mock(AccountPublicationAuthorizationReadClient.class);
    when(accountClient.read(any()))
        .thenAnswer(
            invocation -> {
              var request =
                  (AccountPublicationAuthorizationReadEvidence.Request) invocation.getArgument(0);
              var evidence = mock(AccountPublicationAuthorizationReadEvidence.class);
              when(evidence.request()).thenReturn(request);
              return evidence;
            });
    var authorizationRepository = new WorldSelectedDraftPublicationAuthorizationRepository(dsl);
    var inventoryRepository = new WorldSelectedPublicationArtifactInventoryRepository(dsl);
    var authoredSnapshotRepository = new WorldAuthoredGraphSnapshotRepository(dsl);
    var digestService =
        new WorldDraftDesignDigestServiceImpl(
            new RegionRepository(dsl),
            new ZoneRepository(dsl),
            new RoomRepository(dsl),
            new RoomExitRepository(dsl),
            new GenerationRuleRepository(dsl),
            new WorldEntitySpawnBindingRepository(dsl));
    var frozenTopologyRepository =
        new WorldCanonicalFrozenTopologyRepository(
            dsl, authoredSnapshotRepository, topology, digestService);
    var selectorCapture =
        new WorldSelectedPublicationSelectorCapture(
            NAMESPACE,
            new WorldCanonicalFrozenTopologyService(frozenTopologyRepository, transactionManager));
    var service =
        new WorldSelectedDraftPublicationFreezeService(
            NAMESPACE,
            selectionClient,
            versionStateClient,
            accountClient,
            intakeRepository,
            fence,
            new WorldSelectedDraftPublicationCheckpointRepository(
                fence, applications, topology, digestService),
            authorizationRepository,
            inventoryRepository,
            applications,
            selectorCapture,
            transactionManager);
    return new FreezeFixture(
        service,
        new WorldSelectedPublicationArtifactInventoryReadService(
            NAMESPACE, fence, authorizationRepository, inventoryRepository),
        new WorldPublishedStartLocationRepository(dsl, frozenTopologyRepository, applications));
  }

  private static WorldSelectedDraftPublicationFreezeEvidence.Request freezeRequest(
      AccountPublicationAuthorizationBinding accountBinding) {
    var selection = accountBinding.input().selection();
    return WorldSelectedDraftPublicationFreezeEvidence.Request.create(
        NAMESPACE,
        selection.target().canonicalTenantId(),
        selection.intent().canonicalVersionId(),
        selection.intent().publishRequestId(),
        Long.parseLong(selection.intent().expectedVersionStateEpoch()),
        selection.digest().substring("sha256:".length()),
        accountBinding);
  }

  private static WorldAuthoredGraphSnapshot.CaptureRequest selectorRequest(
      WorldDraftTopologyCommitPlan plan,
      WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement acknowledgement) {
    var request = acknowledgement.request();
    var selection = request.accountBinding().input().selection();
    String workflowId =
        PublicationDigestRequestBinding.full(
                request.canonicalTenantId().toString(),
                Long.toString(selection.target().gameDesignVersionRowId()),
                request.publicationRequestId())
            .derivedWorkflowIdentity();
    var tuples =
        plan.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    return new WorldAuthoredGraphSnapshot.CaptureRequest(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        acknowledgement.intakeRequestId(),
        acknowledgement.publicationFence(),
        request.publicationRequestId(),
        request.requestDigest(),
        acknowledgement.versionStateEpoch(),
        workflowId,
        acknowledgement.appliedCommitId(),
        acknowledgement.contentDigest(),
        acknowledgement.digestSchemaVersion(),
        tuples);
  }

  private static WorldPublishedStartLocationEvidence publishedStartLocationEvidence(
      WorldPublishedStartLocationSource source) {
    var freeze = source.frozenTopology().request().freeze();
    var tuples =
        freeze.suppliedOwnedAffectedTuples().stream()
            .map(
                tuple ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        tuple.owner(),
                        tuple.aggregateType(),
                        tuple.aggregateId(),
                        tuple.scopeType(),
                        tuple.scopeId(),
                        tuple.expectedEpoch()))
            .toList();
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            freeze.targetNamespace(),
            freeze.canonicalTenantId(),
            freeze.canonicalVersionId(),
            freeze.intakeRequestId(),
            freeze.publicationFence(),
            freeze.publicationRequestId(),
            freeze.requestDigest(),
            freeze.versionStateEpoch(),
            freeze.publishWorkflowId(),
            freeze.appliedCommitId(),
            freeze.contentDigest(),
            freeze.digestSchemaVersion(),
            tuples);
    return new WorldPublishedStartLocationEvidence(
        request,
        source.selectorReceipt().canonicalBytes(),
        source.appliedResult().application().operation().accountBindingBytes(),
        source.appliedResult().canonicalBytes());
  }

  private static GameDesignPublicationTerminalEvidence publishedTerminal(
      GameDesignPublicationOperationBinding operation,
      WorldPublishedStartLocationEvidence worldEvidence) {
    long selectedVersionRowId =
        operation.account().input().selection().target().gameDesignVersionRowId();
    String scope = Long.toString(selectedVersionRowId);
    String commitId = worldEvidence.request().appliedCommitId();
    List<Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                participant ->
                    new Participant(
                        participant,
                        scope,
                        null,
                        commitId,
                        "WORLD_MANAGEMENT".equals(participant)
                            ? worldEvidence.request().contentDigest()
                            : "a".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participant,
                            AuthoredWorldReleaseAttestationEvidence
                                .CLOSURE_SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(participant) ? "sha256:" + "b".repeat(64) : null,
                        null,
                        null))
            .toList();
    var release =
        new ReleaseContent(
            worldEvidence.request().canonicalTenantId(),
            worldEvidence.request().canonicalVersionId(),
            "controlled-component-release-" + UUID.randomUUID(),
            1,
            "v3",
            worldEvidence.request().publishWorkflowId(),
            "sha256:" + "c".repeat(64),
            1,
            List.of(),
            List.of(),
            participants,
            List.of(),
            "controlled-generation-config",
            worldEvidence);
    return new GameDesignPublicationTerminalEvidence(
        operation.canonicalBytes(),
        Outcome.PUBLISHED,
        release,
        worldEvidence.request().versionStateEpoch() + 1L);
  }

  private static AccountPublicationAuthorizationBinding publicationBinding(
      WorldDraftTopologyCommitPlan plan) {
    DraftCommitBinding binding = plan.binding();
    UUID actor = UUID.randomUUID();
    String publicationRequest =
        "publish-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    var intent =
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            publicationRequest,
            "1",
            "controlled selected-publication component fixture",
            binding.requestId(),
            binding.commitId(),
            binding.digest());
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            intent,
            binding.target(),
            binding,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                binding.target(),
                binding.requestId(),
                binding.commitId(),
                binding.digest(),
                "[]",
                OffsetDateTime.now(ZoneOffset.UTC)));
    return new AccountPublicationAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new AccountPublicationAuthorizationBinding.PreallocationInput(actor, selection),
        List.of(
            new SourceEvidence(
                SourceKind.GLOBAL_ROLES,
                actor.toString(),
                null,
                "1",
                null,
                null,
                "controlled source state; not Account authorization"
                    .getBytes(StandardCharsets.UTF_8))));
  }

  private static WorldDraftGraphApplication application(WorldDraftTopologyCommitPlan plan) {
    UUID operationId = UUID.randomUUID();
    UUID authorizationFenceId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    DraftCommitBinding binding = plan.binding();
    byte[] accountBytes =
        new DraftAuthorizationFenceBinding(
                operationId,
                binding.requestId(),
                binding.commitId(),
                authorizationFenceId,
                actorId,
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.baseCommitId(),
                "0",
                binding.canonicalBytes(),
                binding.canonicalBytes(),
                binding.digest(),
                List.of(
                    new SourceEvidence(
                        SourceKind.GLOBAL_ROLES,
                        actorId.toString(),
                        null,
                        "1",
                        null,
                        null,
                        "synthetic COMMIT_ORDER component fixture"
                            .getBytes(StandardCharsets.UTF_8))))
            .withRequiredOwners()
            .canonicalBytes();
    var operation =
        new WorldDraftTerminalOperation(
            operationId,
            binding.requestId(),
            binding.commitId(),
            authorizationFenceId,
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding,
            plan.ownerBinding(),
            accountBytes);
    return new WorldDraftGraphApplication(operation, plan);
  }

  private static WorldDraftTopologyCommitPlan topologyPlan(
      OwnerFixture owner, boolean omitGenerationRule) throws Exception {
    return topologyPlan(owner, omitGenerationRule, PlanFault.NONE);
  }

  private static WorldDraftTopologyCommitPlan topologyPlan(
      OwnerFixture owner, boolean omitGenerationRule, PlanFault fault) throws Exception {
    UUID commitId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    UUID zoneId = UUID.randomUUID();
    UUID firstRoomId = UUID.randomUUID();
    UUID secondRoomId = UUID.randomUUID();
    UUID entityTemplateId = UUID.randomUUID();
    var mutations = new ArrayList<WorldDesignMutationRevision>();
    mutations.add(
        mutation(
                commitId,
                regionId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setRegion(RegionDesignMutation.newBuilder().setName("Selected region"))
            .build());
    mutations.add(
        mutation(
                commitId,
                zoneId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
            .setZone(
                ZoneDesignMutation.newBuilder()
                    .setName("Selected zone")
                    .setRegionId(regionId.toString()))
            .build());
    mutations.add(
        mutation(
                commitId,
                firstRoomId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder()
                    .setName("Selected start")
                    .setZoneId(zoneId.toString()))
            .build());
    mutations.add(
        mutation(
                commitId,
                secondRoomId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder()
                    .setName("Selected destination")
                    .setZoneId(zoneId.toString()))
            .build());
    mutations.add(
        mutation(
                commitId,
                UUID.randomUUID(),
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT)
            .setRoomExit(
                RoomExitDesignMutation.newBuilder()
                    .setFromRoomId(firstRoomId.toString())
                    .setToRoomId(secondRoomId.toString())
                    .setDirection("EAST"))
            .build());
    if (!omitGenerationRule) {
      // Match the established guarded storage fixture. These remain opaque inputs that must
      // trigger requiredness refusal; they are not recognized semantics or COMPLETE proof.
      mutations.add(
          mutation(
                  commitId,
                  UUID.randomUUID(),
                  regionId,
                  WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE)
              .setGenerationRule(
                  GenerationRuleDesignMutation.newBuilder().setName("rule").setValue("fixed"))
              .build());
    }
    mutations.add(
        mutation(
                commitId,
                UUID.randomUUID(),
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING)
            .setWorldEntitySpawnBinding(
                WorldEntitySpawnBindingDesignMutation.newBuilder()
                    .setRoomId(firstRoomId.toString())
                    .setEntityTemplateType(
                        EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC)
                    .setEntityTemplateId(entityTemplateId.toString())
                    .setSpawnCount(2))
            .build());

    WorldFreshGraphDeclaration declaration =
        declaration(
            owner.version().canonicalVersionId(),
            owner.intake().canonicalTenantId(),
            firstRoomId,
            mutations);
    if (fault == PlanFault.OMITTED_FAMILY_COUNT) {
      declaration = declaration.toBuilder().removeFamilyCounts(4).build();
    } else if (fault == PlanFault.INCONSISTENT_FAMILY_COUNT) {
      declaration =
          declaration.toBuilder()
              .setFamilyCounts(0, declaration.getFamilyCounts(0).toBuilder().setCount(2).build())
              .build();
    } else if (fault == PlanFault.REPEATED_FAMILY_COUNT) {
      declaration = declaration.toBuilder().addFamilyCounts(declaration.getFamilyCounts(0)).build();
    }
    mutations.set(
        0, mutations.getFirst().toBuilder().setFreshGraphDeclaration(declaration).build());
    if (fault == PlanFault.REPEATED_DECLARATION) {
      mutations.set(1, mutations.get(1).toBuilder().setFreshGraphDeclaration(declaration).build());
    }

    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> affected = new ArrayList<>();
    UUID canonicalVersionId = owner.version().canonicalVersionId();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "controlled selected Game Design Version original-order evidence"));
    affected.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "DRAFT",
            canonicalVersionId.toString(),
            "AGGREGATE",
            canonicalVersionId.toString(),
            "0"));
    for (int index = 0; index < mutations.size(); index++) {
      WorldDesignMutationRevision mutation = mutations.get(index);
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(index + 1),
              UUID.fromString(mutation.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              JsonFormat.printer().omittingInsignificantWhitespace().print(mutation)));
      String family =
          mutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      affected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "AGGREGATE",
              mutation.getAggregateId(),
              "0"));
      affected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "REGION_SUBTREE",
              regionId.toString(),
              "0"));
    }
    if (fault == PlanFault.FABRICATED_MAPPING) {
      UUID invented = UUID.randomUUID();
      affected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              "REGION",
              invented.toString(),
              "AGGREGATE",
              invented.toString(),
              "0"));
    }
    var source = owner.source();
    var binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                source.canonicalTenantId(),
                owner.version().canonicalVersionId(),
                owner.version().gameDesignVersionId(),
                source.sourceGameTenantKey(),
                source.sourceGameRowId(),
                source.sourceGameTenantKey(),
                source.provenanceKind()),
            UUID.randomUUID(),
            commitId,
            "controlled-base-commit",
            revisions,
            affected);
    OwnerBinding ownerBinding =
        new OwnerBinding(
            NAMESPACE,
            source.canonicalTenantId(),
            owner.version().canonicalVersionId(),
            owner.version().operationId(),
            owner.version().gameDesignVersionId(),
            owner.intake().intakeRequestId(),
            owner.intake().operationId(),
            owner.intake().requestDigest(),
            source.operationId(),
            source.evidenceDigest(),
            owner.intake().receiptDigest());
    return WorldDraftTopologyCommitPlan.create(binding, ownerBinding);
  }

  private static WorldFreshGraphDeclaration declaration(
      UUID versionId,
      UUID tenantId,
      UUID startRoomId,
      List<WorldDesignMutationRevision> mutations) {
    return WorldFreshGraphDeclaration.newBuilder()
        .setTenantId(tenantId.toString())
        .setVersionId(versionId.toString())
        .setStartLocation(
            net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                .setTenantId(tenantId.toString())
                .setVersionId(versionId.toString())
                .setRoomTemplateId(startRoomId.toString()))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE))
        .addFamilyCounts(
            count(
                mutations,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING))
        .setInboundSourceClosure(inboundSourceClosure())
        .build();
  }

  private static WorldFreshGraphFamilyCount count(
      List<WorldDesignMutationRevision> mutations, WorldDesignAggregateType family) {
    int count =
        Math.toIntExact(
            mutations.stream().filter(mutation -> mutation.getAggregateType() == family).count());
    return WorldFreshGraphFamilyCount.newBuilder().setFamily(family).setCount(count).build();
  }

  private static WorldInboundSourceClosureDeclaration inboundSourceClosure() {
    return WorldInboundSourceClosureDeclaration.newBuilder()
        .setSchemaVersion(1)
        .addFamilyCounts(
            inboundCount(WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT))
        .addFamilyCounts(
            inboundCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT))
        .addFamilyCounts(
            inboundCount(WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION))
        .addFamilyCounts(
            inboundCount(WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING))
        .addFamilyCounts(
            inboundCount(WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK))
        .addFamilyCounts(
            inboundCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE))
        .addFamilyCounts(
            inboundCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING))
        .build();
  }

  private static WorldInboundSourceFamilyCount inboundCount(WorldInboundSourceFamily family) {
    return WorldInboundSourceFamilyCount.newBuilder().setFamily(family).setCount(0).build();
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

  private static OwnerFixture createOwner(
      WorldAuthoredSourceIntakeRepository intakeRepository,
      WorldAuthoredVersionIdentityRepository versionRepository,
      TransactionTemplate write,
      String label) {
    AuthoredWorldSourceEvidence source = controlledSource(label);
    var intake =
        java.util.Objects.requireNonNull(
            write.execute(
                status -> intakeRepository.acceptFresh(NAMESPACE, UUID.randomUUID(), source)));
    var state =
        AuthoredWorldVersionStateEvidence.create(
            new AuthoredWorldVersionStateEvidence.Request(
                1,
                NAMESPACE,
                UUID.randomUUID(),
                source.canonicalTenantId(),
                source.worldSlug(),
                source.operationId(),
                source.evidenceDigest(),
                42L),
            source,
            UUID.randomUUID(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            1L);
    var version =
        java.util.Objects.requireNonNull(
            write.execute(status -> versionRepository.acceptFresh(intake, state)));
    return new OwnerFixture(source, intake, version);
  }

  private static AuthoredWorldSourceEvidence controlledSource(String label) {
    UUID canonicalTenant = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String tenantSlug = "owner-" + suffix;
    String worldSlug = "world-" + suffix;
    String displayName = "Controlled " + label;
    long sourceRow = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000L) + 1L;
    String sourceTenantKey = "source-" + suffix;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, canonicalTenant, tenantSlug, worldSlug, displayName);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registration,
        operation,
        requestDigest,
        canonicalTenant,
        tenantSlug,
        worldSlug,
        displayName,
        sourceRow,
        sourceTenantKey,
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            operation,
            requestDigest,
            canonicalTenant,
            tenantSlug,
            worldSlug,
            displayName,
            sourceRow,
            sourceTenantKey,
            "NEW_GAME_ROW"));
  }

  private static void assertFamilyCount(
      DSLContext dsl, String table, OwnerFixture owner, long expectedCount) {
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM " + table + " WHERE tenant_id=? AND version_id=?",
                    owner.intake().localTenantKey(),
                    owner.version().localVersionKey())
                .get(0, Long.class))
        .as("committed local rows for %s", table)
        .isEqualTo(expectedCount);
  }

  private static String publicationOwnerPhase(DSLContext dsl, UUID publicationFence) {
    return dsl.fetchSingle(
            "SELECT owner_freeze_phase FROM world_design_publication_fence_owner "
                + "WHERE current_publication_fence=?",
            publicationFence)
        .get("owner_freeze_phase", String.class);
  }

  private static String publicationOwnerPhase(DSLContext dsl, OwnerFixture owner) {
    return dsl.fetchSingle(
            "SELECT owner_freeze_phase FROM world_design_publication_fence_owner "
                + "WHERE local_tenant_key=? AND version_id=?",
            owner.intake().localTenantKey(),
            owner.version().localVersionKey())
        .get("owner_freeze_phase", String.class);
  }

  private static long publicationTerminalCount(DSLContext dsl, UUID publicationFence) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM world_design_publication_terminal WHERE publication_fence=?",
            publicationFence)
        .get(0, Long.class);
  }

  private static byte[] publicationTerminalBytes(DSLContext dsl, UUID publicationFence) {
    return dsl.fetchSingle(
            "SELECT terminal_evidence_bytes FROM world_design_publication_terminal "
                + "WHERE publication_fence=?",
            publicationFence)
        .get("terminal_evidence_bytes", byte[].class);
  }

  private static Server startWorldTerminalReadTransport(
      WorldPublicationTerminalReadGrpcService adapter, TerminalReadTestPki pki) throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        pki.worldServer().certificate().toFile(),
                        pki.worldServer().privateKey().toFile()))
                .trustManager(pki.caCertificate().toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(adapter, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static WorldPublicationTerminalReadClient worldTerminalReadClient(
      Server server, CommonGrpcClientProperties tlsProperties) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("127.0.0.1:" + server.getPort());
    var client =
        new WorldPublicationTerminalReadClient(
            endpoints, tlsProperties, new GrpcChannelFactory(), NAMESPACE);
    client.init();
    return client;
  }

  private static Map<String, List<String>> snapshotMutationRows(DSLContext dsl) {
    Map<String, List<String>> snapshot = new LinkedHashMap<>();
    for (String table : MUTATION_TABLES) {
      snapshot.put(
          table,
          dsl.fetch(
                  "SELECT concat(to_jsonb(t)::text, '|xmin=', xmin::text) FROM "
                      + table
                      + " t ORDER BY to_jsonb(t)::text, xmin::text")
              .getValues(0, String.class));
    }
    return Map.copyOf(snapshot);
  }

  private static String allThrowableText(Throwable failure) {
    List<String> messages = new ArrayList<>();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current.getMessage() != null) messages.add(current.getMessage());
    }
    return String.join("\n", messages);
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.exists(candidate.resolve("settings.gradle.kts"))) return candidate;
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Unable to locate repository filesystem migrations");
  }

  private record FreezeFixture(
      WorldSelectedDraftPublicationFreezeService freezeService,
      WorldSelectedPublicationArtifactInventoryReadService inventoryReadService,
      WorldPublishedStartLocationRepository selectorRepository) {}

  private enum PlanFault {
    NONE,
    OMITTED_FAMILY_COUNT,
    INCONSISTENT_FAMILY_COUNT,
    REPEATED_FAMILY_COUNT,
    REPEATED_DECLARATION,
    FABRICATED_MAPPING
  }

  private record TerminalReadTestPki(
      Path caCertificate, TerminalReadIdentity worldServer, TerminalReadIdentity accountClient) {
    private static TerminalReadTestPki create(Path directory) throws Exception {
      Files.createDirectories(directory);
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var caKeys = generator.generateKeyPair();
      var caName = new org.bouncycastle.asn1.x500.X500Name("CN=World terminal read test CA");
      var now = java.time.Instant.now();
      var caBuilder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              caName,
              java.math.BigInteger.ONE,
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(14L * 24L * 60L * 60L)),
              caName,
              caKeys.getPublic());
      caBuilder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(true));
      caBuilder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          true,
          new org.bouncycastle.asn1.x509.KeyUsage(
              org.bouncycastle.asn1.x509.KeyUsage.keyCertSign
                  | org.bouncycastle.asn1.x509.KeyUsage.cRLSign));
      var caCertificate =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(
                  caBuilder.build(
                      new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                          .build(caKeys.getPrivate())));
      Path caPath = pem(directory.resolve("test-ca.pem"), caCertificate);
      return new TerminalReadTestPki(
          caPath,
          issue(directory, "world-server", "world-management-service", true, caKeys, caCertificate),
          issue(directory, "account-client", "account-service", false, caKeys, caCertificate));
    }

    private static TerminalReadIdentity issue(
        Path directory,
        String alias,
        String service,
        boolean server,
        java.security.KeyPair caKeys,
        java.security.cert.X509Certificate caCertificate)
        throws Exception {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keys = generator.generateKeyPair();
      var now = java.time.Instant.now();
      var builder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              caCertificate,
              new java.math.BigInteger(120, new java.security.SecureRandom()),
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(14L * 24L * 60L * 60L)),
              new org.bouncycastle.asn1.x500.X500Name("CN=" + alias),
              keys.getPublic());
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(false));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          true,
          new org.bouncycastle.asn1.x509.KeyUsage(
              org.bouncycastle.asn1.x509.KeyUsage.digitalSignature
                  | org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
          false,
          new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
              server
                  ? org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth
                  : org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_clientAuth));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
          false,
          new org.bouncycastle.asn1.x509.GeneralNames(
              new org.bouncycastle.asn1.x509.GeneralName[] {
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier,
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service),
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.dNSName, "localhost"),
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.iPAddress, "127.0.0.1")
              }));
      var certificate =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(
                  builder.build(
                      new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                          .build(caKeys.getPrivate())));
      return new TerminalReadIdentity(
          pem(directory.resolve(alias + ".crt"), certificate),
          pem(directory.resolve(alias + ".key"), keys.getPrivate()));
    }

    private static Path pem(Path path, Object value) throws Exception {
      try (var writer =
          new org.bouncycastle.openssl.jcajce.JcaPEMWriter(Files.newBufferedWriter(path))) {
        writer.writeObject(value);
      }
      return path;
    }
  }

  private record TerminalReadIdentity(Path certificate, Path privateKey) {
    private CommonGrpcClientProperties clientProperties(Path caCertificate) {
      var properties = new CommonGrpcClientProperties();
      properties.setPlaintext(false);
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(privateKey.toString());
      properties.setCaCert(caCertificate.toString());
      return properties;
    }
  }

  private record OwnerFixture(
      AuthoredWorldSourceEvidence source,
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version) {}
}
