package net.firedevops.firemud.accountservice.maintenance;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.service.impl.LegacyTenantAssociationImportService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
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
  private final ObjectProvider<ScheduledAnnotationBeanPostProcessor> schedulingPostProcessor;
  private final DataSource dataSource;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected Spring collaborators are internal to the migration Job.")
  public LegacyTenantAssociationImportJobRunner(
      Environment environment,
      LegacyTenantAssociationImportService importService,
      ObjectProvider<ScheduledAnnotationBeanPostProcessor> schedulingPostProcessor,
      DataSource dataSource) {
    this.environment = environment;
    this.importService = importService;
    this.schedulingPostProcessor = schedulingPostProcessor;
    this.dataSource = dataSource;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (schedulingPostProcessor.getIfAvailable() != null) {
      throw new IllegalStateException(
          "Account tenant migration must not start with scheduling enabled");
    }
    if (!"true".equals(System.getenv("FIREMUD_ACCOUNT_TENANT_MIGRATION_ENABLED"))
        || !"none".equalsIgnoreCase(required("spring.main.web-application-type"))
        || environment.getProperty("spring.grpc.server.enabled", Boolean.class, true)) {
      throw new IllegalStateException("Account tenant migration must not start listeners");
    }
    String mode = required("firemud.account-tenant-migration.mode");
    requireDatabaseAuthority(
        mode,
        connectedDatabaseUsername(),
        environment.getProperty("spring.flyway.enabled", Boolean.class, true));
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

  static void requireDatabaseAuthority(String mode, String username, boolean flywayEnabled) {
    if (flywayEnabled) {
      throw new IllegalStateException("Account tenant migration requires Flyway to be disabled");
    }
    String requiredUsername =
        switch (mode) {
          case "evidence" -> "firemud_account_tenant_evidence";
          case "import" -> "firemud_account_tenant_import";
          default ->
              throw new IllegalArgumentException("unsupported Account tenant migration mode");
        };
    if (!requiredUsername.equals(username)) {
      throw new IllegalStateException(
          "Account tenant migration DB credential does not match the mode");
    }
  }

  String connectedDatabaseUsername() {
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData metadata = connection == null ? null : connection.getMetaData();
      if (metadata == null) {
        throw new IllegalStateException("Account tenant migration DB connection is unverifiable");
      }
      String username = metadata.getUserName();
      if (username == null || username.isBlank()) {
        throw new IllegalStateException(
            "Account tenant migration DB connection did not identify its user");
      }
      return username;
    } catch (SQLException failure) {
      // Avoid surfacing driver diagnostics that can echo the configured database username.
      throw new IllegalStateException(
          "Account tenant migration DB connection could not be verified");
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
