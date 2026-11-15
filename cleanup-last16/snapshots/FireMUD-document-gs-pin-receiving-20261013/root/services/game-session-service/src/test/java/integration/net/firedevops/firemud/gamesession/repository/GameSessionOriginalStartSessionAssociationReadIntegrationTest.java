package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import io.grpc.Server;
import io.grpc.ManagedChannel;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec;
import net.firedevops.firemud.gamesession.service.impl.HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService;
import net.firedevops.firemud.gamesession.v1.HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc;
import org.junit.jupiter.api.io.TempDir;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
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
import tools.jackson.databind.json.JsonMapper;

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
  private static final JsonMapper JSON = JsonMapper.builder().build();

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
  void qualifiedStartingRetryRetainsOriginalTupleAndDatabaseRejectsSubstitutionAndMutation() {
    var tuple = GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple("qualified-launch-retry");
    Fixture fixture = fixture(tuple, Duration.ofSeconds(30));
    var first = fixture.startWithOwnerPins(tuple);
    var binding = first.startingInstance().launchAssociation().launchBindingEvidence();
    var retry = fixture.transactions().execute(status -> fixture.starting().createOriginalStartSessionStarting(
        fixture.tenantAssociation(), binding, tuple.preAuthorizationTuple().action().target().ownerAccountId(), tuple.controlPlaneRequestId(), "f".repeat(64)));
    assertThat(retry).isEqualTo(first.startingInstance());
    assertThat(fixture.dsl().fetchOne("SELECT original_start_session_tuple FROM game_session_canonical_instance_launch").get("original_start_session_tuple", byte[].class))
        .containsExactly(tuple.canonicalBytes());
    String before = ownerRows(fixture);
    assertThatThrownBy(() -> fixture.dsl().execute(
        "INSERT INTO game_session_canonical_instance_launch SELECT "
            + "(jsonb_populate_record(NULL::game_session_canonical_instance_launch, "
            + "to_jsonb(retained) || jsonb_build_object('original_start_session_tuple', '\\x0102'))).* "
            + "FROM game_session_canonical_instance_launch retained"))
        .isInstanceOf(RuntimeException.class).hasMessageContaining("chk_gs_qualified_original_launch_binding");
    for (String table : List.of("game_session_canonical_instance_launch",
        "game_session_start_session_template_association_pin", "game_session_start_session_launch_descriptor_pin")) {
      for (String statement : List.of("UPDATE " + table + " SET control_plane_request_id = control_plane_request_id",
          "DELETE FROM " + table, "TRUNCATE " + table + " CASCADE")) {
        assertThatThrownBy(() -> fixture.dsl().execute(statement)).isInstanceOf(RuntimeException.class);
      }
    }
    assertThat(ownerRows(fixture)).isEqualTo(before);
  }

  @Test
  void preQualificationHistoryRemainsNullAndHistoricalWhileCurrentOriginalReadIsDenied() throws Exception {
    var tuple = GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple("old-null-launch-history");
    Fixture fixture = fixture(tuple, Duration.ofSeconds(5), true);
    var original = fixture.startWithOwnerPins(tuple);
    fixture.upgradeQualification();
    String before = ownerRows(fixture);
    assertThat(fixture.dsl().fetchOne("SELECT original_start_session_tuple FROM game_session_canonical_instance_launch").get("original_start_session_tuple", byte[].class)).isNull();
    assertThat(fixture.read(selector(original.startingInstance().launchAssociation()))).isEmpty();
    assertThatThrownBy(() -> fixture.transactions().execute(status -> fixture.starting().createOriginalStartSessionStarting(
        fixture.tenantAssociation(), original.startingInstance().launchAssociation().launchBindingEvidence(),
        tuple.preAuthorizationTuple().action().target().ownerAccountId(), tuple.controlPlaneRequestId(), "f".repeat(64))))
        .isInstanceOf(IllegalStateException.class);
    Thread.sleep(5_250L);
    assertThat(fixture.historicalRead(historicalRequest(original)).orElseThrow().originalTuple().canonicalBytes())
        .containsExactly(tuple.canonicalBytes());
    assertThat(ownerRows(fixture)).isEqualTo(before);
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
  void physicalMtlsHistoricalRpcReadsDatabaseAfterOwnerAndReferenceExpiryWithoutWriting(
      @TempDir Path certificates) throws Exception {
    String namespace = "world-runtime";
    TestIdentity serverIdentity = identity(certificates, "server", namespace, "game-session-service");
    TestIdentity worldIdentity = identity(certificates, "world", namespace, "world-management-service");
    TestIdentity accountIdentity = identity(certificates, "account", namespace, "account-service");
    TestIdentity wrongIdentity = identity(certificates, "wrong", namespace, "logging-admin-service");
    var tuple = GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tupleWithAuthorizationExpiry(
        "historical-socket-expired", java.time.Instant.now().plusSeconds(8));
    Fixture fixture = fixture(tuple, Duration.ofSeconds(5));
    OwnerBackedStarting starting = fixture.startWithOwnerPins(tuple);
    var request = historicalRequest(starting);
    String before = ownerRows(fixture);
    var producer = new HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(fixture.reads(), namespace);
    Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(GrpcSslContexts.configure(SslContextBuilder.forServer(serverIdentity.key(), serverIdentity.certificate()))
            .trustManager(worldIdentity.certificate(), accountIdentity.certificate(), wrongIdentity.certificate())
            .clientAuth(ClientAuth.REQUIRE).build())
        .addService(ServerInterceptors.intercept(producer, new GrpcPeerIdentityInterceptor())).build().start();
    try {
      Thread.sleep(8_250L);
      assertThat(fixture.read(selector(starting.startingInstance().launchAssociation()))).isEmpty();
      for (TestIdentity caller : List.of(worldIdentity, accountIdentity)) {
        ManagedChannel channel = historicalChannel(server.getPort(), serverIdentity, caller);
        try {
          var response = HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS).readHistoricalOriginalStartSessionOwnerEvidence(
                  HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toRequest(request));
          var result = HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromResponse(request, response);
          assertThat(result.originalTuple().canonicalBytes()).containsExactly(tuple.canonicalBytes());
          assertThat(result.accountRedemptionProjection()).containsExactly(tuple.canonicalBytes());
          assertThat(result.ownerAttemptId()).isEqualTo(starting.claim().ownerAttemptId());
          assertThat(result.launchAssociation().launchBindingEvidence()).isEqualTo(starting.startingInstance().launchAssociation().launchBindingEvidence());
        } finally {
          channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        }
      }
      ManagedChannel denied = historicalChannel(server.getPort(), serverIdentity, wrongIdentity);
      try {
        assertThatThrownBy(() -> HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc.newBlockingStub(denied)
            .withDeadlineAfter(10, TimeUnit.SECONDS).readHistoricalOriginalStartSessionOwnerEvidence(
                HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toRequest(request)))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(failure -> assertThat(((StatusRuntimeException) failure).getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
      } finally {
        denied.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      }
      assertThat(ownerRows(fixture)).isEqualTo(before);
    } finally {
      server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private static String ownerRows(Fixture fixture) {
    // Compare every byte-bearing owner row as well as row counts: the RPC must be read-only.
    StringBuilder rows = new StringBuilder();
    for (String table : List.of("game_session_start_session_operator_attempt",
        "game_session_start_session_template_association_pin", "game_session_start_session_launch_descriptor_pin",
        "game_session_canonical_instance_launch", "game_instances")) {
      rows.append(fixture.dsl().fetchOne("SELECT coalesce(jsonb_agg(to_jsonb(owner_row) ORDER BY to_jsonb(owner_row)::text), '[]'::jsonb)::text AS rows FROM "
          + table + " owner_row").get("rows", String.class));
    }
    return rows.toString();
  }

  private static ManagedChannel historicalChannel(int port, TestIdentity server, TestIdentity client) throws Exception {
    return NettyChannelBuilder.forAddress("localhost", port)
        .sslContext(GrpcSslContexts.forClient().trustManager(server.certificate())
            .keyManager(client.key(), client.certificate()).build()).build();
  }

  private record TestIdentity(PrivateKey key, X509Certificate certificate) {}

  private static TestIdentity identity(Path directory, String alias, String namespace, String service) throws Exception {
    Path store = directory.resolve(alias + ".p12");
    String password = "local-historical-proof";
    Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
        "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + alias,
        "-validity", "30", "-ext", "SAN=URI:spiffe://firemud/ns/" + namespace + "/sa/" + service + ",DNS:localhost,IP:127.0.0.1",
        "-ext", "EKU=serverAuth,clientAuth", "-storetype", "PKCS12", "-keystore", store.toString(),
        "-storepass", password, "-keypass", password, "-noprompt").redirectErrorStream(true).start();
    if (!keytool.waitFor(30, TimeUnit.SECONDS)) {
      keytool.destroyForcibly();
      throw new IllegalStateException("Historical RPC test certificate generation timed out");
    }
    if (keytool.exitValue() != 0) throw new IllegalStateException("Historical RPC test certificate generation failed");
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (var input = java.nio.file.Files.newInputStream(store)) {
      keys.load(input, password.toCharArray());
    }
    return new TestIdentity((PrivateKey) keys.getKey(alias, password.toCharArray()), (X509Certificate) keys.getCertificate(alias));
  }

  @Test
  void historicalReadPreservesLegacyProjectionAndCurrentReadRejectsIt() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(
            "historical-original-association-legacy-projection");
    Fixture fixture = fixture(tuple, Duration.ofSeconds(30));
    OwnerBackedStarting starting = fixture.startWithOwnerPins(tuple);
    byte[] legacyProjection = legacyProjection(tuple);
    disableAttemptEvidenceImmutability(fixture);
    disableSelectionPinImmutability(fixture);
    try {
      fixture
          .dsl()
          .execute(
              "UPDATE game_session_start_session_operator_attempt "
                  + "SET account_redemption_projection = ? "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              legacyProjection,
              starting.startingInstance().launchAssociation().targetNamespace(),
              starting.startingInstance().launchAssociation().controlPlaneRequestId());
      fixture
          .dsl()
          .execute(
              "UPDATE game_session_start_session_template_association_pin "
                  + "SET account_redemption_projection_digest = ? "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              HistoricalOriginalStartSessionOwnerEvidence.sha256(legacyProjection),
              starting.startingInstance().launchAssociation().targetNamespace(),
              starting.startingInstance().launchAssociation().controlPlaneRequestId());
    } finally {
      enableSelectionPinImmutability(fixture);
      enableAttemptEvidenceImmutability(fixture);
    }

    var historical = fixture.historicalRead(historicalRequest(starting)).orElseThrow();
    assertThat(historical.accountRedemptionProjection()).containsExactly(legacyProjection);
    assertThatThrownBy(
            () -> fixture.read(selector(starting.startingInstance().launchAssociation())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Persisted original StartSession association evidence is malformed");
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT account_redemption_projection FROM "
                                + "game_session_start_session_operator_attempt "
                                + "WHERE target_namespace = ? AND control_plane_request_id = ?",
                            starting.startingInstance().launchAssociation().targetNamespace(),
                            starting
                                .startingInstance()
                                .launchAssociation()
                                .controlPlaneRequestId()),
                    "persisted historical owner attempt row is required")
                .get("account_redemption_projection", byte[].class))
        .containsExactly(legacyProjection);
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

  private static byte[] legacyProjection(StartSessionPostAuthorizationExecutionTuple tuple) {
    StartSessionAuthorityEvidenceBundle bundle =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    Map<String, Object> value =
        Map.of(
            "projectionSchemaId",
            "accountStartSessionRedemptionProjection",
            "projectionSchemaVersion",
            "1",
            "authorizationReferenceFingerprint",
            tuple.authorizationReferenceFingerprint(),
            "authorityEvidenceBundle",
            bundle.jsonValue(),
            "issuanceOperationId",
            bundle.issuanceOperationId().toString(),
            "issuanceFence",
            Long.parseLong(tuple.issuanceFence()));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (java.io.IOException malformed) {
      throw new IllegalStateException("could not encode legacy projection fixture", malformed);
    }
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

  private static StartingInstance createRetainedStartingBeforeQualification(DSLContext dsl,
      FreshGameSessionTenantAssociation tenant, CompleteLaunchBindingEvidence binding, UUID owner, String digest) {
    var descriptor = binding.descriptor();
    UUID instanceUuid = UUID.randomUUID();
    long id = Objects.requireNonNull(dsl.fetchOne("INSERT INTO game_instances "
        + "(tenant_id, runtime_version, owner_account_uuid, status, row_version, game_template_id, launch_descriptor_id, version_id, release_bundle_id, "
        + "version_state_epoch, generation_config_revision, run_owned_start_request_id, run_owned_start_request_digest, run_owned_start_published_release_bundle_ref, game_instance_uuid) "
        + "VALUES (?, ?, ?, 'STARTING', 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
        tenant.legacyGameSessionTenantId(), Long.toString(descriptor.versionId()), owner, descriptor.gameTemplateId(), descriptor.launchDescriptorId(),
        descriptor.versionId(), descriptor.releaseBundleId(), descriptor.versionStateEpoch(), descriptor.generationConfigRevision(), descriptor.controlPlaneRequestId(),
        digest, descriptor.publishedReleaseBundleRef(), instanceUuid)).get("id", Long.class);
    dsl.execute("INSERT INTO game_session_canonical_instance_launch "
        + "(target_namespace, control_plane_request_id, game_session_tenant_id, canonical_tenant_id, tenant_association_operation_id, tenant_association_request_id, "
        + "tenant_source_schema_version, tenant_source_target_namespace, tenant_source_request_id, tenant_source_canonical_tenant_id, tenant_source_game_row_id, "
        + "tenant_source_game_tenant_key, tenant_source_provenance_kind, tenant_association_kind, game_instance_id, game_instance_uuid, canonical_realm_id, world_slug, "
        + "playable_state_namespace_id, playable_state_scope, public_production, captured_starting_row_version, game_template_id, launch_descriptor_id, version_id, "
        + "release_bundle_id, generation_config_revision, version_state_epoch, complete_launch_binding_evidence) "
        + "SELECT mapping.target_namespace, ?, mapping.legacy_game_session_tenant_id, mapping.canonical_tenant_id, mapping.association_operation_id, mapping.association_request_id, "
        + "mapping.source_schema_version, mapping.source_target_namespace, mapping.source_request_id, mapping.source_canonical_tenant_id, mapping.source_game_row_id, "
        + "mapping.source_game_tenant_key, mapping.provenance_kind, mapping.association_kind, ?, ?, realm.realm_id, realm.world_slug, realm.playable_state_namespace_id, "
        + "'SHARED', TRUE, 0, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb) FROM game_session_fresh_tenant_association mapping "
        + "JOIN game_session_canonical_realm_catalog realm ON realm.target_namespace = mapping.target_namespace AND realm.canonical_tenant_id = mapping.canonical_tenant_id "
        + "WHERE mapping.association_operation_id = ? AND realm.world_slug = ?",
        descriptor.controlPlaneRequestId(), id, instanceUuid, descriptor.gameTemplateId(), descriptor.launchDescriptorId(), descriptor.versionId(), descriptor.releaseBundleId(),
        descriptor.generationConfigRevision(), descriptor.versionStateEpoch(), JSON.writeValueAsString(binding), tenant.associationOperationId(), descriptor.worldSlug());
    var realm = Objects.requireNonNull(dsl.fetchOne("SELECT playable_state_namespace_id FROM game_session_canonical_realm_catalog"));
    return new StartingInstance(id, instanceUuid, new CanonicalGameInstanceLaunchAssociation(descriptor.targetNamespace(), tenant.legacyGameSessionTenantId(), tenant.associationOperationId(),
        descriptor.canonicalTenantId(), instanceUuid, descriptor.worldSlug(), realm.get("playable_state_namespace_id", UUID.class),
        net.firedevops.firemud.common.publication.RealmEntryPolicy.StateScope.SHARED, true, descriptor.controlPlaneRequestId(), descriptor.launchDescriptorId(), 0,
        binding, CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING, 0));
  }

  private static Fixture fixture(
      StartSessionPostAuthorizationExecutionTuple tuple, Duration ownerClaimLease) {
    return fixture(tuple, ownerClaimLease, false);
  }

  private static Fixture fixture(
      StartSessionPostAuthorizationExecutionTuple tuple, Duration ownerClaimLease, boolean preQualification) {
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
        .target(preQualification ? "40" : "41")
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
        dataSource, preQualification,
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
      DriverManagerDataSource dataSource,
      boolean preQualification,
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
      transactions.executeWithoutResult(status -> {
        if (preQualification) {
          // Original V38 writer shape before the nullable derivative existed; bytes are retained unchanged.
          byte[] request = net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec.toRequest(resolved.associationRead().request()).toByteArray();
          byte[] response = net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec.toResponse(resolved.associationRead().request(), resolved.associationRead(), resolved.outcome()).toByteArray();
          dsl.execute("INSERT INTO game_session_start_session_launch_descriptor_pin "
              + "(target_namespace, control_plane_request_id, canonical_tenant_id, owner_attempt_id, owner_fence, association_request_digest, association_response_digest, "
              + "descriptor_request_wire, descriptor_response_wire, descriptor_request_digest, descriptor_response_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              namespace, tuple.controlPlaneRequestId(), tenantId, claim.ownerAttemptId(), claim.ownerFence(), pinnedAssociation.requestDigest(), pinnedAssociation.responseDigest(),
              request, response, HistoricalOriginalStartSessionOwnerEvidence.sha256(request), HistoricalOriginalStartSessionOwnerEvidence.sha256(response));
        } else descriptors.pin(claim, resolved);
      });
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
                  status -> preQualification
                      ? createRetainedStartingBeforeQualification(dsl, tenantAssociation, binding,
                          tuple.preAuthorizationTuple().action().target().ownerAccountId(), "f".repeat(64))
                      : starting.createOriginalStartSessionStarting(
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

    void upgradeQualification() {
      Flyway.configure().dataSource(dataSource).schemas(dataSource.getSchema()).defaultSchema(dataSource.getSchema())
          .table("flyway_schema_history").locations(MIGRATION_LOCATION).load().migrate();
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
