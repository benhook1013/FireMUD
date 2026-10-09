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
  }

  private record OwnerBackedStarting(StartingInstance startingInstance, AttemptClaim claim) {}
}
