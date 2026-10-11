package integration.net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.data.TestDataSeeder;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.smoke.seed-demo-runtime.enabled=true",
      "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED=false",
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
class TestDataSeederIntegrationTest {
  private static final String NAMESPACE = "dev";
  private static final String SESSION_URI_SAN = "spiffe://firemud/ns/dev/sa/game-session-service";
  private static final Table<?> SOURCE_OPERATIONS =
      DSL.table(DSL.name("game_design_authored_world_source_operations"));
  private static final Table<?> SOURCE_DELIVERIES =
      DSL.table(DSL.name("game_design_authored_world_source_deliveries"));
  private static final Field<UUID> REGISTRATION_REQUEST_ID =
      DSL.field(DSL.name("registration_request_id"), UUID.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<UUID> DELIVERY_WORLD_OPERATION_ID =
      DSL.field(DSL.name("world_operation_id"), UUID.class);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private TestDataSeeder seeder;
  @Autowired private GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  @Autowired private GameRepository gameRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void publishesExactOwnerReceiptOnlyAfterCommitAndLeavesNoReceiptOnRollback(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
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
    assertThat(envelope.size()).isEqualTo(9);
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
                "caCertificateSha256",
                "sourceEvidence"));
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
    assertThat(envelope.get("caCertificateSha256").textValue())
        .isEqualTo(fixture.caCertificateSha256());
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

    byte[] originalReceipt = Files.readAllBytes(receiptPath);
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    assertThat(Files.readAllBytes(receiptPath)).isEqualTo(originalReceipt);
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
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
    Files.writeString(
        fixture.capabilityPath(),
        capability.replace(fixture.gameSessionLeafSha256(), wrongLeafDigest));
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

  private Set<String> fieldNames(JsonNode object) {
    Set<String> fields = new HashSet<>();
    object.propertyNames().forEach(fields::add);
    return fields;
  }

  private Fixture prepareFixture(Path testDirectory) throws Exception {
    Path certificateDirectory = Files.createDirectory(testDirectory.resolve("certificates"));
    Path caKey = certificateDirectory.resolve("ca.key");
    Path caCertificate = certificateDirectory.resolve("ca.crt");
    Path sessionKey = certificateDirectory.resolve("game-session.key");
    Path sessionRequest = certificateDirectory.resolve("game-session.csr");
    Path sessionCertificate = certificateDirectory.resolve("game-session.crt");
    Path extensions = certificateDirectory.resolve("game-session.ext");
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
        extensions,
        "basicConstraints=critical,CA:FALSE\n"
            + "keyUsage=critical,digitalSignature,keyEncipherment\n"
            + "extendedKeyUsage=clientAuth\n"
            + "subjectAltName=URI:"
            + SESSION_URI_SAN
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
        extensions.toString());
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

    String leafDigest = certificateDigest(sessionCertificate);
    String caDigest = certificateDigest(caCertificate);
    UUID operationId = UUID.randomUUID();
    String runId = "fixture-commit-proof";
    String composeProjectName = "firemud-smoke-fixture-commit-proof";
    Path capabilityPath = testDirectory.resolve("run-owned-initial-admission-capability.json");
    Map<String, Object> capability = new LinkedHashMap<>();
    capability.put("schema", "firemud.run-owned-initial-admission-fixture.v1");
    capability.put("runId", runId);
    capability.put("composeProjectName", composeProjectName);
    capability.put("operationId", operationId.toString());
    capability.put("tenantId", 1);
    capability.put("gameTemplateId", 1);
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
    capability.put("caCertificateSha256", caDigest);
    Files.write(capabilityPath, objectMapper.writeValueAsBytes(capability));
    Files.setPosixFilePermissions(
        capabilityPath,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));

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
    return new Fixture(
        operationId,
        runId,
        composeProjectName,
        capabilityPath,
        receiptDirectory.resolve("source-receipt.json"),
        sessionCertificate,
        caCertificate,
        leafDigest,
        caDigest);
  }

  private void configureSeeder(Fixture fixture) {
    ReflectionTestUtils.setField(seeder, "initialAdmissionFixtureEnabled", true);
    ReflectionTestUtils.setField(seeder, "fixtureRunId", fixture.runId());
    ReflectionTestUtils.setField(seeder, "fixtureComposeProjectName", fixture.composeProjectName());
    ReflectionTestUtils.setField(
        seeder, "fixtureCapabilityPath", fixture.capabilityPath().toString());
    ReflectionTestUtils.setField(seeder, "fixtureReceiptPath", fixture.receiptPath().toString());
    ReflectionTestUtils.setField(
        seeder, "gameSessionLeafCertPath", fixture.gameSessionCertificatePath().toString());
    ReflectionTestUtils.setField(seeder, "workloadNamespace", NAMESPACE);
    ReflectionTestUtils.setField(seeder, "caCertPath", fixture.caCertificatePath().toString());
  }

  private String certificateDigest(Path path) throws Exception {
    try (var input = Files.newInputStream(path)) {
      X509Certificate certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }
  }

  private void runOpenSsl(String... arguments) throws Exception {
    Process process =
        new ProcessBuilder(concat(new String[] {"openssl"}, arguments))
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    if (process.waitFor() != 0) {
      throw new IOException("OpenSSL fixture certificate generation failed: " + output);
    }
  }

  private String[] concat(String[] prefix, String[] suffix) {
    String[] combined = new String[prefix.length + suffix.length];
    System.arraycopy(prefix, 0, combined, 0, prefix.length);
    System.arraycopy(suffix, 0, combined, prefix.length, suffix.length);
    return combined;
  }

  private record Fixture(
      UUID operationId,
      String runId,
      String composeProjectName,
      Path capabilityPath,
      Path receiptPath,
      Path gameSessionCertificatePath,
      Path caCertificatePath,
      String gameSessionLeafSha256,
      String caCertificateSha256) {}
}
