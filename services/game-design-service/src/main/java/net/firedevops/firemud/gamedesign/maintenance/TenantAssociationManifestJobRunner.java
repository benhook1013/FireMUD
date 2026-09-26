package net.firedevops.firemud.gamedesign.maintenance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest.Signed;
import net.firedevops.firemud.gamedesign.service.impl.TenantAssociationMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Opt-in, owner-approved one-shot migration; no service mutation endpoint is exposed. */
@Component
@ConditionalOnProperty(
    prefix = "firemud.tenant-association-migration",
    name = "enabled",
    havingValue = "true")
public class TenantAssociationManifestJobRunner implements ApplicationRunner {
  private static final Logger LOG =
      LoggerFactory.getLogger(TenantAssociationManifestJobRunner.class);
  private static final String MIGRATOR_SERVICE_ACCOUNT = "game-design-tenant-migrator";

  private final Environment environment;
  private final ObjectMapper objectMapper;
  private final TenantAssociationMigrationService migrationService;

  public TenantAssociationManifestJobRunner(
      Environment environment,
      ObjectMapper objectMapper,
      TenantAssociationMigrationService migrationService) {
    this.environment = environment;
    this.objectMapper = objectMapper.copy();
    this.migrationService = migrationService;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (!"true".equals(System.getenv("FIREMUD_TENANT_ASSOCIATION_MIGRATION_ENABLED"))
        || !"none".equalsIgnoreCase(required("spring.main.web-application-type"))
        || environment.getProperty("spring.grpc.server.enabled", Boolean.class, true)
        || environment.getProperty("firemud.temporal.enabled", Boolean.class, true)) {
      throw new IllegalStateException("tenant migration must not start listeners or workers");
    }
    String podNamespace = required("firemud.tenant-association-migration.pod-namespace");
    String targetNamespace = required("firemud.tenant-association-migration.target-namespace");
    if (!podNamespace.equals(targetNamespace)
        || !MIGRATOR_SERVICE_ACCOUNT.equals(
            required("firemud.tenant-association-migration.pod-service-account"))) {
      throw new IllegalStateException("tenant migration Job has the wrong workload scope");
    }
    requireJobIdentity(podNamespace, verifiedWorkloadIdentity());
    Path manifestPath =
        Path.of(required("firemud.tenant-association-migration.signed-manifest-path"));
    Path publicKeysPath =
        Path.of(required("firemud.tenant-association-migration.owner-public-keys-path"));
    ObjectMapper strictMapper =
        objectMapper
            .copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    Signed signed = strictMapper.readValue(Files.readAllBytes(manifestPath), Signed.class);
    Map<String, String> trustedKeys =
        strictMapper.readValue(
            Files.readAllBytes(publicKeysPath), new TypeReference<Map<String, String>>() {});
    var verified = TenantAssociationManifestVerifier.verify(signed, trustedKeys);
    if (!targetNamespace.equals(verified.manifest().targetNamespace())) {
      throw new IllegalStateException("signed tenant manifest targets a different namespace");
    }
    String mode = required("firemud.tenant-association-migration.mode");
    if ("preflight".equals(mode)) {
      LOG.info(
          "Tenant association preflight operationId={} manifestDigest={} entries={} namespace={}",
          verified.manifest().operationId(),
          verified.manifestDigest(),
          verified.manifest().entries().size(),
          targetNamespace);
    } else if ("apply".equals(mode)) {
      var applied = migrationService.apply(signed, trustedKeys, targetNamespace);
      LOG.info(
          "Tenant association applied operationId={} manifestDigest={} readbackEntries={} namespace={}",
          verified.manifest().operationId(),
          verified.manifestDigest(),
          applied.size(),
          targetNamespace);
    } else {
      throw new IllegalArgumentException("unsupported tenant migration Job mode");
    }
  }

  static void requireJobIdentity(String podNamespace, GrpcPeerIdentity identity) {
    if (identity == null
        || !identity.isService(MIGRATOR_SERVICE_ACCOUNT)
        || !identity.isInNamespace(podNamespace)) {
      throw new IllegalStateException("tenant migration certificate has the wrong identity");
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
            () -> new IllegalStateException("tenant migration certificate lacks a valid URI SAN"));
  }

  private String required(String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " is required");
    }
    return value;
  }
}
