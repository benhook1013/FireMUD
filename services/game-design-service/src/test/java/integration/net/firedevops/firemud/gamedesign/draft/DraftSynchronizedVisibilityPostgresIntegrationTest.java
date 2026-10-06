package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.DraftSynchronizedVisibilityGrpcService;
import net.firedevops.firemud.gamedesign.v1.ReadDraftSynchronizedVisibilityResponse;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Game Design PostgreSQL coordinator read through the standalone handler under a
 * fixture-provided verified peer context. Owner outcomes are fixture-seeded APPLIED rows only; this
 * is not physical-mTLS, Account-authorized World producer, normal creator-write, publication, or
 * activation proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class DraftSynchronizedVisibilityPostgresIntegrationTest {
  private static final String NAMESPACE = "draft-visibility-test";
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static Fixture fixture;

  @BeforeAll
  static void startPostgresAndMigrate() {
    String schema = "gd_visibility_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table(FLYWAY_TABLE)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    fixture =
        new Fixture(
            dsl,
            transaction,
            new GameRepository(dsl),
            new VersionRepository(dsl, null),
            new DraftCommitCoordinatorRepository(dsl));
  }

  @Test
  void readsOldSynchronizedFenceAfterNewerPartialOwnerWorkAndDeniesUnfencedTarget() {
    TargetProof target = fixture.newTarget();
    DraftCommitBinding synchronizedBinding = binding(target, UUID.randomUUID(), UUID.randomUUID());
    List<DraftCommitCoordinatorRepository.OwnerOutcome> synchronizedOutcomes =
        synchronizeFixtureBinding(synchronizedBinding);

    DraftCommitBinding laterBinding = binding(target, UUID.randomUUID(), UUID.randomUUID());
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture.coordinator().claim(laterBinding);
              fixture.coordinator().claimApplicationSlot(laterBinding);
              fixture.coordinator().markOwnerInProgress(laterBinding, Owner.WORLD_MANAGEMENT);
              fixture
                  .coordinator()
                  .recordOwnerOutcome(laterBinding, unknownWorldOutcome(laterBinding));
            });

    DraftSynchronizedVisibilityEvidence.Request read = request(target);
    var observer = new TestObserver<ReadDraftSynchronizedVisibilityResponse>();
    withPeer(
        worldPeer(),
        () ->
            new DraftSynchronizedVisibilityGrpcService(fixture.coordinator(), NAMESPACE)
                .readDraftSynchronizedVisibility(
                    DraftSynchronizedVisibilityGrpcCodec.toRequest(read), observer));

    assertThat(observer.error).isNull();
    assertThat(observer.completed).isTrue();
    var evidence = DraftSynchronizedVisibilityGrpcCodec.fromResponse(read, observer.value);
    assertThat(evidence.binding()).isEqualTo(synchronizedBinding);
    assertThat(evidence.binding().commitId()).isNotEqualTo(laterBinding.commitId());
    assertThat(evidence.appliedOwnerResults()).hasSize(synchronizedOutcomes.size());
    assertThat(
            fixture
                .coordinator()
                .read(target, laterBinding.requestId())
                .orElseThrow()
                .workflowState())
        .isEqualTo(DraftCommitCoordinatorRepository.WorkflowState.RECONCILIATION_REQUIRED);
  }

  @Test
  void noFenceAndPartialOnlyCommitAreUnavailable() {
    TargetProof target = fixture.newTarget();
    DraftCommitBinding pending = binding(target, UUID.randomUUID(), UUID.randomUUID());
    fixture.transaction().executeWithoutResult(status -> fixture.coordinator().claim(pending));

    var observer = new TestObserver<ReadDraftSynchronizedVisibilityResponse>();
    withPeer(
        worldPeer(),
        () ->
            new DraftSynchronizedVisibilityGrpcService(fixture.coordinator(), NAMESPACE)
                .readDraftSynchronizedVisibility(
                    DraftSynchronizedVisibilityGrpcCodec.toRequest(request(target)), observer));

    assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(fixture.coordinator().readVisibilityFence(target)).isEmpty();
  }

  private static List<DraftCommitCoordinatorRepository.OwnerOutcome> synchronizeFixtureBinding(
      DraftCommitBinding binding) {
    List<DraftCommitCoordinatorRepository.OwnerOutcome> outcomes = new ArrayList<>();
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture.coordinator().claim(binding);
              fixture.coordinator().claimApplicationSlot(binding);
              for (Owner owner : binding.requiredOwners()) {
                fixture.coordinator().markOwnerInProgress(binding, owner);
                var outcome = appliedOutcome(binding, owner);
                outcomes.add(outcome);
                fixture.coordinator().recordOwnerOutcome(binding, outcome);
              }
              fixture
                  .coordinator()
                  .advanceVisibilityFence(
                      binding,
                      new DraftCommitCoordinatorRepository.CoordinatorProof(binding, outcomes));
              fixture.coordinator().releaseApplicationSlot(binding);
            });
    return List.copyOf(outcomes);
  }

  private static DraftCommitCoordinatorRepository.OwnerOutcome appliedOutcome(
      DraftCommitBinding binding, Owner owner) {
    List<DraftCommitCoordinatorRepository.AppliedEpoch> epochs =
        binding.affectedUnits(owner).stream()
            .map(
                unit ->
                    new DraftCommitCoordinatorRepository.AppliedEpoch(
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch(),
                        new java.math.BigInteger(unit.expectedEpoch())
                            .add(java.math.BigInteger.ONE)
                            .toString()))
            .toList();
    return new DraftCommitCoordinatorRepository.OwnerOutcome(
        owner,
        DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
        binding.commitId(),
        binding.digest(),
        owner.name().toLowerCase(java.util.Locale.ROOT) + "-fixture-result",
        (owner.name() + " fixture result bytes").getBytes(StandardCharsets.UTF_8),
        epochs);
  }

  private static DraftCommitCoordinatorRepository.OwnerOutcome unknownWorldOutcome(
      DraftCommitBinding binding) {
    return new DraftCommitCoordinatorRepository.OwnerOutcome(
        Owner.WORLD_MANAGEMENT,
        DraftCommitCoordinatorRepository.OwnerStatus.UNKNOWN,
        binding.commitId(),
        binding.digest(),
        null,
        null,
        List.of());
  }

  private static DraftSynchronizedVisibilityEvidence.Request request(TargetProof target) {
    return new DraftSynchronizedVisibilityEvidence.Request(1, NAMESPACE, UUID.randomUUID(), target);
  }

  private static DraftCommitBinding binding(TargetProof target, UUID requestId, UUID commitId) {
    return DraftCommitBinding.create(
        target,
        requestId,
        commitId,
        "base-commit-source-7",
        List.of(
            new RevisionPayload(
                "0", UUID.randomUUID(), Owner.WORLD_MANAGEMENT, "{\"fixture\":\"world\"}"),
            new RevisionPayload(
                "1", UUID.randomUUID(), Owner.ENTITY_MANAGEMENT, "{\"fixture\":\"entity\"}")),
        List.of(
            new AffectedUnit(Owner.WORLD_MANAGEMENT, "WORLD", "w-1", "ROOM", "r-1", "0"),
            new AffectedUnit(Owner.ENTITY_MANAGEMENT, "ENTITY", "e-1", "ACTOR", "a-1", "0")));
  }

  private static GrpcPeerIdentity worldPeer() {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
        NAMESPACE,
        "world-management-service");
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transaction,
      GameRepository games,
      VersionRepository versions,
      DraftCommitCoordinatorRepository coordinator) {
    TargetProof newTarget() {
      String tenantKey = "d-" + UUID.randomUUID().toString().replace("-", "");
      Game game = new Game();
      game.setTenantId(tenantKey);
      game.setName("Synchronized visibility SQL fixture");
      game.setDescription("Synthetic owner-result fixture only");
      Game savedGame = Objects.requireNonNull(transaction.execute(status -> games.save(game)));
      Version version = new Version();
      version.setTenantId(savedGame.getTenantId());
      version.setVersionNumber(1);
      version.setVersionState(net.firedevops.firemud.gamedesign.model.VersionLifecycleState.DRAFT);
      version.setVersionStateEpoch(1L);
      Version savedVersion =
          Objects.requireNonNull(transaction.execute(status -> versions.save(version)));
      return new TargetProof(
          savedVersion.getCanonicalTenantId(),
          savedVersion.getCanonicalVersionId(),
          savedVersion.getId(),
          savedVersion.getTenantId(),
          savedVersion.getIdentitySourceGameRowId(),
          savedVersion.getIdentitySourceGameTenantKey(),
          savedVersion.getIdentitySourceProvenanceKind());
    }
  }

  private static final class TestObserver<T> implements StreamObserver<T> {
    private T value;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(T next) {
      value = next;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "The test recorder retains the exact original throwable solely for assertion.")
    public void onError(Throwable failure) {
      error = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
