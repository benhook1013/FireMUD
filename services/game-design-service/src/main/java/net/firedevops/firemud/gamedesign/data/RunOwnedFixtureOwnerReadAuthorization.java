package net.firedevops.firemud.gamedesign.data;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Path;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.smoke.RunOwnedFixtureImmutableFileReader;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Fixed operation authorization backed by preexisting producer owners; not a wire issuer. */
@Service
@ConditionalOnProperty(
    name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED",
    havingValue = "true")
public class RunOwnedFixtureOwnerReadAuthorization {
  private final RunOwnedFixtureLaunchReadback readback;
  private final RunOwnedFixturePolicyProducer policy;
  private final String namespace;
  private final String runId;
  private final String project;
  private final String capabilityPath;
  private final String receiptPath;
  private final String sessionCertificate;
  private final String worldCertificate;
  private final String caCertificate;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Framework-managed owner collaborators are retained; invalid immutable fixture configuration intentionally refuses startup.")
  public RunOwnedFixtureOwnerReadAuthorization(
      RunOwnedFixtureLaunchReadback readback,
      RunOwnedFixturePolicyProducer policy,
      @Value("${firemud.grpc.workload-namespace:}") String namespace,
      @Value("${FIREMUD_SMOKE_RUN_ID:}") String runId,
      @Value("${FIREMUD_SMOKE_COMPOSE_PROJECT_NAME:}") String project,
      @Value(
              "${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH:/app/run-owned-initial-admission-capability.json}")
          String capabilityPath,
      @Value(
              "${FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_PATH:/app/run-owned-authored-world-source/source-receipt.json}")
          String receiptPath,
      @Value("${FIREMUD_SMOKE_GAME_SESSION_LEAF_CERT_PATH:/app/game-session-fixture-client.crt}")
          String sessionCertificate,
      @Value(
              "${FIREMUD_SMOKE_WORLD_MANAGEMENT_LEAF_CERT_PATH:/app/world-management-fixture-client.crt}")
          String worldCertificate,
      @Value("${firemud.grpc.ca-cert:certs/ca.crt}") String caCertificate) {
    this.readback = Objects.requireNonNull(readback);
    this.policy = Objects.requireNonNull(policy);
    this.namespace = namespace;
    this.runId = runId;
    this.project = project;
    this.capabilityPath = capabilityPath;
    this.receiptPath = receiptPath;
    this.sessionCertificate = sessionCertificate;
    this.worldCertificate = worldCertificate;
    this.caCertificate = caCertificate;
    // Receipt publication happens after startup seeding. Configuration/certificates must already
    // be valid, while missing or uncommitted receipt evidence is refused at every actual read.
    configuration();
  }

  public SourceReceipt authorizeSource(ResolveAuthoredWorldSourceRequest wire) {
    var trusted = authenticatedConfiguration();
    SourceReceipt receipt = receipt(trusted);
    var request = AuthoredWorldSourceGrpcCodec.fromReadRequest(namespace, wire);
    var source = receipt.sourceEvidence();
    if (!request.targetNamespace().equals(source.targetNamespace())
        || !request.operationId().equals(source.operationId())
        || !request.canonicalTenantId().equals(source.canonicalTenantId())
        || !request.worldSlug().equals(source.worldSlug())) {
      throw new SecurityException("Fixed fixture source selector differs");
    }
    qualifyOwners(trusted, receipt);
    return receipt;
  }

  public SourceReceipt authorizeVersion(GetAuthoredWorldVersionStateRequest wire) {
    var trusted = authenticatedConfiguration();
    SourceReceipt receipt = receipt(trusted);
    var request = AuthoredWorldVersionStateGrpcCodec.fromRequest(wire);
    var source = receipt.sourceEvidence();
    if (!namespace.equals(request.targetNamespace())
        || !request.canonicalTenantId().equals(source.canonicalTenantId())
        || !request.worldSlug().equals(source.worldSlug())
        || !request.sourceOperationId().equals(source.operationId())
        || !request.expectedSourceEvidenceDigest().equals(source.evidenceDigest())
        || request.versionId() != receipt.launchReadEvidence().versionState().getVersionId()) {
      throw new SecurityException("Fixed fixture Version selector differs");
    }
    qualifyOwners(trusted, receipt);
    return receipt;
  }

  private TestDataSeeder.FixtureCapability authenticatedConfiguration() {
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw new SecurityException("Fixture owner reads reject caller context");
    }
    var peer = GrpcPeerIdentity.current();
    var certificate = GrpcPeerCertificateEvidence.current();
    if (peer == null
        || certificate == null
        || !peer.uri().equals("spiffe://firemud/ns/dev/sa/world-management-service")) {
      throw new SecurityException("Fixed World TLS peer required");
    }
    var trusted = configuration();
    if (!peer.isInNamespace(namespace)
        || !certificate.leafSha256().equals(trusted.worldManagementLeafSha256())) {
      throw new SecurityException("Fixed World TLS leaf differs");
    }
    return trusted;
  }

  private TestDataSeeder.FixtureCapability configuration() {
    return TestDataSeeder.verifyFixtureConfiguration(
        namespace,
        runId,
        project,
        capabilityPath,
        receiptPath,
        sessionCertificate,
        worldCertificate,
        caCertificate);
  }

  private SourceReceipt receipt(TestDataSeeder.FixtureCapability trusted) {
    byte[] bytes =
        RunOwnedFixtureImmutableFileReader.readBoundedImmutableFile(
            Path.of(receiptPath),
            16 * 1024,
            () -> new IllegalStateException("Fixed fixture receipt unavailable"));
    try {
      return RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(bytes, trusted.wire());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("Fixed fixture receipt invalid", invalid);
    }
  }

  private void qualifyOwners(TestDataSeeder.FixtureCapability trusted, SourceReceipt receipt) {
    var committed = readback.readCommittedReceipt(receipt);
    var actualSeal =
        policy.readExisting(
            trusted, receipt.creationEvidence(), committed.snapshot(), committed.descriptor());
    if (!actualSeal.equals(receipt.fixturePolicySeal())) {
      throw new IllegalStateException("Fixed receipt policy seal differs from committed owner");
    }
  }
}
