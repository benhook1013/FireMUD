package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
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
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
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
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Stipulated held Account ordering proves component atomicity, not authenticated Gameplay handoff.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldDraftGraphApplicationPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final long GAME_DESIGN_VERSION = 9_000_000_000_000_001L;

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void freshApplicationRetainsCompleteExactGraphAndOriginalAccountReadbackAndRetriesAfterFreeze() {
    Fixture f = fixture();
    var application = application(f);
    var result = appliedComponent().apply(application);
    assertThat(result.status()).isEqualTo("APPLIED");
    assertThat(result.appliedEpochs())
        .hasSize(application.operation().binding().affectedUnits(Owner.WORLD_MANAGEMENT).size());
    assertThat(result.appliedEpochs())
        .allMatch(epoch -> epoch.expectedEpoch().equals("0") && epoch.resultingEpoch().equals("1"));
    assertThat(count(f, "world_authored_topology_identity")).isEqualTo(7);
    assertThat(count(f, "world_design_aggregate_epoch")).isEqualTo(7);
    assertThat(count(f, "world_design_scope_epoch")).isEqualTo(1);
    for (String family :
        List.of("region", "zone", "room_exit", "generation_rule", "world_entity_spawn_binding")) {
      assertThat(count(f, family)).isEqualTo(1);
    }
    assertThat(count(f, "room")).isEqualTo(2);
    var readback =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback
            .fromStored(result.ownerReadback().canonicalBytes());
    assertThat(readback.owner())
        .isEqualTo(
            net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner.WORLD);
    assertThat(readback.outcome())
        .isEqualTo(
            net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome
                .COMMITTED);
    readback.requireBinding(
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            application.operation().accountBindingBytes()));
    assertThat(readback.fullBinding())
        .containsExactly(application.operation().accountBindingBytes());
    assertThat(readback.result()).containsExactly(result.canonicalBytes());
    assertThat(
            appliedRepository()
                .readCommitted(NAMESPACE, application.operation().accountBindingBytes())
                .orElseThrow()
                .ownerReadback()
                .canonicalBytes())
        .containsExactly(readback.canonicalBytes());
    var changed =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            changedAccountApplication(application).operation().accountBindingBytes());
    assertThatThrownBy(() -> readback.requireBinding(changed))
        .hasMessageContaining("complete Draft binding");
    assertThatThrownBy(() -> appliedRepository().readCommitted(NAMESPACE, changed.canonicalBytes()))
        .hasMessageContaining("full Account binding");
    assertThatThrownBy(
            () ->
                appliedRepository()
                    .readCommitted(
                        "another-workload", application.operation().accountBindingBytes()))
        .hasMessageContaining("namespace");
    freeze(f);
    // Exact replay reads immutable evidence and does not need a newly acquired authorization.
    var retry =
        new WorldDraftGraphApplicationService(appliedRepository(), manager).apply(application);
    assertThat(retry.canonicalBytes()).containsExactly(result.canonicalBytes());
    assertThat(retry.graphBytes()).containsExactly(result.graphBytes());
    assertThat(
            appliedRepository()
                .readSynchronized(visibility(application, result, result.canonicalBytes())))
        .isPresent();
  }

  @Test
  void authenticatedReceiverReadsGenuineCommittedReceiptAndReplaysAfterFreeze() {
    Fixture f = fixture();
    var application = application(f);
    var receiver =
        new WorldDraftTerminalReadGrpcService(
            new WorldDraftTerminalOutcomeRepository(dsl, fence, mapper),
            appliedRepository(),
            NAMESPACE);
    var request =
        net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence.Request.create(
            NAMESPACE, application.operation().accountBindingBytes());
    assertThat(receiverRead(receiver, request).ownerReadback()).isEmpty();
    var result = appliedComponent().apply(application);
    var received = receiverRead(receiver, request).ownerReadback().orElseThrow();
    assertThat(received.outcome())
        .isEqualTo(
            net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome
                .COMMITTED);
    assertThat(received.canonicalBytes()).containsExactly(result.ownerReadback().canonicalBytes());
    assertThat(received.result()).containsExactly(result.canonicalBytes());
    freeze(f);
    var retryRequest =
        net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence.Request.create(
            NAMESPACE, application.operation().accountBindingBytes());
    assertThat(receiverRead(receiver, retryRequest).ownerReadback().orElseThrow().canonicalBytes())
        .containsExactly(received.canonicalBytes());
  }

  private net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence receiverRead(
      WorldDraftTerminalReadGrpcService receiver,
      net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence.Request request) {
    var response =
        new java.util.concurrent.atomic.AtomicReference<
            net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse>();
    var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var completed = new java.util.concurrent.atomic.AtomicBoolean();
    var context =
        io.grpc.Context.current()
            .withValue(
                net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY,
                new net.firedevops.firemud.common.grpc.GrpcPeerIdentity(
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service",
                    NAMESPACE,
                    "account-service"));
    var previous = context.attach();
    try {
      receiver.readWorldDraftTerminalOutcome(
          net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.toRequest(
              request),
          new io.grpc.stub.StreamObserver<>() {
            public void onNext(
                net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse
                    value) {
              response.set(value);
            }

            public void onError(Throwable value) {
              error.set(value);
            }

            public void onCompleted() {
              completed.set(true);
            }
          });
    } finally {
      context.detach(previous);
    }
    assertThat(error.get()).isNull();
    assertThat(completed.get()).isTrue();
    return net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.fromResponse(
        request, response.get());
  }

  @Test
  void oldPermissionUnverifiedRowsCannotBePromotedByWriterOrLateReceiptInsertion() {
    Fixture f = fixture();
    var application = application(f);
    var old = component().store(application.plan());
    assertThatThrownBy(() -> appliedComponent().apply(application))
        .hasMessageContaining("cannot be promoted");
    assertThat(appliedRepository().readCommitted(application)).isEmpty();
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          insertReceipt(application, new byte[] {1});
                          return null;
                        }))
        .hasMessageContaining("same transaction");
    assertThat(appliedRepository().readCommitted(application)).isEmpty();
    assertThat(repository().readCommitted(application.plan()).orElseThrow().graphBytes())
        .containsExactly(old.graphBytes());
    // Even plausible canonical fixture bytes cannot select old unverified history.
    var fabricated = WorldDraftGraphAppliedResult.create(application, old.graphBytes());
    assertThatThrownBy(
            () ->
                repository()
                    .readSynchronized(
                        visibility(application, fabricated, fabricated.canonicalBytes())))
        .hasMessageContaining("no canonical World APPLIED-result carrier");
  }

  @Test
  void failureAfterGraphAndReceiptInsertionRollsBackEveryFamilyEpochAndHistory() {
    Fixture f = fixture();
    var application = application(f);
    var failing =
        new WorldDraftGraphApplicationRepository(dsl, fence, mapper) {
          @Override
          WorldDraftGraphAppliedResult apply(
              WorldDraftGraphApplication value,
              WorldDraftGraphApplicationService.CommitOrderProof proof) {
            super.apply(value, proof);
            throw new IllegalStateException("after exact receipt insertion");
          }
        };
    assertThatThrownBy(() -> appliedComponent(failing).apply(application))
        .hasMessageContaining("after exact receipt");
    for (String table :
        List.of(
            "region",
            "zone",
            "room",
            "room_exit",
            "generation_rule",
            "world_entity_spawn_binding",
            "world_design_aggregate_epoch",
            "world_design_scope_epoch",
            "world_authored_topology_identity",
            "world_topology_draft_commit")) {
      assertThat(count(f, table)).isZero();
    }
    assertThat(appliedRepository().readCommitted(application)).isEmpty();
    assertThat(aborts().readDefinitiveAbort(application.operation())).isEmpty();
    assertThat(appliedComponent().apply(application).status()).isEqualTo("APPLIED");
  }

  @Test
  void definitiveAbortExcludesDelayedTypedAndRawSqlGraphWrites() {
    Fixture f = fixture();
    var application = application(f);
    aborts().recordDefinitiveAbort(application.operation());
    assertThatThrownBy(() -> appliedComponent().apply(application))
        .hasMessageContaining("definitive no-commit");
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          dsl.fetch(
                              "SELECT world_store_guarded_uuid_topology(?::jsonb,?::jsonb,?::jsonb)",
                              mapper.writeValueAsString(application.plan().ownerBinding()),
                              application.plan().binding().canonicalJson(),
                              mapper.writeValueAsString(
                                  repository().executionRevisions(application.plan())));
                          return null;
                        }))
        .hasMessageContaining("no-commit evidence");
    assertThat(count(f, "world_authored_topology_identity")).isZero();
    assertThat(count(f, "world_topology_draft_commit")).isZero();
    assertThat(appliedRepository().readCommitted(application)).isEmpty();
  }

  @Test
  void committedApplicationCannotBeRelabeledAsAbort() {
    Fixture f = fixture();
    var application = application(f);
    var result = appliedComponent().apply(application);
    assertThatThrownBy(() -> aborts().recordDefinitiveAbort(application.operation()))
        .hasMessageContaining("cannot be relabeled");
    assertThat(aborts().readDefinitiveAbort(application.operation())).isEmpty();
    assertThat(appliedRepository().readCommitted(application).orElseThrow().canonicalBytes())
        .containsExactly(result.canonicalBytes());
  }

  @Test
  void originalAccountActorSourcesAndFenceArePartOfExactReplayIdentity() {
    Fixture f = fixture();
    var application = application(f);
    appliedComponent().apply(application);
    assertThatThrownBy(() -> appliedComponent().apply(changedAccountApplication(application)))
        .hasMessageContaining("changed complete binding");
    var a =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            application.operation().accountBindingBytes());
    var changedFence =
        new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding(
            a.operationId(),
            a.requestId(),
            a.commitId(),
            UUID.randomUUID(),
            a.actorAccountId(),
            a.tenantId(),
            a.versionId(),
            a.baseCommitId(),
            a.expectedDraftEpoch(),
            a.gameDesignBinding(),
            a.normalizedInput(),
            a.inputDigest(),
            a.sources());
    assertThatThrownBy(
            () -> appliedComponent().apply(withAccount(application.plan(), changedFence)))
        .hasMessageContaining("changed complete binding");
  }

  @Test
  void exactDuplicateRaceCommitsOneGraphAndOneImmutableResult() throws Exception {
    Fixture f = fixture();
    var application = application(f);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch begin = new CountDownLatch(1);
    try {
      Future<WorldDraftGraphAppliedResult> first =
          pool.submit(
              () -> {
                await(begin);
                return appliedComponent().apply(application);
              });
      Future<WorldDraftGraphAppliedResult> second =
          pool.submit(
              () -> {
                await(begin);
                return appliedComponent().apply(application);
              });
      begin.countDown();
      assertThat(first.get(20, TimeUnit.SECONDS).canonicalBytes())
          .containsExactly(second.get(20, TimeUnit.SECONDS).canonicalBytes());
      assertThat(count(f, "world_authored_topology_identity")).isEqualTo(7);
      assertThat(
              Objects.requireNonNull(
                  Objects.requireNonNull(
                          dsl.fetchOne(
                              "SELECT count(*) FROM world_draft_graph_application WHERE operation_id=?",
                              application.operation().operationId()))
                      .get(0, Long.class)))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void commitAbortRaceHasExactlyOneDefinitiveOutcomeAndNoLateWrite() throws Exception {
    Fixture f = fixture();
    var application = application(f);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch begin = new CountDownLatch(1);
    try {
      Future<Boolean> commit =
          pool.submit(
              () -> {
                await(begin);
                try {
                  appliedComponent().apply(application);
                  return true;
                } catch (WorldDesignPublicationFenceRepository.ConflictException conflict) {
                  return false;
                }
              });
      Future<Boolean> abort =
          pool.submit(
              () -> {
                await(begin);
                try {
                  aborts().recordDefinitiveAbort(application.operation());
                  return true;
                } catch (WorldDesignPublicationFenceRepository.ConflictException conflict) {
                  return false;
                }
              });
      begin.countDown();
      boolean committed = commit.get(20, TimeUnit.SECONDS);
      boolean aborted = abort.get(20, TimeUnit.SECONDS);
      assertThat(committed ^ aborted).isTrue();
      assertThat(appliedRepository().readCommitted(application).isPresent()).isEqualTo(committed);
      assertThat(aborts().readDefinitiveAbort(application.operation()).isPresent())
          .isEqualTo(aborted);
      assertThat(count(f, "world_authored_topology_identity")).isEqualTo(committed ? 7 : 0);
      if (aborted)
        assertThatThrownBy(() -> appliedComponent().apply(application))
            .hasMessageContaining("no-commit evidence");
      else
        assertThatThrownBy(() -> aborts().recordDefinitiveAbort(application.operation()))
            .hasMessageContaining("cannot be relabeled");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void synchronizedGraphRequiresExactCanonicalAppliedResultNotArbitraryBytes() {
    Fixture f = fixture();
    var application = application(f);
    var result = appliedComponent().apply(application);
    assertThat(
            repository().readSynchronized(visibility(application, result, result.canonicalBytes())))
        .isPresent();
    assertThatThrownBy(
            () ->
                repository()
                    .readSynchronized(visibility(application, result, new byte[] {1, 2, 3})))
        .hasMessageContaining("exact canonical retained graph application");
    Fixture other = fixture();
    var otherApplication = application(other);
    var otherResult = appliedComponent().apply(otherApplication);
    assertThatThrownBy(
            () ->
                repository()
                    .readSynchronized(
                        visibility(application, result, otherResult.canonicalBytes())))
        .hasMessageContaining("exact canonical retained graph application");
  }

  @Test
  void changedTargetRaceCannotClaimTheSameOriginalOperationAndFenceForBothOutcomes()
      throws Exception {
    var application = application(fixture());
    var other = application(fixture());
    var original =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            application.operation().accountBindingBytes());
    var changed =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            other.operation().accountBindingBytes());
    var conflicting =
        withAccount(
            other.plan(),
            new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding(
                original.operationId(),
                changed.requestId(),
                changed.commitId(),
                original.fenceId(),
                changed.actorAccountId(),
                changed.tenantId(),
                changed.versionId(),
                changed.baseCommitId(),
                changed.expectedDraftEpoch(),
                changed.gameDesignBinding(),
                changed.normalizedInput(),
                changed.inputDigest(),
                changed.sources()));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch begin = new CountDownLatch(1);
    try {
      Future<Boolean> commit =
          pool.submit(
              () -> {
                await(begin);
                try {
                  appliedComponent().apply(application);
                  return true;
                } catch (WorldDesignPublicationFenceRepository.ConflictException
                    | org.springframework.dao.DataIntegrityViolationException conflict) {
                  return false;
                }
              });
      Future<Boolean> abort =
          pool.submit(
              () -> {
                await(begin);
                try {
                  aborts().recordDefinitiveAbort(conflicting.operation());
                  return true;
                } catch (WorldDesignPublicationFenceRepository.ConflictException
                    | org.springframework.dao.DataIntegrityViolationException conflict) {
                  return false;
                }
              });
      begin.countDown();
      boolean committed = commit.get(20, TimeUnit.SECONDS);
      boolean aborted = abort.get(20, TimeUnit.SECONDS);
      assertThat(committed ^ aborted).isTrue();
      assertThat(
              Objects.requireNonNull(
                  Objects.requireNonNull(
                          dsl.fetchOne(
                              "SELECT count(*) FROM world_draft_graph_terminal_identity WHERE operation_id=?",
                              original.operationId()))
                      .get(0, Long.class)))
          .isEqualTo(1);
      assertThat(
              Objects.requireNonNull(
                  Objects.requireNonNull(
                          dsl.fetchOne(
                              "SELECT count(*) FROM world_draft_graph_application WHERE operation_id=?",
                              original.operationId()))
                      .get(0, Long.class)))
          .isEqualTo(committed ? 1 : 0);
      assertThat(
              Objects.requireNonNull(
                  Objects.requireNonNull(
                          dsl.fetchOne(
                              "SELECT count(*) FROM world_draft_terminal_outcome WHERE operation_id=?",
                              original.operationId()))
                      .get(0, Long.class)))
          .isEqualTo(aborted ? 1 : 0);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void completeResultAndGraphSubstitutionFailImmutableReadback() {
    Fixture f = fixture();
    var application = application(f);
    var result = appliedComponent().apply(application);
    byte[] corrupt = result.canonicalBytes();
    corrupt[corrupt.length - 2] ^= 1;
    ownerTransaction()
        .execute(
            status -> {
              dsl.execute(
                  "ALTER TABLE world_draft_graph_application DISABLE TRIGGER trg_world_graph_application_guard");
              dsl.execute(
                  "UPDATE world_draft_graph_application SET result_bytes=?,result_digest=? WHERE operation_id=?",
                  corrupt,
                  WorldDraftGraphAppliedResult.digest(corrupt),
                  application.operation().operationId());
              dsl.execute(
                  "ALTER TABLE world_draft_graph_application ENABLE TRIGGER trg_world_graph_application_guard");
              return null;
            });
    assertThatThrownBy(() -> appliedRepository().readCommitted(application))
        .hasMessageContaining("immutable integrity readback");
    assertThatThrownBy(
            () ->
                repository()
                    .readSynchronized(visibility(application, result, result.canonicalBytes())))
        .hasMessageContaining("immutable integrity readback");

    Fixture other = fixture();
    var otherApplication = application(other);
    var otherResult = appliedComponent().apply(otherApplication);
    ownerTransaction()
        .execute(
            status -> {
              dsl.execute(
                  "ALTER TABLE world_topology_draft_commit DISABLE TRIGGER trg_world_topology_history_immutable");
              dsl.execute(
                  "UPDATE world_topology_draft_commit SET graph_bytes=?,graph_sha256=? WHERE request_id=?",
                  result.graphBytes(),
                  WorldDraftGraphAppliedResult.digest(result.graphBytes()).substring(7),
                  otherApplication.operation().requestId());
              dsl.execute(
                  "ALTER TABLE world_topology_draft_commit ENABLE TRIGGER trg_world_topology_history_immutable");
              return null;
            });
    assertThatThrownBy(() -> appliedRepository().readCommitted(otherApplication))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                repository()
                    .readSynchronized(
                        visibility(otherApplication, otherResult, otherResult.canonicalBytes())))
        .isInstanceOf(RuntimeException.class);
  }

  private WorldDraftGraphApplication application(Fixture f) {
    var p = plan(f);
    var account =
        new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            p.binding().requestId(),
            p.binding().commitId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            p.binding().target().canonicalTenantId(),
            p.binding().target().canonicalVersionId(),
            p.binding().baseCommitId(),
            "0",
            p.binding().canonicalBytes(),
            p.binding().canonicalBytes(),
            p.binding().digest(),
            List.of(
                new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                    .SourceEvidence(
                    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                        .SourceKind.TENANT,
                    p.binding().target().canonicalTenantId().toString(),
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {1, 2, 3})));
    return withAccount(p, account);
  }

  private WorldDraftGraphApplication changedAccountApplication(
      WorldDraftGraphApplication application) {
    var a =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            application.operation().accountBindingBytes());
    var changed =
        new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding(
            a.operationId(),
            a.requestId(),
            a.commitId(),
            a.fenceId(),
            UUID.randomUUID(),
            a.tenantId(),
            a.versionId(),
            a.baseCommitId(),
            a.expectedDraftEpoch(),
            a.gameDesignBinding(),
            a.normalizedInput(),
            a.inputDigest(),
            List.of(
                new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                    .SourceEvidence(
                    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                        .SourceKind.TENANT,
                    a.tenantId().toString(),
                    null,
                    "2",
                    null,
                    null,
                    new byte[] {4, 5, 6})));
    return withAccount(application.plan(), changed);
  }

  private WorldDraftGraphApplication withAccount(
      WorldDraftTopologyCommitPlan p,
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding account) {
    var operation =
        new WorldDraftTerminalOperation(
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            account.tenantId(),
            account.versionId(),
            p.binding(),
            p.ownerBinding(),
            account.canonicalBytes());
    return new WorldDraftGraphApplication(operation, p);
  }

  private WorldDraftGraphApplicationRepository appliedRepository() {
    return new WorldDraftGraphApplicationRepository(dsl, fence, mapper);
  }

  private WorldDraftGraphApplicationService appliedComponent() {
    return appliedComponent(appliedRepository());
  }

  private WorldDraftGraphApplicationService appliedComponent(
      WorldDraftGraphApplicationRepository repository) {
    return new WorldDraftGraphApplicationService(
        repository,
        manager,
        operation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          // STIPULATED fixture authority: original Account COMMIT_ORDER remains held until BOTH
          // owners settle.
          // No protected producer is supplied and this is not Gameplay handoff completion.
          return new WorldDraftGraphApplicationService.CommitOrderProof(operation);
        });
  }

  private WorldDraftTerminalOutcomeService aborts() {
    return new WorldDraftTerminalOutcomeService(
        new WorldDraftTerminalOutcomeRepository(dsl, fence, mapper), manager);
  }

  private void insertReceipt(WorldDraftGraphApplication application, byte[] result) {
    var operation = application.operation();
    dsl.execute(
        "INSERT INTO world_draft_graph_application (operation_id,request_id,commit_id,authorization_fence_id,"
            + "operation_bytes,account_binding_bytes,account_binding_digest,result_bytes,result_digest) VALUES (?,?,?,?,?,?,?,?,?)",
        operation.operationId(),
        operation.requestId(),
        operation.commitId(),
        operation.authorizationFenceId(),
        operation.canonicalBytes(),
        operation.accountBindingBytes(),
        operation.accountBindingDigest(),
        result,
        WorldDraftGraphAppliedResult.digest(result));
  }

  private net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence visibility(
      WorldDraftGraphApplication application,
      WorldDraftGraphAppliedResult result,
      byte[] worldResultBytes) {
    var binding = application.operation().binding();
    List<Map<String, Object>> vector = new ArrayList<>();
    for (Owner owner : binding.requiredOwners()) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("owner", owner.name());
      value.put("status", "APPLIED");
      value.put("commitId", binding.commitId().toString());
      value.put("bindingDigest", binding.digest());
      value.put(
          "resultIdentity",
          owner == Owner.WORLD_MANAGEMENT ? result.resultIdentity() : "stipulated-" + owner);
      value.put(
          "resultBytesBase64",
          java.util.Base64.getEncoder()
              .encodeToString(owner == Owner.WORLD_MANAGEMENT ? worldResultBytes : new byte[] {1}));
      value.put(
          "appliedEpochs",
          binding.affectedUnits(owner).stream()
              .map(
                  unit -> {
                    Map<String, String> epoch = new LinkedHashMap<>();
                    epoch.put("aggregateType", unit.aggregateType());
                    epoch.put("aggregateId", unit.aggregateId());
                    epoch.put("scopeType", unit.scopeType());
                    epoch.put("scopeId", unit.scopeId());
                    epoch.put("expectedEpoch", unit.expectedEpoch());
                    epoch.put("resultingEpoch", "1");
                    return epoch;
                  })
              .toList());
      vector.add(value);
    }
    try {
      String json =
          new String(
              net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
                  mapper.writeValueAsString(vector)),
              StandardCharsets.UTF_8);
      return new net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence(
          new net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.Request(
              1, NAMESPACE, UUID.randomUUID(), binding.target()),
          binding,
          "SYNCHRONIZED",
          new net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.Fence(
              binding.requestId(),
              binding.commitId(),
              binding.digest(),
              json,
              java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)));
    } catch (java.io.IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private Fixture fixture() {
    UUID tenant = UUID.randomUUID();
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
    ownerTransaction()
        .execute(status -> intakeRepository.acceptFresh(NAMESPACE, intakeRequest, source));
    WorldAuthoredSourceIntakeReceipt intake =
        intakeRepository.read(NAMESPACE, intakeRequest).orElseThrow();
    return Objects.requireNonNull(
        ownerTransaction()
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
                      new WorldAuthoredVersionIdentityRepository(dsl)
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
                  assertOrigin();
                  return new Fixture(intake, version, owner);
                }));
  }

  private void freeze(Fixture f) {
    OwnerBinding o = f.owner();
    String request = "freeze-" + UUID.randomUUID();
    WorldDesignPublicationFenceEvidence evidence =
        new WorldDesignPublicationFenceEvidence(
            o.targetNamespace(),
            o.canonicalTenantId(),
            o.canonicalVersionId(),
            o.versionIdentityOperationId(),
            o.gameDesignVersionId(),
            o.intakeRequestId(),
            o.intakeOperationId(),
            o.intakeRequestDigest(),
            o.sourceOperationId(),
            o.sourceEvidenceDigest(),
            o.intakeReceiptDigest(),
            request,
            "a".repeat(64),
            1,
            "publish:" + o.canonicalTenantId() + ":publish-request:" + request);
    ownerTransaction()
        .execute(
            status ->
                fence.claimFreeze(
                    evidence,
                    () ->
                        new WorldDesignPublicationFenceEvidence.Checkpoint(
                            "synthetic-unverified-checkpoint", "b".repeat(64), 2)));
  }

  private WorldDraftTopologyCommitPlan plan(Fixture f) {
    UUID logical = UUID.randomUUID();
    UUID secondRoom = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
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
                    .setShardId(7)
                    .setGenerationSeed(9_007_199_254_740_999L)
                    .setGeneratorType("synthetic")
                    .setGeneratorParams("{\"seed\":1}"))
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
    values.add(
        mutation(
                commit,
                secondRoom,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder().setName("second").setZoneId(logical.toString()))
            .build());
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT)
            .setRoomExit(
                RoomExitDesignMutation.newBuilder()
                    .setFromRoomId(logical.toString())
                    .setToRoomId(secondRoom.toString())
                    .setDirection("NORTH"))
            .build());
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE)
            .setGenerationRule(
                GenerationRuleDesignMutation.newBuilder().setName("rule").setValue("seeded"))
            .build());
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING)
            .setWorldEntitySpawnBinding(
                WorldEntitySpawnBindingDesignMutation.newBuilder()
                    .setRoomId(logical.toString())
                    .setEntityTemplateType(
                        EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC)
                    .setEntityTemplateId(entity.toString())
                    .setRespawnDelaySeconds(17))
            .build());
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> units = new ArrayList<>();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.randomUUID(),
            Owner.ENTITY_MANAGEMENT,
            "synthetic opaque Entity input; existence and permission remain unverified"));
    units.add(
        new AffectedUnit(
            Owner.ENTITY_MANAGEMENT,
            "NPC",
            entity.toString(),
            "AGGREGATE",
            entity.toString(),
            "0"));
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "1",
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

  private WorldDraftTopologyCommitService component() {
    return new WorldDraftTopologyCommitService(
        repository(),
        manager,
        p -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertOrigin();
          // Synthetic local-storage fixture only; no Account permission or Entity existence proof.
        });
  }

  private WorldDraftTopologyCommitRepository repository() {
    return new WorldDraftTopologyCommitRepository(dsl, fence, mapper);
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return tx;
  }

  private void assertOrigin() {
    assertThat(
            dsl.resultQuery("SELECT current_setting('session_replication_role')")
                .fetchOne(0, String.class))
        .isEqualTo("origin");
  }

  private long count(Fixture f, String table) {
    String tenant = table.equals("world_topology_draft_commit") ? "local_tenant_key" : "tenant_id";
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT count(*) FROM " + table + " WHERE " + tenant + "=?",
                f.intake().localTenantKey())
            .fetchOne(0, Long.class));
  }

  private long privateKey(WorldDraftTopologyCommitPlan p, String family, UUID id) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT private_row_key FROM world_authored_topology_identity "
                    + "WHERE request_id=? AND family=? AND template_id=?",
                p.binding().requestId(),
                family,
                id)
            .fetchOne(0, Long.class));
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(mutation);
    } catch (InvalidProtocolBufferException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private record Fixture(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version,
      OwnerBinding owner) {}
}
