package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.client.GameDesignFreshTenantCreationClient;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.Candidate;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.StoredSource;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchProofFixtures.SyntheticLaunchProof;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.CanonicalRealmCatalog;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.FreshTenantAssociation;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
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
 * PostgreSQL proof of append-only exact authored-core retention, including a valid empty result.
 * Synthetic result bytes prove storage binding only, not authenticated Game Design transport.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionAuthoredActorCoreRetentionPostgresIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void retainsExactEmptyOwnerResultAndRejectsConflictOrMutation() throws Exception {
    try (Fixture fixture = newFixture()) {
      LaunchInputs launch = fixture.createLaunch();
      var attempt = fixture.createAttempt(launch.proof().originalTuple());
      var selector = exactReplayRequest(launch, attempt.claim());
      byte[] requestBytes =
          StartSessionTemplateAssociationReadGrpcCodec.toRequest(selector).toByteArray();
      byte[] sourceBytes = canonicalEmptyResult(requestBytes);
      Candidate candidate = candidate(launch, attempt, requestBytes, sourceBytes);

      StoredSource first =
          Objects.requireNonNull(
              fixture
                  .transactions()
                  .execute(
                      status -> fixture.retentionRepository().retainInOwnerTransaction(candidate)));
      StoredSource independent =
          fixture
              .retentionRepository()
              .readByOriginalOperation(
                  NAMESPACE, launch.proof().originalTuple().controlPlaneRequestId())
              .orElseThrow();

      assertThat(first.candidate().canonicalSourceResultBytes()).containsExactly(sourceBytes);
      assertThat(independent.candidate().canonicalSourceResultBytes()).containsExactly(sourceBytes);
      assertThat(independent.candidate().sourceResultDigest())
          .isEqualTo(candidate.sourceResultDigest());
      assertThat(independent.retainedAt()).isEqualTo(first.retainedAt());
      assertThat(new String(sourceBytes, java.nio.charset.StandardCharsets.UTF_8))
          .contains("\"projections\":[]");

      byte[] conflictingBytes = canonicalEmptyResult(requestBytes, "different exact result");
      Candidate conflict = candidate(launch, attempt, requestBytes, conflictingBytes);
      assertThatThrownBy(
              () ->
                  fixture
                      .transactions()
                      .execute(
                          status ->
                              fixture.retentionRepository().retainInOwnerTransaction(conflict)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("conflicts with its first exact result");
      assertThat(
              fixture
                  .retentionRepository()
                  .readByOriginalOperation(
                      NAMESPACE, launch.proof().originalTuple().controlPlaneRequestId())
                  .orElseThrow()
                  .candidate()
                  .canonicalSourceResultBytes())
          .containsExactly(sourceBytes);

      String where =
          " WHERE target_namespace = '"
              + NAMESPACE
              + "' AND control_plane_request_id = '"
              + launch.proof().originalTuple().controlPlaneRequestId()
              + "'";
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_original_start_session_authored_actor_core_retention"
                              + " SET source_result_digest = 'sha256:' || repeat('0', 64)"
                              + where))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "DELETE FROM game_session_original_start_session_authored_actor_core_retention"
                              + where))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "TRUNCATE game_session_original_start_session_authored_actor_core_retention"))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void originalSelectionPinIsAppendOnlyAndBoundToTheExactLiveAttemptAndLaunch() throws Exception {
    try (Fixture fixture = newFixture()) {
      LaunchInputs launch = fixture.createLaunch();
      AttemptInputs attempt = fixture.createAttempt(launch.proof().originalTuple());
      var tuple = launch.proof().originalTuple();
      byte[] selectionRequest = new byte[] {1};
      byte[] selectionResponse = new byte[] {2};
      byte[] descriptorRequest = new byte[] {3};
      byte[] descriptorResponse = new byte[] {4};
      String operationId = tuple.controlPlaneRequestId();
      var owner = attempt.snapshot();
      var attemptRepository =
          new GameSessionStartSessionOperatorAttemptRepository(
              fixture.dsl(), Duration.ofMinutes(5));
      assertThatThrownBy(
              () ->
                  attemptRepository.readHistoricalOwnerAttempt(
                      tuple, UUID.randomUUID(), owner.ownerFence()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () ->
                  attemptRepository.readHistoricalOwnerAttempt(
                      tuple, owner.ownerAttemptId(), owner.ownerFence() + 1))
          .isInstanceOf(IllegalStateException.class);
      var launchRepository =
          new GameSessionCanonicalInstanceLaunchRepository(
              fixture.dsl(),
              fixture.transactionManager(),
              NAMESPACE,
              new GameSessionCanonicalRealmCatalogRepository(
                  fixture.dsl(), fixture.transactionManager(), NAMESPACE));
      var exactSelector = historicalSelector(launch);
      var mismatchedSelector =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
              UUID.randomUUID(),
              exactSelector.targetNamespace(),
              exactSelector.canonicalTenantId(),
              "other-world",
              exactSelector.gameInstanceUuid(),
              exactSelector.controlPlaneRequestId(),
              exactSelector.launchDescriptorId(),
              exactSelector.expectedDescriptorRequestDigest(),
              exactSelector.expectedDescriptorResultDigest(),
              exactSelector.expectedReleaseAttestationEvidenceDigest());
      TransactionTemplate readSnapshot = new TransactionTemplate(fixture.transactionManager());
      readSnapshot.setReadOnly(true);
      readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      assertThatThrownBy(
              () ->
                  readSnapshot.execute(
                      status ->
                          launchRepository.readHistoricalAssociationInOwnerTransaction(
                              mismatchedSelector, tuple.canonicalBytes())))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () ->
                  insertSelectionPin(
                      fixture,
                      launch,
                      attempt,
                      UUID.randomUUID(),
                      selectionRequest,
                      selectionResponse,
                      descriptorRequest,
                      descriptorResponse))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  insertSelectionPin(
                      fixture,
                      launch,
                      attempt,
                      launch.catalog().canonicalTenantId(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      owner.ownerFence(),
                      selectionRequest,
                      selectionResponse,
                      descriptorRequest,
                      descriptorResponse))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  insertSelectionPin(
                      fixture,
                      launch,
                      attempt,
                      launch.catalog().canonicalTenantId(),
                      owner.ownerAttemptId(),
                      owner.ownerMutationId(),
                      owner.ownerFence() + 1,
                      selectionRequest,
                      selectionResponse,
                      descriptorRequest,
                      descriptorResponse))
          .isInstanceOf(RuntimeException.class);
      insertSelectionPin(
          fixture,
          launch,
          attempt,
          launch.catalog().canonicalTenantId(),
          selectionRequest,
          selectionResponse,
          descriptorRequest,
          descriptorResponse);

      var retained =
          fixture
              .dsl()
              .fetchOne(
                  "SELECT initial_selection_request_bytes, descriptor_pin_response_bytes"
                      + " FROM game_session_original_start_session_selection_pin"
                      + " WHERE target_namespace = ? AND control_plane_request_id = ?",
                  NAMESPACE,
                  operationId);
      assertThat(retained.get("initial_selection_request_bytes", byte[].class))
          .containsExactly(selectionRequest);
      assertThat(retained.get("descriptor_pin_response_bytes", byte[].class))
          .containsExactly(descriptorResponse);
      String where =
          " WHERE target_namespace = '"
              + NAMESPACE
              + "' AND control_plane_request_id = '"
              + operationId
              + "'";
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_original_start_session_selection_pin"
                              + " SET initial_selection_response_digest = 'sha256:' || repeat('0', 64)"
                              + where))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "DELETE FROM game_session_original_start_session_selection_pin" + where))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute("TRUNCATE game_session_original_start_session_selection_pin"))
          .isInstanceOf(RuntimeException.class);
    }

    try (Fixture fixture = newFixture()) {
      LaunchInputs launch = fixture.createLaunch();
      AttemptInputs expiringAttempt =
          fixture.createAttempt(launch.proof().originalTuple(), Duration.ofSeconds(2));
      Thread.sleep(Duration.ofMillis(2200).toMillis());
      assertThatThrownBy(
              () ->
                  insertSelectionPin(
                      fixture,
                      launch,
                      expiringAttempt,
                      launch.catalog().canonicalTenantId(),
                      new byte[] {11},
                      new byte[] {12},
                      new byte[] {13},
                      new byte[] {14}))
          .isInstanceOf(RuntimeException.class);
      var retainedCount =
          Objects.requireNonNull(
              fixture
                  .dsl()
                  .fetchOne(
                      "SELECT count(*) AS retained FROM game_session_original_start_session_selection_pin"),
              "expired-attempt selection-pin count query returned no row");
      assertThat(retainedCount.get("retained", Long.class)).isZero();
    }
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request historicalSelector(
      LaunchInputs launch) {
    var tuple = launch.proof().originalTuple();
    var descriptor = launch.proof().completeLaunchBindingEvidence().descriptor();
    var release = launch.proof().completeLaunchBindingEvidence().releaseAttestation();
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        UUID.randomUUID(),
        NAMESPACE,
        launch.catalog().canonicalTenantId(),
        launch.catalog().worldSlug(),
        launch.gameInstanceUuid(),
        tuple.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest());
  }

  private static void insertSelectionPin(
      Fixture fixture,
      LaunchInputs launch,
      AttemptInputs attempt,
      UUID canonicalTenantId,
      byte[] selectionRequest,
      byte[] selectionResponse,
      byte[] descriptorRequest,
      byte[] descriptorResponse) {
    var owner = attempt.snapshot();
    insertSelectionPin(
        fixture,
        launch,
        attempt,
        canonicalTenantId,
        owner.ownerAttemptId(),
        owner.ownerMutationId(),
        owner.ownerFence(),
        selectionRequest,
        selectionResponse,
        descriptorRequest,
        descriptorResponse);
  }

  private static void insertSelectionPin(
      Fixture fixture,
      LaunchInputs launch,
      AttemptInputs attempt,
      UUID canonicalTenantId,
      UUID ownerAttemptId,
      UUID ownerMutationId,
      long ownerFence,
      byte[] selectionRequest,
      byte[] selectionResponse,
      byte[] descriptorRequest,
      byte[] descriptorResponse) {
    var tuple = launch.proof().originalTuple();
    var owner = attempt.snapshot();
    fixture
        .dsl()
        .execute(
            "INSERT INTO game_session_original_start_session_selection_pin"
                + " (target_namespace, control_plane_request_id, canonical_tenant_id,"
                + " game_instance_uuid, launch_descriptor_id, owner_attempt_id, owner_mutation_id,"
                + " owner_fence, owner_lease_expires_at, original_post_authorization_tuple,"
                + " account_redemption_projection, initial_selection_request_bytes,"
                + " initial_selection_request_digest, initial_selection_response_bytes,"
                + " initial_selection_response_digest, descriptor_pin_request_bytes,"
                + " descriptor_pin_request_digest, descriptor_pin_response_bytes,"
                + " descriptor_pin_response_digest)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            NAMESPACE,
            tuple.controlPlaneRequestId(),
            canonicalTenantId,
            launch.gameInstanceUuid(),
            launch.proof().completeLaunchBindingEvidence().descriptor().launchDescriptorId(),
            ownerAttemptId,
            ownerMutationId,
            ownerFence,
            java.time.OffsetDateTime.ofInstant(owner.leaseExpiresAt(), java.time.ZoneOffset.UTC),
            tuple.canonicalBytes(),
            owner.accountRedemptionProjection(),
            selectionRequest,
            sha256(selectionRequest),
            selectionResponse,
            sha256(selectionResponse),
            descriptorRequest,
            sha256(descriptorRequest),
            descriptorResponse,
            sha256(descriptorResponse));
  }

  private static Candidate candidate(
      LaunchInputs launch, AttemptInputs attempt, byte[] requestBytes, byte[] sourceBytes) {
    var tuple = launch.proof().originalTuple();
    var binding = launch.proof().completeLaunchBindingEvidence();
    var release = binding.releaseAttestation();
    return new Candidate(
        NAMESPACE,
        tuple.controlPlaneRequestId(),
        launch.catalog().canonicalTenantId(),
        launch.gameInstanceUuid(),
        attempt.snapshot().ownerAttemptId(),
        attempt.snapshot().ownerMutationId(),
        attempt.snapshot().ownerFence(),
        attempt.snapshot().leaseExpiresAt(),
        tuple.canonicalBytes(),
        attempt.snapshot().accountRedemptionProjection(),
        OriginalStartSessionCurrentAttemptEvidence.projectionDigest(
            attempt.snapshot().accountRedemptionProjection()),
        release.canonicalVersionId(),
        UUID.fromString(release.commitId()),
        release.publishWorkflowId(),
        associationDigest(),
        release.authoredWorldSourceOperationId(),
        release.authoredWorldSourceEvidenceDigest(),
        release.worldSlug(),
        binding.descriptor().launchDescriptorId(),
        binding.descriptor().requestDigest(),
        binding.descriptor().resultDigest(),
        release.evidenceDigest(),
        requestBytes,
        sourceBytes,
        sha256(sourceBytes));
  }

  private static StartSessionTemplateAssociationReadEvidence.Request exactReplayRequest(
      LaunchInputs launch, AttemptClaim claim) {
    var tuple = launch.proof().originalTuple();
    var release = launch.proof().completeLaunchBindingEvidence().releaseAttestation();
    return new StartSessionTemplateAssociationReadEvidence.Request(
        StartSessionTemplateAssociationReadEvidence.SCHEMA_VERSION,
        NAMESPACE,
        UUID.randomUUID(),
        tuple.canonicalBytes(),
        claim.ownerAttemptId(),
        claim.ownerFence(),
        new StartSessionTemplateAssociationReadEvidence.ExactReplay(
            release.canonicalVersionId(),
            UUID.fromString(release.commitId()),
            release.publishWorkflowId(),
            associationDigest()));
  }

  private static byte[] canonicalEmptyResult(byte[] requestBytes) {
    return canonicalEmptyResult(requestBytes, "");
  }

  private static byte[] canonicalEmptyResult(byte[] requestBytes, String associationRead) {
    Map<String, Object> result =
        Map.of(
            "schema", "game-design-authored-initial-actor-core-read/v1",
            "request", Base64.getEncoder().encodeToString(requestBytes),
            "associationRead", Base64.getEncoder().encodeToString(associationRead.getBytes()),
            "launchDescriptor", Base64.getEncoder().encodeToString("descriptor".getBytes()),
            "projections", List.of());
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(result));
    } catch (IOException invalid) {
      throw new IllegalStateException("Cannot serialize empty source fixture", invalid);
    }
  }

  private static String associationDigest() {
    return "sha256:" + "1".repeat(64);
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static SyntheticLaunchProof launchProof(CanonicalRealmCatalog catalog) {
    String operationId = "retained-authored-core-" + UUID.randomUUID();
    var stipulation =
        GameSessionCanonicalInstanceLaunchProofFixtures.create(
            NAMESPACE,
            operationId,
            catalog.canonicalTenantId(),
            catalog.worldSlug(),
            catalog.sourceEvidence(),
            91L,
            13L,
            17L,
            "launch-" + UUID.randomUUID(),
            "generation-fixture",
            2L,
            "fixture-release/" + UUID.randomUUID());
    var descriptor = stipulation.completeLaunchBindingEvidence().descriptor();
    String commitId = UUID.randomUUID().toString();
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                participantKey ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participantKey,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        commitId,
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participantKey),
                        "GAME_LOGIC".equals(participantKey),
                        "GAME_LOGIC".equals(participantKey) ? "sha256:" + "d".repeat(64) : null))
            .toList();
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            catalog.canonicalTenantId(),
            UUID.randomUUID(),
            catalog.worldSlug(),
            catalog.sourceEvidence().operationId(),
            catalog.sourceEvidence().evidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "synthetic-publish-workflow",
            commitId,
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new SyntheticLaunchProof(
        stipulation.originalTuple(), new CompleteLaunchBindingEvidence(descriptor, release));
  }

  private static Fixture newFixture() {
    String schema = "gs_authored_core_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
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
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(schema, dataSource, dsl, new DataSourceTransactionManager(dataSource));
  }

  private record LaunchInputs(
      CanonicalRealmCatalog catalog, SyntheticLaunchProof proof, UUID gameInstanceUuid) {}

  private record AttemptInputs(
      AttemptClaim claim,
      GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot snapshot) {}

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      DataSourceTransactionManager transactionManager)
      implements AutoCloseable {
    TransactionTemplate transactions() {
      return new TransactionTemplate(transactionManager);
    }

    GameSessionAuthoredActorCoreRetentionRepository retentionRepository() {
      return new GameSessionAuthoredActorCoreRetentionRepository(
          dsl, transactionManager, NAMESPACE);
    }

    LaunchInputs createLaunch() {
      FreshTenantAssociation fresh = createFreshAssociation();
      var catalogProof = GameSessionCanonicalRealmCatalogProofFixtures.forFreshAssociation(fresh);
      CanonicalRealmCatalog catalog =
          new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, NAMESPACE)
              .establishFreshPublicRealm(
                  fresh,
                  catalogProof.source(),
                  catalogProof.publishedSet(),
                  UUID.randomUUID(),
                  UUID.randomUUID());
      SyntheticLaunchProof proof = launchProof(catalog);
      var launches =
          new GameSessionCanonicalInstanceLaunchRepository(
              dsl,
              transactionManager,
              NAMESPACE,
              new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, NAMESPACE));
      var created =
          launches.createStartingLaunch(
              proof.originalTuple(), proof.completeLaunchBindingEvidence(), catalog);
      return new LaunchInputs(catalog, proof, created.gameInstanceUuid());
    }

    AttemptInputs createAttempt(
        net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple tuple) {
      return createAttempt(tuple, Duration.ofMinutes(5));
    }

    AttemptInputs createAttempt(
        net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple tuple,
        Duration leaseDuration) {
      var repository = new GameSessionStartSessionOperatorAttemptRepository(dsl, leaseDuration);
      var claim =
          Objects.requireNonNull(
                  transactions().execute(status -> repository.reserve(tuple)),
                  "owner attempt reservation")
              .claim()
              .orElseThrow();
      StartSessionAuthorityEvidenceBundle bundle =
          StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
      AccountRedemptionProjection projection =
          new AccountRedemptionProjection(
              tuple.authorizationReferenceFingerprint(),
              tuple.authorityEvidenceBundleBytes(),
              bundle.issuanceOperationId(),
              Long.parseLong(bundle.issuanceFence()));
      var attached =
          Objects.requireNonNull(
              transactions()
                  .execute(
                      status -> repository.attachAccountRedemptionProjection(claim, projection)),
              "attached Account projection");
      return new AttemptInputs(claim, attached);
    }

    private FreshTenantAssociation createFreshAssociation() {
      UUID requestId = UUID.randomUUID();
      UUID canonicalTenantId = UUID.randomUUID();
      String sourceKey = "authored-core-owner-key-" + requestId.toString().substring(0, 8);
      long sourceRowId = Math.abs(requestId.getMostSignificantBits() % 1_000_000L) + 1L;
      UUID sourceOperationId = UUID.randomUUID();
      String requestDigest =
          GameTenantCreationDigest.requestDigest(
              NAMESPACE, requestId, sourceKey, "Retention fixture game", null);
      FreshTenantCreationEvidence evidence =
          new FreshTenantCreationEvidence(
              1,
              NAMESPACE,
              requestId,
              sourceOperationId,
              requestDigest,
              canonicalTenantId,
              sourceRowId,
              sourceKey,
              "NEW_GAME_ROW",
              GameTenantCreationDigest.evidenceDigest(
                  NAMESPACE,
                  requestId,
                  sourceOperationId,
                  requestDigest,
                  canonicalTenantId,
                  sourceRowId,
                  sourceKey,
                  "NEW_GAME_ROW"));
      GameDesignFreshTenantCreationClient client = mock(GameDesignFreshTenantCreationClient.class);
      when(client.resolveCreation(requestId, evidence.requestDigest())).thenReturn(evidence);
      return new GameSessionFreshTenantAssociationRepository(
              dsl, transactionManager, client, NAMESPACE)
          .associateFreshTenant(requestId, evidence.requestDigest());
    }

    @Override
    public void close() throws Exception {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      }
    }
  }
}
