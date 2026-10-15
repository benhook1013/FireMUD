package integration.net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.data.RunOwnedFixtureOwnerReadAuthorization;
import net.firedevops.firemud.gamedesign.data.TestDataSeeder;
import net.firedevops.firemud.gamedesign.data.TestDataSeederIntegrationSupport;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.jooq.tables.GameDesignPublishedRealmPolicySet;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.GameDesignLaunchReadProjectionMapper;
import net.firedevops.firemud.gamedesign.service.impl.RunOwnedFixtureAuthoredWorldSourceReadGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.RunOwnedFixtureAuthoredWorldVersionStateGrpcService;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldSourceReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldVersionStateServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "spring.config.import=file:src/main/resources/application.yml",
      "firemud.smoke.seed-demo-runtime.enabled=true",
      "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED=false",
      "spring.flyway.clean-disabled=false",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "firemud.grpc.workload-namespace=dev",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class TestDataSeederIntegrationTest extends TestDataSeederIntegrationSupport {
  @Test
  void fixedWorldReadsQualifyRealPreexistingOwnersWithoutRewritingOrResolving(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    var capability =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .parseCapability(Files.readAllBytes(fixture.capabilityPath()));
    var receipt =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .parseSourceReceipt(Files.readAllBytes(fixture.receiptPath()), capability);
    var authorization =
        new net.firedevops.firemud.gamedesign.data.RunOwnedFixtureOwnerReadAuthorization(
            fixtureReadback,
            fixturePolicy,
            NAMESPACE,
            fixture.runId(),
            fixture.composeProjectName(),
            fixture.capabilityPath().toString(),
            fixture.receiptPath().toString(),
            fixture.gameSessionCertificatePath().toString(),
            fixture.worldManagementCertificatePath().toString(),
            fixture.caCertificatePath().toString());
    var source = receipt.sourceEvidence();
    var sourceRequest =
        net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec.toReadRequest(
            new net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec.ReadRequest(
                NAMESPACE,
                UUID.randomUUID(),
                source.operationId(),
                source.canonicalTenantId(),
                source.worldSlug()));
    var versionRequest =
        net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setReadRequestId(UUID.randomUUID().toString())
            .setCanonicalTenantId(source.canonicalTenantId().toString())
            .setWorldSlug(source.worldSlug())
            .setSourceOperationId(source.operationId().toString())
            .setExpectedSourceEvidenceDigest(source.evidenceDigest())
            .setVersionId(receipt.launchReadEvidence().versionState().getVersionId())
            .build();
    Map<String, String> before = fixedReadOwnerRows();
    byte[] receiptBefore = Files.readAllBytes(fixture.receiptPath());
    X509Certificate actualMountedWorldLeaf;
    try (var input = Files.newInputStream(fixture.worldManagementCertificatePath())) {
      actualMountedWorldLeaf =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
    // Peer context is controlled here; immutable PKI, producer and PostgreSQL owners are real.
    var peerContext =
        io.grpc.Context.current()
            .withValue(
                net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY,
                net.firedevops.firemud.common.grpc.GrpcPeerIdentity.parseUri(
                        WORLD_MANAGEMENT_URI_SAN)
                    .orElseThrow())
            .withValue(
                net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence.CONTEXT_KEY,
                net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence.fromCertificate(
                        actualMountedWorldLeaf)
                    .orElseThrow());
    org.mockito.Mockito.clearInvocations(launchDescriptorService);
    peerContext.run(
        () -> {
          assertThat(authorization.authorizeSource(sourceRequest)).isEqualTo(receipt);
          assertThat(authorization.authorizeVersion(versionRequest)).isEqualTo(receipt);
          var sourceOutput =
              new FixedReadObserver<
                  net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse>();
          new net.firedevops.firemud.gamedesign.service.impl
                  .RunOwnedFixtureAuthoredWorldSourceReadGrpcService(
                  authorization, authoredWorldSourceRepository, NAMESPACE)
              .resolveAuthoredWorldSource(sourceRequest, sourceOutput);
          assertThat(sourceOutput.failure).isNull();
          assertThat(sourceOutput.completed).isTrue();
          assertThat(
                  net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec
                      .fromReadResponse(
                          net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec
                              .fromReadRequest(NAMESPACE, sourceRequest),
                          sourceOutput.value))
              .isEqualTo(source);
          var versionOutput =
              new FixedReadObserver<
                  net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateResponse>();
          new net.firedevops.firemud.gamedesign.service.impl
                  .RunOwnedFixtureAuthoredWorldVersionStateGrpcService(
                  authorization, transactionManager, authoredWorldSourceRepository, NAMESPACE)
              .getAuthoredWorldVersionState(versionRequest, versionOutput);
          assertThat(versionOutput.failure).isNull();
          assertThat(versionOutput.completed).isTrue();
          var actual =
              net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateGrpcCodec
                  .fromResponse(
                      net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateGrpcCodec
                          .fromRequest(versionRequest),
                      versionOutput.value);
          assertThat(actual.sourceEvidence()).isEqualTo(source);
          assertThat(actual.versionStateEpoch())
              .isEqualTo(receipt.launchReadEvidence().versionState().getVersionStateEpoch());
        });
    org.mockito.Mockito.verify(launchDescriptorService, org.mockito.Mockito.never())
        .resolveLaunchDescriptor(any());
    assertThat(fixedReadOwnerRows()).isEqualTo(before);
    assertThat(Files.readAllBytes(fixture.receiptPath())).containsExactly(receiptBefore);
    org.mockito.Mockito.clearInvocations(authoredWorldSourceRepository, launchDescriptorService);
    peerContext.run(
        () -> {
          assertThatThrownBy(
                  () ->
                      authorization.authorizeSource(
                          sourceRequest.toBuilder()
                              .setOperationId(UUID.randomUUID().toString())
                              .build()))
              .isInstanceOf(SecurityException.class);
          assertThatThrownBy(
                  () ->
                      authorization.authorizeSource(
                          sourceRequest.toBuilder()
                              .setCanonicalTenantId(UUID.randomUUID().toString())
                              .build()))
              .isInstanceOf(SecurityException.class);
          assertThatThrownBy(
                  () ->
                      authorization.authorizeSource(
                          sourceRequest.toBuilder().setWorldSlug("different-world").build()))
              .isInstanceOf(SecurityException.class);
          assertThatThrownBy(
                  () ->
                      authorization.authorizeVersion(
                          versionRequest.toBuilder().setTargetNamespace("other").build()))
              .isInstanceOf(SecurityException.class);
          assertThatThrownBy(
                  () ->
                      authorization.authorizeVersion(
                          versionRequest.toBuilder()
                              .setVersionId(versionRequest.getVersionId() + 1)
                              .build()))
              .isInstanceOf(SecurityException.class);
          assertThatThrownBy(
                  () ->
                      authorization.authorizeVersion(
                          versionRequest.toBuilder()
                              .setExpectedSourceEvidenceDigest("sha256:" + "f".repeat(64))
                              .build()))
              .isInstanceOf(SecurityException.class);
        });
    org.mockito.Mockito.verifyNoInteractions(
        authoredWorldSourceRepository, launchDescriptorService);
    assertThat(fixedReadOwnerRows()).isEqualTo(before);
  }

  @Test
  void fixedWorldClientsReadActualProducerOwnersOverMutualTlsWithoutOwnerWrites(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    var capability =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .parseCapability(Files.readAllBytes(fixture.capabilityPath()));
    var receipt =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .parseSourceReceipt(Files.readAllBytes(fixture.receiptPath()), capability);
    var authorization =
        new RunOwnedFixtureOwnerReadAuthorization(
            fixtureReadback,
            fixturePolicy,
            NAMESPACE,
            fixture.runId(),
            fixture.composeProjectName(),
            fixture.capabilityPath().toString(),
            fixture.receiptPath().toString(),
            fixture.gameSessionCertificatePath().toString(),
            fixture.worldManagementCertificatePath().toString(),
            fixture.caCertificatePath().toString());
    var source = receipt.sourceEvidence();
    var sourceRequest =
        new AuthoredWorldSourceGrpcCodec.ReadRequest(
            NAMESPACE,
            UUID.randomUUID(),
            source.operationId(),
            source.canonicalTenantId(),
            source.worldSlug());
    var versionRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            NAMESPACE,
            UUID.randomUUID(),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            receipt.launchReadEvidence().versionState().getVersionId());
    var serverLeaf =
        issueFixedReadLeaf(
            fixture, "game-design-server", "spiffe://firemud/ns/dev/sa/game-design-service", true);
    var otherWorldLeaf =
        issueFixedReadLeaf(fixture, "different-world-client", WORLD_MANAGEMENT_URI_SAN, false);
    var auth =
        new AuthTokenInterceptor(
            new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L),
            Set.of(
                AuthoredWorldSourceReadServiceGrpc.getResolveAuthoredWorldSourceMethod()
                    .getFullMethodName(),
                AuthoredWorldVersionStateServiceGrpc.getGetAuthoredWorldVersionStateMethod()
                    .getFullMethodName()));
    Map<String, String> originalRows = fixedReadOwnerRows();
    byte[] originalReceipt = Files.readAllBytes(fixture.receiptPath());
    // Actual handlers/interceptors on a loopback socket; Spring registration and Compose are
    // separate proofs.
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverLeaf.certificate().toFile(), serverLeaf.privateKey().toFile()))
                    .trustManager(fixture.caCertificatePath().toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    new RunOwnedFixtureAuthoredWorldSourceReadGrpcService(
                        authorization, authoredWorldSourceRepository, NAMESPACE),
                    new GrpcPeerIdentityInterceptor(),
                    auth))
            .addService(
                ServerInterceptors.intercept(
                    new RunOwnedFixtureAuthoredWorldVersionStateGrpcService(
                        authorization,
                        transactionManager,
                        authoredWorldSourceRepository,
                        NAMESPACE),
                    new GrpcPeerIdentityInterceptor(),
                    auth))
            .build()
            .start();
    try {
      org.mockito.Mockito.clearInvocations(launchDescriptorService, fixturePolicy);
      withFixedReadClients(
          server,
          fixture,
          fixture.worldManagementCertificatePath(),
          fixture.worldManagementCertificatePath().resolveSibling("world-management.key"),
          (sourceClient, versionClient) -> {
            assertThat(sourceClient.read(sourceRequest)).isEqualTo(source);
            var actual = versionClient.read(versionRequest);
            assertThat(actual.request()).isEqualTo(versionRequest);
            assertThat(actual.sourceEvidence()).isEqualTo(source);
            assertThat(actual.canonicalVersionId())
                .isEqualTo(
                    UUID.fromString(
                        receipt
                            .launchReadEvidence()
                            .publishedReleaseBundle()
                            .getCanonicalVersionId()));
            assertThat(actual.versionState())
                .isEqualTo(receipt.launchReadEvidence().versionState().getVersionState());
            assertThat(actual.versionStateEpoch())
                .isEqualTo(receipt.launchReadEvidence().versionState().getVersionStateEpoch());
          });
      org.mockito.Mockito.verify(launchDescriptorService, org.mockito.Mockito.never())
          .resolveLaunchDescriptor(any());
      assertThat(org.mockito.Mockito.mockingDetails(fixturePolicy).getInvocations())
          .extracting(invocation -> invocation.getMethod().getName())
          .contains("readExisting")
          .doesNotContain("sealAndRead");
      assertThat(fixedReadOwnerRows()).isEqualTo(originalRows);
      assertThat(Files.readAllBytes(fixture.receiptPath())).containsExactly(originalReceipt);
      org.mockito.Mockito.clearInvocations(
          authoredWorldSourceRepository, launchDescriptorService, fixtureReadback, fixturePolicy);
      for (var leaf :
          java.util.List.of(
              otherWorldLeaf,
              new FixedReadLeaf(
                  fixture.gameSessionCertificatePath(),
                  fixture.gameSessionCertificatePath().resolveSibling("game-session.key")))) {
        withFixedReadClients(
            server,
            fixture,
            leaf.certificate(),
            leaf.privateKey(),
            (sourceClient, versionClient) -> {
              assertSocketReadDenied(() -> sourceClient.read(sourceRequest));
              assertSocketReadDenied(() -> versionClient.read(versionRequest));
            });
      }
      withFixedReadClients(
          server,
          fixture,
          fixture.worldManagementCertificatePath(),
          fixture.worldManagementCertificatePath().resolveSibling("world-management.key"),
          (sourceClient, versionClient) -> {
            assertSocketReadDenied(
                () ->
                    sourceClient.read(
                        new AuthoredWorldSourceGrpcCodec.ReadRequest(
                            NAMESPACE,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            source.canonicalTenantId(),
                            source.worldSlug())));
            assertSocketReadDenied(
                () ->
                    sourceClient.read(
                        new AuthoredWorldSourceGrpcCodec.ReadRequest(
                            NAMESPACE,
                            UUID.randomUUID(),
                            source.operationId(),
                            UUID.randomUUID(),
                            source.worldSlug())));
            assertSocketReadDenied(
                () ->
                    sourceClient.read(
                        new AuthoredWorldSourceGrpcCodec.ReadRequest(
                            NAMESPACE,
                            UUID.randomUUID(),
                            source.operationId(),
                            source.canonicalTenantId(),
                            "different-world")));
            assertSocketReadDenied(
                () ->
                    versionClient.read(
                        new AuthoredWorldVersionStateEvidence.Request(
                            1,
                            NAMESPACE,
                            UUID.randomUUID(),
                            source.canonicalTenantId(),
                            source.worldSlug(),
                            source.operationId(),
                            source.evidenceDigest(),
                            versionRequest.versionId() + 1)));
            assertSocketReadDenied(
                () ->
                    versionClient.read(
                        new AuthoredWorldVersionStateEvidence.Request(
                            1,
                            NAMESPACE,
                            UUID.randomUUID(),
                            source.canonicalTenantId(),
                            source.worldSlug(),
                            source.operationId(),
                            "sha256:" + "f".repeat(64),
                            versionRequest.versionId())));
          });
      org.mockito.Mockito.verifyNoInteractions(
          authoredWorldSourceRepository, launchDescriptorService, fixtureReadback, fixturePolicy);
      assertThat(fixedReadOwnerRows()).isEqualTo(originalRows);
      assertThat(Files.readAllBytes(fixture.receiptPath())).containsExactly(originalReceipt);
      // No physical missing-header case: immutable producer seal precedes receipt publication.
      // Controlled missing-policy denial is covered separately without forging/deleting owner
      // evidence.
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void fixtureRefusesExistingUnownedDemoInsteadOfBackfillingCreation(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    flyway.clean();
    flyway.migrate();
    ReflectionTestUtils.setField(seeder, "initialAdmissionFixtureEnabled", false);
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    var existing = gameRepository.findByTenantId("1");
    configureSeeder(fixture);
    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .hasMessageContaining("already belongs to a game row");
    assertThat(gameRepository.findByTenantId("1").getId()).isEqualTo(existing.getId());
    assertThat(creationRepository.read(fixture.operationId(), NAMESPACE)).isEmpty();
    assertThat(fixture.receiptPath()).doesNotExist();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS)).isZero();
  }

  @Test
  void publishesExactOwnerReceiptOnlyAfterCommitAndLeavesNoReceiptOnRollback(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory, false);
    configureSeeder(fixture);
    Path receiptPath = fixture.receiptPath();

    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              seeder.run(new DefaultApplicationArguments(new String[] {}));
              assertThat(receiptPath).doesNotExist();
              status.setRollbackOnly();
              return null;
            });

    assertThat(receiptPath).doesNotExist();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(gameRepository.findByTenantId("1")).isNull();
    assertThat(creationRepository.read(fixture.operationId(), NAMESPACE)).isEmpty();
    assertThat(
            launchDescriptorRepository.findByPrivateRequest("1", fixture.operationId().toString()))
        .isEmpty();
    assertThat(dsl.fetchCount(DSL.table(DSL.name("game_design_published_realm_policy_set"))))
        .isZero();

    // PostgreSQL sequences advance on rollback; reset this disposable schema so the capability's
    // template ID matches the independently fresh committed first-entry attempt.
    flyway.clean();
    flyway.migrate();
    seeder.run(new DefaultApplicationArguments(new String[] {}));

    assertThat(receiptPath).isRegularFile();
    assertThat(Files.isSymbolicLink(receiptPath)).isFalse();
    assertThat(Files.size(receiptPath)).isBetween(1L, 16L * 1024L);
    assertThat(Files.getPosixFilePermissions(receiptPath))
        .containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);
    JsonNode envelope = objectMapper.readTree(Files.readAllBytes(receiptPath));
    assertThat(envelope.size()).isEqualTo(14);
    assertThat(fieldNames(envelope))
        .isEqualTo(
            Set.of(
                "schema",
                "runId",
                "composeProjectName",
                "fixtureOperationId",
                "ownerService",
                "gameSessionUriSan",
                "gameSessionLeafSha256",
                "worldManagementUriSan",
                "worldManagementLeafSha256",
                "caCertificateSha256",
                "sourceEvidence",
                "creationEvidence",
                "fixturePolicySeal",
                "launchReadEvidence"));
    assertThat(envelope.get("schema").textValue())
        .isEqualTo("firemud.run-owned-authored-world-source-receipt.v1");
    assertThat(envelope.get("runId").textValue()).isEqualTo(fixture.runId());
    assertThat(envelope.get("composeProjectName").textValue())
        .isEqualTo(fixture.composeProjectName());
    assertThat(envelope.get("fixtureOperationId").textValue())
        .isEqualTo(fixture.operationId().toString());
    assertThat(envelope.get("ownerService").textValue()).isEqualTo("game-design-service");
    assertThat(envelope.get("gameSessionUriSan").textValue()).isEqualTo(SESSION_URI_SAN);
    assertThat(envelope.get("gameSessionLeafSha256").textValue())
        .isEqualTo(fixture.gameSessionLeafSha256());
    assertThat(envelope.get("worldManagementUriSan").textValue())
        .isEqualTo(WORLD_MANAGEMENT_URI_SAN);
    assertThat(envelope.get("worldManagementLeafSha256").textValue())
        .isEqualTo(fixture.worldManagementLeafSha256());
    assertThat(envelope.get("caCertificateSha256").textValue())
        .isEqualTo(fixture.caCertificateSha256());
    var creation =
        objectMapper.treeToValue(
            envelope.get("creationEvidence"),
            net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence.class);
    assertThat(creationRepository.read(fixture.operationId(), NAMESPACE)).contains(creation);
    assertThat(gameRepository.findByTenantId("1").getCanonicalTenantId())
        .isEqualTo(creation.canonicalTenantId());
    var policySeal =
        objectMapper.treeToValue(
            envelope.get("fixturePolicySeal"),
            net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                .FixturePolicySeal.class);
    var storedPolicy =
        dsl.fetchSingle(
            "SELECT * FROM game_design_published_realm_policy_set " + "WHERE canonical_tenant_id=?",
            creation.canonicalTenantId());
    assertThat(policySeal.operationId()).isEqualTo(fixture.operationId());
    assertThat(storedPolicy.get("fixture_operation_id", UUID.class))
        .isEqualTo(policySeal.operationId());
    assertThat(storedPolicy.get("fixture_request_digest", String.class))
        .isEqualTo(policySeal.requestDigest());
    assertThat(storedPolicy.get("fixture_evidence_digest", String.class))
        .isEqualTo(policySeal.evidenceDigest());
    for (String humanField :
        Set.of(
            "source_commit_id",
            "source_epoch",
            "target_proof_json",
            "operation_bytes",
            "capture_bytes",
            "terminal_evidence_bytes",
            "release_content_bytes",
            "published_release_bundle_ref",
            "published_release_bundle_digest",
            "publish_workflow_id",
            "manifest_hash",
            "publication_version_state_epoch",
            "policy_count",
            "policy_set_digest")) {
      assertThat(storedPolicy.get(humanField)).as(humanField).isNull();
    }
    for (String humanTable :
        Set.of(
            "game_design_realm_policy_capture",
            "game_design_publication_operation",
            "game_design_published_realm_policy")) {
      assertThat(dsl.fetchCount(DSL.table(DSL.name(humanTable)))).as(humanTable).isZero();
    }
    var versionForPolicy = versionRepository.findByTenantIdAndVersionNumber("1", 1).orElseThrow();
    var fixtureTarget =
        new net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof(
            creation.canonicalTenantId(),
            versionForPolicy.getCanonicalVersionId(),
            versionForPolicy.getId(),
            "1",
            creation.sourceGameRowId(),
            creation.sourceGameTenantKey(),
            creation.provenanceKind());
    assertThatThrownBy(
            () ->
                new net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationRepository(
                        dsl)
                    .readPublishedSet(fixtureTarget))
        .hasMessageContaining("FIXTURE_POLICY_IS_NOT_HUMAN_PUBLICATION_EVIDENCE");
    assertThat(
            net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                .parseFixturePolicyRequestBytes(
                    storedPolicy.get("fixture_request_bytes", byte[].class))
                .creationEvidence())
        .isEqualTo(creation);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_design_published_realm_policy "
                        + "WHERE canonical_tenant_id=?",
                    creation.canonicalTenantId())
                .get(0, Long.class))
        .isZero();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_design_published_realm_policy_set "
                        + "SET fixture_request_digest=? WHERE canonical_tenant_id=?",
                    "sha256:" + "f".repeat(64),
                    creation.canonicalTenantId()))
        .hasMessageContaining("fixture policy seal is immutable");
    JsonNode sourceJson = envelope.get("sourceEvidence");
    assertThat(sourceJson.size()).isEqualTo(13);
    assertThat(fieldNames(sourceJson))
        .isEqualTo(
            Set.of(
                "schemaVersion",
                "targetNamespace",
                "registrationRequestId",
                "operationId",
                "requestDigest",
                "canonicalTenantId",
                "tenantSlug",
                "worldSlug",
                "worldDisplayName",
                "sourceGameRowId",
                "sourceGameTenantKey",
                "provenanceKind",
                "evidenceDigest"));
    AuthoredWorldSourceEvidence actual =
        objectMapper.treeToValue(sourceJson, AuthoredWorldSourceEvidence.class);
    UUID sourceOperationId =
        dsl.select(SOURCE_OPERATION_ID)
            .from(SOURCE_OPERATIONS)
            .where(REGISTRATION_REQUEST_ID.eq(fixture.operationId()))
            .fetchOne(SOURCE_OPERATION_ID);
    assertThat(sourceOperationId).isNotNull();
    AuthoredWorldSourceEvidence committed =
        authoredWorldSourceRepository
            .read(
                sourceOperationId,
                gameRepository.findByTenantId("1").getCanonicalTenantId(),
                "demo",
                NAMESPACE)
            .orElseThrow();
    assertThat(actual).isEqualTo(committed);
    assertThat(actual.registrationRequestId()).isEqualTo(fixture.operationId());
    assertThat(actual.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(actual.tenantSlug()).isEqualTo("demo");
    assertThat(actual.worldSlug()).isEqualTo("demo");
    assertThat(actual.worldDisplayName()).isEqualTo("Demo World");
    assertThat(actual.sourceGameTenantKey()).isEqualTo("1");
    assertThat(actual.provenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
    assertThat(
            dsl.select(DELIVERY_WORLD_OPERATION_ID)
                .from(SOURCE_DELIVERIES)
                .where(REGISTRATION_REQUEST_ID.eq(fixture.operationId()))
                .fetchOne(DELIVERY_WORLD_OPERATION_ID))
        .isNull();

    JsonNode launchJson = envelope.get("launchReadEvidence");
    assertThat(launchJson.size()).isEqualTo(6);
    assertThat(fieldNames(launchJson))
        .isEqualTo(
            Set.of(
                "schema",
                "gameTemplateId",
                "publishedReleaseBundleBase64",
                "versionAssetArtifactStateBase64",
                "versionStateBase64",
                "launchDescriptorBase64"));
    assertThat(launchJson.get("schema").textValue())
        .isEqualTo("firemud.run-owned-v1-launch-read-evidence.v1");
    var template = templateRepository.findByTenantIdAndName("1", "Default Template").orElseThrow();
    var version = versionRepository.findByTenantIdAndVersionNumber("1", 1).orElseThrow();
    var artifact =
        artifactRepository.findByTenantIdAndVersionId("1", version.getId()).orElseThrow();
    assertThat(launchJson.get("gameTemplateId").longValue())
        .isEqualTo(template.getId())
        .isPositive();
    PublishedReleaseBundleDto bundleDto =
        versionService.getPublishedReleaseBundle("1", version.getId());
    VersionAssetArtifactStateDto artifactDto = artifactService.getState("1", version.getId());
    VersionStateDto versionDto = versionService.getVersionState("1", version.getId());
    String bundleBase64 = launchJson.get("publishedReleaseBundleBase64").textValue();
    byte[] bundleBytes = Base64.getDecoder().decode(bundleBase64);
    assertThat(Base64.getEncoder().encodeToString(bundleBytes)).isEqualTo(bundleBase64);
    PublishedReleaseBundle bundleProof = PublishedReleaseBundle.parseFrom(bundleBytes);
    assertThat(bundleProof).isEqualTo(GameDesignLaunchReadProjectionMapper.toProto(bundleDto));
    assertThat(bundleProof.getId()).isPositive();
    assertThat(bundleProof.getVersionId()).isEqualTo(version.getId());
    assertThat(bundleProof.getPublishedReleaseBundleRef()).isNotBlank();
    assertThat(bundleProof.getCanonicalTenantId()).isEqualTo(actual.canonicalTenantId().toString());
    assertThat(bundleProof.getCanonicalVersionId())
        .isEqualTo(version.getCanonicalVersionId().toString());
    assertThat(bundleProof.getWorkflowRunId()).isEmpty();
    assertThat(bundleProof.getWorkflowStatus()).isEmpty();
    assertThat(bundleProof.getWorkflowFamily()).isEmpty();
    assertThat(bundleProof.getUnknownFields().asMap()).isEmpty();
    bundleProof
        .getParticipantDigestsList()
        .forEach(digest -> assertThat(digest.getUnknownFields().asMap()).isEmpty());
    bundleProof
        .getArtifactDigestsList()
        .forEach(digest -> assertThat(digest.getUnknownFields().asMap()).isEmpty());
    String artifactBase64 = launchJson.get("versionAssetArtifactStateBase64").textValue();
    byte[] artifactBytes = Base64.getDecoder().decode(artifactBase64);
    assertThat(Base64.getEncoder().encodeToString(artifactBytes)).isEqualTo(artifactBase64);
    VersionAssetArtifactState artifactProof = VersionAssetArtifactState.parseFrom(artifactBytes);
    assertThat(artifactProof).isEqualTo(GameDesignLaunchReadProjectionMapper.toProto(artifactDto));
    assertThat(artifactProof.getVersionId()).isEqualTo(version.getId());
    assertThat(artifactProof.getStateEpoch()).isEqualTo(artifact.getStateEpoch());
    assertThat(artifactProof.getManifestHash()).isEqualTo(bundleDto.manifestHash());
    assertThat(artifactProof.getUnknownFields().asMap()).isEmpty();
    String versionBase64 = launchJson.get("versionStateBase64").textValue();
    byte[] versionBytes = Base64.getDecoder().decode(versionBase64);
    assertThat(Base64.getEncoder().encodeToString(versionBytes)).isEqualTo(versionBase64);
    VersionStateSnapshot versionProof = VersionStateSnapshot.parseFrom(versionBytes);
    assertThat(versionProof).isEqualTo(GameDesignLaunchReadProjectionMapper.toProto(versionDto));
    assertThat(versionProof.getVersionId()).isEqualTo(version.getId());
    assertThat(versionProof.getVersionStateEpoch()).isEqualTo(version.getVersionStateEpoch());
    assertThat(versionProof.getUnknownFields().asMap()).isEmpty();

    var persistedDescriptor =
        launchDescriptorRepository
            .findBoundByRequest(
                NAMESPACE, actual.canonicalTenantId(), fixture.operationId().toString())
            .orElseThrow();
    assertThat(persistedDescriptor.getOutcomeStatus()).isEqualTo("SUCCESS");
    assertThat(persistedDescriptor.getAuthoredWorldSourceEvidenceDigest())
        .isEqualTo(actual.evidenceDigest());
    ResolvedLaunchDescriptorDto descriptorDto =
        launchDescriptorService.getLaunchDescriptor(
            UUID.randomUUID(),
            actual.canonicalTenantId(),
            actual.worldSlug(),
            fixture.operationId().toString(),
            persistedDescriptor.getRequestDigest(),
            persistedDescriptor.getResultDigest());
    assertThat(descriptorDto.authoredWorldBinding().request().controlPlaneRequestId())
        .isEqualTo(fixture.operationId().toString());
    assertThat(descriptorDto.authoredWorldBinding().authoredWorldSourceEvidenceDigest())
        .isEqualTo(actual.evidenceDigest());
    String descriptorBase64 = launchJson.get("launchDescriptorBase64").textValue();
    byte[] descriptorBytes = Base64.getDecoder().decode(descriptorBase64);
    assertThat(Base64.getEncoder().encodeToString(descriptorBytes)).isEqualTo(descriptorBase64);
    LaunchDescriptor descriptorProof = LaunchDescriptor.parseFrom(descriptorBytes);
    assertThat(descriptorProof)
        .isEqualTo(
            AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
                descriptorDto.authoredWorldBinding()));
    assertThat(descriptorProof.getAuthoredWorldBinding().getRequestDigest())
        .isEqualTo(persistedDescriptor.getRequestDigest());
    assertThat(descriptorProof.getAuthoredWorldBinding().getResultDigest())
        .isEqualTo(persistedDescriptor.getResultDigest());
    assertThat(descriptorProof.getVersionId()).isEqualTo(version.getId());
    assertThat(descriptorProof.getVersionStateEpoch()).isEqualTo(version.getVersionStateEpoch());
    assertThat(descriptorProof.getReleaseBundleId()).isEqualTo(bundleProof.getId());
    assertThat(descriptorProof.getPublishedReleaseBundleRef())
        .isEqualTo(bundleProof.getPublishedReleaseBundleRef());
    assertThat(descriptorProof.getUnknownFields().asMap()).isEmpty();
    assertThat(descriptorProof.getAuthoredWorldBinding().getUnknownFields().asMap()).isEmpty();

    byte[] originalBundleBytes = readPublishedReleaseBundleBytes();
    JsonNode bundleJson = objectMapper.readTree(originalBundleBytes);
    assertThat(bundleJson.get("id").asLong()).isPositive();
    assertThat(bundleJson.get("published_release_bundle_ref").textValue()).isNotBlank();
    byte[] originalReceipt = Files.readAllBytes(receiptPath);
    var originalVersion = version;
    var originalTemplate = template;
    var originalArtifact = artifact;
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    assertThat(Files.readAllBytes(receiptPath)).isEqualTo(originalReceipt);
    assertThat(readPublishedReleaseBundleBytes()).containsExactly(originalBundleBytes);
    assertThat(versionRepository.findByTenantIdAndId("1", originalVersion.getId()).orElseThrow())
        .isEqualTo(originalVersion);
    assertThat(templateRepository.findByTenantIdAndId("1", originalTemplate.getId()).orElseThrow())
        .isEqualTo(originalTemplate);
    assertThat(
            artifactRepository
                .findByTenantIdAndVersionId("1", originalVersion.getId())
                .orElseThrow())
        .isEqualTo(originalArtifact);
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
    assertThat(
            launchDescriptorRepository.findByPrivateRequest("1", fixture.operationId().toString()))
        .isPresent();
  }

  @Test
  void refusesReceiptWhenVersionEpochChangesAfterCommitBeforeOwnerReadback(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    var existingVersion = versionRepository.findByTenantIdAndVersionNumber("1", 1).orElseThrow();
    String tenantId = existingVersion.getTenantId();
    Long versionId = existingVersion.getId();
    long originalEpoch = existingVersion.getVersionStateEpoch();
    long changedEpoch = originalEpoch + 1L;
    TransactionTemplate versionMutation = new TransactionTemplate(transactionManager);
    versionMutation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    TransactionTemplate registration = new TransactionTemplate(transactionManager);

    try {
      assertThatThrownBy(
              () ->
                  registration.execute(
                      status -> {
                        TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                              @Override
                              public int getOrder() {
                                return Ordered.HIGHEST_PRECEDENCE;
                              }

                              @Override
                              public void afterCommit() {
                                writeVersionStateEpoch(
                                    versionMutation, tenantId, versionId, changedEpoch);
                              }
                            });
                        seeder.run(new DefaultApplicationArguments(new String[] {}));
                        return null;
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Run-owned published version does not match its game source");

      assertThat(fixture.receiptPath()).doesNotExist();
      assertThat(
              launchDescriptorRepository.findByPrivateRequest(
                  "1", fixture.operationId().toString()))
          .isEmpty();
      var changedVersion = versionRepository.findByTenantIdAndId(tenantId, versionId).orElseThrow();
      assertThat(changedVersion.getVersionStateEpoch()).isEqualTo(changedEpoch);
    } finally {
      writeVersionStateEpoch(versionMutation, tenantId, versionId, originalEpoch);
    }

    var restoredVersion = versionRepository.findByTenantIdAndId(tenantId, versionId).orElseThrow();
    assertThat(restoredVersion.getVersionStateEpoch()).isEqualTo(originalEpoch);
  }

  @Test
  void refusesReceiptWhenExactDescriptorReadbackChanges(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    doAnswer(
            invocation -> {
              ResolvedLaunchDescriptorDto actual =
                  (ResolvedLaunchDescriptorDto) invocation.callRealMethod();
              return new ResolvedLaunchDescriptorDto(
                  actual.launchDescriptorId(),
                  actual.canonicalTenantId(),
                  actual.gameTemplateId(),
                  actual.controlPlaneRequestId(),
                  actual.versionId(),
                  actual.scriptPatchVersion(),
                  actual.runtimeFlagsJson(),
                  actual.generationConfigRevision(),
                  actual.versionStateEpoch(),
                  actual.releaseBundleId(),
                  actual.publishedReleaseBundleRef() + "-changed",
                  actual.remapSetId(),
                  actual.authoredWorldBinding());
            })
        .when(launchDescriptorService)
        .getLaunchDescriptor(
            any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), anyString());

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("readback differs from its resolved result");

    assertThat(fixture.receiptPath()).doesNotExist();
    var source = gameRepository.findByTenantId("1");
    assertThat(
            launchDescriptorRepository.findBoundByRequest(
                NAMESPACE, source.getCanonicalTenantId(), fixture.operationId().toString()))
        .isPresent();
  }

  @Test
  void refusesReceiptWhenSourceChangesAfterDescriptorPublicationBeforeFinalReadback(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    AtomicInteger committedSourceReads = new AtomicInteger();
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Optional<AuthoredWorldSourceEvidence> actual =
                  (Optional<AuthoredWorldSourceEvidence>) invocation.callRealMethod();
              if (committedSourceReads.incrementAndGet() == 4) {
                return actual.map(TestDataSeederIntegrationTest.this::changedSourceEvidence);
              }
              return actual;
            })
        .when(authoredWorldSourceRepository)
        .read(any(UUID.class), any(UUID.class), anyString(), anyString());

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Committed authored-world source readback differs from the registration result");

    assertThat(committedSourceReads).hasValue(4);
    assertThat(fixture.receiptPath()).doesNotExist();
    var source = gameRepository.findByTenantId("1");
    assertThat(
            launchDescriptorRepository.findBoundByRequest(
                NAMESPACE, source.getCanonicalTenantId(), fixture.operationId().toString()))
        .isPresent();
  }

  @Test
  void rejectsWrongGameSessionCertificatePinBeforeOwnerRegistration(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    String capability = Files.readString(fixture.capabilityPath());
    String wrongLeafDigest = "0".repeat(64);
    assertThat(wrongLeafDigest).isNotEqualTo(fixture.gameSessionLeafSha256());
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    var changedCapability =
        (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(capability);
    changedCapability.put("gameSessionLeafSha256", wrongLeafDigest);
    UUID changedOperationId =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .fixtureOperationId(
                changedCapability.get("claimDigest").textValue(),
                changedCapability.get("runId").textValue(),
                changedCapability.get("composeProjectName").textValue(),
                wrongLeafDigest,
                changedCapability.get("caCertificateSha256").textValue());
    changedCapability.put("operationId", changedOperationId.toString());
    byte[] changedCapabilityBytes = objectMapper.writeValueAsBytes(changedCapability);
    // The fixed operation is derived from the GS leaf pin. Keep this wire input valid so
    // the negative reaches the actual configured certificate comparison, before any owner write.
    assertThat(
            net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                .parseCapability(changedCapabilityBytes)
                .operationId())
        .isEqualTo(changedOperationId.toString());
    Files.write(fixture.capabilityPath(), changedCapabilityBytes);
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    configureSeeder(fixture);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TLS certificates do not match capability pins");
    assertThat(fixture.receiptPath()).doesNotExist();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(changedOperationId)))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(changedOperationId)))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
  }

  @Test
  void rejectsWrongWorldManagementCertificatePinBeforeOwnerRegistration(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    String capability = Files.readString(fixture.capabilityPath());
    String wrongLeafDigest = "0".repeat(64);
    assertThat(wrongLeafDigest).isNotEqualTo(fixture.worldManagementLeafSha256());
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    Files.writeString(
        fixture.capabilityPath(),
        capability.replace(fixture.worldManagementLeafSha256(), wrongLeafDigest));
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    configureSeeder(fixture);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TLS certificates do not match capability pins");
    assertThat(fixture.receiptPath()).doesNotExist();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(
            launchDescriptorRepository.findByPrivateRequest("1", fixture.operationId().toString()))
        .isEmpty();
  }

  @Test
  void rejectsUnknownCapabilityFieldBeforeOwnerRegistration(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    String capability = Files.readString(fixture.capabilityPath());
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    Files.writeString(
        fixture.capabilityPath(),
        capability.substring(0, capability.lastIndexOf('}')) + ",\"unexpected\":true}");
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    configureSeeder(fixture);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Could not read run-owned fixture capability")
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThat(fixture.receiptPath()).doesNotExist();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(
            launchDescriptorRepository.findByPrivateRequest("1", fixture.operationId().toString()))
        .isEmpty();
  }

  @Test
  void preservesConflictingReceiptInsteadOfReplacingIt(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    byte[] conflict = "existing receipt from another run".getBytes(StandardCharsets.UTF_8);
    Files.write(fixture.receiptPath(), conflict);
    Files.setPosixFilePermissions(
        fixture.receiptPath(),
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    configureSeeder(fixture);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with committed owner evidence");
    assertThat(Files.readAllBytes(fixture.receiptPath())).isEqualTo(conflict);
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
  }

  @Test
  void freshBaselineSeedAndRestartReuseActualOwnerIdentityAndOpaqueBundle() {
    FoundationFixture fixture = foundationFixture(null);
    fixture.migrate(null);
    fixture.seed();
    var game = fixture.games().findByTenantId("1");
    var version = fixture.versions().findByTenantIdAndVersionNumber("1", 1).orElseThrow();
    var bundle = fixture.bundles().findByTenantIdAndVersionId("1", version.getId()).orElseThrow();
    var artifact =
        fixture.artifacts().findByTenantIdAndVersionId("1", version.getId()).orElseThrow();
    assertThat(version.getCanonicalTenantId()).isEqualTo(game.getCanonicalTenantId());
    assertThat(version.getCanonicalVersionId()).isNotNull().isNotEqualTo(new UUID(0L, 0L));
    assertThat(version.getIdentitySourceGameRowId()).isEqualTo(game.getId());
    assertThat(version.getIdentitySourceGameTenantKey()).isEqualTo(game.getTenantId());
    assertThat(version.getIdentitySourceProvenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(bundle.getCanonicalTenantId()).isEqualTo(game.getCanonicalTenantId());
    assertThat(bundle.getCanonicalVersionId()).isEqualTo(version.getCanonicalVersionId());
    assertThat(bundle.getAttestationSchemaVersion()).isEqualTo("v1");
    assertThat(bundle.getPublishedReleaseBundleRef()).isNotBlank();
    String xmin = fixture.bundleXmin();
    String artifactXmin = fixture.artifactXmin();

    fixture.seed();

    assertThat(fixture.bundleXmin()).isEqualTo(xmin);
    assertThat(fixture.artifactXmin()).isEqualTo(artifactXmin);
    var retainedArtifact =
        fixture.artifacts().findByTenantIdAndVersionId("1", version.getId()).orElseThrow();
    assertThat(retainedArtifact.getId()).isEqualTo(artifact.getId());
    assertThat(retainedArtifact.getStateEpoch()).isEqualTo(artifact.getStateEpoch());
    assertThat(retainedArtifact.getStateEpoch()).isEqualTo(1L);
    assertThat(fixture.bundles().findByTenantIdAndVersionId("1", version.getId()).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(bundle);
    assertThat(
            fixture
                .versions()
                .findByTenantIdAndVersionNumber("1", 1)
                .orElseThrow()
                .getCanonicalVersionId())
        .isEqualTo(version.getCanonicalVersionId());
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_authored_world_source_operations")))
        .isZero();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_authored_world_source_deliveries")))
        .isZero();
    assertRetainedDescriptorInsertAndRead(fixture, version.getId(), bundle.getId());
    assertThatThrownBy(() -> fixture.bundles().save(bundle)).hasMessageContaining("immutable");
  }

  @Test
  void baselineUpgradeRetainsUnboundVersionBundleAndDescriptorWithoutIdentityBackfill() {
    FoundationFixture fixture = foundationFixture("30");
    fixture
        .dsl()
        .execute(
            "INSERT INTO game (tenant_id, name, description) VALUES ('1', 'Demo Game', 'Seed game')");
    Long versionId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, is_script_only, notes) VALUES ('1', 1, 'PUBLISHED', 1, false, 'Seed published version') RETURNING id")
            .get("id", Long.class);
    Long bundleId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, attestation_schema_version, publish_workflow_id, manifest_hash, generation_config_revision, required_manifest_asset_keys_json, participant_digests_json, command_definitions_json, script_only) VALUES ('1', ?, 1, 'v1', 'demo-seed-publish', 'demo-manifest-hash', 'genrev:demo', '[]', '[]', '[]', false) RETURNING id",
                versionId)
            .get("id", Long.class);
    fixture.migrate(null);
    String xmin = fixture.bundleXmin();
    fixture.seed();
    var artifact = fixture.artifacts().findByTenantIdAndVersionId("1", versionId).orElseThrow();
    Long artifactId = artifact.getId();
    Long artifactEpoch = artifact.getStateEpoch();
    String artifactXmin = fixture.artifactXmin();

    fixture.seed();

    var version = fixture.versions().findByTenantIdAndId("1", versionId).orElseThrow();
    var bundle = fixture.bundles().findByTenantIdAndVersionId("1", versionId).orElseThrow();
    assertThat(version.getCanonicalVersionId()).isNull();
    assertThat(version.getCanonicalTenantId()).isNull();
    assertThat(bundle.getCanonicalTenantId()).isNull();
    assertThat(bundle.getCanonicalVersionId()).isNull();
    assertThat(bundle.getPublishedReleaseBundleRef()).isNull();
    assertThat(bundle.getId()).isEqualTo(bundleId);
    assertThat(fixture.bundleXmin()).isEqualTo(xmin);
    assertThat(fixture.artifactXmin()).isEqualTo(artifactXmin);
    assertThat(fixture.artifacts().findByTenantIdAndVersionId("1", versionId).orElseThrow())
        .extracting(VersionAssetArtifact::getId, VersionAssetArtifact::getStateEpoch)
        .containsExactly(artifactId, artifactEpoch);
    assertRetainedDescriptorInsertAndRead(fixture, versionId, bundleId);
  }

  @Test
  void retainedBaselineRejectsMismatchedArtifactWithoutChangingItsRow() {
    FoundationFixture fixture = foundationFixture("30");
    fixture
        .dsl()
        .execute(
            "INSERT INTO game (tenant_id, name, description) VALUES ('1', 'Demo Game', 'Seed game')");
    Long versionId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, is_script_only, notes) VALUES ('1', 1, 'PUBLISHED', 1, false, 'Seed published version') RETURNING id")
            .get("id", Long.class);
    fixture
        .dsl()
        .execute(
            "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, attestation_schema_version, publish_workflow_id, manifest_hash, generation_config_revision, required_manifest_asset_keys_json, participant_digests_json, command_definitions_json, script_only) VALUES ('1', ?, 1, 'v1', 'demo-seed-publish', 'demo-manifest-hash', 'genrev:demo', '[]', '[]', '[]', false)",
            versionId);
    fixture.migrate(null);
    fixture.seed();
    fixture
        .dsl()
        .execute(
            "UPDATE version_asset_artifact SET state_epoch = 2 WHERE tenant_id = '1' AND version_id = ?",
            versionId);
    String artifactXmin = fixture.artifactXmin();

    assertThatThrownBy(fixture::seed)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Existing version artifact does not exactly match the demo seed");

    var retainedArtifact =
        fixture.artifacts().findByTenantIdAndVersionId("1", versionId).orElseThrow();
    assertThat(retainedArtifact.getStateEpoch()).isEqualTo(2L);
    assertThat(fixture.artifactXmin()).isEqualTo(artifactXmin);
  }

  @Test
  void retainedVersionWithoutBundleRefusesAndRollsBackAllSeedWrites() {
    FoundationFixture fixture = foundationFixture("30");
    fixture
        .dsl()
        .execute(
            "INSERT INTO game (tenant_id, name, description) VALUES ('1', 'Retained Game', 'Retained description')");
    Long versionId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, is_script_only, notes) VALUES ('1', 1, 'FAILED', 2, false, 'retained-unbundled') RETURNING id")
            .get("id", Long.class);
    fixture.migrate(null);
    var originalGame = fixture.games().findByTenantId("1");
    var originalVersion = fixture.versions().findByTenantIdAndId("1", versionId).orElseThrow();

    assertThatThrownBy(fixture::seed)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No exact canonical Game Design source association");

    assertThat(fixture.games().findByTenantId("1"))
        .usingRecursiveComparison()
        .isEqualTo(originalGame);
    assertThat(fixture.versions().findByTenantIdAndId("1", versionId).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(originalVersion);
    assertThat(originalVersion.getCanonicalVersionId()).isNull();
    assertThat(originalVersion.getCanonicalTenantId()).isNull();
    for (String table :
        java.util.List.of(
            "published_release_bundle",
            "game_templates",
            "revision",
            "version_asset_artifact",
            "game_design_authored_world_source_operations",
            "game_design_authored_world_source_deliveries")) {
      assertThat(fixture.dsl().fetchCount(DSL.table(table))).as(table).isZero();
    }
  }

  private void assertRetainedDescriptorInsertAndRead(
      FoundationFixture fixture, Long versionId, Long bundleId) {
    Long templateId =
        fixture
            .dsl()
            .fetchSingle("SELECT id FROM game_templates WHERE tenant_id = '1'")
            .get("id", Long.class);
    var repository = new LaunchDescriptorRepository(fixture.dsl());
    var descriptor = new net.firedevops.firemud.gamedesign.entity.LaunchDescriptor();
    descriptor.setLaunchDescriptorId("retained-v1-descriptor");
    descriptor.setTenantId("1");
    descriptor.setGameTemplateId(templateId);
    descriptor.setControlPlaneRequestId("baseline-request");
    descriptor.setRequestHash("baseline-request-hash");
    descriptor.setVersionId(versionId);
    descriptor.setRuntimeFlagsJson("{}");
    descriptor.setGenerationConfigRevision("genrev:demo");
    descriptor.setVersionStateEpoch(1L);
    descriptor.setReleaseBundleId(bundleId);
    descriptor.setPublishedReleaseBundleRef("prb:1:" + versionId + ":" + bundleId);
    var saved = repository.save(descriptor);
    assertThat(saved.getDescriptorSchemaVersion()).isNull();
    assertThat(saved.getCanonicalTenantId()).isNull();
    assertThat(saved.getAuthoredWorldSourceOperationId()).isNull();
    assertThat(
            repository
                .findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
                    "1", templateId, "baseline-request")
                .orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(saved);
    assertThatThrownBy(() -> repository.save(saved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Launch descriptors are immutable");
  }

  @Test
  void policyOwnerRetainsValidatedExactHumanCaptureForeignKey() {
    var generatedCaptureReferences =
        GameDesignPublishedRealmPolicySet.GAME_DESIGN_PUBLISHED_REALM_POLICY_SET
            .getReferences()
            .stream()
            .filter(
                reference ->
                    reference
                        .getKey()
                        .getTable()
                        .getName()
                        .equals("game_design_realm_policy_capture"))
            .toList();
    assertThat(generatedCaptureReferences).hasSize(1);
    var generatedCaptureReference = generatedCaptureReferences.getFirst();
    assertThat(generatedCaptureReference.getName())
        .isEqualTo("fk_realm_policy_owner_exact_capture");
    assertThat(
            generatedCaptureReference.getFields().stream().map(field -> field.getName()).toList())
        .containsExactly("canonical_tenant_id", "canonical_version_id", "source_commit_id");
    assertThat(
            generatedCaptureReference.getKey().getFields().stream()
                .map(field -> field.getName())
                .toList())
        .containsExactly("canonical_tenant_id", "canonical_version_id", "commit_id");
    var captureForeignKey =
        dsl.fetchSingle(
            "SELECT constraint_row.convalidated, constraint_row.confmatchtype::text AS match_type, "
                + "constraint_row.confupdtype::text AS update_action, "
                + "constraint_row.confdeltype::text AS delete_action, "
                + "constraint_row.confrelid = 'game_design_realm_policy_capture'::regclass AS exact_target, "
                + "ARRAY(SELECT attribute.attname::text "
                + "FROM unnest(constraint_row.conkey) WITH ORDINALITY AS key(attnum, ordinal) "
                + "JOIN pg_attribute attribute ON attribute.attrelid = constraint_row.conrelid "
                + "AND attribute.attnum = key.attnum ORDER BY key.ordinal) AS owner_columns, "
                + "ARRAY(SELECT attribute.attname::text "
                + "FROM unnest(constraint_row.confkey) WITH ORDINALITY AS key(attnum, ordinal) "
                + "JOIN pg_attribute attribute ON attribute.attrelid = constraint_row.confrelid "
                + "AND attribute.attnum = key.attnum ORDER BY key.ordinal) AS capture_columns "
                + "FROM pg_constraint constraint_row "
                + "WHERE constraint_row.conrelid = 'game_design_published_realm_policy_set'::regclass "
                + "AND constraint_row.contype = 'f' "
                + "AND constraint_row.conname = 'fk_realm_policy_owner_exact_capture'");
    assertThat(captureForeignKey.get("convalidated", Boolean.class)).isTrue();
    assertThat(captureForeignKey.get("exact_target", Boolean.class)).isTrue();
    assertThat(captureForeignKey.get("match_type", String.class)).isEqualTo("s");
    assertThat(captureForeignKey.get("update_action", String.class)).isEqualTo("a");
    assertThat(captureForeignKey.get("delete_action", String.class)).isEqualTo("a");
    assertThat(captureForeignKey.get("owner_columns", String[].class))
        .containsExactly("canonical_tenant_id", "canonical_version_id", "source_commit_id");
    assertThat(captureForeignKey.get("capture_columns", String[].class))
        .containsExactly("canonical_tenant_id", "canonical_version_id", "commit_id");
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM pg_constraint "
                        + "WHERE conrelid = 'game_design_published_realm_policy_set'::regclass "
                        + "AND conname = 'game_design_published_realm__canonical_tenant_id_canonica_fkey1'")
                .get(0, Long.class))
        .isZero();
  }

  private FoundationFixture foundationFixture(String target) {
    String schema = "foundation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    FoundationFixture fixture =
        new FoundationFixture(
            schema,
            dataSource,
            dsl,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    if (target != null) fixture.migrate(target);
    return fixture;
  }

  private record FoundationFixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transactions) {
    void migrate(String target) {
      var configuration =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(schema)
              .defaultSchema(schema)
              .placeholders(Map.of("serviceSchema", schema))
              .locations(
                  "filesystem:"
                      + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize());
      if (target != null) configuration.target(target);
      configuration.load().migrate();
    }

    GameRepository games() {
      return new GameRepository(dsl);
    }

    VersionRepository versions() {
      return new VersionRepository(dsl);
    }

    PublishedReleaseBundleRepository bundles() {
      return new PublishedReleaseBundleRepository(dsl);
    }

    VersionAssetArtifactRepository artifacts() {
      return new VersionAssetArtifactRepository(dsl);
    }

    void seed() {
      TestDataSeeder seeder =
          new TestDataSeeder(
              games(),
              org.mockito.Mockito.mock(GameAuthoredWorldSourceRepository.class),
              new GameTemplateRepository(dsl),
              new RevisionRepository(dsl),
              versions(),
              bundles(),
              new VersionAssetArtifactRepository(dsl),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.gamedesign.data.RunOwnedFixtureLaunchReadback.class),
              new ObjectMapper(),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.gamedesign.data.RunOwnedFixturePolicyProducer.class));
      transactions.executeWithoutResult(
          status -> seeder.run(new DefaultApplicationArguments(new String[] {})));
    }

    String bundleXmin() {
      return dsl.fetchSingle(
              "SELECT xmin::text AS xmin FROM published_release_bundle WHERE tenant_id = '1'")
          .get("xmin", String.class);
    }

    String artifactXmin() {
      return dsl.fetchSingle(
              "SELECT xmin::text AS xmin FROM version_asset_artifact WHERE tenant_id = '1'")
          .get("xmin", String.class);
    }
  }

  private static final class FixedReadObserver<T> implements io.grpc.stub.StreamObserver<T> {
    public FixedReadObserver() {}

    public T value;
    public Status.Code failure;
    public boolean completed;

    @Override
    public void onNext(T response) {
      value = response;
    }

    @Override
    public void onError(Throwable error) {
      failure = Status.fromThrowable(error).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
