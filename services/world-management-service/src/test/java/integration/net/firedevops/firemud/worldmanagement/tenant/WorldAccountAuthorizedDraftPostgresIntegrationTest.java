package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiOriginalOrderFixture;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadClient;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationClient;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.dto.WorldDesignMutationRequestDto;
import net.firedevops.firemud.worldmanagement.service.WorldDesignMutationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Real Account COMMIT_ORDER -> real World APPLIED -> authenticated exact immutable selector read.
 * Platform/custody, legal source, and upstream Game Design source/version/selection inputs are
 * test-only stipulations. No release, settlement, admission, deadline-bearing write or activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldAccountAuthorizedDraftPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final long GAME_DESIGN_VERSION = 9_000_000_000_000_001L;
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> accountPostgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("positive-control-ui-primary")
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
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(redis)
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
              "positive-control-ui-primary",
              "6379");

  @TempDir Path temporary;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager manager;
  @Autowired private WorldAuthoredSourceIntakeRepository intakeRepository;
  @Autowired private WorldDesignPublicationFenceRepository fence;
  @Autowired private ObjectMapper mapper;
  @Autowired private WorldAuthoredGraphSnapshotRepository snapshots;
  @Autowired private WorldDesignMutationService mutationService;

  @Autowired
  private net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService
      digestService;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @Test
  void accountOriginalOrderIsAppliedAndReadAsTheExactAuthenticatedWorldSelector() throws Exception {
    try (var account =
        new AccountControlUiOriginalOrderFixture(
            accountPostgres.getJdbcUrl(),
            accountPostgres.getUsername(),
            accountPostgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary.resolve("account"))) {
      var world = fixture(intakeRepository, manager, dsl, account.tenantId());
      var plan = plan(world);
      // The real World plan is supplied before Account captures sources and claims original order.
      var original = account.claimOriginal(plan.binding());
      var operation =
          new WorldDraftTerminalOperation(
              original.operationId(),
              original.requestId(),
              original.commitId(),
              original.fenceId(),
              original.tenantId(),
              original.versionId(),
              plan.binding(),
              plan.ownerBinding(),
              original.canonicalBytes());
      var application = new WorldDraftGraphApplication(operation, plan);
      var missingApplicationEvidence =
          publicationFreezeEvidence(plan, "unapplied-" + UUID.randomUUID());
      var stateBeforeMissingApplicationDenial = ownerSnapshot(world, plan);
      assertThat(stateBeforeMissingApplicationDenial.publicationOwnerRows()).isEmpty();
      assertThat(stateBeforeMissingApplicationDenial.publicationAttemptRows()).isEmpty();
      assertThatThrownBy(
              () ->
                  ownerTransaction()
                      .execute(
                          status ->
                              fence.claimFreeze(
                                  missingApplicationEvidence,
                                  () ->
                                      checkpointRepository()
                                          .capture(missingApplicationEvidence, plan))))
          .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
          .hasMessageContaining("actual APPLIED graph application");
      var stateAfterMissingApplicationDenial = ownerSnapshot(world, plan);
      assertThat(stateAfterMissingApplicationDenial).isEqualTo(stateBeforeMissingApplicationDenial);
      assertThat(stateAfterMissingApplicationDenial.publicationOwnerRows()).isEmpty();
      assertThat(stateAfterMissingApplicationDenial.publicationAttemptRows()).isEmpty();
      var pki = new TestPki(temporary.resolve("pki"));
      var accountServer =
          serve(
              new AccountDraftCommitOrderReadGrpcService(
                  account.heldOrderOwner(NAMESPACE), NAMESPACE),
              pki.accountServer,
              pki);
      try {
        var endpoints = new ServiceEndpointsProperties();
        endpoints.setAccountService("localhost:" + accountServer.getPort());
        try (var read =
            new DraftCommitOrderReadClient(
                endpoints,
                pki.worldClient.properties(pki.ca),
                new GrpcChannelFactory(),
                NAMESPACE)) {
          read.init();
          var heldRequest =
              DraftCommitOrderReadEvidence.Request.create(NAMESPACE, original.canonicalBytes());
          assertThat(read.read(heldRequest).request()).isEqualTo(heldRequest);
          var writer =
              new WorldDraftGraphApplicationService(
                  appliedRepository(), manager, new WorldDraftCommitOrderVerifier(read, NAMESPACE));
          var applied = writer.apply(application);
          assertThat(applied.status()).isEqualTo("APPLIED");
          assertThat(applied.application().operation().accountBindingBytes())
              .isEqualTo(original.canonicalBytes());
          assertThat(applied.application().plan().binding().canonicalBytes())
              .isEqualTo(plan.binding().canonicalBytes());
          assertThat(writer.readCommitted(application).orElseThrow().canonicalBytes())
              .isEqualTo(applied.canonicalBytes());
          assertThat(writer.apply(application).canonicalBytes())
              .isEqualTo(applied.canonicalBytes());
          assertLegacyNumericWritesCannotChangeCanonicalFreshGraph(world, plan);
          // Both the immutable selection and distinct Account publication order are fixture-only
          // stipulations. They are not proof that Account created or currently holds that order.
          String publicationRequest = "selector-" + UUID.randomUUID();
          var publicationSelection = publicationSelection(plan, publicationRequest, 1L);
          var stipulatedPublicationOrder = stipulatedPublicationOrder(publicationSelection);
          var freezeEvidence = publicationFreezeEvidence(plan, publicationSelection);
          var publicationAuthorizationRepository = publicationAuthorizationRepository();
          var stateBeforeFirstQualifiedCapture = ownerSnapshot(world, plan);
          assertThat(stateBeforeFirstQualifiedCapture.publicationOwnerRows()).hasSize(1);
          String openPublicationOwnerRow =
              stateBeforeFirstQualifiedCapture.publicationOwnerRows().get(0);
          assertThat(mapper.readTree(openPublicationOwnerRow).path("owner_freeze_phase").asText())
              .isEqualTo("OPEN");
          assertThat(stateBeforeFirstQualifiedCapture.publicationAttemptRows()).isEmpty();
          assertThat(stateBeforeFirstQualifiedCapture.publicationAuthorizationRows()).isEmpty();
          var forcedRollback =
              new IllegalStateException("forced first-freeze qualification rollback");
          assertThatThrownBy(
                  () ->
                      ownerTransaction()
                          .execute(
                              status -> {
                                var candidate =
                                    fence.claimFreeze(
                                        freezeEvidence,
                                        () -> checkpointRepository().capture(freezeEvidence, plan));
                                assertThat(
                                        publicationAuthorizationRepository
                                            .retainOrRequireExact(
                                                candidate, stipulatedPublicationOrder, true)
                                            .canonicalBytes())
                                    .containsExactly(stipulatedPublicationOrder.canonicalBytes());
                                throw forcedRollback;
                              }))
              .isSameAs(forcedRollback);
          var stateAfterFirstQualifiedCaptureRollback = ownerSnapshot(world, plan);
          assertThat(stateAfterFirstQualifiedCaptureRollback)
              .isEqualTo(stateBeforeFirstQualifiedCapture);
          assertThat(stateAfterFirstQualifiedCaptureRollback.publicationOwnerRows())
              .containsExactly(openPublicationOwnerRow);
          assertThat(stateAfterFirstQualifiedCaptureRollback.publicationAttemptRows()).isEmpty();
          assertThat(stateAfterFirstQualifiedCaptureRollback.publicationAuthorizationRows())
              .isEmpty();
          var frozen = capture(plan, freezeEvidence, stipulatedPublicationOrder);
          assertThat(frozen.request().freeze().appliedCommitId())
              .isEqualTo(applied.application().operation().commitId().toString());
          var retryEvidence = freezeEvidence;
          var stateBeforeExactRetry = ownerSnapshot(world, plan);
          var checkpointCallbackInvocations = new AtomicInteger();
          var exactRetry =
              Objects.requireNonNull(
                  ownerTransaction()
                      .execute(
                          status -> {
                            WorldDesignPublicationFenceEvidence.FrozenAttempt retry =
                                fence.claimFreeze(
                                    retryEvidence,
                                    () -> {
                                      checkpointCallbackInvocations.incrementAndGet();
                                      return new WorldDesignPublicationFenceEvidence.Checkpoint(
                                          "recapture-must-not-run", "b".repeat(64), 3);
                                    });
                            assertThat(
                                    publicationAuthorizationRepository
                                        .retainOrRequireExact(
                                            retry, stipulatedPublicationOrder, false)
                                        .canonicalBytes())
                                .containsExactly(stipulatedPublicationOrder.canonicalBytes());
                            return retry;
                          }));
          assertThat(exactRetry.request()).isEqualTo(retryEvidence);
          assertThat(exactRetry.publicationFence())
              .isEqualTo(frozen.request().freeze().publicationFence());
          assertThat(exactRetry.checkpoint())
              .isEqualTo(
                  new WorldDesignPublicationFenceEvidence.Checkpoint(
                      frozen.request().freeze().appliedCommitId(),
                      frozen.request().freeze().contentDigest(),
                      frozen.request().freeze().digestSchemaVersion()));
          assertThat(checkpointCallbackInvocations.get()).isZero();
          assertThat(ownerSnapshot(world, plan)).isEqualTo(stateBeforeExactRetry);
          assertThat(
                  publicationAuthorizationRepository
                      .readCommitted(exactRetry)
                      .orElseThrow()
                      .canonicalBytes())
              .containsExactly(stipulatedPublicationOrder.canonicalBytes());
          var changedOperation =
              new AccountPublicationAuthorizationBinding(
                  UUID.randomUUID(),
                  stipulatedPublicationOrder.fenceId(),
                  stipulatedPublicationOrder.input(),
                  stipulatedPublicationOrder.sources());
          assertThatThrownBy(
                  () ->
                      ownerTransaction()
                          .execute(
                              status ->
                                  publicationAuthorizationRepository.retainOrRequireExact(
                                      exactRetry, changedOperation, false)))
              .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
              .hasMessageContaining("changed");
          var changedFence =
              new AccountPublicationAuthorizationBinding(
                  stipulatedPublicationOrder.operationId(),
                  UUID.randomUUID(),
                  stipulatedPublicationOrder.input(),
                  stipulatedPublicationOrder.sources());
          assertThatThrownBy(
                  () ->
                      ownerTransaction()
                          .execute(
                              status ->
                                  publicationAuthorizationRepository.retainOrRequireExact(
                                      exactRetry, changedFence, false)))
              .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
              .hasMessageContaining("changed");
          assertThat(ownerSnapshot(world, plan)).isEqualTo(stateBeforeExactRetry);
          var selectors =
              new WorldPublishedStartLocationRepository(
                  dsl, frozenRepository(), appliedRepository());
          var retained = selectors.readCommitted(frozen.request().freeze()).orElseThrow();
          var request = selectorRequest(frozen.request().freeze());
          var worldServer =
              serve(
                  new WorldPublishedStartLocationReadGrpcService(selectors, NAMESPACE),
                  pki.worldServer,
                  pki);
          try {
            endpoints.setWorldManagementService("localhost:" + worldServer.getPort());
            try (var client =
                new WorldPublishedStartLocationClient(
                    endpoints,
                    pki.gameDesignClient.properties(pki.ca),
                    new GrpcChannelFactory(),
                    NAMESPACE)) {
              client.init();
              var evidence = client.read(request);
              assertThat(evidence.request()).isEqualTo(request);
              assertThat(evidence.originalAccountBindingBytes())
                  .isEqualTo(original.canonicalBytes());
              assertThat(evidence.appliedResultBytes()).isEqualTo(applied.canonicalBytes());
              assertThat(evidence.selectorReceiptBytes())
                  .isEqualTo(retained.selectorReceipt().canonicalBytes());
              assertThat(client.read(request).canonicalBytes())
                  .isEqualTo(evidence.canonicalBytes());
            }
            // Trusted CA alone is not authority: the World workload cannot read the GD-only
            // selector.
            try (var wrong =
                new WorldPublishedStartLocationClient(
                    endpoints,
                    pki.worldClient.properties(pki.ca),
                    new GrpcChannelFactory(),
                    NAMESPACE)) {
              wrong.init();
              assertThatThrownBy(() -> wrong.read(request))
                  .isInstanceOf(io.grpc.StatusRuntimeException.class)
                  .satisfies(
                      error ->
                          assertThat(io.grpc.Status.fromThrowable(error).getCode())
                              .isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
            }
          } finally {
            stop(worldServer);
          }
          // APPLIED does not settle either owner or replace the original Account ordering.
          assertThat(read.read(heldRequest).request()).isEqualTo(heldRequest);
          assertThat(dsl.fetchCount(DSL.table("world_draft_graph_application"))).isEqualTo(1);
        }
      } finally {
        stop(accountServer);
      }
    }
  }

  @Test
  void committedUnqualifiedAttemptCannotBeLateQualified() throws Exception {
    try (var account =
        new AccountControlUiOriginalOrderFixture(
            accountPostgres.getJdbcUrl(),
            accountPostgres.getUsername(),
            accountPostgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary.resolve("unqualified-account"))) {
      var world = fixture(intakeRepository, manager, dsl, account.tenantId());
      var plan = plan(world);
      var original = account.claimOriginal(plan.binding());
      var operation =
          new WorldDraftTerminalOperation(
              original.operationId(),
              original.requestId(),
              original.commitId(),
              original.fenceId(),
              original.tenantId(),
              original.versionId(),
              plan.binding(),
              plan.ownerBinding(),
              original.canonicalBytes());
      var application = new WorldDraftGraphApplication(operation, plan);
      var pki = new TestPki(temporary.resolve("unqualified-pki"));
      var accountServer =
          serve(
              new AccountDraftCommitOrderReadGrpcService(
                  account.heldOrderOwner(NAMESPACE), NAMESPACE),
              pki.accountServer,
              pki);
      try {
        var endpoints = new ServiceEndpointsProperties();
        endpoints.setAccountService("localhost:" + accountServer.getPort());
        try (var read =
            new DraftCommitOrderReadClient(
                endpoints,
                pki.worldClient.properties(pki.ca),
                new GrpcChannelFactory(),
                NAMESPACE)) {
          read.init();
          var writer =
              new WorldDraftGraphApplicationService(
                  appliedRepository(), manager, new WorldDraftCommitOrderVerifier(read, NAMESPACE));
          assertThat(writer.apply(application).status()).isEqualTo("APPLIED");

          // A real World APPLIED graph and checkpoint are retained first. The distinct
          // publication selection/order below is explicitly stipulated; this isolates storage
          // qualification and does not claim an authenticated Account HELD read.
          String publicationRequest = "unqualified-" + UUID.randomUUID();
          var selection = publicationSelection(plan, publicationRequest, 1L);
          var stipulatedOrder = stipulatedPublicationOrder(selection);
          var evidence = publicationFreezeEvidence(plan, selection);
          var unqualified =
              Objects.requireNonNull(
                  ownerTransaction()
                      .execute(
                          status ->
                              fence.claimFreeze(
                                  evidence, () -> checkpointRepository().capture(evidence, plan))));
          var authorizationRepository = publicationAuthorizationRepository();
          assertThat(authorizationRepository.readCommitted(unqualified)).isEmpty();
          var before = ownerSnapshot(world, plan);

          assertThatThrownBy(
                  () ->
                      ownerTransaction()
                          .execute(
                              status ->
                                  authorizationRepository.retainOrRequireExact(
                                      unqualified, stipulatedOrder, true)))
              .satisfies(failure -> assertSqlState(failure, "P0002"));
          assertThat(authorizationRepository.readCommitted(unqualified)).isEmpty();
          assertThat(ownerSnapshot(world, plan)).isEqualTo(before);
          assertThat(before.publicationAuthorizationRows()).isEmpty();
        }
      } finally {
        stop(accountServer);
      }
    }
  }

  private static void assertSqlState(Throwable failure, String expectedState) {
    String sqlState = null;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sqlException) {
        sqlState = sqlException.getSQLState();
      }
    }
    assertThat(sqlState).isEqualTo(expectedState);
  }

  private WorldDraftGraphApplicationRepository appliedRepository() {
    return new WorldDraftGraphApplicationRepository(dsl, fence, mapper);
  }

  private void assertLegacyNumericWritesCannotChangeCanonicalFreshGraph(
      Fixture fixture, WorldDraftTopologyCommitPlan plan) {
    // Use only retained owner receipts/mappings for the private selectors; do not derive them from
    // canonical UUIDs or assume Game Design's numeric selectors are World database keys.
    long tenantKey = fixture.intake().localTenantKey();
    long versionKey = fixture.version().localVersionKey();
    assertThat(fixture.version().sourceIntakeReceipt().localTenantKey()).isEqualTo(tenantKey);

    when(gameDesignClient.getVersionState(tenantKey, versionKey))
        .thenReturn(
            GetVersionStateResponse.newBuilder()
                .setVersionState(
                    VersionStateSnapshot.newBuilder()
                        .setTenantId(Long.toString(tenantKey))
                        .setVersionId(versionKey)
                        .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT)
                        .setVersionStateEpoch(1L)
                        .build())
                .build());

    var owner = plan.ownerBinding();
    List<Long> mappedRegionIds =
        dsl.resultQuery(
                "SELECT private_row_key FROM world_authored_topology_identity "
                    + "WHERE target_namespace=? AND canonical_tenant_id=? "
                    + "AND canonical_version_id=? AND family='REGION'",
                owner.targetNamespace(),
                owner.canonicalTenantId(),
                owner.canonicalVersionId())
            .fetch(0, Long.class);
    assertThat(mappedRegionIds).hasSize(1);
    long mappedRegionId = mappedRegionIds.getFirst();
    Long aggregateEpoch =
        dsl.resultQuery(
                "SELECT draft_revision_epoch FROM world_design_aggregate_epoch "
                    + "WHERE tenant_id=? AND version_id=? AND aggregate_type='REGION' AND aggregate_id=?",
                tenantKey,
                versionKey,
                mappedRegionId)
            .fetchOne(0, Long.class);
    assertThat(aggregateEpoch).isNotNull();

    var before = ownerSnapshot(fixture, plan);
    assertThat(before.currentRows()).hasSize(3);
    assertThat(before.mappingRows()).hasSize(3);
    assertThat(before.startLocationRows()).hasSize(1);
    assertThat(before.publicationOwnerRows()).hasSize(1);
    assertThat(before.publicationAttemptRows()).isEmpty();

    var updateRequest =
        legacyRegionMutation(tenantKey, versionKey, Long.toString(mappedRegionId), aggregateEpoch);
    assertThatThrownBy(() -> mutationService.applyMutation(updateRequest))
        .satisfies(
            failure ->
                assertDatabaseGuardFailure(
                    failure,
                    "Mapped UUID World content cannot be changed by numeric writers",
                    "World tenant key is reserved for a canonical authored source"));
    assertThat(ownerSnapshot(fixture, plan)).isEqualTo(before);

    var createRequest = legacyRegionMutation(tenantKey, versionKey, "", 0L);
    assertThatThrownBy(() -> mutationService.applyMutation(createRequest))
        .satisfies(
            failure ->
                assertDatabaseGuardFailure(
                    failure, "World tenant key is reserved for a canonical authored source"));
    assertThat(ownerSnapshot(fixture, plan)).isEqualTo(before);
  }

  private static WorldDesignMutationRequestDto legacyRegionMutation(
      long tenantKey, long versionKey, String aggregateId, long expectedEpoch) {
    return new WorldDesignMutationRequestDto(
        tenantKey,
        versionKey,
        "legacy-commit-" + UUID.randomUUID(),
        "legacy-revision-" + UUID.randomUUID(),
        "UPSERT",
        "REGION",
        aggregateId,
        expectedEpoch,
        "",
        "",
        0L,
        "",
        new WorldDesignMutationRequestDto.RegionMutationDto(
            "legacy-write", "clear", 8, 17L, "ROOM_GRAPH", "{}", 1.0d),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static void assertDatabaseGuardFailure(Throwable failure, String... messageFragments) {
    String sqlState = null;
    StringBuilder messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sqlException) {
        sqlState = sqlException.getSQLState();
      }
      if (cause.getMessage() != null) {
        messages.append(cause.getMessage()).append('\n');
      }
    }
    assertThat(sqlState).isEqualTo("23514");
    String combinedMessages = messages.toString();
    assertThat(java.util.Arrays.stream(messageFragments).anyMatch(combinedMessages::contains))
        .isTrue();
  }

  private WorldCanonicalFrozenTopologyRepository frozenRepository() {
    return new WorldCanonicalFrozenTopologyRepository(
        dsl, snapshots, new WorldDraftTopologyCommitRepository(dsl, fence, mapper), digestService);
  }

  private TransactionTemplate ownerTransaction() {
    return ownerTransaction(manager);
  }

  private TransactionTemplate ownerTransaction(PlatformTransactionManager owner) {
    var tx = new TransactionTemplate(owner);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return tx;
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(mutation);
    } catch (InvalidProtocolBufferException failure) {
      throw new AssertionError(failure);
    }
  }

  private static WorldPublishedStartLocationEvidence.Request selectorRequest(
      WorldAuthoredGraphSnapshot.CaptureRequest freeze) {
    return new WorldPublishedStartLocationEvidence.Request(
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
        freeze.suppliedOwnedAffectedTuples().stream()
            .map(
                t ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        t.owner(),
                        t.aggregateType(),
                        t.aggregateId(),
                        t.scopeType(),
                        t.scopeId(),
                        t.expectedEpoch()))
            .toList());
  }

  private static Server serve(io.grpc.BindableService service, TestIdentity identity, TestPki pki)
      throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(identity.certificate.toFile(), identity.key.toFile())
                .trustManager(pki.ca.toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    if (!server.awaitTermination(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Test server did not stop");
  }

  private Fixture fixture(
      WorldAuthoredSourceIntakeRepository sourceIntake,
      PlatformTransactionManager transactionManager,
      DSLContext context,
      UUID tenant) {
    UUID registration = UUID.randomUUID();
    UUID sourceOperation = UUID.randomUUID();
    String tenantSlug = "tenant-" + tenant.toString().replace("-", "");
    String worldSlug = "world-" + UUID.randomUUID().toString().replace("-", "");
    long sourceRow = Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
    String sourceTenant = "gd-row-" + sourceRow;
    String digest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, tenantSlug, worldSlug, "Synthetic component world");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            sourceOperation,
            digest,
            tenant,
            tenantSlug,
            worldSlug,
            "Synthetic component world",
            sourceRow,
            sourceTenant,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            sourceOperation,
            digest,
            tenant,
            tenantSlug,
            worldSlug,
            "Synthetic component world",
            sourceRow,
            sourceTenant,
            "NEW_GAME_ROW",
            evidenceDigest);
    UUID intakeRequest = UUID.randomUUID();
    ownerTransaction(transactionManager)
        .execute(status -> sourceIntake.acceptFresh(NAMESPACE, intakeRequest, source));
    WorldAuthoredSourceIntakeReceipt intake =
        sourceIntake.read(NAMESPACE, intakeRequest).orElseThrow();
    return Objects.requireNonNull(
        ownerTransaction(transactionManager)
            .execute(
                status -> {
                  UUID canonicalVersion = UUID.randomUUID();
                  AuthoredWorldVersionStateEvidence.Request stateRequest =
                      new AuthoredWorldVersionStateEvidence.Request(
                          1,
                          NAMESPACE,
                          UUID.randomUUID(),
                          tenant,
                          worldSlug,
                          sourceOperation,
                          evidenceDigest,
                          GAME_DESIGN_VERSION);
                  WorldAuthoredVersionIdentityReceipt version =
                      new WorldAuthoredVersionIdentityRepository(context)
                          .acceptFresh(
                              intake,
                              AuthoredWorldVersionStateEvidence.create(
                                  stateRequest,
                                  source,
                                  canonicalVersion,
                                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                                  1));
                  OwnerBinding owner =
                      new OwnerBinding(
                          NAMESPACE,
                          tenant,
                          canonicalVersion,
                          version.operationId(),
                          GAME_DESIGN_VERSION,
                          intakeRequest,
                          intake.operationId(),
                          intake.requestDigest(),
                          sourceOperation,
                          evidenceDigest,
                          intake.receiptDigest());
                  return new Fixture(intake, version, owner);
                }));
  }

  private WorldDraftTopologyCommitPlan plan(Fixture f) {
    UUID logical = UUID.randomUUID();
    UUID commit = UUID.randomUUID();
    List<WorldDesignMutationRevision> values = new ArrayList<>();
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setRegion(
                RegionDesignMutation.newBuilder()
                    .setName("region")
                    .setWeather("rain")
                    .setShardId(7))
            .build());
    values.add(
        mutation(
                commit, logical, logical, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
            .setZone(
                ZoneDesignMutation.newBuilder().setName("zone").setRegionId(logical.toString()))
            .build());
    values.add(
        mutation(
                commit, logical, logical, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder()
                    .setName("room")
                    .setZoneId(logical.toString())
                    .setDescription("description")
                    .setNameLocalizedVariantsJson("{\"en\":\"room\"}")
                    .setDescriptionLocalizedVariantsJson("{\"en\":\"description\"}"))
            .build());
    var declaration =
        WorldFreshGraphDeclaration.newBuilder()
            .setTenantId(f.owner().canonicalTenantId().toString())
            .setVersionId(f.owner().canonicalVersionId().toString())
            .setStartLocation(
                net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                    .setTenantId(f.owner().canonicalTenantId().toString())
                    .setVersionId(f.owner().canonicalVersionId().toString())
                    .setRoomTemplateId(logical.toString()))
            .addFamilyCounts(
                count(values, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION))
            .addFamilyCounts(
                count(values, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE))
            .addFamilyCounts(
                count(values, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM))
            .addFamilyCounts(
                count(values, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT))
            .addFamilyCounts(
                count(values, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE))
            .addFamilyCounts(
                count(
                    values,
                    WorldDesignAggregateType
                        .WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING))
            .build();
    values.set(0, values.getFirst().toBuilder().setFreshGraphDeclaration(declaration).build());
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> units = new ArrayList<>();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "synthetic Game Design control-plane input"));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "VERSION",
            f.owner().canonicalVersionId().toString(),
            "AGGREGATE",
            f.owner().canonicalVersionId().toString(),
            "0"));
    // Original input order deliberately places children before their parents.
    for (var m : values.reversed()) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(m.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              json(m)));
      String family = m.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              m.getAggregateId(),
              "AGGREGATE",
              m.getAggregateId(),
              "0"));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              m.getAggregateId(),
              "REGION_SUBTREE",
              m.getScopeId(),
              "0"));
    }
    var s = f.intake().source();
    var target =
        new DraftCommitBinding.TargetProof(
            f.owner().canonicalTenantId(),
            f.owner().canonicalVersionId(),
            GAME_DESIGN_VERSION,
            s.sourceGameTenantKey(),
            s.sourceGameRowId(),
            s.sourceGameTenantKey(),
            s.provenanceKind());
    return WorldDraftTopologyCommitPlan.create(
        DraftCommitBinding.create(
            target, UUID.randomUUID(), commit, "retained-base", revisions, units),
        f.owner());
  }

  private WorldFreshGraphFamilyCount count(
      List<WorldDesignMutationRevision> mutations, WorldDesignAggregateType family) {
    int count =
        (int) mutations.stream().filter(mutation -> mutation.getAggregateType() == family).count();
    return WorldFreshGraphFamilyCount.newBuilder().setFamily(family).setCount(count).build();
  }

  private WorldDesignMutationRevision.Builder mutation(
      UUID commit, UUID id, UUID scope, WorldDesignAggregateType type) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateId(id.toString())
        .setAggregateType(type)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(scope.toString());
  }

  private WorldCanonicalFrozenTopology capture(
      WorldDraftTopologyCommitPlan plan,
      WorldDesignPublicationFenceEvidence evidence,
      AccountPublicationAuthorizationBinding stipulatedAccountOrder) {
    var owner = plan.ownerBinding();
    String publicationRequest = evidence.publicationRequestId();
    var authorizationRepository = publicationAuthorizationRepository();
    var checkpointCaptured = new AtomicInteger();
    var attempt =
        Objects.requireNonNull(
            ownerTransaction()
                .execute(
                    status -> {
                      var frozen =
                          fence.claimFreeze(
                              evidence,
                              () -> {
                                var checkpoint = checkpointRepository().capture(evidence, plan);
                                checkpointCaptured.incrementAndGet();
                                return checkpoint;
                              });
                      var stored =
                          authorizationRepository.retainOrRequireExact(
                              frozen, stipulatedAccountOrder, checkpointCaptured.get() == 1);
                      assertThat(stored.canonicalBytes())
                          .containsExactly(stipulatedAccountOrder.canonicalBytes());
                      return frozen;
                    }));
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
    var request =
        new WorldAuthoredGraphSnapshot.CaptureRequest(
            owner.targetNamespace(),
            owner.canonicalTenantId(),
            owner.canonicalVersionId(),
            owner.intakeRequestId(),
            attempt.publicationFence(),
            publicationRequest,
            evidence.requestDigest(),
            evidence.versionStateEpoch(),
            evidence.publishWorkflowId(),
            attempt.checkpoint().appliedCommitId(),
            attempt.checkpoint().contentDigest(),
            attempt.checkpoint().digestSchemaVersion(),
            tuples);
    return new WorldCanonicalFrozenTopologyService(frozenRepository(), manager)
        .capture(new WorldCanonicalFrozenTopology.Request(plan, request));
  }

  private WorldSelectedDraftPublicationCheckpointRepository checkpointRepository() {
    return new WorldSelectedDraftPublicationCheckpointRepository(
        fence,
        appliedRepository(),
        new WorldDraftTopologyCommitRepository(dsl, fence, mapper),
        digestService);
  }

  private WorldSelectedDraftPublicationAuthorizationRepository
      publicationAuthorizationRepository() {
    return new WorldSelectedDraftPublicationAuthorizationRepository(dsl);
  }

  private DraftOwnerSnapshot ownerSnapshot(Fixture fixture, WorldDraftTopologyCommitPlan plan) {
    long tenant = fixture.intake().localTenantKey();
    long version = fixture.version().localVersionKey();
    List<String> currentRows = new ArrayList<>();
    for (String table :
        List.of(
            "region",
            "zone",
            "room",
            "room_exit",
            "generation_rule",
            "world_entity_spawn_binding")) {
      rowJson(
              "SELECT to_jsonb(t)::text AS row_json FROM "
                  + table
                  + " t WHERE tenant_id=? AND version_id=? ORDER BY id",
              tenant,
              version)
          .forEach(row -> currentRows.add(table + ":" + row));
    }
    var aggregateEpochRows =
        rowJson(
            "SELECT to_jsonb(t)::text AS row_json FROM world_design_aggregate_epoch t "
                + "WHERE tenant_id=? AND version_id=? ORDER BY aggregate_type,aggregate_id",
            tenant,
            version);
    var mappingRows =
        rowJson(
            "SELECT to_jsonb(t)::text AS row_json FROM world_authored_topology_identity t "
                + "WHERE target_namespace=? AND canonical_tenant_id=? AND canonical_version_id=? "
                + "ORDER BY family,private_row_key",
            fixture.owner().targetNamespace(),
            fixture.owner().canonicalTenantId(),
            fixture.owner().canonicalVersionId());
    var scopeEpochRows =
        rowJson(
            "SELECT to_jsonb(t)::text AS row_json FROM world_design_scope_epoch t "
                + "WHERE tenant_id=? AND version_id=? ORDER BY scope_type,scope_id",
            tenant,
            version);
    var revisionRows =
        rowJson(
            "SELECT to_jsonb(t)::text AS row_json FROM world_design_revision_ledger t "
                + "WHERE tenant_id=? AND version_id=? ORDER BY id",
            tenant,
            version);
    var topologyRows =
        rowJson(
            "SELECT to_jsonb(t)::text AS row_json FROM world_topology_draft_commit t "
                + "WHERE request_id=? OR commit_id=? ORDER BY request_id",
            plan.binding().requestId(),
            plan.binding().commitId());
    var startLocationRows =
        rowJson(
            "SELECT to_jsonb(t)::text AS row_json FROM world_draft_start_location_receipt t "
                + "WHERE request_id=? OR commit_id=? ORDER BY request_id",
            plan.binding().requestId(),
            plan.binding().commitId());
    var applicationRows =
        rowJson(
            "SELECT to_jsonb(t)::text AS row_json FROM world_draft_graph_application t "
                + "WHERE request_id=? OR commit_id=? ORDER BY request_id",
            plan.binding().requestId(),
            plan.binding().commitId());
    var terminalIdentityRows =
        rowJson(
            "SELECT to_jsonb(t)::text AS row_json FROM world_draft_graph_terminal_identity t "
                + "WHERE request_id=? OR commit_id=? ORDER BY request_id",
            plan.binding().requestId(),
            plan.binding().commitId());
    var publicationOwnerRows =
        rowJson(
            "SELECT to_jsonb(t)::text AS row_json FROM world_design_publication_fence_owner t "
                + "WHERE target_namespace=? AND canonical_tenant_id=? AND version_id=? "
                + "ORDER BY target_namespace,canonical_tenant_id,version_id",
            fixture.owner().targetNamespace(),
            fixture.owner().canonicalTenantId(),
            version);
    var publicationAttemptRows =
        rowJson(
            "SELECT to_jsonb(t)::text AS row_json FROM world_design_publication_fence_attempt t "
                + "WHERE target_namespace=? AND canonical_tenant_id=? AND version_id=? "
                + "ORDER BY publication_fence",
            fixture.owner().targetNamespace(),
            fixture.owner().canonicalTenantId(),
            version);
    var publicationAuthorizationRows =
        rowJson(
            "SELECT to_jsonb(b)::text AS row_json FROM world_design_publication_account_binding b "
                + "JOIN world_design_publication_fence_attempt a USING (publication_fence) "
                + "WHERE a.target_namespace=? AND a.canonical_tenant_id=? AND a.version_id=? "
                + "ORDER BY b.publication_fence",
            fixture.owner().targetNamespace(),
            fixture.owner().canonicalTenantId(),
            version);
    return new DraftOwnerSnapshot(
        List.copyOf(currentRows),
        mappingRows,
        aggregateEpochRows,
        scopeEpochRows,
        revisionRows,
        topologyRows,
        startLocationRows,
        applicationRows,
        terminalIdentityRows,
        publicationOwnerRows,
        publicationAttemptRows,
        publicationAuthorizationRows);
  }

  private List<String> rowJson(String sql, Object... bindings) {
    return dsl.resultQuery(sql, bindings).fetch(0, String.class);
  }

  private record DraftOwnerSnapshot(
      List<String> currentRows,
      List<String> mappingRows,
      List<String> aggregateEpochRows,
      List<String> scopeEpochRows,
      List<String> revisionRows,
      List<String> topologyRows,
      List<String> startLocationRows,
      List<String> applicationRows,
      List<String> terminalIdentityRows,
      List<String> publicationOwnerRows,
      List<String> publicationAttemptRows,
      List<String> publicationAuthorizationRows) {}

  private AuthoredDraftPublishSelectionBinding publicationSelection(
      WorldDraftTopologyCommitPlan plan, String publicationRequest, long versionStateEpoch) {
    var binding = plan.binding();
    var target = binding.target();
    return AuthoredDraftPublishSelectionBinding.capture(
        new PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            publicationRequest,
            Long.toString(versionStateEpoch),
            "test-only stipulated immutable publication selection",
            binding.requestId(),
            binding.commitId(),
            binding.digest()),
        target,
        binding,
        new VisibilityFence(
            target,
            binding.requestId(),
            binding.commitId(),
            binding.digest(),
            "[]",
            java.time.OffsetDateTime.parse("2026-10-01T00:00:00Z")));
  }

  private AccountPublicationAuthorizationBinding stipulatedPublicationOrder(
      AuthoredDraftPublishSelectionBinding selection) {
    return stipulatedPublicationOrder(selection, UUID.randomUUID(), UUID.randomUUID());
  }

  private AccountPublicationAuthorizationBinding stipulatedPublicationOrder(
      AuthoredDraftPublishSelectionBinding selection, UUID operationId, UUID fenceId) {
    UUID actor = UUID.randomUUID();
    return new AccountPublicationAuthorizationBinding(
        operationId,
        fenceId,
        new AccountPublicationAuthorizationBinding.PreallocationInput(actor, selection),
        List.of(
            new DraftAuthorizationFenceBinding.SourceEvidence(
                DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                actor.toString(),
                "1",
                "1",
                null,
                null,
                new byte[] {1})));
  }

  private WorldDesignPublicationFenceEvidence publicationFreezeEvidence(
      WorldDraftTopologyCommitPlan plan, AuthoredDraftPublishSelectionBinding selection) {
    var owner = plan.ownerBinding();
    return new WorldDesignPublicationFenceEvidence(
        owner.targetNamespace(),
        owner.canonicalTenantId(),
        owner.canonicalVersionId(),
        owner.versionIdentityOperationId(),
        owner.gameDesignVersionId(),
        owner.intakeRequestId(),
        owner.intakeOperationId(),
        owner.intakeRequestDigest(),
        owner.sourceOperationId(),
        owner.sourceEvidenceDigest(),
        owner.intakeReceiptDigest(),
        selection.intent().publishRequestId(),
        selection.digest().substring("sha256:".length()),
        Long.parseLong(selection.intent().expectedVersionStateEpoch()),
        "publish:"
            + owner.canonicalTenantId()
            + ":publish-request:"
            + selection.intent().publishRequestId());
  }

  private WorldDesignPublicationFenceEvidence publicationFreezeEvidence(
      WorldDraftTopologyCommitPlan plan, String publicationRequest) {
    var owner = plan.ownerBinding();
    return new WorldDesignPublicationFenceEvidence(
        owner.targetNamespace(),
        owner.canonicalTenantId(),
        owner.canonicalVersionId(),
        owner.versionIdentityOperationId(),
        owner.gameDesignVersionId(),
        owner.intakeRequestId(),
        owner.intakeOperationId(),
        owner.intakeRequestDigest(),
        owner.sourceOperationId(),
        owner.sourceEvidenceDigest(),
        owner.intakeReceiptDigest(),
        publicationRequest,
        "a".repeat(64),
        1,
        "publish:" + owner.canonicalTenantId() + ":publish-request:" + publicationRequest);
  }

  private record Fixture(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version,
      OwnerBinding owner) {}

  /** Ephemeral certificate authority, never a deployed trust root or live workload credential. */
  private static final class TestPki {
    final Path ca;
    final TestIdentity accountServer, worldServer, worldClient, gameDesignClient;

    TestPki(Path root) throws Exception {
      Files.createDirectories(root);
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var caKeys = generator.generateKeyPair();
      var name = new org.bouncycastle.asn1.x500.X500Name("CN=Test-only Account World proof CA");
      var now = java.time.Instant.now();
      var builder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              name,
              java.math.BigInteger.ONE,
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(3600)),
              name,
              caKeys.getPublic());
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(true));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          true,
          new org.bouncycastle.asn1.x509.KeyUsage(org.bouncycastle.asn1.x509.KeyUsage.keyCertSign));
      var signer =
          new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
              .build(caKeys.getPrivate());
      var caCert =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(builder.build(signer));
      ca = pem(root.resolve("ca.pem"), caCert);
      accountServer = issue(root, "account-service", true, caKeys, caCert);
      worldServer = issue(root, "world-management-service", true, caKeys, caCert);
      worldClient = issue(root, "world-management-service", false, caKeys, caCert);
      gameDesignClient = issue(root, "game-design-service", false, caKeys, caCert);
    }

    private static TestIdentity issue(
        Path root,
        String service,
        boolean server,
        java.security.KeyPair caKeys,
        java.security.cert.X509Certificate caCert)
        throws Exception {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keys = generator.generateKeyPair();
      var now = java.time.Instant.now();
      var builder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              caCert,
              new java.math.BigInteger(120, new java.security.SecureRandom()),
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(3600)),
              new org.bouncycastle.asn1.x500.X500Name("CN=" + service),
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
      String prefix = service + (server ? "-server" : "-client");
      return new TestIdentity(
          pem(root.resolve(prefix + ".crt"), certificate),
          pem(root.resolve(prefix + ".key"), keys.getPrivate()));
    }

    private static Path pem(Path path, Object value) throws Exception {
      try (var writer =
          new org.bouncycastle.openssl.jcajce.JcaPEMWriter(Files.newBufferedWriter(path))) {
        writer.writeObject(value);
      }
      return path;
    }
  }

  private record TestIdentity(Path certificate, Path key) {
    CommonGrpcClientProperties properties(Path ca) {
      var result = new CommonGrpcClientProperties();
      result.setPlaintext(false);
      result.setCertChain(certificate.toString());
      result.setPrivateKey(key.toString());
      result.setCaCert(ca.toString());
      return result;
    }
  }
}
