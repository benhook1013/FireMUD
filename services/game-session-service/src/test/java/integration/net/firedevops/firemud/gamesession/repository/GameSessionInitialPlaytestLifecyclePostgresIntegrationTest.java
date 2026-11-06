package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPrivateFreshTargetSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerTestFixtures;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Allocation;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.IdempotencyConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.InitializationMode;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.ReadState;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Request;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Snapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.SourceUnavailableException;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshTargetRepository;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jooq.Field;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical initial allocation/read proof using actual GS source/catalog/preparation owners and SQL.
 *
 * <p>The existing canonical publication fixture stipulates upstream Account/World/Game Design
 * authority; this does not execute those producers or establish caller authorization, participant
 * manifests, isolation, source delivery, registration or playtest admission. Fixture-policy source
 * rows are never accepted by this primitive.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionInitialPlaytestLifecyclePostgresIntegrationTest {
  private static final String NAMESPACE = "initial-playtest-proof";
  private static final String TABLE = "game_session_initial_playtest_preparations";
  private static final tools.jackson.databind.ObjectMapper POLICY_JSON =
      JsonMapper.builder().build();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void awaitPostgresReady() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

  @Test
  void retainsPrivateRealmDisplayNameAtCanonicalCodePointAndUtf8Limits() {
    String displayName = "\ud83d\ude00".repeat(128);
    Context context =
        context(
            true,
            List.of(
                privatePolicy(
                    "private-playtest",
                    "source-world",
                    true,
                    false,
                    "ISOLATED",
                    "Source World",
                    displayName)));
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(request).preparation();
    assertThat(preparation.source().targetPolicy().source().policy().realmDisplayName())
        .isEqualTo(displayName);
    assertThat(
            context
                .read(request, preparation.playtestLifecycleId())
                .preparation()
                .source()
                .targetPolicy()
                .source()
                .policy()
                .realmDisplayName())
        .isEqualTo(displayName);
    var row =
        context
            .dsl()
            .fetchSingle(
                "SELECT char_length(target_policy_realm_display_name) AS characters, octet_length(target_policy_realm_display_name) AS bytes FROM game_session_initial_playtest_preparations WHERE request_id = ?",
                request.requestId());
    assertThat(row.get("characters", Integer.class)).isEqualTo(128);
    assertThat(row.get("bytes", Integer.class)).isEqualTo(512);
    var privateTarget =
        context.allocatePrivateTarget(
            new GameSessionPrivateFreshTargetRepository.Request(
                UUID.randomUUID(), request.requestId(), preparation.playtestLifecycleId()));
    assertThat(privateTarget.snapshot().realmDisplayName()).isEqualTo(displayName);
    assertThat(privateTarget.snapshot().visible()).isFalse();
    assertThat(privateTarget.snapshot().publicProduction()).isFalse();
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void allocatesFreshNonAdmissibleIdentityFromExactRetainedCanonicalSourceAndReplaysOriginal() {
    Context context = context(true);
    UUID legacyNamespace = UUID.randomUUID();
    context
        .dsl()
        .execute(
            "INSERT INTO gameplay_tenant_shared_playable_state_namespace (tenant_id, playable_state_namespace_id) VALUES (99174, ?)",
            legacyNamespace);
    var retainedBefore = context.retainedRows();
    Request request =
        context.request(
            UUID.randomUUID(),
            "private-playtest",
            context
                .now()
                .plusSeconds(3600)
                .with(java.time.temporal.ChronoField.NANO_OF_SECOND, 123456789));
    Allocation allocated = context.prepare(request);
    assertThat(allocated.replayed()).isFalse();
    var preparation = allocated.preparation();
    assertThat(preparation.request()).isEqualTo(request);
    assertThat(preparation.requestDigest()).isEqualTo(request.requestDigest());
    byte[] retainedRequestBytes =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT request_preimage FROM " + TABLE + " WHERE request_id = ?",
                        request.requestId()))
            .get("request_preimage", byte[].class);
    // The 46-byte schema marker has a four-byte big-endian length, not decimal-colon framing.
    assertThat(retainedRequestBytes)
        .startsWith(new byte[] {0, 0, 0, 46})
        .containsExactly(request.canonicalPreimage());
    assertThat(preparation.playtestLifecycleId()).isNotEqualTo(new UUID(0, 0));
    assertThat(preparation.playableStateNamespaceId())
        .isNotEqualTo(new UUID(0, 0))
        .isNotEqualTo(preparation.playtestLifecycleId())
        .isNotEqualTo(context.catalog().playableStateNamespaceId())
        .isNotEqualTo(legacyNamespace);
    assertThat(preparation.playtestStateGeneration()).isEqualTo(1);
    assertThat(preparation.preparationState()).isEqualTo("PREPARING");
    assertThat(preparation.source().intakeOperationId())
        .isEqualTo(context.catalog().sourceIntakeReceipt().operationId());
    assertThat(preparation.source().sourceOperationId())
        .isEqualTo(context.catalog().sourceIntakeReceipt().source().operationId());
    assertThat(preparation.source().sourceEvidenceDigest())
        .isEqualTo(context.catalog().sourceIntakeReceipt().source().evidenceDigest());
    assertThat(preparation.source().canonicalVersionId())
        .isEqualTo(context.published().target().canonicalVersionId());
    assertThat(preparation.source().publishedReleaseBundleDigest())
        .isEqualTo(context.published().publishedReleaseBundleDigest());
    var selectedPolicy = preparation.source().targetPolicy();
    assertThat(selectedPolicy.source().policy().worldSlug()).isEqualTo(request.worldSlug());
    assertThat(selectedPolicy.source().policy().realmSlug()).isEqualTo(request.realmSlug());
    assertThat(selectedPolicy.source().policy().visible()).isTrue();
    assertThat(selectedPolicy.source().policy().publicProduction()).isFalse();
    assertThat(selectedPolicy.source().policy().stateScope())
        .isEqualTo(RealmEntryPolicy.StateScope.ISOLATED);
    assertThat(
            selectedPolicy
                .source()
                .policy()
                .canonicalJson()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .containsExactly(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT target_policy_canonical_json FROM "
                                + TABLE
                                + " WHERE request_id = ?",
                            request.requestId()))
                .get("target_policy_canonical_json", byte[].class));
    assertThat(selectedPolicy)
        .isEqualTo(
            context.published().policies().stream()
                .filter(
                    candidate ->
                        candidate.source().policy().worldSlug().equals(request.worldSlug())
                            && candidate.source().policy().realmSlug().equals(request.realmSlug()))
                .findFirst()
                .orElseThrow());
    assertThat(context.prepare(request)).isEqualTo(new Allocation(preparation, true));
    var snapshot = context.read(request, preparation.playtestLifecycleId());
    assertThat(snapshot.preparation()).isEqualTo(preparation);
    assertThat(snapshot.state()).isEqualTo(ReadState.INCOMPLETE);
    assertThat(snapshot.admissionAllowed()).isFalse();
    assertThat(context.count()).isEqualTo(1);
    assertThat(context.retainedRows()).usingRecursiveComparison().isEqualTo(retainedBefore);
  }

  @Test
  void
      allocatesPrivateCatalogIdentityFromExactPreparationAndReadsBackWithoutPublicOrRuntimeEffects() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(preparationRequest).preparation();
    var publicCatalogBefore = context.publicCatalogRow();
    var namespaceRowsBefore = context.namespaceRows();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            preparation.request().requestId(),
            preparation.playtestLifecycleId());

    var allocated = context.allocatePrivateTarget(targetRequest);
    CanonicalPrivateFreshTargetSnapshot target = allocated.snapshot();
    assertThat(allocated.replayed()).isFalse();
    assertThat(target.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(target.tenantId()).isEqualTo(preparation.request().tenantId());
    assertThat(target.worldSlug()).isEqualTo(preparation.request().worldSlug());
    assertThat(target.realmSlug()).isEqualTo(preparation.request().realmSlug());
    assertThat(target.sourceCatalogRealmId()).isEqualTo(context.catalog().realmId());
    assertThat(target.realmId())
        .isNotEqualTo(target.sourceCatalogRealmId())
        .isNotEqualTo(preparation.source().targetPolicy().policyId());
    assertThat(target.playableStateNamespaceId()).isEqualTo(preparation.playableStateNamespaceId());
    assertThat(target.playtestLifecycleId()).isEqualTo(preparation.playtestLifecycleId());
    assertThat(target.playtestStateGeneration()).isEqualTo(1);
    assertThat(target.catalogRevision()).isEqualTo(1);
    assertThat(target.visible()).isFalse();
    assertThat(target.publicProduction()).isFalse();
    assertThat(target.stateScope()).isEqualTo("ISOLATED");
    assertThat(target.preparationState()).isEqualTo("PREPARING");
    assertThat(target.admissionAllowed()).isFalse();
    assertThat(target.realmDisplayName())
        .isEqualTo(preparation.source().targetPolicy().source().policy().realmDisplayName());
    assertThat(target.characterCreationPolicy())
        .isEqualTo(preparation.source().targetPolicy().source().policy().entryPolicy().name());
    assertThat(target.targetPolicyId()).isEqualTo(preparation.source().targetPolicy().policyId());
    assertThat(target.targetPolicyDigest())
        .isEqualTo(preparation.source().targetPolicy().policyDigest());
    assertThat(target.canonicalVersionId()).isEqualTo(preparation.source().canonicalVersionId());
    assertThat(target.publishedReleaseBundleRef())
        .isEqualTo(preparation.source().publishedReleaseBundleRef());
    assertThat(target.publishedReleaseBundleDigest())
        .isEqualTo(preparation.source().publishedReleaseBundleDigest());

    var independentlyRead =
        context
            .privateTargets()
            .readByPreparation(preparation.request().requestId(), preparation.playtestLifecycleId())
            .orElseThrow();
    assertThat(independentlyRead).isEqualTo(target);
    assertThat(context.allocatePrivateTarget(targetRequest))
        .isEqualTo(new GameSessionPrivateFreshTargetRepository.Allocation(target, true));
    assertThat(context.publicCatalogRow()).isEqualTo(publicCatalogBefore);
    assertThat(context.namespaceRows()).isEqualTo(namespaceRowsBefore);
    assertThat(context.catalogs().readVisiblePublicProductionSnapshots(NAMESPACE))
        .containsExactly(context.catalog());
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_published_realm_catalog_entry")))
        .isZero();
  }

  @Test
  void laterPreparationAndSelectionRemainAvailableAfterPriorPrivateCatalogAllocation() {
    Context context = context(true);
    Request firstRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var firstPreparation = context.prepare(firstRequest).preparation();
    var selectionRepository = new GameSessionCurrentPrivatePlaytestRepository(context.dsl());
    var scope =
        new GameSessionCurrentPrivatePlaytestRepository.Scope(
            NAMESPACE, firstRequest.tenantId(), firstRequest.worldSlug(), firstRequest.realmSlug());
    var firstSelectionRequest =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            UUID.randomUUID(),
            scope,
            firstPreparation.request().requestId(),
            firstPreparation.playtestLifecycleId(),
            null,
            null);
    var firstSelection =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(status -> selectionRepository.select(firstSelectionRequest)));
    assertThat(firstSelection.replayed()).isFalse();
    assertThat(firstSelection.receipt().headRevision()).isEqualTo(1L);
    var firstPrivateTargetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            firstPreparation.request().requestId(),
            firstPreparation.playtestLifecycleId());
    var firstPrivateTarget = context.allocatePrivateTarget(firstPrivateTargetRequest);
    assertThat(firstPrivateTarget.replayed()).isFalse();

    String privateCatalogBefore = privateCatalogRow(context, firstPrivateTargetRequest.requestId());
    String firstPreparationBefore =
        retainedRowWithXmin(context, TABLE, "request_id", firstPreparation.request().requestId());
    String firstSelectionBefore =
        retainedRowWithXmin(
            context,
            "game_session_private_playtest_selections",
            "request_id",
            firstSelectionRequest.requestId());
    byte[] firstPreparationRequestBytes =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT request_preimage FROM " + TABLE + " WHERE request_id = ?",
                        firstPreparation.request().requestId()))
            .get("request_preimage", byte[].class);
    byte[] firstSelectionRequestBytes =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT request_preimage FROM game_session_private_playtest_selections WHERE request_id = ?",
                        firstSelectionRequest.requestId()))
            .get("request_preimage", byte[].class);
    var publicCatalogBefore = context.publicCatalogRow();
    String sourceIntakeBefore =
        retainedRowWithXmin(
            context,
            "game_session_authored_world_source_intake",
            "operation_id",
            context.catalog().sourceIntakeReceipt().operationId());
    String sourceBindingBefore =
        retainedRowWithXmin(
            context,
            "game_session_authored_world_tenant_source_binding",
            "canonical_tenant_id",
            context.catalog().tenantId());
    var namespaceRowsBefore = context.namespaceRows();

    Request laterRequest =
        context.request(
            UUID.randomUUID(), firstRequest.realmSlug(), context.now().plusSeconds(7200));
    var laterAllocation = context.prepare(laterRequest);
    assertThat(laterAllocation.replayed()).isFalse();
    var laterPreparation = laterAllocation.preparation();
    assertThat(laterPreparation.request().realmSlug()).isEqualTo(firstRequest.realmSlug());
    assertThat(laterPreparation.request().requestId())
        .isNotEqualTo(firstPreparation.request().requestId());
    assertThat(laterPreparation.playtestLifecycleId())
        .isNotEqualTo(firstPreparation.playtestLifecycleId());
    assertThat(laterPreparation.playableStateNamespaceId())
        .isNotEqualTo(firstPreparation.playableStateNamespaceId());
    assertThat(laterPreparation.playtestStateGeneration()).isEqualTo(1);

    var replacementSelectionRequest =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            UUID.randomUUID(),
            scope,
            laterPreparation.request().requestId(),
            laterPreparation.playtestLifecycleId(),
            1L,
            firstPreparation.playtestLifecycleId());
    var replacementSelection =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(status -> selectionRepository.select(replacementSelectionRequest)));
    assertThat(replacementSelection.replayed()).isFalse();
    assertThat(replacementSelection.receipt().headRevision()).isEqualTo(2L);
    String replacementSelectionBeforeReplay =
        retainedRowWithXmin(
            context,
            "game_session_private_playtest_selections",
            "request_id",
            replacementSelectionRequest.requestId());
    String replacementHeadBeforeReplay =
        retainedRowWithXmin(
            context,
            "game_session_current_private_playtests",
            "selection_request_id",
            replacementSelectionRequest.requestId());
    var current =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(
                    status ->
                        selectionRepository
                            .readCurrent(
                                NAMESPACE, scope.tenantId(), scope.worldSlug(), scope.realmSlug())
                            .orElseThrow()));
    assertThat(current.selection()).isEqualTo(replacementSelection.receipt());
    assertThat(current.selectedPrepSnapshot().preparation()).isEqualTo(laterPreparation);
    assertThat(current.selectedPrepSnapshot().state()).isEqualTo(ReadState.INCOMPLETE);
    assertThat(current.admissionAllowed()).isFalse();
    assertThat(current.selectedPrepSnapshot().admissionAllowed()).isFalse();

    assertThat(context.prepare(firstRequest)).isEqualTo(new Allocation(firstPreparation, true));
    assertThat(context.read(firstRequest, firstPreparation.playtestLifecycleId()).preparation())
        .isEqualTo(firstPreparation);
    var firstSelectionReplay =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(status -> selectionRepository.select(firstSelectionRequest)));
    assertThat(firstSelectionReplay)
        .isEqualTo(
            new GameSessionCurrentPrivatePlaytestRepository.SelectionResult(
                firstSelection.receipt(), true));
    assertThat(context.allocatePrivateTarget(firstPrivateTargetRequest))
        .isEqualTo(
            new GameSessionPrivateFreshTargetRepository.Allocation(
                firstPrivateTarget.snapshot(), true));
    assertThat(context.prepare(laterRequest)).isEqualTo(new Allocation(laterPreparation, true));

    assertThat(context.count()).isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("game_session_private_playtest_selections")))
        .isEqualTo(2);
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_private_playtest_selections",
                "request_id",
                firstSelectionRequest.requestId()))
        .isEqualTo(firstSelectionBefore);
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_private_playtest_selections",
                "request_id",
                replacementSelectionRequest.requestId()))
        .isEqualTo(replacementSelectionBeforeReplay);
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_current_private_playtests",
                "selection_request_id",
                replacementSelectionRequest.requestId()))
        .isEqualTo(replacementHeadBeforeReplay);
    assertThat(
            retainedRowWithXmin(
                context, TABLE, "request_id", firstPreparation.request().requestId()))
        .isEqualTo(firstPreparationBefore);
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT request_preimage FROM " + TABLE + " WHERE request_id = ?",
                            firstPreparation.request().requestId()))
                .get("request_preimage", byte[].class))
        .containsExactly(firstPreparationRequestBytes);
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT request_preimage FROM game_session_private_playtest_selections WHERE request_id = ?",
                            firstSelectionRequest.requestId()))
                .get("request_preimage", byte[].class))
        .containsExactly(firstSelectionRequestBytes);
    assertThat(privateCatalogRow(context, firstPrivateTargetRequest.requestId()))
        .isEqualTo(privateCatalogBefore);
    assertThat(context.publicCatalogRow()).isEqualTo(publicCatalogBefore);
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_authored_world_source_intake",
                "operation_id",
                context.catalog().sourceIntakeReceipt().operationId()))
        .isEqualTo(sourceIntakeBefore);
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_authored_world_tenant_source_binding",
                "canonical_tenant_id",
                context.catalog().tenantId()))
        .isEqualTo(sourceBindingBefore);
    assertThat(context.namespaceRows()).usingRecursiveComparison().isEqualTo(namespaceRowsBefore);
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(2);
    assertThat(
            context
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_realm_catalog WHERE private_preparation_request_id IS NOT NULL")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
    var currentAfterReplay =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(
                    status ->
                        selectionRepository
                            .readCurrent(
                                NAMESPACE, scope.tenantId(), scope.worldSlug(), scope.realmSlug())
                            .orElseThrow()));
    assertThat(currentAfterReplay.selection()).isEqualTo(replacementSelection.receipt());
    assertThat(currentAfterReplay.admissionAllowed()).isFalse();
  }

  @Test
  void privateCatalogRequestRejectsChangedPreparationLifecycleAndSecondRequestForOnePreparation() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(preparationRequest).preparation();
    var requestId = UUID.randomUUID();
    var original =
        new GameSessionPrivateFreshTargetRepository.Request(
            requestId, preparation.request().requestId(), preparation.playtestLifecycleId());
    var allocation = context.allocatePrivateTarget(original);

    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        requestId, UUID.randomUUID(), preparation.playtestLifecycleId())))
        .isInstanceOf(GameSessionPrivateFreshTargetRepository.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        requestId, preparation.request().requestId(), UUID.randomUUID())))
        .isInstanceOf(GameSessionPrivateFreshTargetRepository.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        UUID.randomUUID(),
                        preparation.request().requestId(),
                        preparation.playtestLifecycleId())))
        .isInstanceOf(GameSessionPrivateFreshTargetRepository.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOf(
            GameSessionPrivateFreshTargetRepository.PreparationUnavailableException.class);
    assertThat(context.allocatePrivateTarget(original).snapshot()).isEqualTo(allocation.snapshot());
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(2);
  }

  @Test
  void committedPrivateCatalogOutcomeReplaysAfterExpiryWithoutAllocatingOrRenewingAdmission() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(5));
    var preparation = context.prepare(preparationRequest).preparation();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            preparation.request().requestId(),
            preparation.playtestLifecycleId());
    var original = context.allocatePrivateTarget(targetRequest);
    assertThat(original.replayed()).isFalse();
    String retainedRow =
        context
            .dsl()
            .fetchSingle(
                "SELECT to_jsonb(c)::text||':'||xmin::text FROM game_session_canonical_realm_catalog c WHERE creation_request_id=?",
                targetRequest.requestId())
            .get(0, String.class);
    awaitExpiry(context, preparationRequest.forkExpiresAt());
    assertThat(context.read(preparationRequest, preparation.playtestLifecycleId()).state())
        .isEqualTo(ReadState.EXPIRED);

    var retry = context.allocatePrivateTarget(targetRequest);
    assertThat(retry.replayed()).isTrue();
    assertThat(retry.snapshot()).isEqualTo(original.snapshot());
    assertThat(retry.snapshot().admissionAllowed()).isFalse();
    assertThat(
            context
                .dsl()
                .fetchSingle(
                    "SELECT to_jsonb(c)::text||':'||xmin::text FROM game_session_canonical_realm_catalog c WHERE creation_request_id=?",
                    targetRequest.requestId())
                .get(0, String.class))
        .isEqualTo(retainedRow);
    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        UUID.randomUUID(),
                        preparation.request().requestId(),
                        preparation.playtestLifecycleId())))
        .isInstanceOf(
            GameSessionPrivateFreshTargetRepository.PreparationUnavailableException.class);
    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        targetRequest.requestId(),
                        preparation.request().requestId(),
                        UUID.randomUUID())))
        .isInstanceOf(GameSessionPrivateFreshTargetRepository.IdempotencyConflictException.class);
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void expiredPreparationCannotAllocatePrivateCatalogIdentity() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(5));
    var preparation = context.prepare(preparationRequest).preparation();
    awaitExpiry(context, preparationRequest.forkExpiresAt());

    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        UUID.randomUUID(),
                        preparation.request().requestId(),
                        preparation.playtestLifecycleId())))
        .isInstanceOf(GameSessionPrivateFreshTargetRepository.PreparationUnavailableException.class)
        .hasMessageContaining("expired");
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(1);
  }

  @Test
  void privateCatalogSqlGuardRejectsReplacementNamespaceAndRollsBackInsert() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(preparationRequest).preparation();
    UUID requestId = UUID.randomUUID();
    UUID wrongNamespace = UUID.randomUUID();
    byte[] requestPreimage =
        frame(
            "game-session-private-fresh-catalog-request/v1",
            requestId.toString(),
            preparation.request().requestId().toString(),
            preparation.playtestLifecycleId().toString(),
            preparation.requestDigest());
    String requestDigest = sha256(requestPreimage);
    String sql =
        """
        INSERT INTO game_session_canonical_realm_catalog (
            target_namespace, canonical_tenant_id, tenant_slug, world_slug, realm_id, realm_slug,
            realm_display_name, visible, public_production, state_scope, playable_state_namespace_id,
            character_creation_policy, catalog_revision, creation_request_id, request_digest,
            receipt_digest, source_intake_operation_id, source_intake_schema_version,
            source_intake_request_id, source_intake_request_digest, source_intake_receipt_digest,
            source_schema_version, source_registration_request_id, source_operation_id,
            source_request_digest, source_world_display_name, source_game_row_id,
            source_game_tenant_key, source_provenance_kind, source_evidence_digest,
            complete_policy_set_evidence, private_target_request_preimage,
            private_preparation_request_id, private_playtest_lifecycle_id,
            private_preparation_request_digest, private_state_generation,
            private_source_catalog_realm_id, private_source_catalog_request_id,
            private_source_catalog_revision, private_source_catalog_receipt_digest,
            private_target_policy_id, private_target_policy_digest,
            private_target_policy_source_commit_id, private_target_policy_source_revision_id,
            private_target_policy_logical_revision_id, private_canonical_version_id,
            private_published_release_bundle_ref, private_published_release_bundle_digest,
            private_source_policy_set_digest, private_source_policy_carrier_digest)
        SELECT p.target_namespace, p.canonical_tenant_id, source.tenant_slug, p.world_slug,
            gen_random_uuid(), p.realm_slug, p.target_policy_realm_display_name, false, false,
            'ISOLATED', ?, p.target_policy_entry_policy, 1, ?, ?,
            'sha256:0000000000000000000000000000000000000000000000000000000000000000',
            source.source_intake_operation_id, source.source_intake_schema_version,
            source.source_intake_request_id, source.source_intake_request_digest,
            source.source_intake_receipt_digest, source.source_schema_version,
            source.source_registration_request_id, source.source_operation_id,
            source.source_request_digest, source.source_world_display_name, source.source_game_row_id,
            source.source_game_tenant_key, source.source_provenance_kind, source.source_evidence_digest,
            source.complete_policy_set_evidence, ?, p.request_id, p.playtest_lifecycle_id,
            p.request_digest, p.playtest_state_generation, p.source_catalog_realm_id,
            p.source_catalog_request_id, p.source_catalog_revision, p.source_catalog_receipt_digest,
            p.target_policy_id, p.target_policy_digest, p.target_policy_source_commit_id,
            p.target_policy_source_revision_id, p.target_policy_logical_revision_id,
            p.canonical_version_id, p.published_release_bundle_ref,
            p.published_release_bundle_digest, p.source_policy_set_digest,
            p.source_policy_carrier_digest
        FROM game_session_initial_playtest_preparations p
        JOIN game_session_canonical_realm_catalog source
          ON source.target_namespace = p.target_namespace
         AND source.canonical_tenant_id = p.canonical_tenant_id
         AND source.realm_id = p.source_catalog_realm_id
        WHERE p.request_id = ? AND p.playtest_lifecycle_id = ?
        """;

    assertThatThrownBy(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .execute(
                                    sql,
                                    wrongNamespace,
                                    requestId,
                                    requestDigest,
                                    requestPreimage,
                                    preparation.request().requestId(),
                                    preparation.playtestLifecycleId())))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining(
            "Private FRESH catalog row differs from its exact retained preparation");
    // A NULL build reference must not turn the SQL guard and receipt conjunction into UNKNOWN.
    // This uses the real guarded INSERT, a matching namespace and the same rolled-back intent.
    String nullBuildSql =
        sql.replace(
            "p.canonical_version_id, p.published_release_bundle_ref,",
            "p.canonical_version_id, NULL,");
    assertThat(nullBuildSql).isNotEqualTo(sql);
    assertThatThrownBy(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .execute(
                                    nullBuildSql,
                                    preparation.playableStateNamespaceId(),
                                    requestId,
                                    requestDigest,
                                    requestPreimage,
                                    preparation.request().requestId(),
                                    preparation.playtestLifecycleId())))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("chk_gs_private_fresh_catalog_binding");
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(1);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
  }

  @Test
  void canonicalIntakeGuardRejectsPrivateForgedTenantSlugWithRecomputedReceipt() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(preparationRequest).preparation();
    UUID creationRequestId = UUID.randomUUID();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            creationRequestId,
            preparation.request().requestId(),
            preparation.playtestLifecycleId());
    var publicCatalogBefore = context.publicCatalogRow();
    DetachedCatalogRow rolledBackPrivateRow =
        Objects.requireNonNull(
            context
                .serializableTransaction()
                .execute(
                    status -> {
                      context.privateTargets().allocate(targetRequest);
                      var genuine =
                          Objects.requireNonNull(
                              context
                                  .dsl()
                                  .fetchOne(
                                      "SELECT * FROM game_session_canonical_realm_catalog WHERE creation_request_id = ?",
                                      creationRequestId));
                      DetachedCatalogRow detachedValues =
                          new DetachedCatalogRow(
                              new LinkedHashMap<>(genuine.intoMap()),
                              List.copyOf(java.util.Arrays.asList(genuine.fields())));
                      status.setRollbackOnly();
                      return detachedValues;
                    }));
    assertThat(
            context
                .privateTargets()
                .readByPreparation(
                    preparation.request().requestId(), preparation.playtestLifecycleId()))
        .isEmpty();
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(1);

    String sourceTenantSlug =
        context
            .dsl()
            .fetchSingle(
                "SELECT tenant_slug FROM game_session_canonical_realm_catalog WHERE realm_id = ?",
                context.catalog().realmId())
            .get(0, String.class);
    assertThat(rolledBackPrivateRow.values().get("tenant_slug")).isEqualTo(sourceTenantSlug);
    String forgedTenantSlug = "forged-" + sourceTenantSlug;
    assertThat(forgedTenantSlug).isNotEqualTo(sourceTenantSlug);
    String forgedReceiptDigest =
        privateCatalogReceiptDigest(rolledBackPrivateRow.values(), forgedTenantSlug);
    assertThat(forgedReceiptDigest)
        .startsWith("sha256:")
        .isNotEqualTo(rolledBackPrivateRow.values().get("receipt_digest"));

    Map<String, Object> forged = new LinkedHashMap<>(rolledBackPrivateRow.values());
    forged.put("tenant_slug", forgedTenantSlug);
    forged.put("receipt_digest", forgedReceiptDigest);
    Field<?>[] insertFields = new Field<?>[rolledBackPrivateRow.fields().size()];
    Object[] values = new Object[insertFields.length];
    for (int index = 0; index < insertFields.length; index++) {
      Field<?> originalField = rolledBackPrivateRow.fields().get(index);
      insertFields[index] =
          DSL.field(DSL.name(originalField.getName()), originalField.getDataType());
      values[index] = forged.get(originalField.getName());
    }
    Throwable insertFailure =
        catchThrowable(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .insertInto(
                                    DSL.table(DSL.name("game_session_canonical_realm_catalog")),
                                    insertFields)
                                .values(values)
                                .execute()));
    String failureSummary = databaseFailureSummary(insertFailure);
    assertThat(insertFailure)
        .as("Forged INSERT failure should expose its database cause: %s", failureSummary)
        .isNotNull();
    assertThat(sqlStateInCauseChain(insertFailure))
        .as("Expected SQLSTATE 23514; actual database failure: %s", failureSummary)
        .isEqualTo("23514");
    assertThat(insertFailure)
        .hasStackTraceContaining(
            "Canonical realm catalog requires exact committed authored-world intake evidence");

    assertThat(context.publicCatalogRow()).isEqualTo(publicCatalogBefore);
    assertThat(
            context
                .privateTargets()
                .readByPreparation(
                    preparation.request().requestId(), preparation.playtestLifecycleId()))
        .isEmpty();
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(1);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void privateCatalogCommitGuardRechecksPreparationExpiry() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(5));
    var preparation = context.prepare(preparationRequest).preparation();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            preparation.request().requestId(),
            preparation.playtestLifecycleId());

    assertThatThrownBy(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status -> {
                          context.privateTargets().allocate(targetRequest);
                          awaitExpiry(context, preparationRequest.forkExpiresAt());
                        }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining(
            "Private FRESH catalog preparation expired before final catalog commit");
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(1);
    assertThat(
            context
                .privateTargets()
                .readByPreparation(
                    preparation.request().requestId(), preparation.playtestLifecycleId()))
        .isEmpty();
  }

  @Test
  void changedIntentUnderOneRequestConflictsBeforeSourceLookupOrAllocation() {
    Context context = context(true);
    Request original =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    Allocation allocated = context.prepare(original);
    for (Request changed :
        List.of(
            context.request(
                original.requestId(), "another-private-realm", original.forkExpiresAt()),
            context.request(
                original.requestId(), original.realmSlug(), original.forkExpiresAt().plusNanos(1)),
            new Request(
                original.requestId(),
                NAMESPACE,
                original.tenantId(),
                "another-world",
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                original.requestId(),
                NAMESPACE,
                UUID.randomUUID(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                original.requestId(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                UUID.randomUUID(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()))) {
      assertThatThrownBy(() -> context.prepare(changed))
          .isInstanceOf(IdempotencyConflictException.class);
    }
    assertThat(context.prepare(original).preparation()).isEqualTo(allocated.preparation());
    assertThat(context.count()).isEqualTo(1);
  }

  @Test
  void missingOrMismatchedRetainedSourceAndBuildNeverAllocate() {
    Context missing = context(false);
    assertThatThrownBy(
            () ->
                missing.prepare(
                    missing.request(
                        UUID.randomUUID(), "private-playtest", missing.now().plusSeconds(300))))
        .isInstanceOf(SourceUnavailableException.class);
    assertThat(missing.count()).isZero();
    Context context = context(true);
    Request original =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(300));
    for (Request changed :
        List.of(
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                UUID.randomUUID(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                UUID.randomUUID(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                UUID.randomUUID(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                "sha256:" + "a".repeat(64),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            context.request(
                UUID.randomUUID(), context.catalog().realmSlug(), original.forkExpiresAt()))) {
      assertThatThrownBy(() -> context.prepare(changed))
          .isInstanceOf(SourceUnavailableException.class);
    }
    assertThat(context.count()).isZero();
  }

  @Test
  void onlyExactVisiblePrivateIsolatedPublishedTargetPolicyCanAllocate() {
    List<List<RealmEntryPolicy>> invalidPolicySets =
        List.of(
            List.of(),
            List.of(privatePolicy("private-playtest", "source-world", false, false, "ISOLATED")),
            List.of(privatePolicy("private-playtest", "source-world", true, false, "SHARED")),
            List.of(
                privatePolicy(
                    "private-playtest", "source-world", true, false, "ISOLATED", "Other World")),
            List.of(privatePolicy("another-target", "source-world", true, false, "ISOLATED")),
            List.of(privatePolicy("private-playtest", "another-world", true, false, "ISOLATED")));
    for (List<RealmEntryPolicy> policies : invalidPolicySets) {
      Context context = context(true, policies);
      Request request =
          context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(600));
      assertThatThrownBy(() -> context.prepare(request))
          .isInstanceOf(SourceUnavailableException.class);
      assertThat(context.count()).isZero();
    }
  }

  @Test
  void sqlGuardRejectsTargetPolicyBytesNotSelectedFromRetainedPublishedSet() {
    Context context = context(true);
    Request original =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    context.prepare(original);
    Request tampered =
        context.request(UUID.randomUUID(), original.realmSlug(), original.forkExpiresAt());
    byte[] storedPolicy =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT target_policy_canonical_json FROM "
                            + TABLE
                            + " WHERE request_id = ?",
                        original.requestId()))
            .get("target_policy_canonical_json", byte[].class);
    String changedPolicy =
        new String(storedPolicy, java.nio.charset.StandardCharsets.UTF_8)
            .replace("\"realmSlug\":\"private-playtest\"", "\"realmSlug\":\"other-target\"");
    assertThat(changedPolicy)
        .isNotEqualTo(new String(storedPolicy, java.nio.charset.StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .execute(
                                    "INSERT INTO "
                                        + TABLE
                                        + " (request_id, request_preimage, request_digest, target_namespace, canonical_tenant_id, world_slug, realm_slug, initialization_mode, preparation_state, playtest_state_generation, fork_expiry_second, fork_expiry_nano, fork_expiry_text, source_catalog_realm_id, source_catalog_request_id, source_catalog_revision, source_catalog_receipt_digest, source_intake_operation_id, source_intake_receipt_digest, source_operation_id, source_evidence_digest, canonical_version_id, published_release_bundle_ref, published_release_bundle_digest, source_policy_set_digest, source_policy_carrier_digest, target_policy_id, target_policy_source_commit_id, target_policy_source_revision_id, target_policy_logical_revision_id, target_policy_digest, target_policy_canonical_json, target_policy_schema_version, target_policy_world_slug, target_policy_realm_slug, target_policy_visible, target_policy_world_display_name, target_policy_public_production, target_policy_state_scope, target_policy_realm_display_name, target_policy_entry_policy) "
                                        + "SELECT ?, ?, ?, target_namespace, canonical_tenant_id, world_slug, realm_slug, initialization_mode, preparation_state, playtest_state_generation, fork_expiry_second, fork_expiry_nano, fork_expiry_text, source_catalog_realm_id, source_catalog_request_id, source_catalog_revision, source_catalog_receipt_digest, source_intake_operation_id, source_intake_receipt_digest, source_operation_id, source_evidence_digest, canonical_version_id, published_release_bundle_ref, published_release_bundle_digest, source_policy_set_digest, source_policy_carrier_digest, target_policy_id, target_policy_source_commit_id, target_policy_source_revision_id, target_policy_logical_revision_id, target_policy_digest, ?, target_policy_schema_version, target_policy_world_slug, target_policy_realm_slug, target_policy_visible, target_policy_world_display_name, target_policy_public_production, target_policy_state_scope, target_policy_realm_display_name, target_policy_entry_policy FROM "
                                        + TABLE
                                        + " WHERE request_id = ?",
                                    tampered.requestId(),
                                    tampered.canonicalPreimage(),
                                    tampered.requestDigest(),
                                    changedPolicy.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    original.requestId())))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("Selected private target policy differs");
    assertThat(context.count()).isEqualTo(1);
  }

  @Test
  void concurrentExactRequestAllocatesOnceAndNewRequestsForSameRealmGetDistinctIdentities()
      throws Exception {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch begin = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                await(begin);
                return context.prepare(request);
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                await(begin);
                return context.prepare(request);
              });
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      begin.countDown();
      Allocation left = first.get(60, TimeUnit.SECONDS);
      Allocation right = second.get(60, TimeUnit.SECONDS);
      assertThat(left.preparation()).isEqualTo(right.preparation());
      assertThat(List.of(left.replayed(), right.replayed())).containsExactlyInAnyOrder(false, true);
      assertThat(context.count()).isEqualTo(1);
      Request newFirst =
          context.request(UUID.randomUUID(), request.realmSlug(), request.forkExpiresAt());
      Request newSecond =
          context.request(UUID.randomUUID(), request.realmSlug(), request.forkExpiresAt());
      var third = executor.submit(() -> context.prepare(newFirst));
      var fourth = executor.submit(() -> context.prepare(newSecond));
      var another = third.get(60, TimeUnit.SECONDS).preparation();
      var other = fourth.get(60, TimeUnit.SECONDS).preparation();
      assertThat(another.playtestLifecycleId())
          .isNotEqualTo(left.preparation().playtestLifecycleId())
          .isNotEqualTo(other.playtestLifecycleId());
      assertThat(another.playableStateNamespaceId())
          .isNotEqualTo(left.preparation().playableStateNamespaceId())
          .isNotEqualTo(other.playableStateNamespaceId());
      assertThat(context.read(request, left.preparation().playtestLifecycleId()).preparation())
          .isEqualTo(left.preparation());
      assertThat(context.count()).isEqualTo(3);
    } finally {
      begin.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void staleSerializableSnapshotMustRetryPrivateAllocationInANewOwnerTransaction()
      throws Exception {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(preparationRequest).preparation();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            preparation.request().requestId(),
            preparation.playtestLifecycleId());
    CountDownLatch firstAllocationHeld = new CountDownLatch(1);
    CountDownLatch secondSnapshotEstablished = new CountDownLatch(1);
    CountDownLatch releaseFirstAllocation = new CountDownLatch(1);
    AtomicInteger secondBackendPid = new AtomicInteger();
    AtomicReference<Throwable> staleFailure = new AtomicReference<>();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstAllocation =
          new AtomicReference<GameSessionPrivateFreshTargetRepository.Allocation>();
      var first =
          executor.submit(
              () ->
                  Objects.requireNonNull(
                      context
                          .serializableTransaction()
                          .execute(
                              status -> {
                                var allocated = context.privateTargets().allocate(targetRequest);
                                firstAllocation.set(allocated);
                                firstAllocationHeld.countDown();
                                await(releaseFirstAllocation);
                                return allocated;
                              })));
      await(firstAllocationHeld);

      var second =
          executor.submit(
              () -> {
                try {
                  context
                      .serializableTransaction()
                      .executeWithoutResult(
                          status -> {
                            var visibleBeforeCommit =
                                context
                                    .dsl()
                                    .fetchSingle(
                                        "SELECT count(*) FROM game_session_canonical_realm_catalog WHERE private_preparation_request_id = ?",
                                        preparation.request().requestId())
                                    .get(0, Long.class);
                            assertThat(visibleBeforeCommit).isZero();
                            secondBackendPid.set(
                                context
                                    .dsl()
                                    .fetchSingle("SELECT pg_backend_pid()")
                                    .get(0, Integer.class));
                            secondSnapshotEstablished.countDown();
                            context.privateTargets().allocate(targetRequest);
                          });
                } catch (Throwable failure) {
                  staleFailure.set(failure);
                }
              });
      await(secondSnapshotEstablished);
      awaitAdvisoryLockWait(context, secondBackendPid.get());
      releaseFirstAllocation.countDown();

      first.get(60, TimeUnit.SECONDS);
      second.get(60, TimeUnit.SECONDS);
      Throwable failure = staleFailure.get();
      assertThat(failure)
          .as("stale SERIALIZABLE allocation must refuse with 40001 instead of committing")
          .isNotNull();
      assertThat(sqlStateInCauseChain(failure)).isEqualTo("40001");

      var committed = Objects.requireNonNull(firstAllocation.get());
      String retainedRowBeforeRetry = privateCatalogRow(context, targetRequest.requestId());
      // This is manual caller composition in the test, not automatic production retry behavior.
      var freshTransactionRetry = context.allocatePrivateTarget(targetRequest);
      assertThat(freshTransactionRetry.replayed()).isTrue();
      assertThat(freshTransactionRetry.snapshot()).isEqualTo(committed.snapshot());
      assertThat(freshTransactionRetry.snapshot().realmId())
          .isEqualTo(committed.snapshot().realmId());
      assertThat(freshTransactionRetry.snapshot().receiptDigest())
          .isEqualTo(committed.snapshot().receiptDigest());
      assertThat(privateCatalogRow(context, targetRequest.requestId()))
          .isEqualTo(retainedRowBeforeRetry);
      assertThat(
              context
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_canonical_realm_catalog WHERE private_preparation_request_id = ?",
                      preparation.request().requestId())
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
      assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
    } finally {
      releaseFirstAllocation.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void exactReadRejectsWrongLifecycleAndReportsRealExpiryWithoutChangingOriginal() {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(5));
    Allocation allocation = context.prepare(request);
    var original = allocation.preparation();
    assertThat(context.read(request, original.playtestLifecycleId()).state())
        .isEqualTo(ReadState.INCOMPLETE);
    Optional<Snapshot> wrongLifecycle =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            request.tenantId(),
                            request.worldSlug(),
                            request.realmSlug(),
                            UUID.randomUUID()));
    Optional<Snapshot> wrongRealm =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            request.tenantId(),
                            request.worldSlug(),
                            "wrong-realm",
                            original.playtestLifecycleId()));
    Optional<Snapshot> wrongTenant =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            UUID.randomUUID(),
                            request.worldSlug(),
                            request.realmSlug(),
                            original.playtestLifecycleId()));
    Optional<Snapshot> wrongWorld =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            request.tenantId(),
                            "wrong-world",
                            request.realmSlug(),
                            original.playtestLifecycleId()));
    assertThat(wrongLifecycle).isEmpty();
    assertThat(wrongRealm).isEmpty();
    assertThat(wrongTenant).isEmpty();
    assertThat(wrongWorld).isEmpty();
    awaitExpiry(context, request.forkExpiresAt());
    var expired = context.read(request, original.playtestLifecycleId());
    assertThat(expired.observedAt()).isAfterOrEqualTo(request.forkExpiresAt());
    assertThat(expired.state()).isEqualTo(ReadState.EXPIRED);
    assertThat(expired.admissionAllowed()).isFalse();
    assertThat(expired.preparation()).isEqualTo(original);
    assertThat(context.prepare(request)).isEqualTo(new Allocation(original, true));
    var reuse =
        context
            .prepare(
                context.request(
                    UUID.randomUUID(), request.realmSlug(), context.now().plusSeconds(3600)))
            .preparation();
    assertThat(reuse.playtestLifecycleId()).isNotEqualTo(original.playtestLifecycleId());
    assertThat(reuse.playableStateNamespaceId()).isNotEqualTo(original.playableStateNamespaceId());
  }

  @Test
  void rollbackExpiredFinalCommitAndImmutableHistoryViolationNeverPublishAuthority() {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          context.repository().prepare(request);
                          throw new IllegalStateException("Controlled coordinator refusal");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled coordinator refusal");
    assertThat(context.count()).isZero();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          Request shortRequest =
                              context.request(
                                  UUID.randomUUID(),
                                  "private-playtest",
                                  context.now().plusSeconds(5));
                          context.repository().prepare(shortRequest);
                          awaitExpiry(context, shortRequest.forkExpiresAt());
                        }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("expired before final preparation commit");
    assertThat(context.count()).isZero();
    var committed = context.prepare(request);
    for (String sql :
        List.of(
            "UPDATE " + TABLE + " SET preparation_state = 'OPEN'",
            "UPDATE " + TABLE + " SET playtest_state_generation = 2",
            "UPDATE " + TABLE + " SET fork_expiry_second = fork_expiry_second + 1",
            "DELETE FROM " + TABLE,
            "TRUNCATE " + TABLE)) {
      assertThatThrownBy(
              () ->
                  context.transaction().executeWithoutResult(status -> context.dsl().execute(sql)))
          .as(sql)
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(context.count()).isEqualTo(1);
    assertThat(context.prepare(request).preparation()).isEqualTo(committed.preparation());
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
  }

  @Test
  void unsupportedInitializationAndMissingOwnerTransactionFailBeforeAllocation() {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    for (InitializationMode mode :
        List.of(InitializationMode.SEEDED, InitializationMode.SNAPSHOT)) {
      assertThatThrownBy(
              () ->
                  new Request(
                      request.requestId(),
                      NAMESPACE,
                      request.tenantId(),
                      request.worldSlug(),
                      request.realmSlug(),
                      request.sourceCatalogRealmId(),
                      request.sourceCatalogRequestId(),
                      request.sourceCatalogRevision(),
                      request.sourceIntakeOperationId(),
                      request.canonicalVersionId(),
                      request.publishedReleaseBundleRef(),
                      request.publishedReleaseBundleDigest(),
                      mode,
                      request.forkExpiresAt()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Only initial FRESH");
    }
    assertThatThrownBy(() -> context.repository().prepare(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owner transaction");
    assertThat(context.count()).isZero();
  }

  @Test
  void canonicalPublicPolicyCannotQualifyAsPrivatePlaytestTarget() {
    Context context = context(true);
    Request publicTarget =
        context.request(
            UUID.randomUUID(), context.catalog().realmSlug(), context.now().plusSeconds(3600));
    assertThatThrownBy(() -> context.prepare(publicTarget))
        .isInstanceOf(
            GameSessionInitialPlaytestLifecycleRepository.SourceUnavailableException.class);
    assertThat(context.count()).isZero();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS))
        throw new AssertionError("Concurrent allocation did not start");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static void awaitAdvisoryLockWait(Context context, int backendPid) {
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(10, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              var activity =
                  context
                      .dsl()
                      .fetchOne(
                          "SELECT wait_event_type, wait_event FROM pg_stat_activity WHERE pid = ?",
                          backendPid);
              assertThat(activity).isNotNull();
              assertThat(activity.get("wait_event_type", String.class)).isEqualTo("Lock");
              assertThat(activity.get("wait_event", String.class)).isEqualTo("advisory");
            });
  }

  private static String privateCatalogRow(Context context, UUID requestId) {
    return context
        .dsl()
        .fetchSingle(
            "SELECT to_jsonb(c)::text || ':' || xmin::text FROM game_session_canonical_realm_catalog c WHERE creation_request_id = ?",
            requestId)
        .get(0, String.class);
  }

  private static String retainedRowWithXmin(
      Context context, String tableName, String identityColumn, UUID identity) {
    return context
        .dsl()
        .fetchSingle(
            "SELECT to_jsonb(retained)::text || ':' || xmin::text FROM "
                + tableName
                + " retained WHERE "
                + identityColumn
                + " = ?",
            identity)
        .get(0, String.class);
  }

  private static String privateCatalogReceiptDigest(Map<String, Object> row, String tenantSlug) {
    return sha256(
        frame(
            "game-session-private-fresh-catalog-receipt/v1",
            privateCatalogReceiptField(row, "creation_request_id"),
            privateCatalogReceiptField(row, "request_digest"),
            privateCatalogReceiptField(row, "target_namespace"),
            privateCatalogReceiptField(row, "canonical_tenant_id"),
            tenantSlug,
            privateCatalogReceiptField(row, "world_slug"),
            privateCatalogReceiptField(row, "realm_id"),
            privateCatalogReceiptField(row, "realm_slug"),
            privateCatalogReceiptField(row, "realm_display_name"),
            privateCatalogReceiptField(row, "visible"),
            privateCatalogReceiptField(row, "public_production"),
            privateCatalogReceiptField(row, "state_scope"),
            privateCatalogReceiptField(row, "playable_state_namespace_id"),
            privateCatalogReceiptField(row, "character_creation_policy"),
            privateCatalogReceiptField(row, "catalog_revision"),
            privateCatalogReceiptField(row, "private_preparation_request_id"),
            privateCatalogReceiptField(row, "private_playtest_lifecycle_id"),
            privateCatalogReceiptField(row, "private_preparation_request_digest"),
            privateCatalogReceiptField(row, "private_state_generation"),
            privateCatalogReceiptField(row, "private_source_catalog_realm_id"),
            privateCatalogReceiptField(row, "private_source_catalog_request_id"),
            privateCatalogReceiptField(row, "private_source_catalog_revision"),
            privateCatalogReceiptField(row, "private_source_catalog_receipt_digest"),
            privateCatalogReceiptField(row, "private_target_policy_id"),
            privateCatalogReceiptField(row, "private_target_policy_digest"),
            privateCatalogReceiptField(row, "private_target_policy_source_commit_id"),
            privateCatalogReceiptField(row, "private_target_policy_source_revision_id"),
            privateCatalogReceiptField(row, "private_target_policy_logical_revision_id"),
            privateCatalogReceiptField(row, "private_canonical_version_id"),
            privateCatalogReceiptField(row, "private_published_release_bundle_ref"),
            privateCatalogReceiptField(row, "private_published_release_bundle_digest"),
            privateCatalogReceiptField(row, "private_source_policy_set_digest"),
            privateCatalogReceiptField(row, "private_source_policy_carrier_digest"),
            privateCatalogReceiptField(row, "source_intake_operation_id"),
            privateCatalogReceiptField(row, "source_intake_receipt_digest")));
  }

  private static String privateCatalogReceiptField(Map<String, Object> row, String field) {
    return Objects.requireNonNull(row.get(field), field).toString();
  }

  private static String sqlStateInCauseChain(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        for (SQLException chained = sqlException;
            chained != null;
            chained = chained.getNextException()) {
          if (chained.getSQLState() != null) return chained.getSQLState();
        }
      }
    }
    return null;
  }

  private static String databaseFailureSummary(Throwable failure) {
    if (failure == null) return "<no exception>";
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        for (SQLException chained = sqlException;
            chained != null;
            chained = chained.getNextException()) {
          if (chained.getSQLState() != null) {
            return boundedDiagnostic(
                chained.getSQLState() + ": " + Objects.toString(chained.getMessage(), ""));
          }
        }
      }
    }
    return boundedDiagnostic(failure.getClass().getName() + ": " + failure.getMessage());
  }

  private static String boundedDiagnostic(String diagnostic) {
    if (diagnostic.length() <= 512) return diagnostic;
    return diagnostic.substring(0, 512) + "...";
  }

  private static void awaitExpiry(Context context, Instant expiry) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (!context.now().isBefore(expiry)) return;
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
    throw new AssertionError("Real database clock did not reach expiry");
  }

  private static byte[] frame(String... values) {
    var out = new ByteArrayOutputStream();
    for (String value : values) DraftAuthorizationFenceBinding.frame(out, value);
    return out.toByteArray();
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private Context context(boolean withSource) {
    return context(
        withSource,
        List.of(privatePolicy("private-playtest", "source-world", true, false, "ISOLATED")));
  }

  private Context context(boolean withSource, List<RealmEntryPolicy> privatePolicies) {
    String schema = "initial_playtest_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        dataSource,
        schema,
        "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
        null);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    TransactionTemplate serializableTransaction = new TransactionTemplate(transactionManager);
    serializableTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    var repository = new GameSessionInitialPlaytestLifecycleRepository(dsl);
    var catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
    var privateTargets = new GameSessionPrivateFreshTargetRepository(dsl);
    if (!withSource)
      return new Context(
          dsl,
          transaction,
          serializableTransaction,
          repository,
          privateTargets,
          catalogs,
          null,
          null);
    // These upstream receipts are explicitly stipulated. Every retained GS row is created through
    // its actual source, fresh association and closed Human canonical catalog owner.
    UUID tenant = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID sourceOperation = UUID.randomUUID();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, "source-tenant", "source-world", "Source World");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            "source-tenant",
            "source-world",
            "Source World",
            731L,
            "controlled-source-game",
            "NEW_GAME_ROW");
    var evidence =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            "source-tenant",
            "source-world",
            "Source World",
            731L,
            "controlled-source-game",
            "NEW_GAME_ROW",
            evidenceDigest);
    var sourceOwner = new GameSessionAuthoredWorldSourceRepository(dsl);
    var intake =
        Objects.requireNonNull(
            transaction.execute(status -> sourceOwner.register(UUID.randomUUID(), evidence)));
    transaction.executeWithoutResult(
        status ->
            new GameSessionFreshTenantAssociationRepository(dsl)
                .associateFresh(
                    UUID.randomUUID(),
                    NAMESPACE,
                    intake,
                    CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(evidence)));
    var catalogRequest =
        new CreateCanonicalRealmCatalogRequest(
            UUID.randomUUID(),
            NAMESPACE,
            tenant,
            intake.operationId(),
            "source-public",
            "Source Public",
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    UUID version = UUID.randomUUID();
    var published =
        CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
            catalogRequest,
            evidence,
            version,
            72L,
            "controlled-publish-" + UUID.randomUUID(),
            "release-bundle:" + version,
            "generation-1",
            3L,
            privatePolicies);
    var preparedCatalog = catalogs.prepareInitialPublicProduction(catalogRequest, published);
    var catalog =
        Objects.requireNonNull(
            transaction.execute(status -> catalogs.createInitialPublicProduction(preparedCatalog)));
    return new Context(
        dsl,
        transaction,
        serializableTransaction,
        repository,
        privateTargets,
        catalogs,
        catalog,
        published);
  }

  private static RealmEntryPolicy privatePolicy(
      String realmSlug,
      String worldSlug,
      boolean visible,
      boolean publicProduction,
      String stateScope) {
    return privatePolicy(
        realmSlug, worldSlug, visible, publicProduction, stateScope, "Source World");
  }

  private static RealmEntryPolicy privatePolicy(
      String realmSlug,
      String worldSlug,
      boolean visible,
      boolean publicProduction,
      String stateScope,
      String worldDisplayName) {
    return privatePolicy(
        realmSlug,
        worldSlug,
        visible,
        publicProduction,
        stateScope,
        worldDisplayName,
        "Private Playtest");
  }

  private static RealmEntryPolicy privatePolicy(
      String realmSlug,
      String worldSlug,
      boolean visible,
      boolean publicProduction,
      String stateScope,
      String worldDisplayName,
      String realmDisplayName) {
    try {
      String json =
          new String(
              net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
                  "{\"entryPolicy\":\"PRESEEDED_ONLY\",\"realmDisplayName\":\""
                      + realmDisplayName
                      + "\",\"realmSlug\":\""
                      + realmSlug
                      + "\",\"publicProduction\":"
                      + publicProduction
                      + ",\"schemaVersion\":1,\"stateScope\":\""
                      + stateScope
                      + "\",\"visible\":"
                      + visible
                      + ",\"worldDisplayName\":\""
                      + worldDisplayName
                      + "\",\"worldSlug\":\""
                      + worldSlug
                      + "\"}"),
              java.nio.charset.StandardCharsets.UTF_8);
      return RealmEntryPolicy.parseCanonical(json, POLICY_JSON);
    } catch (java.io.IOException malformed) {
      throw new IllegalStateException(
          "Controlled private policy fixture encoding failed", malformed);
    }
  }

  private record DetachedCatalogRow(Map<String, Object> values, List<Field<?>> fields) {}

  private record Context(
      DSLContext dsl,
      TransactionTemplate transaction,
      TransactionTemplate serializableTransaction,
      GameSessionInitialPlaytestLifecycleRepository repository,
      GameSessionPrivateFreshTargetRepository privateTargets,
      GameSessionCanonicalRealmCatalogRepository catalogs,
      CanonicalRealmCatalogSnapshot catalog,
      RealmPolicyPublishedEvidence.PublishedSet published) {
    Instant now() {
      return Objects.requireNonNull(dsl.fetchOne("SELECT clock_timestamp() AS now"))
          .get("now", OffsetDateTime.class)
          .toInstant();
    }

    Request request(UUID request, String realm, Instant expiry) {
      if (catalog == null)
        return new Request(
            request,
            NAMESPACE,
            UUID.randomUUID(),
            "missing-world",
            realm,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "missing-release",
            "sha256:" + "b".repeat(64),
            InitializationMode.FRESH,
            expiry);
      return new Request(
          request,
          NAMESPACE,
          catalog.tenantId(),
          catalog.worldSlug(),
          realm,
          catalog.realmId(),
          catalog.creationRequestId(),
          catalog.catalogRevision(),
          catalog.sourceIntakeReceipt().operationId(),
          published.target().canonicalVersionId(),
          published.publishedReleaseBundleRef(),
          published.publishedReleaseBundleDigest(),
          InitializationMode.FRESH,
          expiry);
    }

    Allocation prepare(Request request) {
      return Objects.requireNonNull(transaction.execute(status -> repository.prepare(request)));
    }

    GameSessionPrivateFreshTargetRepository.Allocation allocatePrivateTarget(
        GameSessionPrivateFreshTargetRepository.Request request) {
      return Objects.requireNonNull(
          serializableTransaction.execute(status -> privateTargets.allocate(request)));
    }

    GameSessionInitialPlaytestLifecycleRepository.Snapshot read(Request request, UUID lifecycle) {
      return Objects.requireNonNull(
          transaction.execute(
              status ->
                  repository
                      .readExact(
                          NAMESPACE,
                          request.tenantId(),
                          request.worldSlug(),
                          request.realmSlug(),
                          lifecycle)
                      .orElseThrow()));
    }

    int count() {
      return dsl.fetchCount(DSL.table(TABLE));
    }

    List<Map<String, Object>> retainedRows() {
      return dsl.fetch(
              "SELECT 'catalog' AS source, realm_id::TEXT AS id, xmin::TEXT AS version FROM game_session_canonical_realm_catalog "
                  + "UNION ALL SELECT 'namespace', playable_state_namespace_id::TEXT, xmin::TEXT FROM gameplay_tenant_shared_playable_state_namespace ORDER BY 1, 2")
          .intoMaps();
    }

    Map<String, Object> publicCatalogRow() {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT to_jsonb(catalog)::TEXT AS retained_row, xmin::TEXT AS row_version "
                      + "FROM game_session_canonical_realm_catalog catalog WHERE realm_id = ?",
                  catalog.realmId()))
          .intoMap();
    }

    List<Map<String, Object>> namespaceRows() {
      return dsl.fetch(
              "SELECT tenant_id, canonical_tenant_id, target_namespace, "
                  + "playable_state_namespace_id::TEXT AS namespace_id, xmin::TEXT AS row_version "
                  + "FROM gameplay_tenant_shared_playable_state_namespace "
                  + "ORDER BY playable_state_namespace_id")
          .intoMaps();
    }
  }
}
