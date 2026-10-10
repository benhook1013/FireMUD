package integration.net.firedevops.firemud.accountservice.hostedterms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff.DisclosureResult;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff.Kind;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff.Outcome;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoffRepository;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoffRepository.Status;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Owner-store proof only. Authority and disclosure evidence below is explicitly test-only; this
 * does not authenticate a publisher or implement an external dispatch/receipt owner.
 */
@Testcontainers(disabledWithoutDocker = true)
class HostedTermsDisclosureHandoffPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void exactRetryAmbiguityAndTerminalEvidenceAreDurableAndImmutable() throws Exception {
    Database database = database();
    DraftAuthorizationFenceRepository fences =
        new DraftAuthorizationFenceRepository(database.dsl());
    HostedTermsDisclosureHandoffRepository journal =
        new HostedTermsDisclosureHandoffRepository(database.dsl(), fences);
    HostedTermsDisclosureHandoff handoff = handoff(source("journal"));

    var prepared = tx(database, () -> journal.prepare(handoff));
    assertThat(prepared.status()).isEqualTo(Status.PREPARED);
    assertThat(prepared.dispatchAttempts()).isZero();
    var preparedRetry = tx(database, () -> journal.prepare(handoff));
    assertThat(preparedRetry.handoff().canonicalBytes()).containsExactly(handoff.canonicalBytes());
    assertThat(preparedRetry.status()).isEqualTo(Status.PREPARED);

