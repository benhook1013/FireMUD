package integration.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.Checkpoint;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplication;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphAppliedResult;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOperation;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcome;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcomeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcomeService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalReadGrpcService;
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
import org.jooq.DSLContext;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Controlled source/Version, Account binding, and PKI fixtures exercise the actual World APPLIED
 * and abort SQL owners plus same-namespace mTLS peer/read handlers. Account permission and held
 * COMMIT_ORDER evidence are stipulated by controlled fixtures, not supplied by a production Account
 * authority. This proves SQL owner outcomes and transport handling only, not genuine source
 * provenance, production Account authorization, or live runtime registration.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldDraftTerminalOutcomePostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final long GAME_DESIGN_VERSION = 9_000_000_000_000_001L;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

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
  @TempDir Path temporaryDirectory;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void abortBlocksDelayedGraphApplicationAndGuardedStorageAndExactRetrySurvivesFreeze()
      throws Exception {
    Fixture f = fixture();
    WorldDraftGraphApplication application = currentGraphApplication(f);
    CountDownLatch verificationStarted = new CountDownLatch(1);
    CountDownLatch releaseVerification = new CountDownLatch(1);
    var applications = new WorldDraftGraphApplicationRepository(dsl, fence, mapper);
    var delayedService =
        new WorldDraftGraphApplicationService(
            applications,
            manager,
            operation -> {
              verificationStarted.countDown();
              await(releaseVerification);
              return new WorldDraftGraphApplicationService.CommitOrderProof(operation);
            });
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> delayedApply =
          executor.submit(() -> succeeded(() -> delayedService.apply(application)));
      await(verificationStarted);
      WorldDraftTerminalOutcome original =
          terminalService().recordDefinitiveAbort(application.operation());

      releaseVerification.countDown();
      assertThat(delayedApply.get(30, TimeUnit.SECONDS)).isFalse();
      assertThat(count("world_draft_graph_application", application.operation().requestId()))
          .isZero();
      assertThat(count("world_topology_draft_commit", application.operation().requestId()))
          .isZero();
      assertGuardedUuidTopologyFunctionDenied(application);
      assertThat(count("world_topology_draft_commit", application.operation().requestId()))
          .isZero();
      assertThat(count("world_authored_topology_identity", application.operation().requestId()))
          .isZero();

      freeze(f);
      WorldDraftTerminalOutcome replay =
          terminalService().recordDefinitiveAbort(application.operation());
      assertThat(replay.canonicalBytes()).containsExactly(original.canonicalBytes());
      assertThat(replay.recordedAt()).isEqualTo(original.recordedAt());
    } finally {
      releaseVerification.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void retainedUnverifiedTopologyOrRegionHistoryCannotBeRelabeledAsAborted() throws Exception {
    Fixture topologyFixture = fixture();
    WorldDraftGraphApplication topology = currentGraphApplication(topologyFixture);
    insertRetainedUnverifiedHistory(topologyFixture, topology, "world_topology_draft_commit");
    assertThatThrownBy(() -> terminalService().recordDefinitiveAbort(topology.operation()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("history");
    assertThat(count("world_topology_draft_commit", topology.operation().requestId()))
        .isEqualTo(1L);
    assertThat(terminalService().readDefinitiveAbort(topology.operation())).isEmpty();

    Fixture regionFixture = fixture();
    WorldDraftGraphApplication region = currentGraphApplication(regionFixture);
    insertRetainedUnverifiedHistory(regionFixture, region, "world_region_draft_commit");
    assertThatThrownBy(() -> terminalService().recordDefinitiveAbort(region.operation()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("history");
    assertThat(count("world_region_draft_commit", region.operation().requestId())).isEqualTo(1L);
    assertThat(terminalService().readDefinitiveAbort(region.operation())).isEmpty();
  }

  @Test
  void committedGraphApplicationCannotBeRelabeledAsAborted() throws Exception {
    WorldDraftGraphApplication application = currentGraphApplication(fixture());
    var applicationService = controlledGraphApplicationService();
    WorldDraftGraphAppliedResult applied = applicationService.apply(application);

    assertThatThrownBy(() -> terminalService().recordDefinitiveAbort(application.operation()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("cannot be relabeled as no-commit");
    assertThat(terminalService().readDefinitiveAbort(application.operation())).isEmpty();
    assertThat(
            applicationService
                .readCommitted(NAMESPACE, application.operation().accountBindingBytes())
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(applied.canonicalBytes());
    assertThat(count("world_draft_graph_application", application.operation().requestId()))
        .isEqualTo(1L);
  }

  @Test
  void authenticatedMtlsReadReturnsExactCurrentAppliedOwnerAndExactRetry() throws Exception {
    Fixture f = fixture();
    WorldDraftGraphApplication application = currentGraphApplication(f);
    var applications = new WorldDraftGraphApplicationRepository(dsl, fence, mapper);
    var applicationService = controlledGraphApplicationService(applications);

    // The controlled fixture supplies a typed proof so the current World owner application runs;
    // it is not an Account producer or evidence of production original-order authority.
    WorldDraftGraphAppliedResult applied = applicationService.apply(application);
    WorldDraftGraphAppliedResult retained =
        applicationService
            .readCommitted(NAMESPACE, application.operation().accountBindingBytes())
            .orElseThrow();
    assertThat(retained.canonicalBytes()).containsExactly(applied.canonicalBytes());
    var terminalIdentity =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT outcome, account_binding_bytes FROM world_draft_graph_terminal_identity "
                    + "WHERE operation_id = ?",
                application.operation().operationId()),
            "World APPLIED terminal identity must be retained");
    assertThat(terminalIdentity.get("outcome", String.class)).isEqualTo("APPLIED");
    assertThat(terminalIdentity.get("account_binding_bytes", byte[].class))
        .containsExactly(application.operation().accountBindingBytes());

    WorldDraftTerminalReadGrpcService receiver =
        new WorldDraftTerminalReadGrpcService(terminalRepository(), applications, NAMESPACE);
    TerminalReadTestPki pki =
        TerminalReadTestPki.create(temporaryDirectory.resolve("world-applied-read-pki"));
    Server server = startTerminalReadTransport(receiver, pki);
    try (WorldDraftTerminalReadClient client = terminalReadClient(server, pki)) {
      client.init();
      var request =
          WorldDraftTerminalReadEvidence.Request.create(
              NAMESPACE, application.operation().accountBindingBytes());
      var response = client.read(request);
      var exactRetry = client.read(request);
      var readback = response.ownerReadback().orElseThrow();
      assertThat(response.request()).isEqualTo(request);
      assertThat(exactRetry.request()).isEqualTo(request);
      assertThat(readback.owner()).isEqualTo(DraftAuthorizationFenceBinding.Owner.WORLD);
      assertThat(readback.outcome()).isEqualTo(DraftAuthorizationFenceBinding.Outcome.COMMITTED);
      assertThat(readback.fullBinding())
          .containsExactly(application.operation().accountBindingBytes());
      assertThat(readback.canonicalBytes())
          .containsExactly(applied.ownerReadback().canonicalBytes());
      assertThat(exactRetry.ownerReadback().orElseThrow().canonicalBytes())
          .containsExactly(readback.canonicalBytes());
      assertThat(exactRetry.ownerReadback().orElseThrow().result())
          .containsExactly(applied.canonicalBytes());
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void graphApplicationAndAbortSerializeToExactlyOneImmutableTerminalResult() throws Exception {
    WorldDraftGraphApplication application = currentGraphApplication(fixture());
    WorldDraftTerminalOperation operation = application.operation();
    var applicationService = controlledGraphApplicationService();
    Race result =
        race(
            () -> applicationService.apply(application),
            () -> terminalService().recordDefinitiveAbort(operation));

    assertThat(result.commitWon()).isNotEqualTo(result.abortWon());
    assertThat(terminalService().readDefinitiveAbort(operation).isPresent())
        .isEqualTo(result.abortWon());
    assertThat(count("world_draft_graph_application", operation.requestId()) > 0L)
        .isEqualTo(result.commitWon());
    assertThat(count("world_topology_draft_commit", operation.requestId()) > 0L)
        .isEqualTo(result.commitWon());
    assertThat(count("world_authored_topology_identity", operation.requestId()) > 0L)
        .isEqualTo(result.commitWon());
    var appliedReadback =
        applicationService.readCommitted(NAMESPACE, operation.accountBindingBytes());
    assertThat(appliedReadback.isPresent()).isEqualTo(result.commitWon());
    if (result.abortWon()) {
      assertThatThrownBy(() -> applicationService.apply(application))
          .isInstanceOf(RuntimeException.class);
    } else {
      assertThat(appliedReadback.orElseThrow().status()).isEqualTo("APPLIED");
      assertThat(appliedReadback.orElseThrow().ownerReadback().fullBinding())
          .containsExactly(operation.accountBindingBytes());
      assertThatThrownBy(() -> terminalService().recordDefinitiveAbort(operation))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("cannot be relabeled as no-commit");
    }
  }

  @Test
  void absentReadbackIsUnknownChangedBindingsConflictAndRolledBackAbortLeavesNoRow()
      throws Exception {
    Fixture f = fixture();
    WorldDraftGraphApplication application = currentGraphApplication(f);
    DraftCommitBinding binding = application.plan().binding();
    WorldDraftTerminalOperation operation = application.operation();
    WorldDraftTerminalOutcomeService service = terminalService();
    assertThat(service.readDefinitiveAbort(operation)).isEmpty();

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          terminalRepository().recordDefinitiveAbort(operation);
                          throw new IllegalStateException("rollback fixture");
                        }))
        .hasMessageContaining("rollback fixture");
    assertThat(service.readDefinitiveAbort(operation)).isEmpty();

    WorldDraftTerminalOutcome original = service.recordDefinitiveAbort(operation);
    byte[] changedAccount =
        accountBinding(f, binding, operationIds(operation), new byte[] {9}).canonicalBytes();
    WorldDraftTerminalOperation changedSource =
        new WorldDraftTerminalOperation(
            operation.operationId(),
            operation.requestId(),
            operation.commitId(),
            operation.authorizationFenceId(),
            operation.canonicalTenantId(),
            operation.canonicalVersionId(),
            operation.binding(),
            operation.ownerBinding(),
            changedAccount);
    assertThatThrownBy(() -> service.readDefinitiveAbort(changedSource))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("changed binding");

    OwnerBinding substitutedOwner =
        new OwnerBinding(
            operation.ownerBinding().targetNamespace(),
            operation.ownerBinding().canonicalTenantId(),
            operation.ownerBinding().canonicalVersionId(),
            operation.ownerBinding().versionIdentityOperationId(),
            operation.ownerBinding().gameDesignVersionId(),
            operation.ownerBinding().intakeRequestId(),
            operation.ownerBinding().intakeOperationId(),
            operation.ownerBinding().intakeRequestDigest(),
            UUID.randomUUID(),
            operation.ownerBinding().sourceEvidenceDigest(),
            operation.ownerBinding().intakeReceiptDigest());
    WorldDraftTerminalOperation changedOwnerSource =
        new WorldDraftTerminalOperation(
            operation.operationId(),
            operation.requestId(),
            operation.commitId(),
            operation.authorizationFenceId(),
            operation.canonicalTenantId(),
            operation.canonicalVersionId(),
            operation.binding(),
            substitutedOwner,
            operation.accountBindingBytes());
    assertThatThrownBy(() -> service.readDefinitiveAbort(changedOwnerSource))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("changed binding");

    DraftCommitBinding changedBinding = changedPayload(binding, "substituted game design bytes");
    WorldDraftTerminalOperation changedInput =
        operation(f, changedBinding, operation.operationId(), operation.authorizationFenceId());
    assertThatThrownBy(() -> service.readDefinitiveAbort(changedInput))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("changed binding");
    assertThat(service.readDefinitiveAbort(operation).orElseThrow().canonicalBytes())
        .containsExactly(original.canonicalBytes());
  }

  @Test
  void authenticatedMtlsReadReturnsUnknownThenExactAbortAcrossFreezeAndRejectsSubstitution()
      throws Exception {
    Fixture f = fixture();
    WorldDraftGraphApplication application = currentGraphApplication(f);
    WorldDraftTerminalOperation operation = application.operation();
    WorldDraftTerminalOutcomeRepository repository = terminalRepository();
    WorldDraftTerminalReadGrpcService receiver =
        new WorldDraftTerminalReadGrpcService(
            repository, new WorldDraftGraphApplicationRepository(dsl, fence, mapper), NAMESPACE);
    TerminalReadTestPki pki =
        TerminalReadTestPki.create(temporaryDirectory.resolve("world-terminal-read-pki"));
    Server server = startTerminalReadTransport(receiver, pki);
    try (WorldDraftTerminalReadClient client = terminalReadClient(server, pki)) {
      client.init();
      var request =
          WorldDraftTerminalReadEvidence.Request.create(NAMESPACE, operation.accountBindingBytes());
      var absent = client.read(request);
      assertThat(absent.request()).isEqualTo(request);
      assertThat(absent.ownerReadback()).isEmpty();

      WorldDraftTerminalOutcome stored = terminalService().recordDefinitiveAbort(operation);
      var abortIdentity =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT outcome, account_binding_bytes FROM world_draft_graph_terminal_identity "
                      + "WHERE operation_id = ?",
                  operation.operationId()),
              "World abort terminal identity must be retained");
      assertThat(abortIdentity.get("outcome", String.class)).isEqualTo("DEFINITIVELY_ABORTED");
      assertThat(abortIdentity.get("account_binding_bytes", byte[].class))
          .containsExactly(operation.accountBindingBytes());
      var abortBytes =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT outcome_bytes FROM world_draft_terminal_outcome WHERE operation_id = ?",
                  operation.operationId()),
              "World abort owner result must be retained");
      assertThat(abortBytes.get("outcome_bytes", byte[].class))
          .containsExactly(stored.canonicalBytes());
      var abortEvidence = client.read(request);
      var abort = abortEvidence.ownerReadback().orElseThrow();
      assertThat(abortEvidence.request()).isEqualTo(request);
      assertThat(abort.owner()).isEqualTo(DraftAuthorizationFenceBinding.Owner.WORLD);
      assertThat(abort.outcome())
          .isEqualTo(DraftAuthorizationFenceBinding.Outcome.DEFINITIVELY_ABORTED);
      assertThat(abort.fullBinding()).containsExactly(operation.accountBindingBytes());
      assertThat(abort.result()).containsExactly(stored.canonicalBytes());
      assertThat(client.read(request).ownerReadback().orElseThrow().canonicalBytes())
          .containsExactly(abort.canonicalBytes());

      byte[] changedAccount =
          accountBinding(f, application.plan().binding(), operationIds(operation), new byte[] {9})
              .canonicalBytes();
      var changedBindingRequest =
          WorldDraftTerminalReadEvidence.Request.create(NAMESPACE, changedAccount);
      assertThatThrownBy(() -> client.read(changedBindingRequest))
          .isInstanceOf(RuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));

      freeze(f);
      var postFreeze = client.read(request);
      assertThat(postFreeze.ownerReadback().orElseThrow().canonicalBytes())
          .containsExactly(abort.canonicalBytes());
      assertThat(postFreeze.ownerReadback().orElseThrow().result())
          .containsExactly(stored.canonicalBytes());
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Race race(ThrowingRunnable commit, ThrowingRunnable abort) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> commitFuture =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return succeeded(commit);
              });
      Future<Boolean> abortFuture =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return succeeded(abort);
              });
      await(ready);
      start.countDown();
      return new Race(
          commitFuture.get(30, TimeUnit.SECONDS), abortFuture.get(30, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private boolean succeeded(ThrowingRunnable action) {
    try {
      action.run();
      return true;
    } catch (RuntimeException expectedRaceLoser) {
      return false;
    }
  }

  private void assertGuardedUuidTopologyFunctionDenied(WorldDraftGraphApplication application)
      throws Exception {
    List<Map<String, Object>> executionRevisions = new ArrayList<>();
    for (RevisionPayload revision : application.operation().binding().revisions()) {
      if (revision.owner() != Owner.WORLD_MANAGEMENT) {
        continue;
      }
      Map<String, Object> encoded = new LinkedHashMap<>();
      encoded.put("revisionOrder", revision.revisionOrder());
      encoded.put("revisionId", revision.revisionId().toString());
      encoded.put("owner", revision.owner().name());
      encoded.put("payload", revision.payload());
      executionRevisions.add(encoded);
    }
    String ownerJson = mapper.writeValueAsString(application.plan().ownerBinding());
    String executionJson = mapper.writeValueAsString(executionRevisions);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status ->
                            dsl.resultQuery(
                                    "SELECT world_store_guarded_uuid_topology("
                                        + "?::jsonb, ?::jsonb, ?::jsonb)",
                                    ownerJson,
                                    application.operation().binding().canonicalJson(),
                                    executionJson)
                                .fetch()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("definitive no-commit");
  }

  /**
   * Inserts a controlled retained STORED_PERMISSION_UNVERIFIED row using the current immutable
   * schema, enabled triggers/constraints, and owner identity foreign key. Synthetic graph/result
   * bytes make this a history fixture, not a claim that either legacy writer produced it or that
   * Account authorized it.
   */
  private void insertRetainedUnverifiedHistory(
      Fixture fixture, WorldDraftGraphApplication application, String table) {
    if (!List.of("world_topology_draft_commit", "world_region_draft_commit").contains(table)) {
      throw new IllegalArgumentException("Unsupported controlled history fixture table");
    }
    byte[] retainedGraphBytes =
        "controlled retained World graph history fixture"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] retainedResultBytes =
        "controlled retained World result history fixture"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    ownerTransaction()
        .execute(
            status ->
                dsl.execute(
                    "INSERT INTO "
                        + table
                        + " (request_id,commit_id,version_identity_operation_id,target_namespace,"
                        + "canonical_tenant_id,canonical_version_id,local_tenant_key,local_version_key,"
                        + "owner_binding_json,binding_json,binding_digest,graph_bytes,graph_sha256,"
                        + "result_bytes,storage_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    application.operation().requestId(),
                    application.operation().commitId(),
                    application.operation().ownerBinding().versionIdentityOperationId(),
                    application.operation().ownerBinding().targetNamespace(),
                    application.operation().canonicalTenantId(),
                    application.operation().canonicalVersionId(),
                    fixture.intake().localTenantKey(),
                    fixture.version().localVersionKey(),
                    mapper.writeValueAsString(application.operation().ownerBinding()),
                    application.operation().binding().canonicalJson(),
                    application.operation().binding().digest(),
                    retainedGraphBytes,
                    digest(retainedGraphBytes).substring("sha256:".length()),
                    retainedResultBytes,
                    "STORED_PERMISSION_UNVERIFIED"));
  }

  private Fixture fixture() {
    UUID tenant = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID sourceOperation = UUID.randomUUID();
    String tenantSlug = "tenant-" + tenant.toString().replace("-", "");
    String worldSlug = "world-" + UUID.randomUUID().toString().replace("-", "");
    long sourceRow = Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
    String sourceTenant = "gd-row-" + sourceRow;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, tenantSlug, worldSlug, "Synthetic terminal world");
    String sourceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            tenantSlug,
            worldSlug,
            "Synthetic terminal world",
            sourceRow,
            sourceTenant,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            tenantSlug,
            worldSlug,
            "Synthetic terminal world",
            sourceRow,
            sourceTenant,
            "NEW_GAME_ROW",
            sourceDigest);
    UUID intakeRequest = UUID.randomUUID();
    ownerTransaction()
        .execute(status -> intakeRepository.acceptFresh(NAMESPACE, intakeRequest, source));
    WorldAuthoredSourceIntakeReceipt intake =
        intakeRepository.read(NAMESPACE, intakeRequest).orElseThrow();
    Fixture created =
        ownerTransaction()
            .execute(
                status -> {
                  UUID versionId = UUID.randomUUID();
                  AuthoredWorldVersionStateEvidence.Request stateRequest =
                      new AuthoredWorldVersionStateEvidence.Request(
                          1,
                          NAMESPACE,
                          UUID.randomUUID(),
                          tenant,
                          worldSlug,
                          sourceOperation,
                          sourceDigest,
                          GAME_DESIGN_VERSION);
                  WorldAuthoredVersionIdentityReceipt version =
                      new WorldAuthoredVersionIdentityRepository(dsl)
                          .acceptFresh(
                              intake,
                              AuthoredWorldVersionStateEvidence.create(
                                  stateRequest,
                                  source,
                                  versionId,
                                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                                  1));
                  OwnerBinding owner =
                      new OwnerBinding(
                          NAMESPACE,
                          tenant,
                          versionId,
                          version.operationId(),
                          GAME_DESIGN_VERSION,
                          intakeRequest,
                          intake.operationId(),
                          intake.requestDigest(),
                          sourceOperation,
                          sourceDigest,
                          intake.receiptDigest());
                  return new Fixture(intake, version, owner);
                });
    return created;
  }

  private WorldDraftGraphApplication currentGraphApplication(Fixture f) throws Exception {
    UUID commitId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    UUID zoneId = UUID.randomUUID();
    UUID roomId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID fenceId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    var region =
        currentMutation(
                commitId,
                regionId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setRegion(
                RegionDesignMutation.newBuilder()
                    .setName("Controlled terminal proof region")
                    .setWeather("clear")
                    .setShardId(0)
                    .setGenerationSeed(17)
                    .setGeneratorType("test")
                    .setGeneratorParams("{}"))
            .build();
    var zone =
        currentMutation(
                commitId,
                zoneId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
            .setZone(
                ZoneDesignMutation.newBuilder()
                    .setName("Controlled terminal proof zone")
                    .setRegionId(regionId.toString()))
            .build();
    var room =
        currentMutation(
                commitId,
                roomId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder()
                    .setName("Controlled terminal proof room")
                    .setZoneId(zoneId.toString()))
            .build();
    WorldFreshGraphDeclaration declaration = currentFreshGraphDeclaration(f, roomId);
    region = region.toBuilder().setFreshGraphDeclaration(declaration).build();
    List<WorldDesignMutationRevision> mutations = List.of(region, zone, room);

    UUID canonicalVersionId = f.version().canonicalVersionId();
    List<RevisionPayload> revisions = new ArrayList<>();
    revisions.add(
        new RevisionPayload(
            "0",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "controlled Game Design Version fixture; no producer provenance"));
    List<AffectedUnit> affected = new ArrayList<>();
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
          new RevisionPayload(
              Integer.toString(index + 1),
              UUID.fromString(mutation.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              json(mutation)));
      String family =
          mutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      String aggregateId = mutation.getAggregateId();
      affected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT, family, aggregateId, "AGGREGATE", aggregateId, "0"));
      affected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              aggregateId,
              "REGION_SUBTREE",
              regionId.toString(),
              "0"));
    }

    AuthoredWorldSourceEvidence source = f.intake().source();
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            new TargetProof(
                source.canonicalTenantId(),
                canonicalVersionId,
                f.version().gameDesignVersionId(),
                source.sourceGameTenantKey(),
                source.sourceGameRowId(),
                source.sourceGameTenantKey(),
                source.provenanceKind()),
            UUID.randomUUID(),
            commitId,
            "controlled-terminal-proof-base",
            revisions,
            affected);
    WorldDraftTopologyCommitPlan plan = WorldDraftTopologyCommitPlan.create(binding, f.owner());
    byte[] draftBytes = binding.canonicalBytes();
    byte[] accountBinding =
        new DraftAuthorizationFenceBinding(
                operationId,
                binding.requestId(),
                binding.commitId(),
                fenceId,
                actorId,
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.baseCommitId(),
                "0",
                draftBytes,
                draftBytes,
                binding.digest(),
                List.of(
                    new SourceEvidence(
                        SourceKind.GLOBAL_ROLES,
                        actorId.toString(),
                        null,
                        "1",
                        null,
                        null,
                        "controlled Account fixture; not production authorization"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .withRequiredOwners()
            .canonicalBytes();
    WorldDraftTerminalOperation operation =
        new WorldDraftTerminalOperation(
            operationId,
            binding.requestId(),
            binding.commitId(),
            fenceId,
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding,
            f.owner(),
            accountBinding);
    return new WorldDraftGraphApplication(operation, plan);
  }

  private WorldDesignMutationRevision.Builder currentMutation(
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

  private WorldFreshGraphDeclaration currentFreshGraphDeclaration(Fixture f, UUID roomId) {
    UUID tenantId = f.owner().canonicalTenantId();
    UUID versionId = f.owner().canonicalVersionId();
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
      inbound.addFamilyCounts(inboundCount(family));
    }
    return WorldFreshGraphDeclaration.newBuilder()
        .setTenantId(tenantId.toString())
        .setVersionId(versionId.toString())
        .setStartLocation(
            net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                .setTenantId(tenantId.toString())
                .setVersionId(versionId.toString())
                .setRoomTemplateId(roomId.toString()))
        .addFamilyCounts(
            familyCount(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION, 1))
        .addFamilyCounts(familyCount(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE, 1))
        .addFamilyCounts(familyCount(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM, 1))
        .addFamilyCounts(
            familyCount(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT, 0))
        .addFamilyCounts(
            familyCount(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE, 0))
        .addFamilyCounts(
            familyCount(
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING, 0))
        .setInboundSourceClosure(inbound.build())
        .build();
  }

  private WorldFreshGraphFamilyCount familyCount(WorldDesignAggregateType family, int count) {
    return WorldFreshGraphFamilyCount.newBuilder().setFamily(family).setCount(count).build();
  }

  private WorldInboundSourceFamilyCount inboundCount(WorldInboundSourceFamily family) {
    return WorldInboundSourceFamilyCount.newBuilder().setFamily(family).setCount(0).build();
  }

  private DraftCommitBinding changedPayload(DraftCommitBinding binding, String payload) {
    List<RevisionPayload> revisions = new ArrayList<>();
    for (RevisionPayload revision : binding.revisions()) {
      revisions.add(
          new RevisionPayload(
              revision.revisionOrder(),
              revision.revisionId(),
              revision.owner(),
              revision.owner() == Owner.GAME_DESIGN_CONTROL_PLANE ? payload : revision.payload()));
    }
    return DraftCommitBinding.create(
        binding.target(),
        binding.requestId(),
        binding.commitId(),
        binding.baseCommitId(),
        revisions,
        binding.affectedUnits());
  }

  private WorldDraftTerminalOperation operation(Fixture f, DraftCommitBinding binding) {
    return operation(f, binding, UUID.randomUUID(), UUID.randomUUID());
  }

  private WorldDraftTerminalOperation operation(
      Fixture f, DraftCommitBinding binding, UUID operationId, UUID fenceId) {
    UUID actor = UUID.randomUUID();
    DraftAuthorizationFenceBinding accountBinding =
        accountBinding(
            f,
            binding,
            new OperationIds(operationId, binding.requestId(), binding.commitId(), fenceId, actor),
            new byte[] {1, 2, 3});
    return new WorldDraftTerminalOperation(
        operationId,
        binding.requestId(),
        binding.commitId(),
        fenceId,
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding,
        f.owner(),
        accountBinding.canonicalBytes());
  }

  private DraftAuthorizationFenceBinding accountBinding(
      Fixture f, DraftCommitBinding binding, OperationIds ids, byte[] evidence) {
    return new DraftAuthorizationFenceBinding(
        ids.operation(),
        ids.request(),
        ids.commit(),
        ids.fence(),
        ids.actor(),
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.baseCommitId(),
        "0",
        binding.canonicalBytes(),
        binding.canonicalBytes(),
        binding.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.TENANT,
                binding.target().canonicalTenantId().toString(),
                null,
                "1",
                null,
                null,
                evidence)));
  }

  private OperationIds operationIds(WorldDraftTerminalOperation operation) {
    return new OperationIds(
        operation.operationId(),
        operation.requestId(),
        operation.commitId(),
        operation.authorizationFenceId(),
        UUID.randomUUID());
  }

  private WorldDraftGraphApplicationService controlledGraphApplicationService() {
    return controlledGraphApplicationService(
        new WorldDraftGraphApplicationRepository(dsl, fence, mapper));
  }

  private WorldDraftGraphApplicationService controlledGraphApplicationService(
      WorldDraftGraphApplicationRepository applications) {
    // This typed proof is stipulated by the test; it does not implement production Account
    // authorization or held original COMMIT_ORDER authority.
    return new WorldDraftGraphApplicationService(
        applications,
        manager,
        operation -> new WorldDraftGraphApplicationService.CommitOrderProof(operation));
  }

  private WorldDraftTerminalOutcomeService terminalService() {
    return new WorldDraftTerminalOutcomeService(terminalRepository(), manager);
  }

  private WorldDraftTerminalOutcomeRepository terminalRepository() {
    return new WorldDraftTerminalOutcomeRepository(dsl, fence, mapper);
  }

  private void freeze(Fixture f) {
    OwnerBinding owner = f.owner();
    String request = "terminal-freeze-" + UUID.randomUUID();
    WorldDesignPublicationFenceEvidence evidence =
        new WorldDesignPublicationFenceEvidence(
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
            request,
            "a".repeat(64),
            1,
            "publish:" + owner.canonicalTenantId() + ":publish-request:" + request);
    ownerTransaction()
        .execute(
            status ->
                fence.claimFreeze(
                    evidence, () -> new Checkpoint("synthetic-checkpoint", "b".repeat(64), 3)));
  }

  private long count(String table, UUID requestId) {
    var result =
        Objects.requireNonNull(
            dsl.fetchOne("SELECT count(*) FROM " + table + " WHERE request_id = ?", requestId),
            "count query must return a row");
    return Objects.requireNonNull(result.get(0, Long.class), "count query must return a value");
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return transaction;
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(mutation);
    } catch (InvalidProtocolBufferException exception) {
      throw new AssertionError(exception);
    }
  }

  private String digest(byte[] value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Server startTerminalReadTransport(
      WorldDraftTerminalReadGrpcService adapter, TerminalReadTestPki pki) throws Exception {
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

  private WorldDraftTerminalReadClient terminalReadClient(Server server, TerminalReadTestPki pki)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("127.0.0.1:" + server.getPort());
    return new WorldDraftTerminalReadClient(
        endpoints,
        pki.accountClient().clientProperties(pki.caCertificate()),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Fixture timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run();
  }

  private record Race(boolean commitWon, boolean abortWon) {}

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

  private record OperationIds(UUID operation, UUID request, UUID commit, UUID fence, UUID actor) {}

  private record Fixture(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version,
      OwnerBinding owner) {}
}
