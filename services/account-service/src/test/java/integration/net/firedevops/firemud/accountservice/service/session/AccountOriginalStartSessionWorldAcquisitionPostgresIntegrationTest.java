package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService.AcquisitionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** Physical Account issuer-to-World-acquisition proof; Game Session evidence stays stipulated. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountOriginalStartSessionWorldAcquisitionPostgresIntegrationTest {
  private static final String NAMESPACE = "original-world-acquisition-proof";
  private static final String WORLD_PEER_URI =
      "spiffe://firemud/ns/original-world-acquisition-proof/sa/world-management-service";
  private static final long GAME_TEMPLATE_ID = 42L;
  private static final Network NETWORK = Network.newNetwork();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("original-world-acquisition-primary")
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
              "original-world-acquisition-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void realOriginalIssueRedemptionAndAcquisitionRetainImmutableObservationAndSources()
      throws Exception {
    try (var controlUi =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      prepareOriginalCreator(controlUi);
      var proof =
          new AccountOriginalStartSessionAuthorizationProofFixture(controlUi, temporary, NAMESPACE);
      UUID accountId = proof.creatorAccountId();
      UUID tenantId = controlUi.tenantId();
      String controlPlaneRequestId = "original-world-acquisition-" + UUID.randomUUID();
      UUID reservationOwnerId = UUID.randomUUID();
      UUID gameSessionAttemptId = UUID.randomUUID();
      UUID gameSessionMutationId = UUID.randomUUID();
      UUID gameInstanceId = UUID.randomUUID();
      long reservationClaimFence = 31L;
      long gameSessionOwnerFence = 47L;

      var original =
          proof.issueAndRedeemOriginal(
              controlPlaneRequestId,
              new StartSessionOperatorAction(
                  StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                  StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                  new StartSessionOperatorAction.Scope(tenantId, NAMESPACE),
                  new StartSessionOperatorAction.Target(GAME_TEMPLATE_ID, accountId),
                  StartSessionOperatorAction.ExpectedVersion.ABSENT,
                  new StartSessionOperatorAction.Mutation(
                      StartSessionOperatorAction.ClientIp.absent()),
                  "real Account original World acquisition integration proof"),
              reservationOwnerId,
              reservationClaimFence,
              gameSessionAttemptId,
              gameSessionOwnerFence);
      assertThat(original.originalTuple().preAuthorizationTuple().targetOwner())
          .isEqualTo("game-session-service");

      Instant stipulatedGameSessionLeaseExpiry =
          original.accountReferenceExpiresAt().minusSeconds(30);
      assertThat(stipulatedGameSessionLeaseExpiry).isAfter(Instant.now());
      byte[] redemptionProjection =
          StartSessionAccountRedemptionProjection.fromOriginalTuple(original.originalTuple());
      List<Request> currentAttemptReads = new ArrayList<>();
      OriginalStartSessionCurrentAttemptClient currentAttemptClient =
          request -> {
            currentAttemptReads.add(request);
            return new Result(request, stipulatedGameSessionLeaseExpiry, redemptionProjection);
          };
      var acquisitionService = proof.worldParticipationAcquisitionService(currentAttemptClient);
      String preparationInput =
          preparationInput(
              NAMESPACE, tenantId, controlPlaneRequestId, gameInstanceId, GAME_TEMPLATE_ID);
      var acquisitionRequest =
          new AcquisitionRequest(
              original.originalTuple().canonicalBytes(),
              gameSessionMutationId,
              gameSessionAttemptId,
              gameSessionOwnerFence,
              gameInstanceId,
              preparationInput,
              digest(preparationInput));

      var first = withWorldPeer(() -> acquisitionService.acquire(acquisitionRequest));
      StoredParticipation firstParticipation = first.participation();
      StoredEvidence firstObservation = first.originalAttemptEvidence();
      assertThat(firstParticipation.participationId()).isNotEqualTo(new UUID(0L, 0L));
      assertThat(firstParticipation.participationFence()).isPositive();
      assertThat(firstParticipation.originalPostAuthorizationTuple())
          .containsExactly(original.originalTuple().canonicalBytes());
      assertThat(firstParticipation.gameSessionOwnerAttemptId()).isEqualTo(gameSessionAttemptId);
      assertThat(firstParticipation.gameSessionOwnerFence()).isEqualTo(gameSessionOwnerFence);
      assertThat(firstParticipation.sources()).isNotEmpty();
      assertThat(firstObservation.gameSessionOwnerMutationId()).isEqualTo(gameSessionMutationId);
      assertThat(firstObservation.originalLeaseExpiresAt())
          .isEqualTo(stipulatedGameSessionLeaseExpiry);
      assertThat(firstObservation.originalResponseBytes())
          .containsExactly(
              OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
                      new Result(
                          currentAttemptReads.getFirst(),
                          stipulatedGameSessionLeaseExpiry,
                          redemptionProjection))
                  .toByteArray());

      DSLContext dsl = proof.sourceDsl();
      assertPersistedAcquisition(
          dsl, original.originalTuple(), firstParticipation, firstObservation);

      // The stipulated Game Session observation is repeated with a newly generated correlation.
      // Account's exact-operation retry must preserve the first bytes and participation fence.
      var exactRetry = withWorldPeer(() -> acquisitionService.acquire(acquisitionRequest));
      assertThat(currentAttemptReads).hasSize(2);
      assertThat(currentAttemptReads.get(0).readRequestId())
          .isNotEqualTo(currentAttemptReads.get(1).readRequestId());
      assertThat(exactRetry.participation().participationId())
          .isEqualTo(firstParticipation.participationId());
      assertThat(exactRetry.participation().participationFence())
          .isEqualTo(firstParticipation.participationFence());
      assertThat(exactRetry.originalAttemptEvidence().originalResponseBytes())
          .containsExactly(firstObservation.originalResponseBytes());
      assertThat(exactRetry.originalAttemptEvidence().result().request().readRequestId())
          .isEqualTo(currentAttemptReads.get(0).readRequestId());
      assertPersistedAcquisition(
          dsl, original.originalTuple(), firstParticipation, firstObservation);

      int retainedParticipationCount =
          dsl.fetchCount(DSL.table("account_start_session_world_participations"));
      int retainedSourceCount =
          dsl.fetchCount(DSL.table("account_start_session_world_participation_sources"));
      int retainedObservationCount =
          dsl.fetchCount(DSL.table("account_start_session_world_original_attempt_evidence"));

      var changedTuple =
          StartSessionPostAuthorizationExecutionTuple.createHuman(
              original.originalTuple().preAuthorizationTuple(),
              original.originalTuple().authenticatedWorkloadIdentity(),
              "arfp/v1/changed-proof/" + "c".repeat(64),
              original.originalTuple().reservationOwnerId(),
              original.originalTuple().reservationClaimFence(),
              original.originalTuple().authorityEvidenceBundleBytes(),
              original.originalTuple().bundleReference());
      var changedTupleRequest =
          new AcquisitionRequest(
              changedTuple.canonicalBytes(),
              gameSessionMutationId,
              gameSessionAttemptId,
              gameSessionOwnerFence,
              gameInstanceId,
              preparationInput,
              digest(preparationInput));
      assertFailedPrecondition(
          () -> withWorldPeer(() -> acquisitionService.acquire(changedTupleRequest)));

      var changedOwnerFenceRequest =
          new AcquisitionRequest(
              original.originalTuple().canonicalBytes(),
              gameSessionMutationId,
              gameSessionAttemptId,
              gameSessionOwnerFence + 1,
              gameInstanceId,
              preparationInput,
              digest(preparationInput));
      assertFailedPrecondition(
          () -> withWorldPeer(() -> acquisitionService.acquire(changedOwnerFenceRequest)));
      assertThat(dsl.fetchCount(DSL.table("account_start_session_world_participations")))
          .isEqualTo(retainedParticipationCount);
      assertThat(dsl.fetchCount(DSL.table("account_start_session_world_participation_sources")))
          .isEqualTo(retainedSourceCount);
      assertThat(dsl.fetchCount(DSL.table("account_start_session_world_original_attempt_evidence")))
          .isEqualTo(retainedObservationCount);

      var sourceFixture = controlUi.preparedOriginalCreator().sources();
      String originalRole =
          dsl.fetchSingle("SELECT role FROM accounts WHERE account_uuid = ?", accountId)
              .get(0, String.class);
      assertThatThrownBy(sourceFixture::changeRoleToAdmin)
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining(
              "StartSession World participation remains pending for a required source");
      assertThat(
              dsl.fetchSingle("SELECT role FROM accounts WHERE account_uuid = ?", accountId)
                  .get(0, String.class))
          .isEqualTo(originalRole);
    }
  }

  private static void assertPersistedAcquisition(
      DSLContext dsl,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      StoredParticipation participation,
      StoredEvidence observation) {
    assertThat(dsl.fetchCount(DSL.table("account_start_session_world_participations")))
        .isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("account_start_session_world_participation_sources")))
        .isEqualTo(participation.sources().size());
    assertThat(dsl.fetchCount(DSL.table("account_start_session_world_original_attempt_evidence")))
        .isEqualTo(1);

    Record participationRow =
        dsl.fetchOne(
            "SELECT * FROM account_start_session_world_participations WHERE participation_id = ?",
            participation.participationId());
    assertThat(participationRow).isNotNull();
    assertThat(participationRow.get("participation_fence", Long.class))
        .isEqualTo(participation.participationFence());
    assertThat(participationRow.get("original_post_authorization_tuple", byte[].class))
        .containsExactly(originalTuple.canonicalBytes());
    assertThat(participationRow.get("game_session_owner_attempt_id", UUID.class))
        .isEqualTo(participation.gameSessionOwnerAttemptId());
    assertThat(participationRow.get("game_session_owner_fence", Long.class))
        .isEqualTo(participation.gameSessionOwnerFence());
    assertThat(participationRow.get("preparation_input_json", String.class))
        .isEqualTo(participation.preparationInputJson());
    assertThat(participationRow.get("preparation_input_digest", String.class))
        .isEqualTo(participation.preparationInputDigest());

    var sourceRows =
        dsl.fetch(
            "SELECT source_key, source_evidence FROM account_start_session_world_participation_sources "
                + "WHERE participation_id = ? ORDER BY source_key",
            participation.participationId());
    var expectedSources =
        participation.sources().stream()
            .sorted(Comparator.comparing(source -> source.key()))
            .toList();
    assertThat(sourceRows).hasSize(expectedSources.size());
    for (int index = 0; index < expectedSources.size(); index++) {
      assertThat(sourceRows.get(index).get("source_key", String.class))
          .isEqualTo(expectedSources.get(index).key());
      assertThat(sourceRows.get(index).get("source_evidence", byte[].class))
          .containsExactly(expectedSources.get(index).canonicalBytes());
    }

    Record evidenceRow =
        dsl.fetchOne(
            "SELECT game_session_owner_mutation_id, game_session_owner_attempt_id, "
                + "game_session_owner_fence, original_lease_expires_at, original_response_bytes, "
                + "original_response_digest FROM account_start_session_world_original_attempt_evidence "
                + "WHERE participation_id = ?",
            participation.participationId());
    assertThat(evidenceRow).isNotNull();
    assertThat(evidenceRow.get("game_session_owner_mutation_id", UUID.class))
        .isEqualTo(observation.gameSessionOwnerMutationId());
    assertThat(evidenceRow.get("game_session_owner_attempt_id", UUID.class))
        .isEqualTo(participation.gameSessionOwnerAttemptId());
    assertThat(evidenceRow.get("game_session_owner_fence", Long.class))
        .isEqualTo(participation.gameSessionOwnerFence());
    assertThat(evidenceRow.get("original_lease_expires_at", OffsetDateTime.class))
        .isEqualTo(OffsetDateTime.ofInstant(observation.originalLeaseExpiresAt(), ZoneOffset.UTC));
    assertThat(evidenceRow.get("original_response_bytes", byte[].class))
        .containsExactly(observation.originalResponseBytes());
    assertThat(evidenceRow.get("original_response_digest", String.class))
        .isEqualTo(observation.originalResponseDigest());

    try {
      var decoded =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
              observation.result().request(),
              ReadOriginalStartSessionCurrentAttemptResponse.parseFrom(
                  evidenceRow.get("original_response_bytes", byte[].class)));
      assertThat(decoded.request().readRequestId())
          .isEqualTo(observation.result().request().readRequestId());
      assertThat(decoded.accountRedemptionProjection())
          .containsExactly(observation.result().accountRedemptionProjection());
      assertThat(decoded.originalLeaseExpiresAt()).isEqualTo(observation.originalLeaseExpiresAt());
    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
      throw new AssertionError(
          "Retained current-attempt response must be a complete wire value", malformed);
    }
  }

  private static void prepareOriginalCreator(AccountControlUiOriginalOrderFixture controlUi) {
    UUID versionId = UUID.randomUUID();
    String tenantKey = "world-acquisition-proof-tenant";
    var binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                controlUi.tenantId(), versionId, 17L, tenantKey, 23L, tenantKey, "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULES",
                    versionId.toString(),
                    "GAMEPLAY_RULES",
                    "all",
                    "0")));
    controlUi.prepareOriginalDraftOrder(binding, NAMESPACE);
  }

  private static String preparationInput(
      String namespace, UUID tenantId, String requestId, UUID gameInstanceId, long templateId)
      throws Exception {
    String descriptorJson = JSON.writeValueAsString(Map.of("gameTemplateId", templateId));
    return JSON.writeValueAsString(
        Map.of(
            "schemaVersion",
            1,
            "identity",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                "original-world-acquisition-proof"),
            "gameSessionReadRequest",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                "original-world-acquisition-proof"),
            "gameSessionReadEvidence",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                "original-world-acquisition-proof",
                "descriptorJson",
                descriptorJson),
            "launchBinding",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "worldSlug",
                "original-world-acquisition-proof")));
  }

  private static String digest(String value) throws Exception {
    return "sha256:"
        + HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static void assertFailedPrecondition(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
  }

  private static <T> T withWorldPeer(java.util.function.Supplier<T> action) {
    try (var ignored = AccountControlUiOwnerSourcesFixture.withPeer(WORLD_PEER_URI)) {
      return action.get();
    }
  }
}
