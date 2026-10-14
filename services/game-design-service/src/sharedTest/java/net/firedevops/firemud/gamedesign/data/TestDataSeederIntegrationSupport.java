package net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.ResolvedGrpcTlsMaterial;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public abstract class TestDataSeederIntegrationSupport {
  protected static final String NAMESPACE = "dev";
  protected static final String SESSION_URI_SAN = "spiffe://firemud/ns/dev/sa/game-session-service";
  protected static final String WORLD_MANAGEMENT_URI_SAN =
      "spiffe://firemud/ns/dev/sa/world-management-service";
  protected static final Table<?> SOURCE_OPERATIONS =
      DSL.table(DSL.name("game_design_authored_world_source_operations"));
  protected static final Table<?> SOURCE_DELIVERIES =
      DSL.table(DSL.name("game_design_authored_world_source_deliveries"));
  protected static final Field<UUID> REGISTRATION_REQUEST_ID =
      DSL.field(DSL.name("registration_request_id"), UUID.class);
  protected static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  protected static final Field<UUID> DELIVERY_WORLD_OPERATION_ID =
      DSL.field(DSL.name("world_operation_id"), UUID.class);

  @Container
  protected static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @DynamicPropertySource
  protected static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired protected TestDataSeeder seeder;

  @Autowired
  protected net.firedevops.firemud.gamedesign.service.impl.GameDesignGrpcService
      gameDesignGrpcService;

  @MockitoSpyBean
  protected net.firedevops.firemud.gamedesign.data.RunOwnedFixtureLaunchReadback fixtureReadback;

  @MockitoSpyBean
  protected net.firedevops.firemud.gamedesign.data.RunOwnedFixturePolicyProducer fixturePolicy;

  @Autowired protected Flyway flyway;
  @Autowired protected ConfigurableEnvironment environment;
  @MockitoSpyBean protected GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  @Autowired protected GameRepository gameRepository;

  @Autowired
  protected net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository
      creationRepository;

  @Autowired protected GameTemplateRepository templateRepository;
  @Autowired protected VersionRepository versionRepository;
  @Autowired protected VersionAssetArtifactRepository artifactRepository;
  @Autowired protected VersionService versionService;
  @Autowired protected VersionAssetArtifactService artifactService;
  @Autowired protected LaunchDescriptorRepository launchDescriptorRepository;
  @MockitoSpyBean protected LaunchDescriptorService launchDescriptorService;
  @Autowired protected PlatformTransactionManager transactionManager;
  @Autowired protected DSLContext dsl;
  @Autowired protected ObjectMapper objectMapper;

  @BeforeEach
  protected void resetDisposableServiceSchema() {
    ReflectionTestUtils.setField(seeder, "initialAdmissionFixtureEnabled", false);
    flyway.clean();
    flyway.migrate();
    seeder.run(new DefaultApplicationArguments(new String[] {}));
  }

  protected static void assertSocketReadDenied(Runnable read) {
    assertThatThrownBy(read::run)
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  protected void withFixedReadClients(
      Server server,
      Fixture fixture,
      Path certificate,
      Path privateKey,
      BiConsumer<AuthoredWorldSourceClient, AuthoredWorldVersionStateClient> reads)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("127.0.0.1:" + server.getPort());
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate.toString());
    tls.setPrivateKey(privateKey.toString());
    tls.setCaCert(fixture.caCertificatePath().toString());
    var channels = new CopyOnWriteArrayList<ManagedChannel>();
    var factory =
        new GrpcChannelFactory() {
          @Override
          public ManagedChannel buildChannel(
              String target,
              int port,
              CommonGrpcClientProperties properties,
              boolean keepAlive,
              ResolvedGrpcTlsMaterial material)
              throws javax.net.ssl.SSLException {
            ManagedChannel channel =
                super.buildChannel(target, port, properties, keepAlive, material);
            channels.add(channel);
            return channel;
          }
        };
    try (var sourceClient = new AuthoredWorldSourceClient(endpoints, tls, factory, NAMESPACE);
        var versionClient =
            new AuthoredWorldVersionStateClient(endpoints, tls, factory, NAMESPACE)) {
      sourceClient.init();
      versionClient.init();
      reads.accept(sourceClient, versionClient);
    } finally {
      for (ManagedChannel channel : channels) {
        channel.shutdownNow();
        assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  protected FixedReadLeaf issueFixedReadLeaf(
      Fixture fixture, String name, String uri, boolean server) throws Exception {
    Path directory = java.util.Objects.requireNonNull(fixture.caCertificatePath().getParent());
    Path key = directory.resolve(name + ".key");
    Path csr = directory.resolve(name + ".csr");
    Path certificate = directory.resolve(name + ".crt");
    Path extensions = directory.resolve(name + ".ext");
    runOpenSsl(
        "req",
        "-new",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-sha256",
        "-keyout",
        key.toString(),
        "-out",
        csr.toString(),
        "-subj",
        "/CN=" + name);
    Files.writeString(
        extensions,
        "basicConstraints=critical,CA:FALSE\n"
            + "keyUsage=critical,digitalSignature,keyEncipherment\n"
            + "extendedKeyUsage="
            + (server ? "serverAuth" : "clientAuth")
            + "\n"
            + "subjectAltName=URI:"
            + uri
            + (server
                ? ",DNS:game-design-service,DNS:game-design-service.dev,DNS:game-design-service.dev.svc,DNS:game-design-service.dev.svc.cluster.local,DNS:localhost,IP:127.0.0.1"
                : ",DNS:world-management-service")
            + "\n");
    runOpenSsl(
        "x509",
        "-req",
        "-in",
        csr.toString(),
        "-CA",
        fixture.caCertificatePath().toString(),
        "-CAkey",
        directory.resolve("ca.key").toString(),
        "-CAcreateserial",
        "-out",
        certificate.toString(),
        "-days",
        "2",
        "-sha256",
        "-extfile",
        extensions.toString());
    Files.setPosixFilePermissions(
        certificate,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    awaitFixtureCertificatesValid(certificate);
    return new FixedReadLeaf(certificate, key);
  }

  protected record FixedReadLeaf(Path certificate, Path privateKey) {
    public FixedReadLeaf {}
  }

  protected Map<String, String> fixedReadOwnerRows() {
    Map<String, String> rows = new LinkedHashMap<>();
    for (String table :
        Set.of(
            "game_tenant_creation_operations",
            "game_design_authored_world_source_operations",
            "game_design_authored_world_source_deliveries",
            "game",
            "game_templates",
            "version",
            "version_asset_artifact",
            "published_release_bundle",
            "launch_descriptor",
            "game_design_published_realm_policy_set",
            "game_design_realm_policy_capture",
            "game_design_publication_operation",
            "game_design_published_realm_policy")) {
      rows.put(
          table,
          dsl.fetch(
                  "SELECT row_to_json(t)::text AS bytes, xmin::text AS row_version FROM "
                      + table
                      + " t ORDER BY row_to_json(t)::text")
              .formatJSON());
    }
    return rows;
  }

  protected Set<String> fieldNames(JsonNode object) {
    Set<String> fields = new HashSet<>();
    object.propertyNames().forEach(fields::add);
    return fields;
  }

  protected byte[] readPublishedReleaseBundleBytes() {
    Record row =
        dsl.fetchOne(
            "select convert_to(row_to_json(release_bundle)::text, 'UTF8') as bundle_bytes "
                + "from published_release_bundle as release_bundle "
                + "where tenant_id = ? and version_id = ?",
            "1",
            1L);
    assertThat(row).isNotNull();
    return row.get("bundle_bytes", byte[].class);
  }

  protected void writeVersionStateEpoch(
      TransactionTemplate transaction, String tenantId, Long versionId, long epoch) {
    transaction.executeWithoutResult(
        status -> {
          int updated =
              dsl.execute(
                  "update version set version_state_epoch = ? where tenant_id = ? and id = ?",
                  epoch,
                  tenantId,
                  versionId);
          assertThat(updated).isOne();
        });
  }

  protected Fixture prepareFixture(Path testDirectory) throws Exception {
    return prepareFixture(testDirectory, true);
  }

  protected Fixture prepareFixture(Path testDirectory, boolean prepareExistingOwner)
      throws Exception {
    Path certificateDirectory = Files.createDirectory(testDirectory.resolve("certificates"));
    Path caKey = certificateDirectory.resolve("ca.key");
    Path caCertificate = certificateDirectory.resolve("ca.crt");
    Path sessionKey = certificateDirectory.resolve("game-session.key");
    Path sessionRequest = certificateDirectory.resolve("game-session.csr");
    Path sessionCertificate = certificateDirectory.resolve("game-session.crt");
    Path sessionExtensions = certificateDirectory.resolve("game-session.ext");
    Path worldManagementKey = certificateDirectory.resolve("world-management.key");
    Path worldManagementRequest = certificateDirectory.resolve("world-management.csr");
    Path worldManagementCertificate = certificateDirectory.resolve("world-management.crt");
    Path worldManagementExtensions = certificateDirectory.resolve("world-management.ext");
    runOpenSsl(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-sha256",
        "-days",
        "2",
        "-keyout",
        caKey.toString(),
        "-out",
        caCertificate.toString(),
        "-subj",
        "/CN=FireMUD test fixture CA",
        "-addext",
        "basicConstraints=critical,CA:TRUE",
        "-addext",
        "keyUsage=critical,keyCertSign,cRLSign");
    runOpenSsl(
        "req",
        "-new",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-sha256",
        "-keyout",
        sessionKey.toString(),
        "-out",
        sessionRequest.toString(),
        "-subj",
        "/CN=game-session-service");
    Files.writeString(
        sessionExtensions,
        "basicConstraints=critical,CA:FALSE\n"
            + "keyUsage=critical,digitalSignature,keyEncipherment\n"
            + "extendedKeyUsage=clientAuth,serverAuth\n"
            + "subjectAltName=URI:"
            + SESSION_URI_SAN
            + ",DNS:game-session-service"
            + ",DNS:game-session-service.dev"
            + ",DNS:game-session-service.dev.svc"
            + ",DNS:game-session-service.dev.svc.cluster.local,DNS:localhost,IP:127.0.0.1"
            + "\n");
    runOpenSsl(
        "x509",
        "-req",
        "-in",
        sessionRequest.toString(),
        "-CA",
        caCertificate.toString(),
        "-CAkey",
        caKey.toString(),
        "-CAcreateserial",
        "-out",
        sessionCertificate.toString(),
        "-days",
        "2",
        "-sha256",
        "-extfile",
        sessionExtensions.toString());
    runOpenSsl(
        "req",
        "-new",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-sha256",
        "-keyout",
        worldManagementKey.toString(),
        "-out",
        worldManagementRequest.toString(),
        "-subj",
        "/CN=world-management-service");
    Files.writeString(
        worldManagementExtensions,
        "basicConstraints=critical,CA:FALSE\n"
            + "keyUsage=critical,digitalSignature,keyEncipherment\n"
            + "extendedKeyUsage=clientAuth,serverAuth\n"
            + "subjectAltName=URI:"
            + WORLD_MANAGEMENT_URI_SAN
            + ",DNS:world-management-service"
            + ",DNS:world-management-service.dev"
            + ",DNS:world-management-service.dev.svc"
            + ",DNS:world-management-service.dev.svc.cluster.local,DNS:localhost,IP:127.0.0.1"
            + "\n");
    runOpenSsl(
        "x509",
        "-req",
        "-in",
        worldManagementRequest.toString(),
        "-CA",
        caCertificate.toString(),
        "-CAkey",
        caKey.toString(),
        "-CAcreateserial",
        "-out",
        worldManagementCertificate.toString(),
        "-days",
        "2",
        "-sha256",
        "-extfile",
        worldManagementExtensions.toString());
    Files.setPosixFilePermissions(
        caCertificate,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    Files.setPosixFilePermissions(
        sessionCertificate,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    Files.setPosixFilePermissions(
        worldManagementCertificate,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));

    String leafDigest = certificateDigest(sessionCertificate);
    String worldManagementLeafDigest = certificateDigest(worldManagementCertificate);
    String caDigest = certificateDigest(caCertificate);
    String runId = "fixture-commit-proof";
    String composeProjectName = "firemud-smoke-fixture-commit-proof";
    String claimDigest = "e".repeat(64);
    UUID operationId =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .fixtureOperationId(claimDigest, runId, composeProjectName, leafDigest, caDigest);
    Path capabilityPath = testDirectory.resolve("run-owned-initial-admission-capability.json");
    Map<String, Object> capability = new LinkedHashMap<>();
    capability.put("schema", "firemud.run-owned-initial-admission-fixture.v1");
    capability.put("runId", runId);
    capability.put("composeProjectName", composeProjectName);
    capability.put("claimDigest", claimDigest);
    capability.put("operationId", operationId.toString());
    capability.put("tenantId", 1);
    capability.put(
        "gameTemplateId",
        templateRepository.findByTenantIdAndName("1", "Default Template").orElseThrow().getId());
    capability.put("ownerAccountId", 1);
    capability.put("worldSlug", "demo");
    capability.put("worldDisplayName", "Demo World");
    capability.put("realmSlug", "production");
    capability.put("realmDisplayName", "Live Realm");
    capability.put("visible", true);
    capability.put("publicProductionRealm", true);
    capability.put("requiresCharacterSelection", false);
    capability.put("stateScope", "SHARED");
    capability.put("characterCreationPolicy", "ALLOW_NEW");
    capability.put("gameSessionLeafSha256", leafDigest);
    capability.put("gameSessionUriSan", SESSION_URI_SAN);
    capability.put("worldManagementLeafSha256", worldManagementLeafDigest);
    capability.put("worldManagementUriSan", WORLD_MANAGEMENT_URI_SAN);
    capability.put("caCertificateSha256", caDigest);
    Files.write(capabilityPath, objectMapper.writeValueAsBytes(capability));
    Files.setPosixFilePermissions(
        capabilityPath,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));

    awaitFixtureCertificatesValid(sessionCertificate, worldManagementCertificate, caCertificate);

    Path receiptDirectory =
        Files.createDirectory(testDirectory.resolve("run-owned-authored-world-source"));
    Files.setPosixFilePermissions(
        receiptDirectory,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE));
    Fixture fixture =
        new Fixture(
            operationId,
            runId,
            composeProjectName,
            capabilityPath,
            receiptDirectory.resolve("source-receipt.json"),
            sessionCertificate,
            worldManagementCertificate,
            caCertificate,
            leafDigest,
            worldManagementLeafDigest,
            caDigest);
    configureSeeder(fixture);
    ReflectionTestUtils.invokeMethod(seeder, "loadFixtureCapability");
    // Disposable test schema only: create the real fixture owner from inception, never backfill.
    flyway.clean();
    flyway.migrate();
    if (prepareExistingOwner) {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(
              status ->
                  creationRepository.createCandidate(
                      NAMESPACE, operationId, "1", "Demo Game", "Seed game"));
      ReflectionTestUtils.setField(seeder, "initialAdmissionFixtureEnabled", false);
      seeder.run(new DefaultApplicationArguments(new String[] {}));
    }
    return fixture;
  }

  protected void configureSeeder(Fixture fixture) {
    ReflectionTestUtils.setField(seeder, "initialAdmissionFixtureEnabled", true);
    ReflectionTestUtils.setField(seeder, "fixtureRunId", fixture.runId());
    ReflectionTestUtils.setField(seeder, "fixtureComposeProjectName", fixture.composeProjectName());
    ReflectionTestUtils.setField(
        seeder, "fixtureCapabilityPath", fixture.capabilityPath().toString());
    ReflectionTestUtils.setField(seeder, "fixtureReceiptPath", fixture.receiptPath().toString());
    ReflectionTestUtils.setField(
        seeder, "gameSessionLeafCertPath", fixture.gameSessionCertificatePath().toString());
    ReflectionTestUtils.setField(
        seeder, "worldManagementLeafCertPath", fixture.worldManagementCertificatePath().toString());
    ReflectionTestUtils.setField(seeder, "workloadNamespace", NAMESPACE);
    ReflectionTestUtils.setField(seeder, "caCertPath", fixture.caCertificatePath().toString());
  }

  protected String certificateDigest(Path path) throws Exception {
    try (var input = Files.newInputStream(path)) {
      X509Certificate certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }
  }

  protected void awaitFixtureCertificatesValid(Path... certificatePaths) throws Exception {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      try {
        for (Path path : certificatePaths) {
          try (var input = Files.newInputStream(path)) {
            X509Certificate certificate =
                (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(input);
            certificate.checkValidity();
          }
        }
        return;
      } catch (CertificateNotYetValidException exception) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
          throw new IllegalStateException(
              "Run-owned fixture certificates did not become valid within 5 seconds", exception);
        }
        TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(10), remainingNanos));
      }
    }
  }

  protected AuthoredWorldSourceEvidence changedSourceEvidence(AuthoredWorldSourceEvidence source) {
    String changedWorldSlug = source.worldSlug() + "-changed";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            source.targetNamespace(),
            source.registrationRequestId(),
            source.canonicalTenantId(),
            source.tenantSlug(),
            changedWorldSlug,
            source.worldDisplayName());
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            source.targetNamespace(),
            source.registrationRequestId(),
            source.operationId(),
            requestDigest,
            source.canonicalTenantId(),
            source.tenantSlug(),
            changedWorldSlug,
            source.worldDisplayName(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind());
    return new AuthoredWorldSourceEvidence(
        source.schemaVersion(),
        source.targetNamespace(),
        source.registrationRequestId(),
        source.operationId(),
        requestDigest,
        source.canonicalTenantId(),
        source.tenantSlug(),
        changedWorldSlug,
        source.worldDisplayName(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        evidenceDigest);
  }

  protected void runOpenSsl(String... arguments) throws Exception {
    Process process =
        new ProcessBuilder(concat(new String[] {"openssl"}, arguments))
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    if (process.waitFor() != 0) {
      throw new IOException("OpenSSL fixture certificate generation failed: " + output);
    }
  }

  protected String[] concat(String[] prefix, String[] suffix) {
    String[] combined = new String[prefix.length + suffix.length];
    System.arraycopy(prefix, 0, combined, 0, prefix.length);
    System.arraycopy(suffix, 0, combined, prefix.length, suffix.length);
    return combined;
  }

  protected record Fixture(
      UUID operationId,
      String runId,
      String composeProjectName,
      Path capabilityPath,
      Path receiptPath,
      Path gameSessionCertificatePath,
      Path worldManagementCertificatePath,
      Path caCertificatePath,
      String gameSessionLeafSha256,
      String worldManagementLeafSha256,
      String caCertificateSha256) {}
}
