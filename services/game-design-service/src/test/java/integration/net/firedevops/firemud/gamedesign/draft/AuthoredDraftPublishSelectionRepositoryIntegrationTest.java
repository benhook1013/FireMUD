package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository.SelectionSnapshot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.ApplicationSlot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.DraftCommitNotFoundException;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.DraftCommitStateConflictException;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerOutcome;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.WorkflowState;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
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

@Testcontainers(disabledWithoutDocker = true)
class AuthoredDraftPublishSelectionRepositoryIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final String LARGE_EPOCH = "9007199254740993";
  private static final String WORLD_EPOCH = "900719925474099312345678901234567890";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static Fixture fixture;

  @BeforeAll
  static void setUpSchema() {
    fixture = createFixture();
  }

  @Test
  void reusesExistingAuthoredVersionAndExactReplayIgnoresLaterServerState() {
    VersionFixture version = fixture.newVersion(Long.parseLong(LARGE_EPOCH));
    DraftCommitBinding binding = binding(version.target(), UUID.randomUUID(), UUID.randomUUID());
    fixture.synchronize(binding);
    long versionCountBeforeSelection = fixture.versionCount();
    PublishIntent intent = intent(version, binding, "publication notes");

    SelectionSnapshot first = inTransaction(() -> fixture.selections().reserve(intent));
    inTransaction(
        () -> {
          int updated =
              fixture
                  .dsl()
                  .execute(
                      "UPDATE version SET version_state = ?, version_state_epoch = version_state_epoch + 1 "
                          + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                      VersionLifecycleState.PUBLISHED.name(),
                      version.target().canonicalTenantId(),
                      version.target().canonicalVersionId());
          assertThat(updated).isEqualTo(1);
          return null;
        });
    SelectionSnapshot exactReplay = inTransaction(() -> fixture.selections().reserve(intent));

    assertThat(first.selection().target().gameDesignVersionRowId()).isEqualTo(version.rowId());
    assertThat(exactReplay.selection().canonicalBytes())
        .containsExactly(first.selection().canonicalBytes());
    assertThat(exactReplay.selection().digest()).isEqualTo(first.selection().digest());
    assertThat(exactReplay.createdAt()).isEqualTo(first.createdAt());
    assertThat(fixture.versionCount()).isEqualTo(versionCountBeforeSelection);
    assertThat(
            fixture
                .selections()
                .read(
                    version.target().canonicalTenantId(),
                    version.target().canonicalVersionId(),
                    intent.publishRequestId()))
        .contains(exactReplay);

    assertChangedIntentConflicts(intent, binding);
    assertThat(
            fixture
                .selections()
                .read(
                    fixture.newVersion(1L).target().canonicalTenantId(),
                    version.target().canonicalVersionId(),
                    intent.publishRequestId()))
        .isEmpty();
  }

  @Test
  void rejectsPublishedScriptOnlyUnmappedPartialStaleAndMismatchedTargets() {
    VersionFixture published = fixture.newVersion(1L);
    inTransaction(
        () -> {
          fixture
              .dsl()
              .execute(
                  "UPDATE version SET version_state = ? WHERE id = ?",
                  VersionLifecycleState.PUBLISHED.name(),
                  published.rowId());
          return null;
        });
    assertThatThrownBy(
            () -> inTransaction(() -> fixture.selections().reserve(emptyIntent(published))))
        .isInstanceOf(DraftCommitStateConflictException.class)
        .hasMessageContaining("DRAFT");

    VersionFixture full = fixture.newVersion(1L);
    VersionFixture scriptOnly = fixture.newScriptOnlyVersion(full);
    assertThatThrownBy(
            () -> inTransaction(() -> fixture.selections().reserve(emptyIntent(scriptOnly))))
        .isInstanceOf(DraftCommitStateConflictException.class)
        .hasMessageContaining("non-script-only");

    PublishIntent unmapped =
        new PublishIntent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "unmapped-request",
            "1",
            "notes",
            UUID.randomUUID(),
            UUID.randomUUID(),
            sha256("unmapped"));
    assertThatThrownBy(() -> inTransaction(() -> fixture.selections().reserve(unmapped)))
        .isInstanceOf(DraftCommitNotFoundException.class);

    DraftCommitBinding partial = binding(full.target(), UUID.randomUUID(), UUID.randomUUID());
    inTransaction(() -> fixture.coordinator().claim(partial));
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> fixture.selections().reserve(intent(full, partial, "partial notes"))))
        .isInstanceOf(DraftCommitStateConflictException.class)
        .hasMessageContaining("synchronized");

    VersionFixture staleVersion = fixture.newVersion(1L);
    DraftCommitBinding oldCommit =
        binding(staleVersion.target(), UUID.randomUUID(), UUID.randomUUID());
    fixture.synchronize(oldCommit);
    DraftCommitBinding currentCommit =
        binding(staleVersion.target(), UUID.randomUUID(), UUID.randomUUID());
    fixture.synchronize(currentCommit);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> fixture.selections().reserve(intent(staleVersion, oldCommit, "stale"))))
        .isInstanceOf(DraftCommitStateConflictException.class)
        .hasMessageContaining("stale");

    VersionFixture mismatchedVersion = fixture.newVersion(1L);
    DraftCommitBinding actual =
        binding(mismatchedVersion.target(), UUID.randomUUID(), UUID.randomUUID());
    fixture.synchronize(actual);
    PublishIntent mismatched =
        new PublishIntent(
            mismatchedVersion.target().canonicalTenantId(),
            mismatchedVersion.target().canonicalVersionId(),
            "mismatched-request",
            "1",
            "notes",
            actual.requestId(),
            UUID.randomUUID(),
            actual.digest());
    assertThatThrownBy(() -> inTransaction(() -> fixture.selections().reserve(mismatched)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failedReservationTransactionLeavesNoSelection() {
    VersionFixture version = fixture.newVersion(1L);
    DraftCommitBinding binding = binding(version.target(), UUID.randomUUID(), UUID.randomUUID());
    fixture.synchronize(binding);
    PublishIntent intent = intent(version, binding, "rollback notes");

    fixture
        .ownerTransaction()
        .executeWithoutResult(
            status -> {
              fixture.selections().reserve(intent);
              status.setRollbackOnly();
            });

    assertThat(
            fixture
                .selections()
                .read(
                    version.target().canonicalTenantId(),
                    version.target().canonicalVersionId(),
                    intent.publishRequestId()))
        .isEmpty();
  }

  @Test
  void versionRowLockSerializesSelectionAgainstApplicationSlotClaim() throws Exception {
    VersionFixture version = fixture.newVersion(1L);
    DraftCommitBinding selectedCommit =
        binding(version.target(), UUID.randomUUID(), UUID.randomUUID());
    fixture.synchronize(selectedCommit);
    DraftCommitBinding queuedCommit =
        binding(version.target(), UUID.randomUUID(), UUID.randomUUID());
    inTransaction(() -> fixture.coordinator().claim(queuedCommit));
    PublishIntent intent = intent(version, selectedCommit, "race notes");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> selectionWon =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                try {
                  inTransaction(() -> fixture.selections().reserve(intent));
                  return true;
                } catch (DraftCommitStateConflictException expected) {
                  return false;
                }
              });
      Future<Boolean> applicationWon =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                try {
                  inTransaction(() -> fixture.coordinator().claimApplicationSlot(queuedCommit));
                  return true;
                } catch (DraftCommitStateConflictException expected) {
                  return false;
                }
              });
      await(ready);
      start.countDown();
      boolean selected = selectionWon.get(20, TimeUnit.SECONDS);
      boolean applicationClaimed = applicationWon.get(20, TimeUnit.SECONDS);

      assertThat(selected).isNotEqualTo(applicationClaimed);
      assertThat(
              fixture.coordinator().readVisibilityFence(version.target()).orElseThrow().commitId())
          .isEqualTo(selectedCommit.commitId());
      if (selected) {
        assertThat(
                fixture
                    .selections()
                    .read(
                        version.target().canonicalTenantId(),
                        version.target().canonicalVersionId(),
                        intent.publishRequestId()))
            .isPresent();
        assertThat(fixture.coordinator().readApplicationSlot(version.target())).isEmpty();
      } else {
        Optional<ApplicationSlot> slot =
            fixture.coordinator().readApplicationSlot(version.target());
        assertThat(slot).isPresent();
        assertThat(slot.orElseThrow().commitId()).isEqualTo(queuedCommit.commitId());
        assertThat(
                fixture
                    .selections()
                    .read(
                        version.target().canonicalTenantId(),
                        version.target().canonicalVersionId(),
                        intent.publishRequestId()))
            .isEmpty();
      }
      assertThat(
              fixture
                  .coordinator()
                  .read(version.target(), queuedCommit.requestId())
                  .orElseThrow()
                  .workflowState())
          .isEqualTo(WorkflowState.QUEUED);
    } finally {
      executor.shutdownNow();
    }
  }

  private static void assertChangedIntentConflicts(
      PublishIntent intent, DraftCommitBinding binding) {
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .selections()
                            .reserve(
                                withIntent(
                                    intent,
                                    "changed notes",
                                    intent.expectedVersionStateEpoch(),
                                    intent.publishRequestId(),
                                    intent.selectedCommitId(),
                                    intent.selectedCommitDigest()))))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .selections()
                            .reserve(
                                withIntent(
                                    intent,
                                    intent.notes(),
                                    "1",
                                    intent.publishRequestId(),
                                    intent.selectedCommitId(),
                                    intent.selectedCommitDigest()))))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .selections()
                            .reserve(
                                withIntent(
                                    intent,
                                    intent.notes(),
                                    intent.expectedVersionStateEpoch(),
                                    "changed-request",
                                    intent.selectedCommitId(),
                                    intent.selectedCommitDigest()))))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .selections()
                            .reserve(
                                withIntent(
                                    intent,
                                    intent.notes(),
                                    intent.expectedVersionStateEpoch(),
                                    intent.publishRequestId(),
                                    UUID.randomUUID(),
                                    intent.selectedCommitDigest()))))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .selections()
                            .reserve(
                                withIntent(
                                    intent,
                                    intent.notes(),
                                    intent.expectedVersionStateEpoch(),
                                    intent.publishRequestId(),
                                    intent.selectedCommitId(),
                                    sha256("changed digest")))))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
  }

  private static PublishIntent withIntent(
      PublishIntent original,
      String notes,
      String expectedEpoch,
      String requestId,
      UUID selectedCommitId,
      String selectedDigest) {
    return new PublishIntent(
        original.canonicalTenantId(),
        original.canonicalVersionId(),
        requestId,
        expectedEpoch,
        notes,
        original.selectedCommitRequestId(),
        selectedCommitId,
        selectedDigest);
  }

  private static PublishIntent intent(
      VersionFixture version, DraftCommitBinding binding, String notes) {
    return new PublishIntent(
        version.target().canonicalTenantId(),
        version.target().canonicalVersionId(),
        "publish-" + UUID.randomUUID(),
        version.stateEpoch(),
        notes,
        binding.requestId(),
        binding.commitId(),
        binding.digest());
  }

  private static PublishIntent emptyIntent(VersionFixture version) {
    return new PublishIntent(
        version.target().canonicalTenantId(),
        version.target().canonicalVersionId(),
        "publish-" + UUID.randomUUID(),
        version.stateEpoch(),
        "notes",
        UUID.randomUUID(),
        UUID.randomUUID(),
        sha256("unselected"));
  }

  private static DraftCommitBinding binding(TargetProof target, UUID requestId, UUID commitId) {
    return DraftCommitBinding.create(
        target,
        requestId,
        commitId,
        "base-commit-source-7",
        List.of(
            new RevisionPayload(
                "0",
                UUID.randomUUID(),
                Owner.WORLD_MANAGEMENT,
                "{\"completeWorldMutation\":{\"opaque\":\"retained\"}}")),
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "WORLD_TEMPLATE",
                "world-17",
                "ROOM_SCOPE",
                "room-scope-5",
                WORLD_EPOCH)));
  }

  private static OwnerOutcome appliedOutcome(DraftCommitBinding binding) {
    AffectedUnit expected = binding.affectedUnits(Owner.WORLD_MANAGEMENT).getFirst();
    String resultingEpoch = new BigInteger(expected.expectedEpoch()).add(BigInteger.ONE).toString();
    return new OwnerOutcome(
        Owner.WORLD_MANAGEMENT,
        OwnerStatus.APPLIED,
        binding.commitId(),
        binding.digest(),
        "world-result-" + binding.commitId(),
        ("full owner result " + binding.commitId()).getBytes(StandardCharsets.UTF_8),
        List.of(
            new AppliedEpoch(
                expected.aggregateType(),
                expected.aggregateId(),
                expected.scopeType(),
                expected.scopeId(),
                expected.expectedEpoch(),
                resultingEpoch)));
  }

  private static Fixture createFixture() {
    String schema = "gd_draft_select_" + UUID.randomUUID().toString().replace("-", "");
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
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    GameRepository games = new GameRepository(dsl);
    VersionRepository versions = new VersionRepository(dsl, null);
    DraftCommitCoordinatorRepository coordinator = new DraftCommitCoordinatorRepository(dsl);
    AuthoredDraftPublishSelectionRepository selections =
        new AuthoredDraftPublishSelectionRepository(dsl, coordinator);
    return new Fixture(dsl, transaction, games, versions, coordinator, selections);
  }

  private static <T> T inTransaction(Supplier<T> work) {
    return fixture.ownerTransaction().execute(status -> work.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for authored Draft selection race");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while racing authored Draft selection", exception);
    }
  }

  private static String sha256(String value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record VersionFixture(TargetProof target, long rowId, String stateEpoch) {}

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate ownerTransaction,
      GameRepository games,
      VersionRepository versions,
      DraftCommitCoordinatorRepository coordinator,
      AuthoredDraftPublishSelectionRepository selections) {
    VersionFixture newVersion(long stateEpoch) {
      String tenantKey = "d-" + UUID.randomUUID().toString().replace("-", "");
      Game game = new Game();
      game.setTenantId(tenantKey);
      game.setName("Authored Draft selection source");
      game.setDescription("Canonical Version reuse fixture");
      Game savedGame = Objects.requireNonNull(ownerTransaction.execute(status -> games.save(game)));

      Version version = new Version();
      version.setTenantId(savedGame.getTenantId());
      version.setVersionNumber(1);
      version.setVersionState(VersionLifecycleState.DRAFT);
      version.setVersionStateEpoch(stateEpoch);
      Version savedVersion =
          Objects.requireNonNull(ownerTransaction.execute(status -> versions.save(version)));
      return new VersionFixture(
          target(savedVersion), savedVersion.getId(), Long.toString(stateEpoch));
    }

    VersionFixture newScriptOnlyVersion(VersionFixture base) {
      Version version = new Version();
      version.setTenantId(base.target().gameDesignVersionTenantKey());
      version.setVersionNumber(2);
      version.setVersionState(VersionLifecycleState.DRAFT);
      version.setVersionStateEpoch(1L);
      version.setScriptOnly(true);
      version.setScriptPatchVersion("patch-1");
      version.setBaseVersionId(base.rowId());
      Version savedVersion =
          Objects.requireNonNull(ownerTransaction.execute(status -> versions.save(version)));
      return new VersionFixture(target(savedVersion), savedVersion.getId(), "1");
    }

    void synchronize(DraftCommitBinding binding) {
      ownerTransaction.executeWithoutResult(
          status -> {
            coordinator.claim(binding);
            coordinator.claimApplicationSlot(binding);
            coordinator.markOwnerInProgress(binding, Owner.WORLD_MANAGEMENT);
            OwnerOutcome outcome = appliedOutcome(binding);
            coordinator.recordOwnerOutcome(binding, outcome);
            coordinator.advanceVisibilityFence(
                binding,
                new DraftCommitCoordinatorRepository.CoordinatorProof(binding, List.of(outcome)));
            coordinator.releaseApplicationSlot(binding);
          });
    }

    long versionCount() {
      return dsl.fetchCount(DSL.table(DSL.name("version")));
    }

    private TargetProof target(Version version) {
      return new TargetProof(
          version.getCanonicalTenantId(),
          version.getCanonicalVersionId(),
          version.getId(),
          version.getTenantId(),
          version.getIdentitySourceGameRowId(),
          version.getIdentitySourceGameTenantKey(),
          version.getIdentitySourceProvenanceKind());
    }
  }
}
