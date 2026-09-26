package net.firedevops.firemud.gamedesign.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService.ExpectedSource;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService.MigrationCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Trusted, one-shot entry point; no HTTP, gRPC server, or user-JWT mutation route is exposed. */
@Component
@ConditionalOnProperty(
    prefix = "firemud.entity-baseline-migration",
    name = "enabled",
    havingValue = "true")
public class EntityDigestBaselineMigrationJobRunner implements ApplicationRunner {
  private static final Logger LOG =
      LoggerFactory.getLogger(EntityDigestBaselineMigrationJobRunner.class);
  private static final String MIGRATOR_SERVICE = "game-design-baseline-migrator";
  private static final int MAX_BATCH_SIZE = 500;

  private final Environment environment;
  private final ObjectMapper objectMapper;
  private final EntityDigestBaselineMigrationService migrationService;

  public EntityDigestBaselineMigrationJobRunner(
      Environment environment,
      ObjectMapper objectMapper,
      EntityDigestBaselineMigrationService migrationService) {
    this.environment = environment;
    this.objectMapper = objectMapper;
    this.migrationService = migrationService;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (!"true".equals(System.getenv("FIREMUD_ENTITY_BASELINE_MIGRATION_ENABLED"))
        || !"none".equalsIgnoreCase(required("spring.main.web-application-type"))
        || environment.getProperty("spring.grpc.server.enabled", Boolean.class, true)
        || environment.getProperty("firemud.temporal.enabled", Boolean.class, true)) {
      throw new IllegalStateException(
          "migration Job must not start application listeners or workers");
    }
    String namespace = required("firemud.entity-baseline-migration.pod-namespace");
    GrpcPeerIdentity identity = verifiedWorkloadIdentity();
    requireJobAuthority(
        namespace,
        required("firemud.entity-baseline-migration.target-namespace"),
        required("firemud.entity-baseline-migration.pod-service-account"),
        identity);
    String workloadIdentity = identity.uri();

    String mode = required("firemud.entity-baseline-migration.mode");
    switch (mode) {
      case "enumerate" -> {
        long afterId = optionalLong("firemud.entity-baseline-migration.after-baseline-id", 0);
        int limit = (int) optionalLong("firemud.entity-baseline-migration.limit", MAX_BATCH_SIZE);
        LOG.info(
            "Entity v1 baseline enumeration targetNamespace={} result={}",
            namespace,
            objectMapper.writeValueAsString(
                migrationService.enumerateEntityV1Batch(afterId, limit)));
      }
      case "preflight" -> {
        String tenantId = required("firemud.entity-baseline-migration.tenant-id");
        long versionId = requiredPositiveLong("firemud.entity-baseline-migration.version-id");
        LOG.info(
            "Entity baseline preflight targetNamespace={} result={}",
            namespace,
            objectMapper.writeValueAsString(migrationService.preflightScope(tenantId, versionId)));
      }
      case "migrate" -> {
        String operationId = required("firemud.entity-baseline-migration.operation-id");
        String tenantId = required("firemud.entity-baseline-migration.tenant-id");
        long versionId = requiredPositiveLong("firemud.entity-baseline-migration.version-id");
        String actorIdentity = required("firemud.entity-baseline-migration.actor-identity");
        Path expectedSourcePath =
            Path.of(required("firemud.entity-baseline-migration.expected-source-path"));
        ExpectedSource expectedSource =
            objectMapper.readValue(Files.readAllBytes(expectedSourcePath), ExpectedSource.class);
        if (expectedSource.baselineId() == null) {
          throw new IllegalArgumentException("expected source lacks its baseline id");
        }
        MigrationCommand command =
            new MigrationCommand(
                operationId,
                expectedSource.baselineId(),
                tenantId,
                versionId,
                expectedSource,
                actorIdentity,
                workloadIdentity);
        LOG.info(
            "Entity baseline migration targetNamespace={} result={}",
            namespace,
            objectMapper.writeValueAsString(migrationService.migrate(command)));
      }
      default -> throw new IllegalArgumentException("unsupported migration Job mode");
    }
  }

  static void requireJobAuthority(
      String podNamespace,
      String targetNamespace,
      String podServiceAccount,
      GrpcPeerIdentity identity) {
    if (!podNamespace.equals(targetNamespace)) {
      throw new IllegalStateException("migration Job is outside its exact target namespace");
    }
    if (!MIGRATOR_SERVICE.equals(podServiceAccount)) {
      throw new IllegalStateException("migration Job lacks the dedicated service account");
    }
    if (identity == null
        || !identity.isService(MIGRATOR_SERVICE)
        || !identity.isInNamespace(podNamespace)) {
      throw new IllegalStateException(
          "migration client certificate has the wrong workload identity");
    }
  }

  private GrpcPeerIdentity verifiedWorkloadIdentity() throws Exception {
    Path certificatePath = Path.of(required("FIREMUD_GRPC_CERT_CHAIN_PATH"));
    X509Certificate leaf;
    try (InputStream input = Files.newInputStream(certificatePath)) {
      leaf = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
    return GrpcPeerIdentity.fromCertificate(leaf)
        .orElseThrow(
            () ->
                new IllegalStateException("migration client certificate lacks one valid URI SAN"));
  }

  private long optionalLong(String property, long fallback) {
    String value = environment.getProperty(property);
    return value == null || value.isBlank() ? fallback : Long.parseLong(value);
  }

  private long requiredPositiveLong(String property) {
    long value = Long.parseLong(required(property));
    if (value < 1) {
      throw new IllegalArgumentException(property + " must be positive");
    }
    return value;
  }

  private String required(String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " is required");
    }
    return value;
  }
}