    HostedTermsDisclosureHandoff conflict =
        new HostedTermsDisclosureHandoff(
            UUID.randomUUID(),
            handoff.requestId(),
            handoff.kind(),
            handoff.sourceKey(),
            handoff.predecessorDigest(),
            HostedTermsEncoding.digest(new byte[] {99}),
            handoff.effectiveAt(),
            handoff.sources(),
            "test-only-conflicting-authority-bytes".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> tx(database, () -> journal.prepare(conflict)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicts");

    var firstPermit = tx(database, () -> journal.authorizeDispatch(handoff));
    assertThat(firstPermit.newlyAuthorized()).isTrue();
    assertThat(firstPermit.snapshot().status()).isEqualTo(Status.DISPATCH_AUTHORIZED);
    assertThat(firstPermit.snapshot().dispatchAttempts()).isEqualTo(1);
    assertThat(firstPermit.snapshot().result()).isEmpty();
    var lostPermitRecovery = tx(database, () -> journal.authorizeDispatch(handoff));
    assertThat(lostPermitRecovery.newlyAuthorized()).isFalse();
    assertThat(lostPermitRecovery.snapshot().dispatchAttempts()).isEqualTo(1);

    assertThat(tx(database, () -> journal.recordAmbiguous(handoff)).status())
        .isEqualTo(Status.AMBIGUOUS);
    var retryPermit = tx(database, () -> journal.authorizeDispatch(handoff));
    assertThat(retryPermit.newlyAuthorized()).isTrue();
    assertThat(retryPermit.snapshot().dispatchAttempts()).isEqualTo(2);

    DisclosureResult mismatchedBindingResult =
        new DisclosureResult(
            handoff.handoffId(),
            handoff.requestId(),
            HostedTermsEncoding.digest(new byte[] {42}),
            Outcome.DISCLOSED,
            "test-only-mismatched-owner-readback".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(
            () -> tx(database, () -> journal.recordResult(handoff, mismatchedBindingResult)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not bound to the exact handoff");
    var afterMismatchedBinding = tx(database, () -> journal.read(handoff.handoffId()));
    assertThat(afterMismatchedBinding).isPresent();
    assertThat(afterMismatchedBinding.orElseThrow().status()).isEqualTo(Status.DISPATCH_AUTHORIZED);
    assertThat(afterMismatchedBinding.orElseThrow().result()).isEmpty();

    byte[] testOnlyOwnerReadback =
        "test-only-authenticated-disclosure-receipt".getBytes(StandardCharsets.UTF_8);
    DisclosureResult result =
        new DisclosureResult(
            handoff.handoffId(),
            handoff.requestId(),
            HostedTermsEncoding.digest(handoff.canonicalBytes()),
            Outcome.DISCLOSED,
            testOnlyOwnerReadback);
    var disclosed = tx(database, () -> journal.recordResult(handoff, result));
    assertThat(disclosed.status()).isEqualTo(Status.DISCLOSED);
    assertThat(disclosed.result().orElseThrow().canonicalBytes())
        .containsExactly(result.canonicalBytes());
    var resultRetry = tx(database, () -> journal.recordResult(handoff, result));
    assertThat(resultRetry.result().orElseThrow().canonicalBytes())
        .containsExactly(result.canonicalBytes());
    Database restarted = reopen(database);
    var restartedRead =
        tx(
            restarted,
            () ->
                new HostedTermsDisclosureHandoffRepository(
                        restarted.dsl(), new DraftAuthorizationFenceRepository(restarted.dsl()))
                    .read(handoff.handoffId()));
    assertThat(restartedRead).isPresent();
    assertThat(restartedRead.orElseThrow().status()).isEqualTo(Status.DISCLOSED);
    assertThat(restartedRead.orElseThrow().handoff().canonicalBytes())
        .containsExactly(handoff.canonicalBytes());

    DisclosureResult changedResult =
        new DisclosureResult(
            handoff.handoffId(),
            handoff.requestId(),
            HostedTermsEncoding.digest(handoff.canonicalBytes()),
            Outcome.DISCLOSED,
            "test-only-changed-owner-readback".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> tx(database, () -> journal.recordResult(handoff, changedResult)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Changed terminal");
    assertThatThrownBy(
            () ->
                tx(
                    database,
                    () -> {
                      database
                          .dsl()
                          .execute(
                              "UPDATE account_hosted_terms_disclosure_handoffs "
                                  + "SET result_payload = ? WHERE handoff_id = ?",
                              "test-only-overwrite".getBytes(StandardCharsets.UTF_8),
                              handoff.handoffId());
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);

    HostedTermsDisclosureHandoff noDisclosure = handoff(source("definitive-no-disclosure"));
    tx(database, () -> journal.prepare(noDisclosure));
    assertThatThrownBy(
            () ->
                tx(
                    database,
                    () ->
                        journal.recordResult(
                            noDisclosure,
                            new DisclosureResult(
                                noDisclosure.handoffId(),
                                noDisclosure.requestId(),
                                HostedTermsEncoding.digest(noDisclosure.canonicalBytes()),
                                Outcome.DEFINITIVELY_NOT_DISCLOSED,
                                "test-only-premature-negative".getBytes(StandardCharsets.UTF_8)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dispatch authorization");
    tx(database, () -> journal.authorizeDispatch(noDisclosure));
    tx(database, () -> journal.recordAmbiguous(noDisclosure));
    DisclosureResult negativeResult =
        new DisclosureResult(
            noDisclosure.handoffId(),
            noDisclosure.requestId(),
            HostedTermsEncoding.digest(noDisclosure.canonicalBytes()),
            Outcome.DEFINITIVELY_NOT_DISCLOSED,
            "test-only-authenticated-definitive-no-disclosure".getBytes(StandardCharsets.UTF_8));
    var noDisclosureSnapshot =
        tx(database, () -> journal.recordResult(noDisclosure, negativeResult));
    assertThat(noDisclosureSnapshot.status()).isEqualTo(Status.DEFINITIVELY_NOT_DISCLOSED);
    assertThat(noDisclosureSnapshot.handoff().effectiveAt()).isEqualTo(noDisclosure.effectiveAt());
    assertThat(noDisclosureSnapshot.result().orElseThrow().canonicalBytes())
        .containsExactly(negativeResult.canonicalBytes());
  }

  @Test
  void preparationRefusesReservedAndUnsettledCommitOrderWithoutRewritingEither() throws Exception {
    Database database = database();
    DraftAuthorizationFenceRepository fences =
        new DraftAuthorizationFenceRepository(database.dsl());
    HostedTermsDisclosureHandoffRepository journal =
        new HostedTermsDisclosureHandoffRepository(database.dsl(), fences);
    SourceEvidence source = source("fenced-operation");
    DraftAuthorizationFenceBinding original = draftBinding(source);
    HostedTermsDisclosureHandoff handoff = handoff(source);

    tx(database, () -> fences.reserve(original));
    assertThatThrownBy(() -> tx(database, () -> journal.prepare(handoff)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsettled original Draft operation");
    assertThat(tx(database, () -> fences.read(original).ordering()))
        .isEqualTo(DraftAuthorizationFenceRepository.Ordering.RESERVED);

    tx(database, () -> fences.claimCommitOrder(original));
    assertThatThrownBy(() -> tx(database, () -> journal.prepare(handoff)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsettled original Draft operation");
    assertThat(tx(database, () -> fences.read(original).ordering()))
        .isEqualTo(DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER);

    tx(
        database,
        () -> {
          recordOwnerResult(fences, original, Owner.GAME_DESIGN);
          return null;
        });
    tx(
        database,
        () -> {
          recordOwnerResult(fences, original, Owner.WORLD);
          return null;
        });
    var prepared = tx(database, () -> journal.prepare(handoff));
    assertThat(prepared.status()).isEqualTo(Status.PREPARED);
    assertThat(tx(database, () -> fences.read(original).ordering()))
        .isEqualTo(DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER);
  }

  @Test
  void preparedHandoffAllowsOriginalOrderingButDispatchWaitsForExactSettlement() throws Exception {
    Database database = database();
    DraftAuthorizationFenceRepository fences =
        new DraftAuthorizationFenceRepository(database.dsl());
    HostedTermsDisclosureHandoffRepository journal =
        new HostedTermsDisclosureHandoffRepository(database.dsl(), fences);
    SourceEvidence source = source("prepare-first");
    HostedTermsDisclosureHandoff handoff = handoff(source);
    DraftAuthorizationFenceBinding original = draftBinding(source);

    tx(database, () -> journal.prepare(handoff));
    tx(database, () -> fences.reserve(original));
    assertDispatchUnsettled(database, journal, handoff);
    tx(database, () -> fences.claimCommitOrder(original));
    assertDispatchUnsettled(database, journal, handoff);
    tx(
        database,
        () -> {
          recordOwnerResult(fences, original, Owner.GAME_DESIGN);
          return null;
        });
    assertDispatchUnsettled(database, journal, handoff);
    tx(
        database,
        () -> {
          recordOwnerResult(fences, original, Owner.WORLD);
          return null;
        });
    assertThat(tx(database, () -> journal.authorizeDispatch(handoff)).newlyAuthorized()).isTrue();
    assertThat(tx(database, () -> fences.reserve(original)).ordering())
        .isEqualTo(DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER);
    assertThat(tx(database, () -> fences.claimCommitOrder(original)).ordering())
        .isEqualTo(DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER);
    assertDisclosureBlocked(database, fences, draftBinding(source));
  }

  @Test
  void dispatchGuardUsesOnlyExactChangedTermsEvidenceAndDefinitiveNegativeReleasesIt()
      throws Exception {
    Database database = database();
    DraftAuthorizationFenceRepository fences =
        new DraftAuthorizationFenceRepository(database.dsl());
    HostedTermsDisclosureHandoffRepository journal =
        new HostedTermsDisclosureHandoffRepository(database.dsl(), fences);
    SourceEvidence changed = source("exact-changed-source");
    SourceEvidence unrelated = source("unrelated-terms-source");
    SourceEvidence account =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            UUID.randomUUID().toString(),
            "1",
            "1",
            null,
            null,
            "test-only-account-evidence".getBytes(StandardCharsets.UTF_8));
    HostedTermsDisclosureHandoff original = handoff(changed);
    HostedTermsDisclosureHandoff handoff =
        new HostedTermsDisclosureHandoff(
            original.handoffId(),
            original.requestId(),
            original.kind(),
            original.sourceKey(),
            original.predecessorDigest(),
            original.candidateDigest(),
            original.effectiveAt(),
            List.of(changed, unrelated, account),
            original.authenticatedAuthorityEvidence());
    tx(database, () -> journal.prepare(handoff));
    tx(database, () -> journal.authorizeDispatch(handoff));
    assertDisclosureBlocked(database, fences, draftBinding(changed));
    // Other vector members and newer source bytes cannot inherit the changed source's guard.
    tx(database, () -> fences.reserve(draftBinding(unrelated)));
    tx(database, () -> fences.reserve(draftBinding(account)));
    SourceEvidence newer =
        new SourceEvidence(
            changed.kind(),
            changed.scopeId(),
            changed.generation(),
            "8",
            null,
            null,
            "test-only-newer-terms-evidence".getBytes(StandardCharsets.UTF_8));
    DraftAuthorizationFenceBinding newerOriginal = draftBinding(newer);
    tx(database, () -> fences.reserve(newerOriginal));
    tx(database, () -> fences.claimCommitOrder(newerOriginal));
    tx(database, () -> journal.recordAmbiguous(handoff));
    assertDisclosureBlocked(database, fences, draftBinding(changed));
    assertThat(tx(database, () -> journal.authorizeDispatch(handoff)).newlyAuthorized()).isTrue();
    tx(
        database,
        () ->
            journal.recordResult(
                handoff,
                new DisclosureResult(
                    handoff.handoffId(),
                    handoff.requestId(),
                    HostedTermsEncoding.digest(handoff.canonicalBytes()),
                    Outcome.DEFINITIVELY_NOT_DISCLOSED,
                    "test-only-definitive-negative".getBytes(StandardCharsets.UTF_8))));
    DraftAuthorizationFenceBinding afterNegative = draftBinding(changed);
    tx(database, () -> fences.reserve(afterNegative));
    tx(database, () -> fences.claimCommitOrder(afterNegative));

    SourceEvidence disclosedSource = source("disclosed-guard");
    HostedTermsDisclosureHandoff disclosed = handoff(disclosedSource);
    tx(database, () -> journal.prepare(disclosed));
    tx(database, () -> journal.authorizeDispatch(disclosed));
    tx(
        database,
        () ->
            journal.recordResult(
                disclosed,
                new DisclosureResult(
                    disclosed.handoffId(),
                    disclosed.requestId(),
                    HostedTermsEncoding.digest(disclosed.canonicalBytes()),
                    Outcome.DISCLOSED,
                    "test-only-disclosed-result".getBytes(StandardCharsets.UTF_8))));
    assertDisclosureBlocked(database, fences, draftBinding(disclosedSource));
    SourceEvidence differentBytes =
        new SourceEvidence(
            disclosedSource.kind(),
            disclosedSource.scopeId(),
            disclosedSource.generation(),
            disclosedSource.sourceVersion(),
            null,
            null,
            "test-only-different-source-bytes".getBytes(StandardCharsets.UTF_8));
    tx(database, () -> fences.reserve(draftBinding(differentBytes)));
    SourceEvidence newerDisclosed =
        new SourceEvidence(
            disclosedSource.kind(),
            disclosedSource.scopeId(),
            disclosedSource.generation(),
            "8",
            null,
            null,
            "test-only-new-version".getBytes(StandardCharsets.UTF_8));
    tx(database, () -> fences.reserve(draftBinding(newerDisclosed)));
  }

  @Test
  void sourceLocksSerializeConcurrentOriginalAcquisitionBeforeDispatch() throws Exception {
    concurrentAcquisitionAndDispatch(false);
  }

  @Test
  void sourceLocksSerializeConcurrentDispatchBeforeOriginalAcquisition() throws Exception {
    concurrentAcquisitionAndDispatch(true);
  }

  private static void concurrentAcquisitionAndDispatch(boolean dispatchFirst) throws Exception {
    Database database = database();
    DraftAuthorizationFenceRepository fences =
        new DraftAuthorizationFenceRepository(database.dsl());
    HostedTermsDisclosureHandoffRepository journal =
        new HostedTermsDisclosureHandoffRepository(database.dsl(), fences);
    SourceEvidence source = source("concurrent-" + dispatchFirst);
    HostedTermsDisclosureHandoff handoff = handoff(source);
    DraftAuthorizationFenceBinding original = draftBinding(source);
    tx(database, () -> journal.prepare(handoff));
    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    AtomicInteger firstPid = new AtomicInteger();
    AtomicInteger secondPid = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  tx(
                      database,
                      () -> {
                        firstPid.set(
                            Objects.requireNonNull(
                                    database.dsl().fetchOne("SELECT pg_backend_pid()"),
                                    "Expected first transaction PostgreSQL backend PID row")
                                .get(0, Integer.class));
                        if (dispatchFirst) {
                          journal.authorizeDispatch(handoff);
                        } else {
                          fences.reserve(original);
                          fences.claimCommitOrder(original);
                        }
                        firstLocked.countDown();
                        await(releaseFirst);
                        return null;
                      }));
      assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
      var second =
          executor.submit(
              () ->
                  tx(
                      database,
                      () -> {
                        secondPid.set(
                            Objects.requireNonNull(
                                    database.dsl().fetchOne("SELECT pg_backend_pid()"),
                                    "Expected second transaction PostgreSQL backend PID row")
                                .get(0, Integer.class));
                        secondStarted.countDown();
                        if (dispatchFirst) {
                          fences.reserve(original);
                        } else {
                          journal.authorizeDispatch(handoff);
                        }
                        return null;
                      }));
      assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitBlocked(database, secondPid.get(), firstPid.get());
      assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      releaseFirst.countDown();
      first.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage(
              dispatchFirst
                  ? "Exact hosted terms source has authorized disclosure"
                  : "Disclosure deadline cannot pass an unsettled original Draft operation");
      assertThat(tx(database, () -> journal.read(handoff.handoffId())).orElseThrow().status())
          .isEqualTo(dispatchFirst ? Status.DISPATCH_AUTHORIZED : Status.PREPARED);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  private static void awaitBlocked(Database database, int waitingPid, int blockerPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          Objects.requireNonNull(
                  database
                      .dsl()
                      .fetchOne("SELECT ? = ANY(pg_blocking_pids(?))", blockerPid, waitingPid),
                  "Expected PostgreSQL blocking-state row")
              .get(0, Boolean.class))) {
        return;
      }
      Thread.sleep(10);
    }
    throw new IllegalStateException(
        "Expected original and dispatch transactions to share source locks");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for test transaction release");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted waiting for test transaction release", interrupted);
    }
  }

  private static void assertDispatchUnsettled(
      Database database,
      HostedTermsDisclosureHandoffRepository journal,
      HostedTermsDisclosureHandoff handoff) {
    assertThatThrownBy(() -> tx(database, () -> journal.authorizeDispatch(handoff)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsettled original Draft operation");
    assertThat(tx(database, () -> journal.read(handoff.handoffId())).orElseThrow().status())
        .isEqualTo(Status.PREPARED);
  }

  private static void assertDisclosureBlocked(
      Database database,
      DraftAuthorizationFenceRepository fences,
      DraftAuthorizationFenceBinding binding) {
    assertThatThrownBy(() -> tx(database, () -> fences.reserve(binding)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Exact hosted terms source has authorized disclosure");
  }

  private static void recordOwnerResult(
      DraftAuthorizationFenceRepository fences,
      DraftAuthorizationFenceBinding binding,
      Owner owner) {
    fences.recordOwnerReadback(
        binding,
        new OwnerReadback(
            owner,
            DraftAuthorizationFenceBinding.Outcome.COMMITTED,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            binding.canonicalBytes(),
            "test-only-owner-result".getBytes(StandardCharsets.UTF_8)));
  }

  private static SourceEvidence source(String scopeSuffix) {
    return new SourceEvidence(
        SourceKind.HOSTED_TERMS,
        UUID.nameUUIDFromBytes(scopeSuffix.getBytes(StandardCharsets.UTF_8)).toString(),
        null,
        "7",
        null,
        null,
        ("test-only-source-evidence-" + scopeSuffix).getBytes(StandardCharsets.UTF_8));
  }

  private static HostedTermsDisclosureHandoff handoff(SourceEvidence source) {
    return new HostedTermsDisclosureHandoff(
        UUID.randomUUID(),
        UUID.randomUUID(),
        Kind.CATALOG,
        source.key(),
        HostedTermsEncoding.digest("test-only-predecessor".getBytes(StandardCharsets.UTF_8)),
        HostedTermsEncoding.digest("test-only-candidate".getBytes(StandardCharsets.UTF_8)),
        Instant.parse("2026-10-08T00:00:00Z"),
        List.of(source),
        "test-only-authority-evidence".getBytes(StandardCharsets.UTF_8));
  }

  private static DraftAuthorizationFenceBinding draftBinding(SourceEvidence source) {
    UUID actor = UUID.randomUUID();
    DraftCommitBinding complete =
        DraftCommitBinding.create(
            new TargetProof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "test-only-tenant-key",
                2,
                "test-only-tenant-key",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-only-base-commit",
            List.of(
                new RevisionPayload(
                    "0", UUID.randomUUID(), DraftCommitBinding.Owner.WORLD_MANAGEMENT, "payload")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "region-1",
                    "aggregate",
                    "region-1",
                    "0")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        actor,
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "1",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        List.of(source));
  }

  private static Database database() throws Exception {
    String schema = "terms_disclosure_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema="
                + schema,
            postgres.getUsername(),
            postgres.getPassword());
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(source);
    Database database =
        new Database(
            schema,
            source,
            DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES),
            transactionManager,
            new TransactionTemplate(transactionManager));
    Flyway.configure()
        .dataSource(database.source())
        .schemas(database.schema())
        .defaultSchema(database.schema())
        .placeholders(Map.of("serviceSchema", database.schema()))
        .locations("classpath:db/migration")
        .target("latest")
        .load()
        .migrate();
    return database;
  }

  private static Database reopen(Database original) {
    DataSourceTransactionManager transactionManager =
        new DataSourceTransactionManager(original.source());
    return new Database(
        original.schema(),
        original.source(),
        DSL.using(new TransactionAwareDataSourceProxy(original.source()), SQLDialect.POSTGRES),
        transactionManager,
        new TransactionTemplate(transactionManager));
  }

  private static <T> T tx(Database database, Supplier<T> work) {
    return database.transactions().execute(status -> work.get());
  }

  private record Database(
      String schema,
      DriverManagerDataSource source,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transactions) {}
}
