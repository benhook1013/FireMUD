package net.firedevops.firemud.accountservice.maintenance;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import net.firedevops.firemud.accountservice.service.impl.LegacyTenantAssociationImportService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Opt-in Account owner import; ordinary service startup performs no legacy mapping. */
@Component
@ConditionalOnProperty(
    prefix = "firemud.account-tenant-migration",
    name = "enabled",
    havingValue = "true")
public class LegacyTenantAssociationImportJobRunner implements ApplicationRunner {
  private static final Logger LOG =
      LoggerFactory.getLogger(LegacyTenantAssociationImportJobRunner.class);

  private final Environment environment;
  private final LegacyTenantAssociationImportService importService;

  public LegacyTenantAssociationImportJobRunner(
      Environment environment, LegacyTenantAssociationImportService importService) {
    this.environment = environment;
    this.importService = importService;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (!"true".equals(System.getenv("FIREMUD_ACCOUNT_TENANT_MIGRATION_ENABLED"))
        || !"none".equalsIgnoreCase(required("spring.main.web-application-type"))
        || environment.getProperty("spring.grpc.server.enabled", Boolean.class, true)) {
      throw new IllegalStateException("Account tenant migration must not start listeners");
    }
    String podNamespace = required("firemud.account-tenant-migration.pod-namespace");
    String targetNamespace = required("firemud.account-tenant-migration.target-namespace");
    if (!podNamespace.equals(targetNamespace)
        || !"account-tenant-migrator"
            .equals(required("firemud.account-tenant-migration.pod-service-account"))) {
      throw new IllegalStateException("Account tenant migration Job has the wrong workload scope");
    }
    GrpcPeerIdentity identity = verifiedWorkloadIdentity();
    requireJobIdentity(podNamespace, identity);
    long legacyTenantId =
        Long.parseLong(required("firemud.account-tenant-migration.legacy-tenant-id"));
    if (legacyTenantId <= 0) {
      throw new IllegalArgumentException("legacy Account tenant key must be positive");
    }
    String mode = required("firemud.account-tenant-migration.mode");
    if ("evidence".equals(mode)) {
      LOG.info(
          "Account tenant evidence legacyTenantId={} digest={} namespace={}",
          legacyTenantId,
          importService.retainedEvidenceDigest(legacyTenantId),
          targetNamespace);
    } else if ("import".equals(mode)) {
      var imported = importService.importApproved(legacyTenantId);
      LOG.info(
          "Account tenant association imported legacyTenantId={} canonicalTenantId={} "
              + "operationId={} manifestDigest={} namespace={}",
          legacyTenantId,
          imported.canonicalTenantId(),
          imported.operationId(),
          imported.manifestDigest(),
          targetNamespace);
    } else {
      throw new IllegalArgumentException("unsupported Account tenant migration Job mode");
    }
  }

  static void requireJobIdentity(String podNamespace, GrpcPeerIdentity identity) {
    if (identity == null
        || !identity.isService("account-tenant-migrator")
        || !identity.isInNamespace(podNamespace)) {
      throw new IllegalStateException(
          "Account tenant migration certificate has the wrong identity");
    }
  }

  private GrpcPeerIdentity verifiedWorkloadIdentity() throws Exception {
    Path certificatePath = Path.of(required("FIREMUD_GRPC_CERT_CHAIN_PATH"));
    X509Certificate leaf;
    try (InputStream input = Files.newInputStream(certificatePath)) {
      leaf = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
    return GrpcPeerIdentity.fromCertificate(leaf)
        .orElseThrow(() -> new IllegalStateException("Account certificate lacks a valid URI SAN"));
  }

  private String required(String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " is required");
    }
    return value;
  }
}
