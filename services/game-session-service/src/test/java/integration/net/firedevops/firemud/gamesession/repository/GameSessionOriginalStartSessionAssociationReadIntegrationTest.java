package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository.LaunchAssociationOwnerReadMismatchException;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository.OriginalStartSessionAssociation;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository.StartingInstance;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationResult;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
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

/**
 * PostgreSQL proof that the World handoff is bound to the original retained StartSession owner.
 * Uses existing typed repository fixtures; it is not Account/World producer or through-commit
 * authority proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionOriginalStartSessionAssociationReadIntegrationTest {
  private static final UUID CANONICAL_VERSION =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void readsOnlyTheExactRetainedAssociationAndDescriptorForTheOriginalPendingAttempt() {
    StartSessionPostAuthorizationExecutionTuple tuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(
            "original-association-handoff-positive");
    Fixture fixture = fixture(tuple, Duration.ofSeconds(30));
    OwnerBackedStarting starting = fixture.startWithOwnerPins(tuple);

    OriginalStartSessionAssociation read =
        fixture.read(selector(starting.startingInstance().launchAssociation())).orElseThrow();

    assertThat(read.association()).isEqualTo(starting.startingInstance().launchAssociation());
    assertThat(read.postAuthorizationExecutionTuple().canonicalBytes())
        .containsExactly(tuple.canonicalBytes());
    assertThat(read.ownerAttemptId()).isEqualTo(starting.claim().ownerAttemptId());
    assertThat(read.ownerFence()).isEqualTo(starting.claim().ownerFence()).isPositive();
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    DSL.table(DSL.name("game_session_start_session_template_association_pin"))))
        .isEqualTo(1);
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    DSL.table(DSL.name("game_session_start_session_launch_descriptor_pin"))))
        .isEqualTo(1);
  }

  @Test
  void absentOriginalAttemptAndPinsDoNotExposeTheStartingAssociation() {
    StartSessionPostAuthorizationExecutionTuple tuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(
            "original-association-handoff-no-owner");
    Fixture fixture = fixture(tuple, Duration.ofSeconds(30));
    StartingInstance starting = fixture.startWithoutOwnerPins(tuple);

    assertThat(fixture.read(selector(starting.launchAssociation()))).isEmpty();
  }

  @Test
  void changedDescriptorSelectorFailsClosedAgainstTheRetainedOwnerRead() {
    StartSessionPostAuthorizationExecutionTuple tuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(
            "original-association-handoff-mismatch");
    Fixture fixture = fixture(tuple, Duration.ofSeconds(30));
    OwnerBackedStarting starting = fixture.startWithOwnerPins(tuple);
    var exact = selector(starting.startingInstance().launchAssociation());
    var changed =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            exact.readRequestId(),
            exact.targetNamespace(),
            exact.canonicalTenantId(),
            exact.worldSlug(),
            exact.gameInstanceUuid(),
            exact.controlPlaneRequestId(),
            exact.launchDescriptorId(),
            exact.expectedDescriptorRequestDigest(),
            "sha256:" + "c".repeat(64),
            exact.expectedReleaseAttestationEvidenceDigest());

    assertThatThrownBy(() -> fixture.read(changed))
        .isInstanceOf(LaunchAssociationOwnerReadMismatchException.class);
  }

  @Test
  void expiredOwnerLeaseHidesOtherwiseRetainedAssociationEvidence() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(
            "original-association-handoff-expired");
    Fixture fixture = fixture(tuple, Duration.ofSeconds(5));
    OwnerBackedStarting starting = fixture.startWithOwnerPins(tuple);
    Thread.sleep(5_250L);

    assertThat(fixture.read(selector(starting.startingInstance().launchAssociation()))).isEmpty();
  }

  @Test
  void historicalReadReturnsExactEvidenceAfterLeaseExpiryWithoutWritingOwnerState()
      throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(
            "historical-original-association-expired");
    Fixture fixture = fixture(tuple, Duration.ofSeconds(5));
    OwnerBackedStarting starting = fixture.startWithOwnerPins(tuple);
    HistoricalOriginalStartSessionOwnerEvidence.Request request = historicalRequest(starting);
    int attemptsBefore = count(fixture, "game_session_start_session_operator_attempt");
    int selectionPinsBefore = count(fixture, "game_session_start_session_template_association_pin");
    int descriptorPinsBefore = count(fixture, "game_session_start_session_launch_descriptor_pin");
    int associationsBefore = count(fixture, "game_session_canonical_instance_launch");
    Thread.sleep(5_250L);

    var evidence = fixture.historicalRead(request).orElseThrow();

    assertThat(evidence.originalTuple().canonicalBytes()).containsExactly(tuple.canonicalBytes());
    assertThat(evidence.ownerAttemptId()).isEqualTo(starting.claim().ownerAttemptId());
    assertThat(evidence.ownerFence()).isEqualTo(starting.claim().ownerFence()).isPositive();
    assertThat(evidence.accountRedemptionProjection())
        .containsExactly(
            GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.projection(tuple)
                .canonicalBytes());
    assertThat(evidence.firstSelection().request().selection())
        .isInstanceOf(InitialConfigured.class);
    assertThat(evidence.firstSelection().association().templateId())
        .isEqualTo(tuple.preAuthorizationTuple().action().target().gameTemplateId());
    assertThat(evidence.descriptorPin().outcome())
        .isInstanceOf(
            net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec
                .DescriptorOutcome.class);
    assertThat(evidence.launchAssociation().gameInstanceUuid())
        .isEqualTo(starting.startingInstance().gameInstanceUuid());
    assertThat(evidence.launchAssociation().launchBindingEvidence())
        .isEqualTo(starting.startingInstance().launchAssociation().launchBindingEvidence());
    assertThat(count(fixture, "game_session_start_session_operator_attempt"))
        .isEqualTo(attemptsBefore);
    assertThat(count(fixture, "game_session_start_session_template_association_pin"))
        .isEqualTo(selectionPinsBefore);
    assertThat(count(fixture, "game_session_start_session_launch_descriptor_pin"))
        .isEqualTo(descriptorPinsBefore);
    assertThat(count(fixture, "game_session_canonical_instance_launch"))
        .isEqualTo(associationsBefore);
  }

  @Test
  void historicalReadFailsClosedForWrongAttemptFenceTupleAssociationAndMissingProjectionOrPin() {
    StartSessionPostAuthorizationExecutionTuple tuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(
            "historical-original-association-mismatch");
    Fixture fixture = fixture(tuple, Duration.ofSeconds(30));
    OwnerBackedStarting starting = fixture.startWithOwnerPins(tuple);
    var exact = historicalRequest(starting);
    var selector = exact.associationSelector();

    assertThat(
            fixture.historicalRead(
                new HistoricalOriginalStartSessionOwnerEvidence.Request(
                    selector, UUID.randomUUID(), exact.expectedOwnerFence())))
        .isEmpty();
    assertThat(
            fixture.historicalRead(
                new HistoricalOriginalStartSessionOwnerEvidence.Request(
                    selector, exact.expectedOwnerAttemptId(), exact.expectedOwnerFence() + 1L)))
        .isEmpty();
    var wrongAssociation =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            selector.readRequestId(),
            selector.targetNamespace(),
            selector.canonicalTenantId(),
            selector.worldSlug(),
            UUID.randomUUID(),
            selector.controlPlaneRequestId(),
            selector.launchDescriptorId(),
            selector.expectedDescriptorRequestDigest(),
            selector.expectedDescriptorResultDigest(),
            selector.expectedReleaseAttestationEvidenceDigest());
    assertThat(
            fixture.historicalRead(
                new HistoricalOriginalStartSessionOwnerEvidence.Request(
                    wrongAssociation, exact.expectedOwnerAttemptId(), exact.expectedOwnerFence())))
        .isEmpty();

    disableAttemptEvidenceImmutability(fixture);
    try {
      fixture
          .dsl()
          .execute(
              "UPDATE game_session_start_session_operator_attempt "
                  + "SET post_authorization_execution_tuple = decode('0102', 'hex') "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              starting.startingInstance().launchAssociation().targetNamespace(),
              starting.startingInstance().launchAssociation().controlPlaneRequestId());
    } finally {
      enableAttemptEvidenceImmutability(fixture);
    }
    assertThat(fixture.historicalRead(exact)).isEmpty();
  }

  @Test
  void historicalReadDeniesMissingAndCorruptedAttachedAccountProjection() {
    StartSessionPostAuthorizationExecutionTuple tuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(
            "historical-original-association-projection");
    Fixture missing = fixture(tuple, Duration.ofSeconds(30));
    OwnerBackedStarting missingStarting = missing.startWithOwnerPins(tuple);
    var missingRequest = historicalRequest(missingStarting);
    disableAttemptEvidenceImmutability(missing);
    try {
      missing
          .dsl()
          .execute(
              "UPDATE game_session_start_session_operator_attempt "
                  + "SET account_redemption_projection = NULL "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              missingStarting.startingInstance().launchAssociation().targetNamespace(),
              missingStarting.startingInstance().launchAssociation().controlPlaneRequestId());
    } finally {
      enableAttemptEvidenceImmutability(missing);
    }
    assertThat(missing.historicalRead(missingRequest)).isEmpty();

    Fixture corrupt = fixture(tuple, Duration.ofSeconds(30));
    OwnerBackedStarting corruptStarting = corrupt.startWithOwnerPins(tuple);
    var corruptRequest = historicalRequest(corruptStarting);
    disableAttemptEvidenceImmutability(corrupt);
    try {
      corrupt
          .dsl()
          .execute(
              "UPDATE game_session_start_session_operator_attempt "
                  + "SET account_redemption_projection = decode('00', 'hex') "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              corruptStarting.startingInstance().launchAssociation().targetNamespace(),
              corruptStarting.startingInstance().launchAssociation().controlPlaneRequestId());
    } finally {
      enableAttemptEvidenceImmutability(corrupt);
    }
    assertThatThrownBy(() -> corrupt.historicalRead(corruptRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("historical original StartSession owner evidence is malformed");
  }

  @Test
  void historicalReadDeniesAnAbsentImmutableSelectionPin() {
    StartSessionPostAuthorizationExecutionTuple tuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(
            "historical-original-association-no-selection-pin");
    Fixture fixture = fixture(tuple, Duration.ofSeconds(30));
    OwnerBackedStarting starting = fixture.startWithOwnerAttemptWithoutPins(tuple);

    assertThat(fixture.historicalRead(historicalRequest(starting))).isEmpty();

    Fixture mismatched = fixture(tuple, Duration.ofSeconds(30));
    OwnerBackedStarting mismatchedStarting = mismatched.startWithOwnerPins(tuple);
    disableSelectionPinImmutability(mismatched);
    try {
      mismatched
          .dsl()
          .execute(
              "UPDATE game_session_start_session_template_association_pin "
                  + "SET association_request_digest = ? "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              "sha256:" + "c".repeat(64),
              mismatchedStarting.startingInstance().launchAssociation().targetNamespace(),
              mismatchedStarting.startingInstance().launchAssociation().controlPlaneRequestId());
    } finally {
      enableSelectionPinImmutability(mismatched);
    }
    assertThat(mismatched.historicalRead(historicalRequest(mismatchedStarting))).isEmpty();
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector(
      net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation association) {
    var binding = association.launchBindingEvidence();
    var descriptor = binding.descriptor();
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        UUID.randomUUID(),
        association.targetNamespace(),
        association.canonicalTenantId(),
        association.worldSlug(),
        association.gameInstanceUuid(),
        association.controlPlaneRequestId(),
        association.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        binding.releaseAttestation().evidenceDigest());
  }

  private static HistoricalOriginalStartSessionOwnerEvidence.Request historicalRequest(
      OwnerBackedStarting starting) {
    return new HistoricalOriginalStartSessionOwnerEvidence.Request(
        selector(starting.startingInstance().launchAssociation()),
        starting.claim().ownerAttemptId(),
        starting.claim().ownerFence());
  }

  private static int count(Fixture fixture, String tableName) {
    return fixture.dsl().fetchCount(DSL.table(DSL.name(tableName)));
  }

  private static void disableAttemptEvidenceImmutability(Fixture fixture) {
    fixture
        .dsl()
        .execute(
            "ALTER TABLE game_session_start_session_operator_attempt "
                + "DISABLE TRIGGER game_session_start_session_operator_attempt_immutable");
  }

  private static void enableAttemptEvidenceImmutability(Fixture fixture) {
    fixture
        .dsl()
        .execute(
            "ALTER TABLE game_session_start_session_operator_attempt "
                + "ENABLE TRIGGER game_session_start_session_operator_attempt_immutable");
  }

  private static void disableSelectionPinImmutability(Fixture fixture) {
    fixture
        .dsl()
        .execute(
            "ALTER TABLE game_session_start_session_template_association_pin "
                + "DISABLE TRIGGER gs_start_session_template_association_pin_immutable");
  }

  private static void enableSelectionPinImmutability(Fixture fixture) {
    fixture
        .dsl()
        .execute(
            "ALTER TABLE game_session_start_session_template_association_pin "
                + "ENABLE TRIGGER gs_start_session_template_association_pin_immutable");
  }

  private static CompleteLaunchBindingEvidence completeBinding(
      AuthoredWorldLaunchDescriptorEvidence descriptor, UUID canonicalVersionId) {
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        "original-association-commit",
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null))
            .toList();
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            canonicalVersionId,
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "original-association-publish",
            "original-association-commit",
            participants,
            "sha256:" + "e".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, release);
  }

  private static AuthoredWorldLaunchDescriptorEvidence unpinnedDescriptor(
      StartSessionPostAuthorizationExecutionTuple tuple, AuthoredWorldSourceEvidence source) {
    var action = tuple.preAuthorizationTuple().action();
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            action.scope().targetNamespace(),
            tuple.controlPlaneRequestId(),
            action.scope().tenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            action.target().gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            "{}");
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "unowned-start-session-descriptor",
        19L,
        false,
        null,
        "{}",
        "generation-1",
        5L,
        91L,
        "immutable-release-ref",
        false,
        null);
  }

  private static Fixture fixture(
      StartSessionPostAuthorizationExecutionTuple tuple, Duration ownerClaimLease) {
    String namespace = tuple.preAuthorizationTuple().action().scope().targetNamespace();
    UUID tenantId = tuple.preAuthorizationTuple().action().scope().tenantId();
    String schema = "gs_original_assoc_" + UUID.randomUUID().toString().replace("-", "");
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
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var attempts = new GameSessionStartSessionOperatorAttemptRepository(dsl, ownerClaimLease);
    var templateAssociations =
        new GameSessionStartSessionTemplateAssociationRepository(dsl, attempts);
    var descriptors =
        new GameSessionStartSessionLaunchDescriptorRepository(dsl, templateAssociations);
    var sourceRepository = new GameSessionAuthoredWorldSourceRepository(dsl);
    var tenantRepository = new GameSessionFreshTenantAssociationRepository(dsl);
    var catalogRepository = new GameSessionCanonicalRealmCatalogRepository(dsl);
    var startingRepository = new GameSessionCanonicalStartingInstanceRepository(dsl, namespace);
    var source = GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.source();
    if (!source.targetNamespace().equals(namespace)
        || !source.canonicalTenantId().equals(tenantId)) {
      throw new IllegalStateException("Shared StartSession source fixture differs from its tuple");
    }
    var sourceReceipt =
        Objects.requireNonNull(
            transactions.execute(status -> sourceRepository.register(UUID.randomUUID(), source)),
            "registered fresh source receipt");
    var createCatalog =
        new CreateCanonicalRealmCatalogRequest(
            UUID.randomUUID(),
            namespace,
            tenantId,
            sourceReceipt.operationId(),
            source.worldSlug(),
            source.worldDisplayName(),
            true,
            true,
            "SHARED",
            "explicit-policy-v1",
            null,
            null);
    var preparedCatalog = catalogRepository.prepareInitialPublicProduction(createCatalog);
    transactions.executeWithoutResult(
        status -> catalogRepository.createInitialPublicProduction(preparedCatalog));
    FreshGameSessionTenantAssociation tenantAssociation =
        Objects.requireNonNull(
            transactions.execute(
                status ->
                    tenantRepository.associateFresh(UUID.randomUUID(), namespace, sourceReceipt)),
            "fresh tenant association");
    return new Fixture(
        namespace,
        tenantId,
        dsl,
        transactions,
        attempts,
        templateAssociations,
        descriptors,
        startingRepository,
        new CanonicalGameInstanceLaunchAssociationRepository(dsl, namespace),
        source,
        tenantAssociation);
  }

  private record Fixture(
      String namespace,
      UUID tenantId,
      DSLContext dsl,
      TransactionTemplate transactions,
      GameSessionStartSessionOperatorAttemptRepository attempts,
      GameSessionStartSessionTemplateAssociationRepository templateAssociations,
      GameSessionStartSessionLaunchDescriptorRepository descriptors,
      GameSessionCanonicalStartingInstanceRepository starting,
      CanonicalGameInstanceLaunchAssociationRepository reads,
      AuthoredWorldSourceEvidence source,
      FreshGameSessionTenantAssociation tenantAssociation) {
    OwnerBackedStarting startWithOwnerPins(StartSessionPostAuthorizationExecutionTuple tuple) {
      ReservationResult reservation =
          Objects.requireNonNull(transactions.execute(status -> attempts.reserve(tuple)));
      AttemptClaim claim = reservation.claim().orElseThrow();
      var projection =
          GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.projection(tuple);
      transactions.executeWithoutResult(
          status -> attempts.attachAccountRedemptionProjection(claim, projection));
      var initial =
          GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.result(
              tuple, claim, new InitialConfigured(), UUID.randomUUID());
      PinnedAssociationSnapshot pinnedAssociation =
          Objects.requireNonNull(
              transactions.execute(
                  status -> templateAssociations.pinInitialOrValidateExactReplay(claim, initial)));
      var resolved =
          GameSessionStartSessionLaunchDescriptorRepositoryIntegrationTest.descriptorFor(
              pinnedAssociation.result(), "original-start-session-descriptor");
      transactions.executeWithoutResult(status -> descriptors.pin(claim, resolved));
      var descriptor =
          ((net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec
                      .DescriptorOutcome)
                  resolved.outcome())
              .descriptor();
      var binding =
          completeBinding(
              descriptor, pinnedAssociation.result().association().canonicalVersionId());
      StartingInstance startingInstance =
          Objects.requireNonNull(
              transactions.execute(
                  status ->
                      starting.createStarting(
                          tenantAssociation,
                          binding,
                          tuple.preAuthorizationTuple().action().target().ownerAccountId(),
                          tuple.controlPlaneRequestId(),
                          "f".repeat(64))));
      return new OwnerBackedStarting(startingInstance, claim);
    }

    OwnerBackedStarting startWithOwnerAttemptWithoutPins(
        StartSessionPostAuthorizationExecutionTuple tuple) {
      ReservationResult reservation =
          Objects.requireNonNull(transactions.execute(status -> attempts.reserve(tuple)));
      AttemptClaim claim = reservation.claim().orElseThrow();
      transactions.executeWithoutResult(
          status ->
              attempts.attachAccountRedemptionProjection(
                  claim,
                  GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.projection(
                      tuple)));
      var descriptor = unpinnedDescriptor(tuple, source);
      var binding = completeBinding(descriptor, CANONICAL_VERSION);
      StartingInstance startingInstance =
          Objects.requireNonNull(
              transactions.execute(
                  status ->
                      starting.createStarting(
                          tenantAssociation,
                          binding,
                          tuple.preAuthorizationTuple().action().target().ownerAccountId(),
                          tuple.controlPlaneRequestId(),
                          "f".repeat(64))));
      return new OwnerBackedStarting(startingInstance, claim);
    }

    StartingInstance startWithoutOwnerPins(StartSessionPostAuthorizationExecutionTuple tuple) {
      var descriptor = unpinnedDescriptor(tuple, source);
      var binding = completeBinding(descriptor, CANONICAL_VERSION);
      return Objects.requireNonNull(
          transactions.execute(
              status ->
                  starting.createStarting(
                      tenantAssociation,
                      binding,
                      tuple.preAuthorizationTuple().action().target().ownerAccountId(),
                      tuple.controlPlaneRequestId(),
                      "f".repeat(64))));
    }

    java.util.Optional<OriginalStartSessionAssociation> read(
        CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector) {
      return reads.readOriginalStartSessionAssociation(selector);
    }

    java.util.Optional<HistoricalOriginalStartSessionOwnerEvidence.Result> historicalRead(
        HistoricalOriginalStartSessionOwnerEvidence.Request request) {
      return reads.readHistoricalOriginalStartSessionOwnerEvidence(request);
    }
  }

  private record OwnerBackedStarting(StartingInstance startingInstance, AttemptClaim claim) {}
}
