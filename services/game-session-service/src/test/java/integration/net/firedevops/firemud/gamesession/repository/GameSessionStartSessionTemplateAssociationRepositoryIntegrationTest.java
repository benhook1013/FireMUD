package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
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
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationResult;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL proof that one normalized association is immutably pinned to the original attempt. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest {
  private static final UUID TENANT = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID VERSION = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID COMMIT = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID WORLD_OPERATION =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID WORLD_REQUEST = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID WORLD_FENCE = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ROOM = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID INTAKE_REQUEST =
      UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final UUID INTAKE_OPERATION =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UUID SOURCE_OPERATION =
      UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
  private static final UUID REGISTRATION_REQUEST =
      UUID.fromString("abababab-abab-4bab-8bab-abababababab");
  private static final UUID REGION = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final UUID ZONE = UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID REGION_REVISION =
      UUID.fromString("12345678-1234-4234-8234-123456789001");
  private static final UUID ZONE_REVISION = UUID.fromString("12345678-1234-4234-8234-123456789002");
  private static final UUID ROOM_REVISION = UUID.fromString("12345678-1234-4234-8234-123456789003");
  private static final UUID RESERVATION_OWNER =
      UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String NAMESPACE = "world-runtime";
  private static final String WORKLOAD =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final String WORLD_SLUG = "authored-world";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final Instant AUTHORITY_REFERENCE_TIME = Instant.now();
  private static final Instant AUTHORITY_EVALUATED_AT = AUTHORITY_REFERENCE_TIME.minusSeconds(60L);
  private static final Instant AUTHORITY_EXPIRES_AT =
      AUTHORITY_REFERENCE_TIME.plus(Duration.ofHours(1));

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void pinsOneInitialSelectionAndExactReplayReturnsTheOriginalWireSelection() {
    Fixture fixture = fixture();
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("association-pin-exact-replay");
    AttemptClaim claim = fixture.reserveAndAttach(tuple);
    Result initial = result(tuple, claim, new InitialConfigured(), UUID.randomUUID());

    PinnedAssociationSnapshot first = fixture.pin(claim, initial);
    PinnedAssociationSnapshot stored = fixture.find(claim).orElseThrow();
    Result exactReplay =
        result(
            tuple,
            claim,
            new ExactReplay(
                first.result().association().canonicalVersionId(),
                first.result().association().selectedCommitId(),
                first.result().association().publishWorkflowId(),
                first.result().association().associationDigest()),
            UUID.randomUUID());
    PinnedAssociationSnapshot replay = fixture.pin(claim, exactReplay);

    assertThat(first.result().request().selection()).isInstanceOf(InitialConfigured.class);
    assertThat(stored.result().request()).isEqualTo(first.result().request());
    assertThat(replay.result().request()).isEqualTo(first.result().request());
    assertThat(replay.requestDigest()).isEqualTo(first.requestDigest());
    assertThat(replay.responseDigest()).isEqualTo(first.responseDigest());
    assertThat(fixture.pinCount()).isEqualTo(1);
    PinnedAssociationSnapshot exactLostAcknowledgementReplay = fixture.pin(claim, initial);
    assertThat(exactLostAcknowledgementReplay.result().request())
        .isEqualTo(first.result().request());
    Result changedInitialRead = result(tuple, claim, new InitialConfigured(), UUID.randomUUID());
    assertThatThrownBy(() -> fixture.pin(claim, changedInitialRead))
        .isInstanceOf(
            GameSessionStartSessionTemplateAssociationRepository
                .StartSessionTemplateAssociationConflictException.class)
        .hasMessageContaining("Exact replay");
    StartSessionPostAuthorizationExecutionTuple changedTuple =
        tuple("association-pin-exact-replay", "substituted authorized action");
    Result changedTupleResult =
        result(changedTuple, claim, new InitialConfigured(), UUID.randomUUID());
    assertThatThrownBy(() -> fixture.pin(claim, changedTupleResult))
        .isInstanceOf(
            GameSessionStartSessionTemplateAssociationRepository
                .StartSessionTemplateAssociationConflictException.class)
        .hasMessageContaining("exact current StartSession owner attempt");
    Result changedAssociationReplay =
        withAssociationDigest(exactReplay, "sha256:" + "c".repeat(64));
    assertThatThrownBy(() -> fixture.pin(claim, changedAssociationReplay))
        .isInstanceOf(
            GameSessionStartSessionTemplateAssociationRepository
                .StartSessionTemplateAssociationConflictException.class)
        .hasMessageContaining("Exact replay");
    assertThat(fixture.pinCount()).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances")))).isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();

    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE game_session_start_session_template_association_pin "
                        + "SET selected_commit_id = ? WHERE target_namespace = ? "
                        + "AND control_plane_request_id = ?",
                    UUID.fromString("66666666-6666-4666-8666-666666666666"),
                    claim.targetNamespace(),
                    claim.controlPlaneRequestId()))
        .isInstanceOf(RuntimeException.class);
    assertThat(fixture.pinCount()).isEqualTo(1);
  }

  @Test
  void concurrentInitialSelectionsCannotReplaceTheFirstCommittedPin() throws Exception {
    Fixture fixture = fixture();
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("association-pin-concurrent");
    AttemptClaim claim = fixture.reserveAndAttach(tuple);
    Result firstInitial = result(tuple, claim, new InitialConfigured(), UUID.randomUUID());
    Result secondInitial = withAssociationDigest(firstInitial, "sha256:" + "c".repeat(64));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<PinOutcome> first =
          executor.submit(() -> contender(fixture, claim, firstInitial, ready, start));
      Future<PinOutcome> second =
          executor.submit(() -> contender(fixture, claim, secondInitial, ready, start));
      assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<PinOutcome> outcomes =
          List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

      assertThat(outcomes.stream().filter(PinOutcome::pinned).count()).isEqualTo(1);
      assertThat(outcomes.stream().filter(value -> !value.pinned()).count()).isEqualTo(1);
      assertThat(fixture.pinCount()).isEqualTo(1);
      assertThat(fixture.find(claim)).isPresent();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void claimWithoutAccountProjectionCannotReadOrCreateASelectionPin() {
    Fixture fixture = fixture();
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("association-pin-no-account");
    ReservationResult reservation = fixture.reserve(tuple);
    AttemptClaim claim = reservation.claim().orElseThrow();
    Result initial = result(tuple, claim, new InitialConfigured(), UUID.randomUUID());

    assertThatThrownBy(() -> fixture.pin(claim, initial))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StaleStartSessionOperatorAttemptClaimException.class)
        .hasMessageContaining("Account-attached");
    assertThatThrownBy(() -> fixture.find(claim))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StaleStartSessionOperatorAttemptClaimException.class)
        .hasMessageContaining("Account-attached");
    assertThat(fixture.pinCount()).isZero();
  }

  @Test
  void oversizedTypedReleaseIsDeniedByTheLocalStorageBudgetBeforeOwnerStorageAccess() {
    Fixture fixture = fixture();
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("association-pin-oversized-response");
    AttemptClaim claim = fixture.reserveAndAttach(tuple);
    Result valid = result(tuple, claim, new InitialConfigured(), UUID.randomUUID());
    PublishedReleaseBundle oversizedRelease =
        valid.releaseBundle().toBuilder()
            .addCommandDefinitions("x".repeat(4 * 1024 * 1024 + 1))
            .build();
    Result oversized =
        new Result(
            valid.request(),
            valid.association(),
            oversizedRelease,
            valid.worldPublishedStartLocationEvidence(),
            valid.phaseEpoch());

    assertThatThrownBy(() -> fixture.pin(claim, oversized))
        .isInstanceOf(
            GameSessionStartSessionTemplateAssociationRepository
                .StartSessionTemplateAssociationConflictException.class)
        .hasMessageContaining("local fail-closed storage budget");
    assertThat(fixture.pinCount()).isZero();
  }

  private static PinOutcome contender(
      Fixture fixture,
      AttemptClaim claim,
      Result initial,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(15, TimeUnit.SECONDS)) {
      throw new IllegalStateException("association pin contender did not start");
    }
    try {
      return new PinOutcome(true, fixture.pin(claim, initial));
    } catch (
        GameSessionStartSessionTemplateAssociationRepository
                .StartSessionTemplateAssociationConflictException
            expected) {
      return new PinOutcome(false, null);
    }
  }

  static Result result(
      StartSessionPostAuthorizationExecutionTuple tuple,
      AttemptClaim claim,
      StartSessionTemplateAssociationReadEvidence.Selection selection,
      UUID readRequestId) {
    // A closed typed carrier fixture for repository semantics, not GD/World producer or runtime
    // authority proof.
    WorldPublishedStartLocationEvidence world = worldEvidence();
    var worldRequest = world.request();
    AuthoredWorldSourceEvidence source = source();
    IntakeRequest intakeRequest =
        new IntakeRequest(
            1,
            NAMESPACE,
            INTAKE_REQUEST,
            TENANT,
            WORLD_SLUG,
            SOURCE_OPERATION,
            source.evidenceDigest());
    PublicReceipt receipt =
        new PublicReceipt(
            1,
            NAMESPACE,
            INTAKE_REQUEST,
            INTAKE_OPERATION,
            TENANT,
            WORLD_SLUG,
            SOURCE_OPERATION,
            source.evidenceDigest(),
            WorldAuthoredSourceIntakeGrpcCodec.requestDigest(intakeRequest),
            "sha256:" + "9".repeat(64),
            source);
    Association association =
        new Association(
            TENANT,
            tuple.preAuthorizationTuple().action().target().gameTemplateId(),
            VERSION,
            COMMIT,
            worldRequest.publishWorkflowId(),
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            NAMESPACE,
            INTAKE_REQUEST,
            INTAKE_OPERATION,
            SOURCE_OPERATION,
            WORLD_SLUG,
            source.evidenceDigest(),
            new ByIdReadRequest(1, NAMESPACE, UUID.randomUUID(), INTAKE_REQUEST, TENANT),
            receipt);
    Request request =
        new Request(
            1,
            NAMESPACE,
            readRequestId,
            tuple.canonicalBytes(),
            claim.ownerAttemptId(),
            claim.ownerFence(),
            selection);
    return new Result(request, association, release(world), world, 5L);
  }

  static AuthoredWorldSourceEvidence source() {
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, REGISTRATION_REQUEST, TENANT, "test-tenant", WORLD_SLUG, "Authored World");
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_REQUEST,
            SOURCE_OPERATION,
            sourceRequestDigest,
            TENANT,
            "test-tenant",
            WORLD_SLUG,
            "Authored World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        REGISTRATION_REQUEST,
        SOURCE_OPERATION,
        sourceRequestDigest,
        TENANT,
        "test-tenant",
        WORLD_SLUG,
        "Authored World",
        42L,
        "tenant-key",
        "NEW_GAME_ROW",
        sourceEvidenceDigest);
  }

  private static Result withAssociationDigest(Result original, String changedDigest) {
    Association pinned = original.association();
    Association changed =
        new Association(
            pinned.canonicalTenantId(),
            pinned.templateId(),
            pinned.canonicalVersionId(),
            pinned.selectedCommitId(),
            pinned.publishWorkflowId(),
            pinned.publicationSelectionDigest(),
            changedDigest,
            pinned.targetNamespace(),
            pinned.intakeRequestId(),
            pinned.worldOperationId(),
            pinned.sourceOperationId(),
            pinned.worldSlug(),
            pinned.sourceEvidenceDigest(),
            pinned.worldReadRequest(),
            pinned.worldReceipt());
    Request oldRequest = original.request();
    StartSessionTemplateAssociationReadEvidence.Selection selection =
        oldRequest.selection() instanceof ExactReplay
            ? new ExactReplay(
                changed.canonicalVersionId(),
                changed.selectedCommitId(),
                changed.publishWorkflowId(),
                changed.associationDigest())
            : oldRequest.selection();
    Request replay =
        new Request(
            oldRequest.schemaVersion(),
            oldRequest.targetNamespace(),
            UUID.randomUUID(),
            oldRequest.canonicalPostAuthorizationTuple(),
            oldRequest.ownerAttemptId(),
            oldRequest.ownerFence(),
            selection);
    return new Result(
        replay,
        changed,
        original.releaseBundle(),
        original.worldPublishedStartLocationEvidence(),
        original.phaseEpoch());
  }

  private static PublishedReleaseBundle release(WorldPublishedStartLocationEvidence world) {
    var request = world.request();
    var builder =
        PublishedReleaseBundle.newBuilder()
            .setId(91L)
            .setVersionId(19L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v2")
            .setPublishWorkflowId(request.publishWorkflowId())
            .setManifestHash("sha256:" + "c".repeat(64))
            .setGenerationConfigRevision("generation-1")
            .setPublishedReleaseBundleRef("immutable-release-ref")
            .setCanonicalTenantId(TENANT.toString())
            .setCanonicalVersionId(VERSION.toString())
            .setManifestSchemaVersion(1);
    AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder()
        .forEach(
            owner -> {
              ParticipantDigest.Builder participant =
                  ParticipantDigest.newBuilder()
                      .setParticipantKey(owner)
                      .setScopeValue("19")
                      .setAppliedCommitId(request.appliedCommitId())
                      .setContentDigest(
                          "WORLD_MANAGEMENT".equals(owner)
                              ? request.contentDigest()
                              : "d".repeat(64))
                      .setDigestSchemaVersion(
                          AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                              owner,
                              AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION));
              if ("GAME_LOGIC".equals(owner)) {
                participant.setAbilitySchemaDigest("sha256:" + "e".repeat(64));
              }
              builder.addParticipantDigests(participant);
            });
    return builder.build();
  }

  private static WorldPublishedStartLocationEvidence worldEvidence() {
    try {
      DraftCommitBinding draft = freshGraphBinding();
      byte[] accountBytes = accountBinding(draft);
      WorldDraftTerminalReadEvidence.Request terminalRequest =
          WorldDraftTerminalReadEvidence.Request.create(NAMESPACE, accountBytes);
      byte[] appliedBytes = appliedReadback(terminalRequest, draft);
      String graphDigest = graphDigest(terminalRequest, draft);
      WorldDraftStartLocationEvidence receipt =
          WorldDraftStartLocationEvidence.create(
              NAMESPACE,
              WORLD_OPERATION,
              WORLD_REQUEST,
              COMMIT,
              WORLD_FENCE,
              sha256(accountBytes),
              draft.digest(),
              new RoomTemplateRef(TENANT, VERSION, ROOM),
              graphDigest);
      var tuples =
          draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
              .map(
                  unit ->
                      new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                          unit.owner().name(),
                          unit.aggregateType(),
                          unit.aggregateId(),
                          unit.scopeType(),
                          unit.scopeId(),
                          unit.expectedEpoch()))
              .toList();
      var selectorRequest =
          new WorldPublishedStartLocationEvidence.Request(
              NAMESPACE,
              TENANT,
              VERSION,
              INTAKE_REQUEST,
              WORLD_FENCE,
              "publication-request",
              "a".repeat(64),
              5L,
              "publish-workflow",
              COMMIT.toString(),
              "b".repeat(64),
              3,
              tuples);
      return new WorldPublishedStartLocationEvidence(
          selectorRequest, receipt.canonicalBytes(), accountBytes, appliedBytes);
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static DraftCommitBinding freshGraphBinding() throws IOException {
    String declaration =
        JSON.writeValueAsString(
            orderedMap(
                "tenantId",
                TENANT.toString(),
                "versionId",
                VERSION.toString(),
                "startLocation",
                orderedMap(
                    "tenantId", TENANT.toString(),
                    "versionId", VERSION.toString(),
                    "roomTemplateId", ROOM.toString()),
                "familyCounts",
                List.of(
                    orderedMap("family", "WORLD_DESIGN_AGGREGATE_TYPE_REGION", "count", 1),
                    orderedMap("family", "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", "count", 1),
                    orderedMap("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", "count", 1),
                    orderedMap("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT", "count", 0),
                    orderedMap("family", "WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE", "count", 0),
                    orderedMap(
                        "family",
                        "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING",
                        "count",
                        0))));
    return DraftCommitBinding.create(
        new TargetProof(TENANT, VERSION, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
        WORLD_REQUEST,
        COMMIT,
        "base-1",
        List.of(
            new RevisionPayload(
                "0",
                REGION_REVISION,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevision(
                    REGION_REVISION, "WORLD_DESIGN_AGGREGATE_TYPE_REGION", REGION, declaration)),
            new RevisionPayload(
                "1",
                ZONE_REVISION,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevision(ZONE_REVISION, "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", ZONE, null)),
            new RevisionPayload(
                "2",
                ROOM_REVISION,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevision(ROOM_REVISION, "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", ROOM, null))),
        List.of(
                affected("REGION", REGION, "REGION_SUBTREE", REGION),
                affected("ZONE", ZONE, "REGION_SUBTREE", REGION),
                affected("ROOM", ROOM, "ZONE_SUBTREE", ZONE))
            .stream()
            .flatMap(List::stream)
            .toList());
  }

  private static byte[] accountBinding(DraftCommitBinding draft) {
    return new DraftAuthorizationFenceBinding(
            WORLD_OPERATION,
            WORLD_REQUEST,
            COMMIT,
            WORLD_FENCE,
            ACTOR,
            TENANT,
            VERSION,
            "base-1",
            "0",
            draft.canonicalBytes(),
            draft.canonicalBytes(),
            draft.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc").toString(),
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {4, 5})))
        .canonicalBytes();
  }

  private static byte[] appliedReadback(
      WorldDraftTerminalReadEvidence.Request terminalRequest, DraftCommitBinding draft)
      throws IOException {
    byte[] operation = operationBytes(terminalRequest, draft);
    byte[] graph = graphBytes(terminalRequest, draft);
    String graphDigest = sha256(graph);
    WorldDraftStartLocationEvidence receipt =
        WorldDraftStartLocationEvidence.create(
            NAMESPACE,
            WORLD_OPERATION,
            WORLD_REQUEST,
            COMMIT,
            WORLD_FENCE,
            sha256(terminalRequest.originalAccountBinding()),
            draft.digest(),
            new RoomTemplateRef(TENANT, VERSION, ROOM),
            graphDigest);
    Map<String, Object> applied = new LinkedHashMap<>();
    applied.put("schema", "world-draft-graph-applied/v2");
    applied.put("status", "APPLIED");
    applied.put("operationBytesBase64", Base64.getEncoder().encodeToString(operation));
    applied.put("graphBytesBase64", Base64.getEncoder().encodeToString(graph));
    applied.put("graphDigest", graphDigest);
    applied.put(
        "startLocationReceiptBase64", Base64.getEncoder().encodeToString(receipt.canonicalBytes()));
    applied.put("startLocationReceiptDigest", receipt.receiptDigest());
    applied.put(
        "appliedEpochs",
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    Map.of(
                        "aggregateType", unit.aggregateType(),
                        "aggregateId", unit.aggregateId(),
                        "scopeType", unit.scopeType(),
                        "scopeId", unit.scopeId(),
                        "expectedEpoch", unit.expectedEpoch(),
                        "resultingEpoch", "1"))
            .toList());
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(applied));
  }

  private static byte[] operationBytes(
      WorldDraftTerminalReadEvidence.Request terminalRequest, DraftCommitBinding draft)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    DataOutputStream frames = new DataOutputStream(output);
    frame(frames, "world-draft-terminal-operation/v1");
    for (UUID id : List.of(WORLD_OPERATION, WORLD_REQUEST, COMMIT, WORLD_FENCE, TENANT, VERSION)) {
      frame(frames, id.toString());
    }
    frame(frames, draft.canonicalBytes());
    for (String value :
        List.of(
            NAMESPACE,
            TENANT.toString(),
            VERSION.toString(),
            WORLD_OPERATION.toString(),
            "19",
            INTAKE_REQUEST.toString(),
            INTAKE_OPERATION.toString(),
            "a".repeat(64),
            SOURCE_OPERATION.toString(),
            "b".repeat(64),
            "c".repeat(64))) {
      frame(frames, value);
    }
    byte[] account = terminalRequest.originalAccountBinding();
    frame(frames, sha256(account));
    frame(frames, account);
    return output.toByteArray();
  }

  private static byte[] graphBytes(
      WorldDraftTerminalReadEvidence.Request terminalRequest, DraftCommitBinding draft)
      throws IOException {
    Map<String, Object> graph = new LinkedHashMap<>();
    graph.put("schemaVersion", "2");
    graph.put("canonicalTenantId", TENANT.toString());
    graph.put("canonicalVersionId", VERSION.toString());
    List<Map<String, Object>> rows = new ArrayList<>();
    int mappingId = 1;
    long privateRowKey = 101L;
    for (RevisionPayload revision : draft.revisions()) {
      String family =
          revision.payload().contains("WORLD_DESIGN_AGGREGATE_TYPE_REGION")
              ? "REGION"
              : revision.payload().contains("WORLD_DESIGN_AGGREGATE_TYPE_ZONE") ? "ZONE" : "ROOM";
      UUID templateId = "REGION".equals(family) ? REGION : "ZONE".equals(family) ? ZONE : ROOM;
      Map<String, Object> mapping = new LinkedHashMap<>();
      mapping.put("id", mappingId++);
      mapping.put("target_namespace", terminalRequest.targetNamespace());
      mapping.put("canonical_tenant_id", TENANT.toString());
      mapping.put("canonical_version_id", VERSION.toString());
      mapping.put("family", family);
      mapping.put("template_id", templateId.toString());
      mapping.put("private_row_key", privateRowKey++);
      mapping.put("tenant_id", 11);
      mapping.put("version_id", 19);
      mapping.put("version_identity_operation_id", WORLD_OPERATION.toString());
      mapping.put("request_id", draft.requestId().toString());
      mapping.put("commit_id", draft.commitId().toString());
      mapping.put("revision_id", revision.revisionId().toString());
      mapping.put("revision_order", revision.revisionOrder());
      rows.add(Map.of("mapping", mapping, "content", Map.of()));
    }
    graph.put("rows", rows);
    return JSON.writeValueAsBytes(graph);
  }

  private static String graphDigest(
      WorldDraftTerminalReadEvidence.Request terminalRequest, DraftCommitBinding draft)
      throws IOException {
    return sha256(graphBytes(terminalRequest, draft));
  }

  private static String worldRevision(
      UUID revisionId, String family, UUID templateId, String declaration) throws IOException {
    Map<String, Object> mutation = new LinkedHashMap<>();
    mutation.put("logicalRevisionId", revisionId.toString());
    mutation.put("commitId", COMMIT.toString());
    mutation.put("aggregateType", family);
    mutation.put("aggregateId", templateId.toString());
    if (declaration != null) mutation.put("freshGraphDeclaration", JSON.readTree(declaration));
    return JSON.writeValueAsString(mutation);
  }

  private static List<AffectedUnit> affected(
      String family, UUID templateId, String scopeType, UUID scopeId) {
    return List.of(
        new AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "WORLD_DESIGN_AGGREGATE_TYPE_" + family,
            templateId.toString(),
            "AGGREGATE",
            templateId.toString(),
            "0"),
        new AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "WORLD_DESIGN_AGGREGATE_TYPE_" + family,
            templateId.toString(),
            scopeType,
            scopeId.toString(),
            "0"));
  }

  static StartSessionPostAuthorizationExecutionTuple tuple(String requestId) {
    return tuple(requestId, "pin one normalized template association");
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(
      String requestId, String auditReason) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
            new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            auditReason);
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(requestId, ACTOR, action);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        WORKLOAD,
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    Map<String, Object> projection =
        Map.of(
            "sourceType",
            "ACCOUNT",
            "sourceEvidenceId",
            "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion",
            "17",
            "projectionStatus",
            "CURRENT",
            "evaluatedAt",
            AUTHORITY_EVALUATED_AT.toString(),
            "expiresAt",
            AUTHORITY_EXPIRES_AT.toString());
    Map<String, Object> identity =
        Map.of(
            "issuanceOperationId", ISSUANCE_ID.toString(),
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(TENANT.toString(), 3L),
            "membershipAuthorityGeneration", Map.of(TENANT.toString(), 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> evidence =
        Map.of(
            "evidenceType",
            StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId",
            ACTOR.toString(),
            "controlUiTokenJti",
            TOKEN_JTI.toString(),
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", TENANT.toString(), "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", TENANT.toString()),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            identity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(TENANT.toString(), 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static AccountRedemptionProjection projection(StartSessionPostAuthorizationExecutionTuple tuple) {
    return new AccountRedemptionProjection(
        tuple.authorizationReferenceFingerprint(),
        tuple.authorityEvidenceBundleBytes(),
        ISSUANCE_ID,
        23L);
  }

  private static Map<String, Object> orderedMap(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    return result;
  }

  private static void frame(DataOutputStream frames, String value) throws IOException {
    frame(frames, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void frame(DataOutputStream frames, byte[] value) throws IOException {
    frames.writeInt(value.length);
    frames.write(value);
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Fixture fixture() {
    String schema = "gs_template_association_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var attempts =
        new GameSessionStartSessionOperatorAttemptRepository(dsl, Duration.ofSeconds(30));
    return new Fixture(
        dsl,
        transactions,
        attempts,
        new GameSessionStartSessionTemplateAssociationRepository(dsl, attempts));
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transactions,
      GameSessionStartSessionOperatorAttemptRepository attempts,
      GameSessionStartSessionTemplateAssociationRepository pins) {
    ReservationResult reserve(StartSessionPostAuthorizationExecutionTuple tuple) {
      return Objects.requireNonNull(
          transactions.execute(status -> attempts.reserve(tuple)), "owner attempt reservation");
    }

    AttemptClaim reserveAndAttach(StartSessionPostAuthorizationExecutionTuple tuple) {
      ReservationResult reservation = reserve(tuple);
      AttemptClaim claim = reservation.claim().orElseThrow();
      transactions.executeWithoutResult(
          status -> attempts.attachAccountRedemptionProjection(claim, projection(tuple)));
      return claim;
    }

    PinnedAssociationSnapshot pin(AttemptClaim claim, Result result) {
      return Objects.requireNonNull(
          transactions.execute(status -> pins.pinInitialOrValidateExactReplay(claim, result)),
          "pinned template association");
    }

    java.util.Optional<PinnedAssociationSnapshot> find(AttemptClaim claim) {
      return Objects.requireNonNull(
          transactions.execute(status -> pins.findPinned(claim)), "template association read");
    }

    int pinCount() {
      return dsl.fetchCount(
          DSL.table(DSL.name("game_session_start_session_template_association_pin")));
    }
  }

  private record PinOutcome(boolean pinned, PinnedAssociationSnapshot snapshot) {}
}
