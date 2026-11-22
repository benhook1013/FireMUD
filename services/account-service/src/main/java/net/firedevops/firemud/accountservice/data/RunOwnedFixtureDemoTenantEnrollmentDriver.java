package net.firedevops.firemud.accountservice.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.service.FreshTenantIdentityEnrollmentService;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Enrolls the run-owned Compose fixture's fresh Game Design tenant into Account and provisions its
 * non-paid demo entitlement, so public REALMS and JOIN can admit players to it. The fixture receipt
 * only locates the creation; Account imports the authenticated Game Design readback. Retries until
 * Game Design has committed the fixture, and replays idempotently after a restart. Under the pre-v1
 * gate waivers it also establishes the fresh fixture's player usage and refusal budget for PLAY.
 */
public final class RunOwnedFixtureDemoTenantEnrollmentDriver {
  private static final Logger LOG =
      LoggerFactory.getLogger(RunOwnedFixtureDemoTenantEnrollmentDriver.class);
  private static final long RETRY_INTERVAL_MS = 10_000L;
  private static final int MAX_FILE_BYTES = 16 * 1024;
  private static final DemoTenantEntitlementRequest.Quotas FIXTURE_QUOTAS =
      new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L);
  static final int PRE_V1_CAPACITY_ORIGIN_REFUSALS = 128;

  private final FreshTenantIdentityEnrollmentService enrollment;
  private final AccountDemoTenantEntitlementRepository demos;
  private final DSLContext dsl;
  private final boolean preV1Waivers;
  private final Path capabilityPath;
  private final Path receiptPath;
  private final String runId;
  private final String composeProjectName;
  private final AtomicBoolean enrolled = new AtomicBoolean();

  public RunOwnedFixtureDemoTenantEnrollmentDriver(
      FreshTenantIdentityEnrollmentService enrollment,
      AccountDemoTenantEntitlementRepository demos,
      DSLContext dsl,
      boolean preV1Waivers,
      Path capabilityPath,
      Path receiptPath,
      String runId,
      String composeProjectName) {
    this.enrollment = Objects.requireNonNull(enrollment, "enrollment");
    this.demos = Objects.requireNonNull(demos, "demos");
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.preV1Waivers = preV1Waivers;
    this.capabilityPath = Objects.requireNonNull(capabilityPath, "capabilityPath");
    this.receiptPath = Objects.requireNonNull(receiptPath, "receiptPath");
    this.runId = requireText(runId, "runId");
    this.composeProjectName = requireText(composeProjectName, "composeProjectName");
  }

  @Scheduled(initialDelay = RETRY_INTERVAL_MS, fixedDelay = RETRY_INTERVAL_MS)
  public void enrollWhenReady() {
    if (enrolled.get()) return;
    try {
      FreshTenantCreationEvidence creation = enroll();
      enrolled.set(true);
      LOG.info(
          "Run-owned fixture tenant {} enrolled with its demo entitlement",
          creation.canonicalTenantId());
    } catch (IOException | RuntimeException notYet) {
      LOG.warn("Run-owned fixture tenant enrollment not complete yet: {}", notYet.toString());
    }
  }

  public boolean enrolled() {
    return enrolled.get();
  }

  FreshTenantCreationEvidence enroll() throws IOException {
    var capability = RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(read(capabilityPath));
    if (!runId.equals(capability.runId())
        || !composeProjectName.equals(capability.composeProjectName())) {
      throw new IllegalStateException("Fixture capability belongs to another run");
    }
    if (!Files.exists(receiptPath)) {
      throw new IllegalStateException("Game Design has not published the fixture receipt yet");
    }
    var receipt =
        RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(read(receiptPath), capability);
    FreshTenantCreationEvidence located = Objects.requireNonNull(receipt.creationEvidence());
    // The receipt locates the source. The owner readback must match it before initialization.
    return enrollment.enrollAndInitialize(
        located.creationRequestId(),
        located.requestDigest(),
        creation -> initialize(creation, receipt));
  }

  private void initialize(FreshTenantCreationEvidence creation, SourceReceipt receipt) {
    if (!creation.equals(receipt.creationEvidence())) {
      throw new IllegalStateException(
          "Authenticated tenant creation differs from the fixture receipt");
    }
    if (preV1Waivers) {
      initializePlayerCapacity(creation, receipt);
      dsl.execute(
          "INSERT INTO account_capacity_origin_refusal_budget"
              + " (tenant_uuid, maximum_refusals) VALUES (?, ?) ON CONFLICT DO NOTHING",
          creation.canonicalTenantId(),
          PRE_V1_CAPACITY_ORIGIN_REFUSALS);
    }
    var request =
        new DemoTenantEntitlementRequest(
            UUID.nameUUIDFromBytes(
                ("firemud.run-owned-fixture.demo-entitlement:" + creation.creationRequestId())
                    .getBytes(StandardCharsets.UTF_8)),
            creation.canonicalTenantId(),
            creation.creationRequestId(),
            creation.requestDigest(),
            null,
            null,
            null,
            true,
            true,
            true,
            true,
            FIXTURE_QUOTAS);
    demos.provision(request, creation);
  }

  private void initializePlayerCapacity(
      FreshTenantCreationEvidence creation, SourceReceipt receipt) {
    UUID tenantId = creation.canonicalTenantId();
    UUID operationId = UUID.fromString(receipt.fixtureOperationId());
    String policyDigest = receipt.fixturePolicySeal().evidenceDigest();
    if (!operationId.equals(creation.creationRequestId())) {
      throw new IllegalStateException(
          "Fixture capacity requires the original Game Design creation request");
    }
    dsl.execute(
        "INSERT INTO account_run_owned_fixture_capacity_birth"
            + " (tenant_uuid, fixture_operation_id, run_id, compose_project, creation_evidence_digest,"
            + " fixture_policy_evidence_digest) VALUES (?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (tenant_uuid) DO NOTHING",
        tenantId,
        operationId,
        runId,
        composeProjectName,
        creation.evidenceDigest(),
        policyDigest);
    boolean matches =
        Boolean.TRUE.equals(
            dsl.fetchValue(
                "SELECT EXISTS (SELECT 1 FROM account_run_owned_fixture_capacity_birth birth"
                    + " JOIN account_tenant_capacity_usage usage"
                    + " ON usage.fixture_birth_tenant_uuid = birth.tenant_uuid"
                    + " WHERE birth.tenant_uuid = ? AND birth.fixture_operation_id = ?"
                    + " AND birth.run_id = ? AND birth.compose_project = ?"
                    + " AND birth.creation_evidence_digest = ? AND birth.fixture_policy_evidence_digest = ?"
                    + " AND usage.tenant_uuid = birth.tenant_uuid"
                    + " AND usage.owner_service = 'game-session-service'"
                    + " AND usage.creation_evidence_digest = birth.creation_evidence_digest)",
                tenantId,
                operationId,
                runId,
                composeProjectName,
                creation.evidenceDigest(),
                policyDigest));
    if (!matches) {
      throw new IllegalStateException("Run-owned fixture capacity birth did not read back exactly");
    }
  }

  private static byte[] read(Path path) throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
      if (bytes.length > MAX_FILE_BYTES) {
        throw new IllegalStateException("Fixture file exceeds its size bound: " + path);
      }
      return bytes;
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
