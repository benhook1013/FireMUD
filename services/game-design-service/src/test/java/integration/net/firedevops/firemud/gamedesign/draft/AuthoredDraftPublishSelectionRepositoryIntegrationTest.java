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
import org.flywaydb.core.api.FlywayException;
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
  void v44PreservesExactSelectionsRetainedAtV43() {
    Fixture retained = createFixture("43");
    VersionFixture first = retained.newVersion(Long.parseLong(LARGE_EPOCH));
    VersionFixture second = retained.newFullVersionInTenant(first);
    VersionFixture otherTenant = retained.newVersion(1L);
    DraftCommitBinding firstCommit = binding(first.target(), UUID.randomUUID(), UUID.randomUUID());
    DraftCommitBinding secondCommit =
        binding(second.target(), UUID.randomUUID(), UUID.randomUUID());
    DraftCommitBinding otherCommit =
        binding(otherTenant.target(), UUID.randomUUID(), UUID.randomUUID());
    retained.synchronize(firstCommit);
    retained.synchronize(secondCommit);
    retained.synchronize(otherCommit);
    PublishIntent firstIntent = intent(first, firstCommit, "retained notes \"quoted\"\n世界");
    PublishIntent secondIntent = intent(second, secondCommit, "second retained selection");
    PublishIntent otherIntent =
        sameRequestIntent(otherTenant, otherCommit, firstIntent.publishRequestId());
    SelectionSnapshot firstSelection = reserve(retained, firstIntent);
    SelectionSnapshot secondSelection = reserve(retained, secondIntent);
    SelectionSnapshot otherSelection = reserve(retained, otherIntent);
    List<Map<String, Object>> before = retainedSelectionRows(retained);
    assertThat(before).hasSize(3);

    migrate(retained.dataSource(), retained.schema(), "44");

    assertThat(retainedSelectionRows(retained)).isEqualTo(before);
    assertRetainedSelection(retained, firstSelection);
    assertRetainedSelection(retained, secondSelection);
    assertRetainedSelection(retained, otherSelection);
    assertThat(reserve(retained, firstIntent)).isEqualTo(firstSelection);
    assertThat(reserve(retained, secondIntent)).isEqualTo(secondSelection);
    assertThat(reserve(retained, otherIntent)).isEqualTo(otherSelection);
    assertThat(retainedSelectionRows(retained)).isEqualTo(before);
    assertThat(
            retained
                .dsl()
                .fetchCount(DSL.table(DSL.name(FLYWAY_TABLE)), DSL.field("version").eq("44")))
        .isEqualTo(1);
  }

  @Test
  void v44RejectsConflictingRetainedRequestAcrossVersionsWithoutDiscardingEitherHistory() {
    Fixture retained = createFixture("43");
    VersionFixture first = retained.newVersion(1L);
    VersionFixture second = retained.newFullVersionInTenant(first);
    DraftCommitBinding firstCommit = binding(first.target(), UUID.randomUUID(), UUID.randomUUID());
    DraftCommitBinding secondCommit =
        binding(second.target(), UUID.randomUUID(), UUID.randomUUID());
    retained.synchronize(firstCommit);
    retained.synchronize(secondCommit);
    PublishIntent firstIntent = intent(first, firstCommit, "first retained history");
    PublishIntent secondIntent =
        sameRequestIntent(second, secondCommit, firstIntent.publishRequestId());
    SelectionSnapshot firstSelection = reserve(retained, firstIntent);
    // V43 permitted this exact tenant/request reuse across Versions. The current repository
    // prevents it, so insert the historically valid second selection under V43's real triggers.
    insertPreV44Selection(retained, second, secondIntent);
    SelectionSnapshot secondSelection =
        retained
            .selections()
            .read(
                secondIntent.canonicalTenantId(),
                secondIntent.canonicalVersionId(),
                secondIntent.publishRequestId())
            .orElseThrow();
    List<Map<String, Object>> before = retainedSelectionRows(retained);
    assertThat(before).hasSize(2);

    assertThatThrownBy(() -> migrate(retained.dataSource(), retained.schema(), "44"))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("uq_gd_authored_draft_publish_operation");

    assertThat(retainedSelectionRows(retained)).isEqualTo(before);
    assertRetainedTargetSelection(retained, firstSelection);
    assertRetainedTargetSelection(retained, secondSelection);
    assertThat(
            retained
                .dsl()
                .fetchCount(DSL.table(DSL.name(FLYWAY_TABLE)), DSL.field("version").eq("44")))
        .isZero();
    assertThat(
            retained
                .dsl()
                .fetchCount(DSL.table(DSL.name(FLYWAY_TABLE)), DSL.field("version").eq("43")))
        .isEqualTo(1);
  }

  private static SelectionSnapshot reserve(Fixture retained, PublishIntent intent) {
    return Objects.requireNonNull(
        retained.ownerTransaction().execute(status -> retained.selections().reserve(intent)));
  }

  private static List<Map<String, Object>> retainedSelectionRows(Fixture retained) {
    return retained
        .dsl()
        .fetch(
            "SELECT * FROM game_design_authored_draft_publish_selection "
                + "ORDER BY canonical_tenant_id, canonical_version_id")
        .intoMaps();
  }

  private static void assertRetainedSelection(Fixture retained, SelectionSnapshot expected) {
    assertRetainedTargetSelection(retained, expected);
    PublishIntent intent = expected.selection().intent();
    assertThat(
            retained
                .selections()
                .readByPublishRequest(intent.canonicalTenantId(), intent.publishRequestId()))
        .contains(expected);
  }

  private static void assertRetainedTargetSelection(Fixture retained, SelectionSnapshot expected) {
    PublishIntent intent = expected.selection().intent();
    SelectionSnapshot actual =
        retained
            .selections()
            .read(
                intent.canonicalTenantId(), intent.canonicalVersionId(), intent.publishRequestId())
            .orElseThrow();
    assertThat(actual).isEqualTo(expected);
    assertThat(actual.selection().canonicalBytes())
        .containsExactly(expected.selection().canonicalBytes());
    assertThat(actual.selection().digest()).isEqualTo(expected.selection().digest());
    assertThat(actual.createdAt()).isEqualTo(expected.createdAt());
  }

  private static void insertPreV44Selection(
      Fixture retained, VersionFixture version, PublishIntent intent) {
    retained
        .ownerTransaction()
        .executeWithoutResult(
            status -> {
              retained
                  .coordinator()
                  .lockVersionTarget(intent.canonicalTenantId(), intent.canonicalVersionId());
              AuthoredDraftPublishSelection selection =
                  AuthoredDraftPublishSelection.capture(
                      intent,
                      version.target(),
                      retained
                          .coordinator()
                          .requireSynchronizedPublicationEvidence(
                              version.target(),
                              intent.selectedCommitRequestId(),
                              intent.selectedCommitId(),
                              intent.selectedCommitDigest()));
              TargetProof target = selection.target();
              assertThat(
                      retained
                          .dsl()
                          .execute(
                              "INSERT INTO game_design_authored_draft_publish_selection "
                                  + "(canonical_tenant_id, canonical_version_id, game_design_version_row_id, "
                                  + "game_design_version_tenant_key, source_game_row_id, source_game_tenant_key, "
                                  + "source_provenance_kind, publish_request_id, version_state_epoch, "
                                  + "selected_commit_request_id, selected_commit_id, selected_commit_digest, "
                                  + "selection_digest, selection_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                              target.canonicalTenantId(),
                              target.canonicalVersionId(),
                              target.gameDesignVersionRowId(),
                              target.gameDesignVersionTenantKey(),
                              target.sourceGameRowId(),
                              target.sourceGameTenantKey(),
                              target.sourceProvenanceKind(),
                              intent.publishRequestId(),
                              Long.parseLong(intent.expectedVersionStateEpoch()),
                              intent.selectedCommitRequestId(),
                              intent.selectedCommitId(),
                              intent.selectedCommitDigest(),
                              selection.digest(),
                              selection.canonicalJson()))
                  .isEqualTo(1);
            });
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
  void samePublicationRequestCannotSelectAnotherVersionInTheSameTenant() {
    VersionFixture first = fixture.newVersion(1L);
    VersionFixture second = fixture.newFullVersionInTenant(first);
    DraftCommitBinding firstCommit = binding(first.target(), UUID.randomUUID(), UUID.randomUUID());
    DraftCommitBinding secondCommit =
        binding(second.target(), UUID.randomUUID(), UUID.randomUUID());
    fixture.synchronize(firstCommit);
    fixture.synchronize(secondCommit);
    PublishIntent original = intent(first, firstCommit, "selected notes");
    PublishIntent changed = sameRequestIntent(second, secondCommit, original.publishRequestId());
    SelectionSnapshot retained = inTransaction(() -> fixture.selections().reserve(original));

    assertThatThrownBy(() -> inTransaction(() -> fixture.selections().reserve(changed)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
    assertThat(
            fixture
                .selections()
                .readByPublishRequest(original.canonicalTenantId(), original.publishRequestId()))
        .contains(retained);
    assertThat(
            fixture
                .selections()
                .read(
                    changed.canonicalTenantId(),
                    changed.canonicalVersionId(),
                    changed.publishRequestId()))
        .isEmpty();
  }

  @Test
  void concurrentPublicationRequestClaimsAcrossVersionsRetainOnlyOneSelection() throws Exception {
    VersionFixture first = fixture.newVersion(1L);
    VersionFixture second = fixture.newFullVersionInTenant(first);
    DraftCommitBinding firstCommit = binding(first.target(), UUID.randomUUID(), UUID.randomUUID());
    DraftCommitBinding secondCommit =
        binding(second.target(), UUID.randomUUID(), UUID.randomUUID());
    fixture.synchronize(firstCommit);
    fixture.synchronize(secondCommit);
    String requestId = "racing-publication-" + UUID.randomUUID();
    PublishIntent firstIntent = sameRequestIntent(first, firstCommit, requestId);
    PublishIntent secondIntent = sameRequestIntent(second, secondCommit, requestId);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> firstClaim =
          executor.submit(() -> claimRacingIntent(firstIntent, ready, start));
      Future<Boolean> secondClaim =
          executor.submit(() -> claimRacingIntent(secondIntent, ready, start));
      await(ready);
      start.countDown();
      assertThat(firstClaim.get(20, TimeUnit.SECONDS))
          .isNotEqualTo(secondClaim.get(20, TimeUnit.SECONDS));
      SelectionSnapshot retained =
          fixture
              .selections()
              .readByPublishRequest(firstIntent.canonicalTenantId(), requestId)
              .orElseThrow();
      assertThat(retained.selection().intent()).isIn(firstIntent, secondIntent);
      assertThat(inTransaction(() -> fixture.selections().reserve(retained.selection().intent())))
          .isEqualTo(retained);
    } finally {
      executor.shutdownNow();
    }
  }

  private static boolean claimRacingIntent(
      PublishIntent intent, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    try {
      inTransaction(() -> fixture.selections().reserve(intent));
      return true;
    } catch (DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException expected) {
      return false;
    }
  }

  private static PublishIntent sameRequestIntent(
      VersionFixture version, DraftCommitBinding commit, String requestId) {
    return new PublishIntent(
        version.target().canonicalTenantId(),
        version.target().canonicalVersionId(),
        requestId,
        version.stateEpoch(),
        "selected notes",
        commit.requestId(),
        commit.commitId(),
        commit.digest());
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
    return createFixture(null);
  }

  private static Fixture createFixture(String migrationTarget) {
    String schema = "gd_draft_select_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    dataSource.setSchema(schema);
    migrate(dataSource, schema, migrationTarget);
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
    return new Fixture(
        schema, dataSource, dsl, transaction, games, versions, coordinator, selections);
  }

  private static void migrate(
      DriverManagerDataSource dataSource, String schema, String migrationTarget) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (migrationTarget != null) {
      configuration.target(migrationTarget);
    }
    configuration.load().migrate();
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
      String schema,
      DriverManagerDataSource dataSource,
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

    VersionFixture newFullVersionInTenant(VersionFixture base) {
      Version version = new Version();
      version.setTenantId(base.target().gameDesignVersionTenantKey());
      version.setVersionNumber(2);
      version.setVersionState(VersionLifecycleState.DRAFT);
      version.setVersionStateEpoch(1L);
      Version saved =
          Objects.requireNonNull(ownerTransaction.execute(status -> versions.save(version)));
      return new VersionFixture(target(saved), saved.getId(), "1");
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
