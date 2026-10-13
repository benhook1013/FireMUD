package integration.net.firedevops.firemud.accountservice.hostedterms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsCatalogVersion;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
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
import org.jooq.Record;
import org.jooq.SQLDialect;
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
 * PostgreSQL workflow proof. Operator publication and authenticated identity rows are explicit
 * test-only upstream fixtures; these tests do not prove live legal publication or identity.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountHostedTermsPostgresIntegrationTest {
  private static final String TEST_NAMESPACE = "account-hosted-terms-isolated-test";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void migrationRetainsExistingPartyAndBackfillsNoCatalogOrAcceptance() throws Exception {
    Database db = database("84");
    UUID accountId = insertAccount(db);
    IndividualCreatorPartySource party =
        party(UUID.randomUUID(), accountId, 1, "test-id-evidence-v1");
    tx(db, () -> insertParty(db, party));
    Map<String, Object> accountBefore =
        Objects.requireNonNull(
                db.dsl().fetchOne("SELECT * FROM accounts WHERE account_uuid = ?", accountId))
            .intoMap();
    Map<String, Object> partyBefore =
        new HashMap<>(
            Objects.requireNonNull(
                    db.dsl()
                        .fetchOne(
                            "SELECT * FROM account_individual_creator_party_sources WHERE creator_party_id = ?",
                            party.creatorPartyId()))
                .intoMap());
    byte[] partySourceBefore = (byte[]) partyBefore.remove("source_payload");

    migrate(db, "85");

    assertThat(
            Objects.requireNonNull(
                    db.dsl().fetchOne("SELECT * FROM accounts WHERE account_uuid = ?", accountId))
                .intoMap())
        .isEqualTo(accountBefore);
    Map<String, Object> partyAfter =
        new HashMap<>(
            Objects.requireNonNull(
                    db.dsl()
                        .fetchOne(
                            "SELECT * FROM account_individual_creator_party_sources WHERE creator_party_id = ?",
                            party.creatorPartyId()))
                .intoMap());
    byte[] partySourceAfter = (byte[]) partyAfter.remove("source_payload");
    assertThat(partyAfter).isEqualTo(partyBefore);
    assertThat(partySourceAfter).containsExactly(partySourceBefore);
    assertThat(count(db, "account_hosted_terms_scopes")).isZero();
    assertThat(count(db, "account_hosted_terms_catalog_versions")).isZero();
    assertThat(count(db, "account_hosted_terms_publication_operations")).isZero();
    assertThat(count(db, "account_individual_hosted_terms_acceptances")).isZero();
  }

  @Test
  void realServicePublishesAffirmativelyAcceptsReadsBackAndAdvancesMateriality() throws Exception {
    Database db = database("latest");
    UUID accountId = insertAccount(db);
    UUID partyId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    IndividualCreatorPartySource party = party(partyId, accountId, 1, "test-id-evidence-v1");
    tx(db, () -> insertParty(db, party));
    Map<UUID, AccountHostedTermsService.PublicationEvidence> publications = new HashMap<>();
    Map<UUID, AccountHostedTermsService.AcceptanceAction> actions = new HashMap<>();
    AccountHostedTermsService service = service(db, publications, actions);

    UUID firstRequest = UUID.randomUUID();
    byte[] originalDocument = new byte[] {0, 1, 2, (byte) 0xff, 13, 10};
    publications.put(
        firstRequest,
        publication(
            scopeId,
            originalDocument,
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.INITIAL,
            now().minusSeconds(5)));
    AccountHostedTermsService.PublicationResult first = service.publish(firstRequest);
    assertThat(first.status()).isEqualTo(HostedTermsRepository.PublicationStatus.COMMITTED);
    assertThat(first.candidate().documentBytes()).containsExactly(originalDocument);
    assertThat(first.candidate().documentDigest())
        .isEqualTo(HostedTermsEncoding.digest(originalDocument));
    assertThat(service.publish(firstRequest)).isEqualTo(first); // lost-result exact recovery
    publications.put(
        firstRequest,
        publication(
            scopeId,
            "changed request payload".getBytes(),
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.INITIAL,
            now().minusSeconds(5)));
    assertThatThrownBy(() -> service.publish(firstRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicts");
    publications.put(
        firstRequest,
        publication(
            scopeId,
            originalDocument,
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.INITIAL,
            first.candidate().effectiveAt()));
    assertThat(count(db, "account_hosted_terms_catalog_versions")).isEqualTo(1);

    UUID firstActionId = UUID.randomUUID();
    AccountHostedTermsService.AcceptanceAction firstAction =
        action(firstActionId, accountId, partyId, scopeId, first.candidate(), true);
    actions.put(firstActionId, firstAction);
    var accepted = service.accept(firstActionId);
    assertThat(accepted.evidenceId()).isNotNull();
    assertThat(accepted.termsVersionId()).isEqualTo(first.candidate().versionId());
    assertThat(accepted.acceptedAt()).isNotNull(); // generated by PostgreSQL, not the caller action
    assertThat(service.accept(firstActionId)).isEqualTo(accepted);
    actions.put(
        firstActionId,
        new AccountHostedTermsService.AcceptanceAction(
            firstAction.actionRequestId(),
            true,
            firstAction.accountId(),
            firstAction.creatorPartyId(),
            firstAction.hostedScopeId(),
            firstAction.shownVersionId(),
            HostedTermsEncoding.digest("changed shown document".getBytes()),
            firstAction.shownOperatorLegalIdentity(),
            firstAction.shownOperatorIdentityVersion(),
            firstAction.authenticatedActionReference(),
            firstAction.authenticatedActionVersion(),
            firstAction.actionCreatedAt()));
    assertThatThrownBy(() -> service.accept(firstActionId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicts");
    assertThat(count(db, "account_individual_hosted_terms_acceptances")).isEqualTo(1);
    var current = service.requireCurrentness(scopeId, partyId);
    assertThat(current.acceptanceEvidenceId()).isEqualTo(accepted.evidenceId());
    assertThat(current.materialGeneration()).isEqualTo(1);

    UUID secondRequest = UUID.randomUUID();
    publications.put(
        secondRequest,
        publication(
            scopeId,
            "material-v2".getBytes(),
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.MATERIAL,
            now().minusSeconds(3)));
    AccountHostedTermsService.PublicationResult material = service.publish(secondRequest);
    assertThat(material.candidate().sourceVersion()).isEqualTo(2);
    assertThat(material.candidate().materialGeneration()).isEqualTo(2);
    assertThatThrownBy(() -> service.requireCurrentness(scopeId, partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("affirmative party-wide");

    UUID secondActionId = UUID.randomUUID();
    AccountHostedTermsService.AcceptanceAction secondAction =
        action(secondActionId, accountId, partyId, scopeId, material.candidate(), true);
    actions.put(secondActionId, secondAction);
    var reaccepted = service.accept(secondActionId);
    assertThat(service.requireCurrentness(scopeId, partyId).acceptanceEvidenceId())
        .isEqualTo(reaccepted.evidenceId());

    UUID thirdRequest = UUID.randomUUID();
    publications.put(
        thirdRequest,
        publication(
            scopeId,
            "nonmaterial-v3".getBytes(),
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.NONMATERIAL,
            now().minusSeconds(2)));
    AccountHostedTermsService.PublicationResult nonmaterial = service.publish(thirdRequest);
    assertThat(nonmaterial.candidate().sourceVersion()).isEqualTo(3);
    assertThat(nonmaterial.candidate().materialGeneration()).isEqualTo(2);
    assertThat(service.requireCurrentness(scopeId, partyId).acceptanceEvidenceId())
        .isEqualTo(reaccepted.evidenceId()); // prior exact acceptance remains in active ancestry

    UUID operatorChangeRequest = UUID.randomUUID();
    publications.put(
        operatorChangeRequest,
        publication(
            scopeId,
            "operator-change-v4".getBytes(),
            "Replacement Test Operator",
            2,
            HostedTermsCatalogVersion.Materiality.MATERIAL,
            now().minusSeconds(1)));
    AccountHostedTermsService.PublicationResult operatorChange =
        service.publish(operatorChangeRequest);
    assertThat(operatorChange.candidate().sourceVersion()).isEqualTo(4);
    assertThat(operatorChange.candidate().materialGeneration()).isEqualTo(3);
    assertThat(operatorChange.candidate().operatorLegalIdentity())
        .isEqualTo("Replacement Test Operator");
    assertThatThrownBy(() -> service.requireCurrentness(scopeId, partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("affirmative party-wide");
  }

  @Test
  void acceptanceRejectsWrongSignerNegativeActionChangedShownTermsAndUnavailableIdentity()
      throws Exception {
    Database db = database("latest");
    UUID accountId = insertAccount(db);
    UUID partyId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    IndividualCreatorPartySource party = party(partyId, accountId, 1, "test-id-evidence-v1");
    tx(db, () -> insertParty(db, party));
    Map<UUID, AccountHostedTermsService.PublicationEvidence> publications = new HashMap<>();
    Map<UUID, AccountHostedTermsService.AcceptanceAction> actions = new HashMap<>();
    AccountHostedTermsService service = service(db, publications, actions);
    UUID publicationId = UUID.randomUUID();
    publications.put(
        publicationId,
        publication(
            scopeId,
            "test-terms".getBytes(),
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.INITIAL,
            now().minusSeconds(1)));
    HostedTermsCatalogVersion terms = service.publish(publicationId).candidate();

    UUID negativeId = UUID.randomUUID();
    actions.put(negativeId, action(negativeId, accountId, partyId, scopeId, terms, false));
    assertThatThrownBy(() -> service.accept(negativeId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Affirmative");

    UUID wrongAccountId = UUID.randomUUID();
    UUID wrongSignerId = UUID.randomUUID();
    actions.put(
        wrongSignerId, action(wrongSignerId, wrongAccountId, partyId, scopeId, terms, true));
    assertThatThrownBy(() -> service.accept(wrongSignerId))
        .isInstanceOf(IllegalStateException.class);

    UUID wrongPartyId = UUID.randomUUID();
    actions.put(
        wrongPartyId, action(wrongPartyId, accountId, UUID.randomUUID(), scopeId, terms, true));
    assertThatThrownBy(() -> service.accept(wrongPartyId))
        .isInstanceOf(IllegalStateException.class);

    UUID mismatchId = UUID.randomUUID();
    AccountHostedTermsService.AcceptanceAction correct =
        action(mismatchId, accountId, partyId, scopeId, terms, true);
    actions.put(
        mismatchId,
        new AccountHostedTermsService.AcceptanceAction(
            correct.actionRequestId(),
            true,
            correct.accountId(),
            correct.creatorPartyId(),
            correct.hostedScopeId(),
            correct.shownVersionId(),
            HostedTermsEncoding.digest("different document".getBytes()),
            correct.shownOperatorLegalIdentity(),
            correct.shownOperatorIdentityVersion(),
            correct.authenticatedActionReference(),
            correct.authenticatedActionVersion(),
            correct.actionCreatedAt()));
    assertThatThrownBy(() -> service.accept(mismatchId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shown catalog");

    assertThat(count(db, "account_individual_hosted_terms_acceptances")).isZero();
  }

  @Test
  void futureTermsCarryDeadlineWithoutFreezingPriorTermsEarly() throws Exception {
    Database db = database("latest");
    UUID accountId = insertAccount(db);
    UUID partyId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    IndividualCreatorPartySource party = party(partyId, accountId, 1, "test-id-evidence-v1");
    tx(db, () -> insertParty(db, party));
    Map<UUID, AccountHostedTermsService.PublicationEvidence> publications = new HashMap<>();
    Map<UUID, AccountHostedTermsService.AcceptanceAction> actions = new HashMap<>();
    AccountHostedTermsService service = service(db, publications, actions);
    UUID initialRequest = UUID.randomUUID();
    publications.put(
        initialRequest,
        publication(
            scopeId,
            "test-terms-v1".getBytes(),
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.INITIAL,
            now().minusSeconds(1)));
    HostedTermsCatalogVersion initial = service.publish(initialRequest).candidate();
    UUID actionId = UUID.randomUUID();
    actions.put(actionId, action(actionId, accountId, partyId, scopeId, initial, true));
    var accepted = service.accept(actionId);

    UUID futureRequest = UUID.randomUUID();
    Instant deadline = databaseNow(db).plusSeconds(5).truncatedTo(ChronoUnit.MICROS);
    publications.put(
        futureRequest,
        publication(
            scopeId,
            "test-terms-v2".getBytes(),
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.MATERIAL,
            deadline));
    AccountHostedTermsService.PublicationResult scheduled = service.publish(futureRequest);
    assertThat(scheduled.status()).isEqualTo(HostedTermsRepository.PublicationStatus.SCHEDULED);
    UUID advanceAcceptanceId = UUID.randomUUID();
    AccountHostedTermsService.AcceptanceAction advanceAcceptance =
        action(advanceAcceptanceId, accountId, partyId, scopeId, scheduled.candidate(), true);
    actions.put(advanceAcceptanceId, advanceAcceptance);
    var acceptedInAdvance = service.accept(advanceAcceptanceId);
    assertThat(acceptedInAdvance.termsVersionId()).isEqualTo(scheduled.candidate().versionId());
    var beforeDeadline = service.requireCurrentness(scopeId, partyId);
    assertThat(beforeDeadline.acceptanceEvidenceId()).isEqualTo(accepted.evidenceId());
    assertThat(beforeDeadline.validUntil()).isEqualTo(deadline);
    assertThat(count(db, "account_individual_hosted_terms_acceptances")).isEqualTo(2);
    assertThat(service.resumePublication(futureRequest).status())
        .isEqualTo(HostedTermsRepository.PublicationStatus.SCHEDULED);

    assertThat(
            currentnessBlockedAcrossEffectiveDateIsDenied(db, service, scopeId, partyId, deadline))
        .isTrue();
  }

  @Test
  void futureTermsCannotPassUnsettledOriginalCommitOrderAndExactRetryWaitsForSettlement()
      throws Exception {
    Database db = database("latest");
    UUID accountId = insertAccount(db);
    UUID partyId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    IndividualCreatorPartySource party = party(partyId, accountId, 1, "test-id-evidence-v1");
    tx(db, () -> insertParty(db, party));
    Map<UUID, AccountHostedTermsService.PublicationEvidence> publications = new HashMap<>();
    Map<UUID, AccountHostedTermsService.AcceptanceAction> actions = new HashMap<>();
    AccountHostedTermsService service = service(db, publications, actions);

    UUID initialRequest = UUID.randomUUID();
    publications.put(
        initialRequest,
        publication(
            scopeId,
            "test-terms-v1".getBytes(),
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.INITIAL,
            databaseNow(db).minusSeconds(10)));
    HostedTermsCatalogVersion initial = service.publish(initialRequest).candidate();
    UUID actionId = UUID.randomUUID();
    actions.put(actionId, action(actionId, accountId, partyId, scopeId, initial, true));
    service.accept(actionId);

    AccountHostedTermsService.CurrentnessEvidence beforeDeadline =
        service.requireCurrentness(scopeId, partyId);
    assertThat(beforeDeadline.disclosedDeadline()).isNull();
    SourceEvidence currentTermsSource =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS,
            scopeId.toString(),
            Long.toString(beforeDeadline.materialGeneration()),
            Long.toString(beforeDeadline.sourceVersion()),
            null,
            null,
            beforeDeadline.exactCurrentnessSource());
    DraftAuthorizationFenceBinding originalOrder =
        testDraftBinding(accountId, List.of(currentTermsSource));
    DraftAuthorizationFenceRepository fences = new DraftAuthorizationFenceRepository(db.dsl());
    tx(
        db,
        () -> {
          fences.reserve(originalOrder);
          fences.claimCommitOrder(originalOrder);
        });

    HostedTermsRepository repository = new HostedTermsRepository(db.dsl());
    tx(db, () -> repository.ensureScope(scopeId));
    var scopeAfterEnsure =
        Objects.requireNonNull(
            db.dsl()
                .fetchOne(
                    "SELECT current_version_id, current_source_version, "
                        + "current_material_generation FROM account_hosted_terms_scopes "
                        + "WHERE hosted_scope_id = ?",
                    scopeId));
    assertThat(scopeAfterEnsure.get("current_version_id", UUID.class))
        .isEqualTo(initial.versionId());
    assertThat(scopeAfterEnsure.get("current_source_version", Long.class))
        .isEqualTo(initial.sourceVersion());
    assertThat(scopeAfterEnsure.get("current_material_generation", Long.class))
        .isEqualTo(initial.materialGeneration());
    assertThatThrownBy(
            () ->
                tx(
                    db,
                    () ->
                        db.dsl()
                            .execute(
                                "UPDATE account_hosted_terms_scopes "
                                    + "SET current_version_id = ?, current_source_version = ?, "
                                    + "current_material_generation = ? WHERE hosted_scope_id = ?",
                                UUID.randomUUID(),
                                2L,
                                2L,
                                scopeId)))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("Required creator source is held");
    var scopeAfterRejectedWrite =
        Objects.requireNonNull(
            db.dsl()
                .fetchOne(
                    "SELECT current_version_id, current_source_version, "
                        + "current_material_generation FROM account_hosted_terms_scopes "
                        + "WHERE hosted_scope_id = ?",
                    scopeId));
    assertThat(scopeAfterRejectedWrite.get("current_version_id", UUID.class))
        .isEqualTo(initial.versionId());
    assertThat(scopeAfterRejectedWrite.get("current_source_version", Long.class))
        .isEqualTo(initial.sourceVersion());
    assertThat(scopeAfterRejectedWrite.get("current_material_generation", Long.class))
        .isEqualTo(initial.materialGeneration());

    UUID futureRequest = UUID.randomUUID();
    Instant deadline = databaseNow(db).plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    publications.put(
        futureRequest,
        publication(
            scopeId,
            "test-terms-v2".getBytes(),
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.MATERIAL,
            deadline));
    assertThatThrownBy(() -> service.publish(futureRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Disclosure deadline cannot pass an unsettled original Draft operation");
    assertThat(
            db.dsl()
                .fetchOne(
                    "SELECT request_id FROM account_hosted_terms_publication_operations "
                        + "WHERE request_id = ?",
                    futureRequest))
        .isNull();
    assertThat(service.requireCurrentness(scopeId, partyId).disclosedDeadline()).isNull();
    String originalOrdering =
        db.transactions().execute(ignored -> fences.read(originalOrder).ordering().name());
    assertThat(originalOrdering).isEqualTo("COMMIT_ORDER");

    tx(
        db,
        () -> {
          fences.recordOwnerReadback(
              originalOrder, testReadback(originalOrder, Owner.WORLD, Outcome.COMMITTED));
          fences.recordOwnerReadback(
              originalOrder, testReadback(originalOrder, Owner.GAME_DESIGN, Outcome.COMMITTED));
        });
    AccountHostedTermsService.PublicationResult scheduled = service.publish(futureRequest);
    assertThat(scheduled.status()).isEqualTo(HostedTermsRepository.PublicationStatus.SCHEDULED);
    assertThat(scheduled.candidate().effectiveAt()).isEqualTo(deadline);
    assertThat(service.publish(futureRequest)).isEqualTo(scheduled);
    assertThat(service.requireCurrentness(scopeId, partyId).disclosedDeadline())
        .isEqualTo(scheduled.candidate());
    assertThat(service.requireCurrentness(scopeId, partyId).validUntil()).isEqualTo(deadline);
  }

  private static boolean currentnessBlockedAcrossEffectiveDateIsDenied(
      Database db, AccountHostedTermsService service, UUID scopeId, UUID partyId, Instant deadline)
      throws Exception {
    CountDownLatch scopeLocked = new CountDownLatch(1);
    CountDownLatch releaseScope = new CountDownLatch(1);
    AtomicInteger blockerPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> blocker =
        executor.submit(
            () ->
                db.transactions()
                    .executeWithoutResult(
                        ignored -> {
                          blockerPid.set(
                              Objects.requireNonNull(
                                      db.dsl().fetchOne("SELECT pg_backend_pid() AS pid"))
                                  .get("pid", Integer.class));
                          db.dsl()
                              .fetchOne(
                                  "SELECT hosted_scope_id FROM account_hosted_terms_scopes "
                                      + "WHERE hosted_scope_id = ? FOR UPDATE",
                                  scopeId);
                          scopeLocked.countDown();
                          awaitLatch(releaseScope, "Hosted scope blocker was not released");
                        }));
    try {
      assertThat(scopeLocked.await(5, TimeUnit.SECONDS)).isTrue();
      Future<AccountHostedTermsService.CurrentnessEvidence> waiter =
          executor.submit(() -> service.requireCurrentness(scopeId, partyId));
      Record waiting = awaitHostedScopeLockWait(db, blockerPid.get(), deadline);
      assertThat(waiting.get("query_start", OffsetDateTime.class).toInstant())
          .as("currentness lock wait must begin before the disclosed effective date")
          .isBefore(deadline);

      awaitDatabaseTime(db, deadline);
      releaseScope.countDown();
      try {
        waiter.get(5, TimeUnit.SECONDS);
        throw new AssertionError("Currentness returned after the disclosed terms deadline");
      } catch (ExecutionException failure) {
        assertThat(failure.getCause())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("effective date has arrived");
      }
      blocker.get(5, TimeUnit.SECONDS);
      return true;
    } finally {
      releaseScope.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static Record awaitHostedScopeLockWait(Database db, int blockerPid, Instant deadline)
      throws InterruptedException {
    long timeoutAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < timeoutAt) {
      Record waiting =
          db.dsl()
              .fetchOne(
                  "SELECT waiting.pid, waiting.query_start, waiting.xact_start "
                      + "FROM pg_stat_activity waiting "
                      + "WHERE waiting.wait_event_type = 'Lock' "
                      + "AND waiting.query ILIKE '%account_hosted_terms_scopes%' "
                      + "AND waiting.query ILIKE '%for update%' "
                      + "AND ? = ANY(pg_blocking_pids(waiting.pid)) LIMIT 1",
                  blockerPid);
      if (waiting != null) {
        assertThat(waiting.get("xact_start", OffsetDateTime.class).toInstant())
            .as("currentness transaction must also have begun before the deadline")
            .isBefore(deadline);
        return waiting;
      }
      Thread.sleep(10L);
    }
    throw new AssertionError("Currentness did not reach the hosted-scope lock wait");
  }

  private static void awaitDatabaseTime(Database db, Instant deadline) throws InterruptedException {
    long timeoutAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < timeoutAt) {
      Instant databaseTime = databaseNow(db);
      if (!databaseTime.isBefore(deadline)) {
        return;
      }
      Thread.sleep(10L);
    }
    throw new AssertionError("PostgreSQL clock did not reach the disclosed effective date");
  }

  private static Instant databaseNow(Database db) {
    return Objects.requireNonNull(db.dsl().fetchOne("SELECT clock_timestamp() AS database_now"))
        .get("database_now", OffsetDateTime.class)
        .toInstant();
  }

  private static void awaitLatch(CountDownLatch latch, String message) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException(message);
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(message, failure);
    }
  }

  @Test
  void ownerTransactionRollbackRemovesUncommittedCatalogAndPublicationIntent() throws Exception {
    Database db = database("latest");
    HostedTermsRepository repository = new HostedTermsRepository(db.dsl());
    UUID scopeId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    byte[] request = "test-only publication".getBytes();
    HostedTermsCatalogVersion candidate =
        catalogCandidate(
            scopeId,
            "test-only terms".getBytes(),
            "Test Operator",
            1,
            HostedTermsCatalogVersion.Materiality.INITIAL,
            now().minusSeconds(1),
            UUID.randomUUID());

    assertThatThrownBy(
            () ->
                tx(
                    db,
                    () ->
                        db.transactions()
                            .executeWithoutResult(
                                status -> {
                                  repository.ensureScope(scopeId);
                                  repository.claimPublication(requestId, scopeId, request);
                                  repository.lockScope(scopeId);
                                  repository.insertCandidate(candidate);
                                  throw new IllegalStateException("test-only rollback");
                                })))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rollback");

    assertThat(count(db, "account_hosted_terms_scopes")).isZero();
    assertThat(count(db, "account_hosted_terms_catalog_versions")).isZero();
    assertThat(count(db, "account_hosted_terms_publication_operations")).isZero();
  }

  private static AccountHostedTermsService service(
      Database db,
      Map<UUID, AccountHostedTermsService.PublicationEvidence> publications,
      Map<UUID, AccountHostedTermsService.AcceptanceAction> actions) {
    IndividualCreatorPartyRepository individualParties =
        new IndividualCreatorPartyRepository(
            db.dsl(),
            new FreshTenantIdentityAssociationRepository(db.dsl(), TEST_NAMESPACE),
            new AccountTenantCreationBootstrapOperationRepository(db.dsl()));
    return new AccountHostedTermsService(
        db.transactionManager(),
        new HostedTermsRepository(db.dsl()),
        individualParties,
        new DraftAuthorizationFenceRepository(db.dsl()),
        requestId ->
            Objects.requireNonNull(publications.get(requestId), "test-only operator fixture"),
        actionId ->
            Objects.requireNonNull(actions.get(actionId), "test-only identity/action fixture"));
  }

  private static DraftAuthorizationFenceBinding testDraftBinding(
      UUID actorAccountId, List<SourceEvidence> sources) {
    UUID tenantId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID commitId = UUID.randomUUID();
    DraftCommitBinding complete =
        DraftCommitBinding.create(
            new TargetProof(tenantId, versionId, 1, "tenant-key", 2, "tenant-key", "NEW_GAME_ROW"),
            requestId,
            commitId,
            "opaque/base:1",
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "test-only")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "test-region",
                    "aggregate",
                    "test-region",
                    "0")));
    return new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            requestId,
            commitId,
            UUID.randomUUID(),
            actorAccountId,
            tenantId,
            versionId,
            complete.baseCommitId(),
            "0",
            complete.canonicalBytes(),
            complete.canonicalBytes(),
            complete.digest(),
            sources)
        .withRequiredOwners();
  }

  private static OwnerReadback testReadback(
      DraftAuthorizationFenceBinding binding, Owner owner, Outcome outcome) {
    return new OwnerReadback(
        owner,
        outcome,
        binding.operationId(),
        binding.commitId(),
        binding.fenceId(),
        binding.inputDigest(),
        binding.canonicalBytes(),
        new byte[] {1});
  }

  private static AccountHostedTermsService.PublicationEvidence publication(
      UUID scope,
      byte[] document,
      String operator,
      long operatorVersion,
      HostedTermsCatalogVersion.Materiality materiality,
      Instant effectiveAt) {
    return new AccountHostedTermsService.PublicationEvidence(
        scope,
        operator,
        operatorVersion,
        document,
        materiality,
        materiality == HostedTermsCatalogVersion.Materiality.INITIAL
            ? null
            : "test-only-materiality",
        materiality == HostedTermsCatalogVersion.Materiality.INITIAL ? null : 1L,
        "test-only-publication-evidence",
        1,
        "test-only-notice-evidence",
        1,
        effectiveAt.truncatedTo(ChronoUnit.MICROS));
  }

  private static HostedTermsCatalogVersion catalogCandidate(
      UUID scope,
      byte[] document,
      String operator,
      long operatorVersion,
      HostedTermsCatalogVersion.Materiality materiality,
      Instant effectiveAt,
      UUID versionId) {
    return new HostedTermsCatalogVersion(
        versionId,
        scope,
        null,
        operator,
        operatorVersion,
        document,
        HostedTermsEncoding.digest(document),
        1,
        1,
        HostedTermsCatalogVersion.Materiality.INITIAL,
        null,
        null,
        "test-only-publication-evidence",
        1,
        "test-only-notice-evidence",
        1,
        effectiveAt.truncatedTo(ChronoUnit.MICROS));
  }

  private static AccountHostedTermsService.AcceptanceAction action(
      UUID actionId,
      UUID accountId,
      UUID partyId,
      UUID scopeId,
      HostedTermsCatalogVersion terms,
      boolean affirmative) {
    return new AccountHostedTermsService.AcceptanceAction(
        actionId,
        affirmative,
        accountId,
        partyId,
        scopeId,
        terms.versionId(),
        terms.documentDigest(),
        terms.operatorLegalIdentity(),
        terms.operatorIdentityVersion(),
        "test-only-authenticated-action",
        1,
        OffsetDateTime.ofInstant(now(), ZoneOffset.UTC));
  }

  private static IndividualCreatorPartySource party(
      UUID partyId, UUID accountId, long identityVersion, String evidenceRef) {
    return new IndividualCreatorPartySource(
        partyId,
        accountId,
        VerificationStatus.VERIFIED,
        identityVersion,
        "test-only-verification-policy",
        1L,
        evidenceRef,
        1L,
        1);
  }

  private static void insertParty(Database db, IndividualCreatorPartySource party) {
    byte[] source = CreatorPartyEncoding.party(party);
    db.dsl()
        .execute(
            "INSERT INTO account_individual_creator_party_sources "
                + "(creator_party_id, account_uuid, verification_status, identity_version, "
                + "policy_reference, policy_version, verification_evidence_reference, "
                + "verification_evidence_version, source_version, source_payload, source_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            party.creatorPartyId(),
            party.accountId(),
            party.verificationStatus().name(),
            party.identityVersion(),
            party.policyReference(),
            party.policyVersion(),
            party.verificationEvidenceReference(),
            party.verificationEvidenceVersion(),
            party.sourceVersion(),
            source,
            CreatorPartyEncoding.digest(source));
  }

  private static UUID insertAccount(Database db) {
    UUID accountId = UUID.randomUUID();
    db.dsl()
        .execute(
            "INSERT INTO accounts (account_uuid, username, email, password_hash) "
                + "VALUES (?, ?, ?, 'test-only-password-hash')",
            accountId,
            "hosted-" + accountId.toString().substring(0, 8),
            accountId + "@example.test");
    return accountId;
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static long count(Database db, String table) {
    return Objects.requireNonNull(
        Objects.requireNonNull(db.dsl().fetchOne("SELECT count(*) AS count FROM " + table))
            .get("count", Long.class));
  }

  private static Database database(String target) throws Exception {
    String schema = "hosted_terms_" + UUID.randomUUID().toString().replace("-", "");
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
    Database db =
        new Database(
            schema,
            source,
            DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES),
            transactionManager,
            new TransactionTemplate(transactionManager));
    migrate(db, target);
    return db;
  }

  private static void migrate(Database db, String target) {
    Flyway.configure()
        .dataSource(db.source())
        .schemas(db.schema())
        .defaultSchema(db.schema())
        .placeholders(Map.of("serviceSchema", db.schema()))
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private static void tx(Database db, Runnable work) {
    db.transactions().executeWithoutResult(status -> work.run());
  }

  private record Database(
      String schema,
      DriverManagerDataSource source,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transactions) {}
}
