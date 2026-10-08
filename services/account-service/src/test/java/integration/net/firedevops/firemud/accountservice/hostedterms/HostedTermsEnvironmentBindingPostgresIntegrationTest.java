package integration.net.firedevops.firemud.accountservice.hostedterms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsCatalogVersion;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBindingEncoding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBindingRepository;
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
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof with explicitly test-only environment publisher and identity authorities. */
@Testcontainers(disabledWithoutDocker = true)
class HostedTermsEnvironmentBindingPostgresIntegrationTest {
  private static final String TEST_NAMESPACE = "hosted-terms-binding-isolated-test";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void realAccountWorkflowBindsCurrentEnvironmentAndFencesIndependentSourceVersions()
      throws Exception {
    Database db = database("97");
    UUID accountId = insertAccount(db);
    UUID partyId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    IndividualCreatorPartySource party = party(partyId, accountId);
    tx(db, () -> insertParty(db, party));
    Map<UUID, AccountHostedTermsService.PublicationEvidence> catalogPublications = new HashMap<>();
    Map<UUID, AccountHostedTermsService.AcceptanceAction> actions = new HashMap<>();
    Map<UUID, HostedTermsEnvironmentBinding.PublicationEvidence> bindingPublications =
        new HashMap<>();
    AtomicReference<HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary> boundary =
        new AtomicReference<>(currentBoundary("production"));
    AccountHostedTermsService service =
        service(db, catalogPublications, actions, bindingPublications, boundary);

    assertThat(count(db, "account_hosted_terms_environment_binding_heads")).isZero();
    assertThat(count(db, "account_hosted_terms_environment_bindings")).isZero();
    assertThat(count(db, "account_hosted_terms_environment_binding_publications")).isZero();

    UUID firstTermsRequest = UUID.randomUUID();
    catalogPublications.put(
        firstTermsRequest,
        publication(
            scopeId,
            "test-only terms version one".getBytes(),
            HostedTermsCatalogVersion.Materiality.INITIAL,
            now().minusSeconds(10)));
    HostedTermsCatalogVersion firstCatalog = service.publish(firstTermsRequest).candidate();
    UUID acceptanceRequest = UUID.randomUUID();
    actions.put(acceptanceRequest, action(acceptanceRequest, accountId, partyId, firstCatalog));
    var acceptance = service.accept(acceptanceRequest);

    UUID firstBindingRequest = UUID.randomUUID();
    HostedTermsEnvironmentBinding.PublicationEvidence firstBindingEvidence =
        bindingEvidence("production", firstCatalog, null, null, "test-only-publisher-v1");
    bindingPublications.put(firstBindingRequest, firstBindingEvidence);
    var firstBinding = service.publishEnvironmentBinding(firstBindingRequest);
    assertThat(firstBinding.status())
        .isEqualTo(HostedTermsEnvironmentBindingRepository.PublicationStatus.COMMITTED);
    assertThat(firstBinding.candidate().sourceVersion()).isOne();
    assertThat(firstBinding.exactReceipt())
        .containsExactly(HostedTermsEnvironmentBindingEncoding.receipt(firstBinding.candidate()));
    assertThat(service.publishEnvironmentBinding(firstBindingRequest)).isEqualTo(firstBinding);
    assertThat(
            Objects.requireNonNull(
                    db.dsl()
                        .fetchOne(
                            "SELECT current_binding_id FROM account_hosted_terms_environment_binding_heads "
                                + "WHERE environment_boundary = 'production'"))
                .get("current_binding_id", UUID.class))
        .isEqualTo(firstBinding.candidate().bindingId());

    HostedTermsEnvironmentBinding.PublicationEvidence changedRetry =
        bindingEvidence("production", firstCatalog, null, null, "changed-test-publisher");
    bindingPublications.put(firstBindingRequest, changedRetry);
    assertThatThrownBy(() -> service.publishEnvironmentBinding(firstBindingRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicts");
    bindingPublications.put(firstBindingRequest, firstBindingEvidence);

    var current = service.requireCurrentnessForCurrentEnvironment(partyId);
    var capturedBoundary = service.captureCurrentEnvironmentBoundary();
    var sameSnapshot =
        db.transactions()
            .execute(
                ignored -> service.requireCurrentnessInOwnerTransaction(capturedBoundary, partyId));
    assertThat(sameSnapshot.environment()).isEqualTo(current.environment());
    assertThat(sameSnapshot.binding()).isEqualTo(current.binding());
    assertThat(sameSnapshot.terms().acceptanceEvidenceId())
        .isEqualTo(current.terms().acceptanceEvidenceId());
    assertThat(sameSnapshot.terms().exactCurrentnessSource())
        .containsExactly(current.terms().exactCurrentnessSource());
    TransactionTemplate wrongIsolation = new TransactionTemplate(db.transactionManager());
    wrongIsolation.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(
            () ->
                wrongIsolation.execute(
                    ignored ->
                        service.requireCurrentnessInOwnerTransaction(capturedBoundary, partyId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_COMMITTED");
    TransactionTemplate readOnly = new TransactionTemplate(db.transactionManager());
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () ->
                readOnly.execute(
                    ignored ->
                        service.requireCurrentnessInOwnerTransaction(capturedBoundary, partyId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    assertThat(current.environment().environmentBoundary()).isEqualTo("production");
    assertThat(current.binding()).isEqualTo(firstBinding.candidate());
    assertThat(current.terms().acceptanceEvidenceId()).isEqualTo(acceptance.evidenceId());
    assertThat(current.sourceEvidence()).hasSize(3);
    assertThat(current.sourceEvidence())
        .anySatisfy(
            source -> {
              assertThat(source.kind()).isEqualTo(SourceKind.HOSTED_TERMS);
              assertThat(source.scopeId()).isEqualTo("environment-boundary/production");
              assertThat(source.generation()).isNull();
              assertThat(source.sourceVersion()).isEqualTo("1");
              assertThat(source.evidence())
                  .containsExactly(
                      HostedTermsEnvironmentBindingEncoding.receipt(firstBinding.candidate()));
            })
        .anySatisfy(
            source -> {
              assertThat(source.kind()).isEqualTo(SourceKind.HOSTED_TERMS);
              assertThat(source.scopeId()).isEqualTo(scopeId.toString());
              assertThat(source.generation()).isEqualTo("1");
              assertThat(source.sourceVersion()).isEqualTo("1");
              assertThat(new String(source.evidence(), StandardCharsets.UTF_8))
                  .contains("account-hosted-terms-currentness-source/v2")
                  .contains(HostedTermsEncoding.digest(HostedTermsEncoding.acceptance(acceptance)));
            })
        .anySatisfy(
            source -> {
              assertThat(source.kind()).isEqualTo(SourceKind.CREATOR_PARTY);
              assertThat(source.scopeId()).isEqualTo(partyId.toString());
              assertThat(source.generation()).isEqualTo("1");
              assertThat(source.sourceVersion()).isEqualTo("1");
              assertThat(source.evidence()).containsExactly(CreatorPartyEncoding.party(party));
            });

    UUID nextTermsRequest = UUID.randomUUID();
    catalogPublications.put(
        nextTermsRequest,
        publication(
            scopeId,
            "test-only nonmaterial terms version two".getBytes(),
            HostedTermsCatalogVersion.Materiality.NONMATERIAL,
            now().minusSeconds(5)));
    HostedTermsCatalogVersion secondCatalog = service.publish(nextTermsRequest).candidate();
    assertThat(secondCatalog.sourceVersion()).isEqualTo(2);
    assertThat(secondCatalog.materialGeneration()).isEqualTo(1);
    assertThatThrownBy(() -> service.requireCurrentnessForCurrentEnvironment(partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed catalog authority");
    assertThatThrownBy(
            () ->
                db.transactions()
                    .execute(
                        ignored ->
                            service.requireCurrentnessInOwnerTransaction(
                                capturedBoundary, partyId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed catalog authority");

    UUID secondBindingRequest = UUID.randomUUID();
    HostedTermsEnvironmentBinding.PublicationEvidence secondBindingEvidence =
        bindingEvidence(
            "production",
            secondCatalog,
            firstBinding.candidate().bindingId(),
            firstBinding.candidate().sourceVersion(),
            "test-only-publisher-v2");
    bindingPublications.put(secondBindingRequest, secondBindingEvidence);
    var secondBinding = service.publishEnvironmentBinding(secondBindingRequest);
    assertThat(secondBinding.status())
        .isEqualTo(HostedTermsEnvironmentBindingRepository.PublicationStatus.COMMITTED);
    assertThat(secondBinding.candidate().sourceVersion()).isEqualTo(2);
    assertThat(secondBinding.candidate().catalogSourceVersion()).isEqualTo(2);
    assertThat(secondBinding.candidate().predecessorBindingId())
        .isEqualTo(firstBinding.candidate().bindingId());
    assertThat(service.publishEnvironmentBinding(secondBindingRequest)).isEqualTo(secondBinding);
    HostedTermsEnvironmentBindingRepository bindingRepository =
        new HostedTermsEnvironmentBindingRepository(db.dsl());
    assertThat(count(db, "account_hosted_terms_environment_bindings")).isEqualTo(2);
    HostedTermsEnvironmentBinding retainedBinding =
        db.transactions()
            .execute(
                ignored ->
                    bindingRepository.readBinding(
                        "production", firstBinding.candidate().bindingId()));
    assertThat(retainedBinding).isEqualTo(firstBinding.candidate());
    assertThat(service.requireCurrentnessForCurrentEnvironment(partyId).binding())
        .isEqualTo(secondBinding.candidate());
    assertThat(
            db.transactions()
                .execute(
                    ignored ->
                        service.requireCurrentnessInOwnerTransaction(capturedBoundary, partyId))
                .binding())
        .isEqualTo(secondBinding.candidate());

    boundary.set(currentBoundary("unbound-test-environment"));
    assertThatThrownBy(() -> service.requireCurrentnessForCurrentEnvironment(partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("head is unavailable");
  }

  @Test
  void bindingUpdateWaitsForExactTerminalSettlementOfAffectedDraftOperation() throws Exception {
    Database db = database("97");
    UUID accountId = insertAccount(db);
    UUID partyId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    IndividualCreatorPartySource party = party(partyId, accountId);
    tx(db, () -> insertParty(db, party));
    Map<UUID, AccountHostedTermsService.PublicationEvidence> catalogPublications = new HashMap<>();
    Map<UUID, AccountHostedTermsService.AcceptanceAction> actions = new HashMap<>();
    Map<UUID, HostedTermsEnvironmentBinding.PublicationEvidence> bindingPublications =
        new HashMap<>();
    AtomicReference<HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary> boundary =
        new AtomicReference<>(currentBoundary("settlement-test-environment"));
    AccountHostedTermsService service =
        service(db, catalogPublications, actions, bindingPublications, boundary);

    UUID termsRequest = UUID.randomUUID();
    catalogPublications.put(
        termsRequest,
        publication(
            scopeId,
            "test-only terms for settlement proof".getBytes(StandardCharsets.UTF_8),
            HostedTermsCatalogVersion.Materiality.INITIAL,
            now().minusSeconds(10)));
    HostedTermsCatalogVersion catalog = service.publish(termsRequest).candidate();
    UUID acceptanceRequest = UUID.randomUUID();
    actions.put(acceptanceRequest, action(acceptanceRequest, accountId, partyId, catalog));
    service.accept(acceptanceRequest);

    UUID firstRequest = UUID.randomUUID();
    HostedTermsEnvironmentBinding.PublicationEvidence firstEvidence =
        bindingEvidence(
            "settlement-test-environment", catalog, null, null, "test-only-publisher-v1");
    bindingPublications.put(firstRequest, firstEvidence);
    var first = service.publishEnvironmentBinding(firstRequest);
    var captured = service.requireCurrentnessForCurrentEnvironment(partyId);

    // This is an explicit isolated-test fence fixture, not proof of the missing authenticated
    // Draft producer. The Account binding update still uses the real PostgreSQL owner/fence repos.
    DraftAuthorizationFenceBinding pendingDraft =
        testDraftBinding(accountId, captured.sourceEvidence());
    var draftFences = new DraftAuthorizationFenceRepository(db.dsl());
    tx(db, () -> draftFences.reserve(pendingDraft));

    UUID secondRequest = UUID.randomUUID();
    HostedTermsEnvironmentBinding.PublicationEvidence secondEvidence =
        bindingEvidence(
            "settlement-test-environment",
            catalog,
            first.candidate().bindingId(),
            first.candidate().sourceVersion(),
            "test-only-publisher-v2");
    bindingPublications.put(secondRequest, secondEvidence);
    var pendingBinding = service.publishEnvironmentBinding(secondRequest);
    assertThat(pendingBinding.status())
        .isEqualTo(
            HostedTermsEnvironmentBindingRepository.PublicationStatus.PENDING_OWNER_SETTLEMENT);
    assertThat(pendingBinding.candidate().sourceVersion()).isEqualTo(2);
    assertThatThrownBy(() -> service.requireCurrentnessForCurrentEnvironment(partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source change is unresolved");
    String pendingDraftOrdering =
        db.transactions().execute(ignored -> draftFences.read(pendingDraft).ordering().name());
    assertThat(pendingDraftOrdering).isEqualTo("REVOKE_ORDER");
    DraftAuthorizationFenceRepository.Settlement pendingSettlement =
        db.transactions().execute(ignored -> draftFences.readSettlement(pendingDraft));
    assertThat(pendingSettlement).isEqualTo(DraftAuthorizationFenceRepository.Settlement.PENDING);

    // Explicitly simulated terminal abort evidence satisfies both original required owners.
    tx(
        db,
        () -> {
          draftFences.recordOwnerReadback(
              pendingDraft, testReadback(pendingDraft, Owner.WORLD, new byte[] {1}));
          draftFences.recordOwnerReadback(
              pendingDraft, testReadback(pendingDraft, Owner.GAME_DESIGN, new byte[] {2}));
        });
    assertThat(service.resumeEnvironmentBinding(secondRequest).status())
        .isEqualTo(HostedTermsEnvironmentBindingRepository.PublicationStatus.COMMITTED);
    DraftAuthorizationFenceRepository.Settlement completedSettlement =
        db.transactions().execute(ignored -> draftFences.readSettlement(pendingDraft));
    assertThat(completedSettlement)
        .isEqualTo(DraftAuthorizationFenceRepository.Settlement.FAILED_NONPUBLICATION);
    assertThat(service.requireCurrentnessForCurrentEnvironment(partyId).binding().sourceVersion())
        .isEqualTo(2);
  }

  @Test
  void boundCurrentnessCarriesExactScheduledCatalogAndUnextendedDeadline() throws Exception {
    Database db = database("97");
    UUID accountId = insertAccount(db);
    UUID partyId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    IndividualCreatorPartySource party = party(partyId, accountId);
    tx(db, () -> insertParty(db, party));
    Map<UUID, AccountHostedTermsService.PublicationEvidence> catalogPublications = new HashMap<>();
    Map<UUID, AccountHostedTermsService.AcceptanceAction> actions = new HashMap<>();
    Map<UUID, HostedTermsEnvironmentBinding.PublicationEvidence> bindingPublications =
        new HashMap<>();
    AtomicReference<HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary> boundary =
        new AtomicReference<>(currentBoundary("deadline-test-environment"));
    AccountHostedTermsService service =
        service(db, catalogPublications, actions, bindingPublications, boundary);

    UUID initialRequest = UUID.randomUUID();
    catalogPublications.put(
        initialRequest,
        publication(
            scopeId,
            "test-only current terms".getBytes(StandardCharsets.UTF_8),
            HostedTermsCatalogVersion.Materiality.INITIAL,
            databaseNow(db).minusSeconds(10)));
    HostedTermsCatalogVersion currentCatalog = service.publish(initialRequest).candidate();
    UUID acceptanceRequest = UUID.randomUUID();
    actions.put(acceptanceRequest, action(acceptanceRequest, accountId, partyId, currentCatalog));
    var acceptance = service.accept(acceptanceRequest);

    UUID bindingRequest = UUID.randomUUID();
    bindingPublications.put(
        bindingRequest,
        bindingEvidence(
            "deadline-test-environment", currentCatalog, null, null, "test-only-publisher"));
    service.publishEnvironmentBinding(bindingRequest);

    var capturedBoundary = service.captureCurrentEnvironmentBoundary();
    Instant deadline = databaseNow(db).plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    UUID scheduledRequest = UUID.randomUUID();
    catalogPublications.put(
        scheduledRequest,
        publication(
            scopeId,
            "test-only scheduled terms".getBytes(StandardCharsets.UTF_8),
            HostedTermsCatalogVersion.Materiality.MATERIAL,
            deadline));
    HostedTermsCatalogVersion scheduled = service.publish(scheduledRequest).candidate();

    var currentness = service.requireCurrentnessForCurrentEnvironment(partyId);
    var sameTransactionCurrentness =
        db.transactions()
            .execute(
                ignored -> service.requireCurrentnessInOwnerTransaction(capturedBoundary, partyId));
    assertThat(sameTransactionCurrentness.binding()).isEqualTo(currentness.binding());
    assertThat(sameTransactionCurrentness.terms().exactCurrentnessSource())
        .containsExactly(currentness.terms().exactCurrentnessSource());
    assertThat(currentness.terms().acceptanceEvidenceId()).isEqualTo(acceptance.evidenceId());
    assertThat(currentness.terms().validUntil()).isEqualTo(scheduled.effectiveAt());
    assertThat(currentness.terms().disclosedDeadline()).isEqualTo(scheduled);
    byte[] exactTermsSource =
        HostedTermsEncoding.currentnessSource(currentCatalog, acceptance, scheduled);
    assertThat(currentness.terms().exactCurrentnessSource()).containsExactly(exactTermsSource);
    assertThat(currentness.sourceEvidence())
        .filteredOn(source -> source.scopeId().equals(scopeId.toString()))
        .singleElement()
        .satisfies(
            source -> {
              assertThat(source.kind()).isEqualTo(SourceKind.HOSTED_TERMS);
              assertThat(source.generation()).isEqualTo("1");
              assertThat(source.sourceVersion()).isEqualTo("1");
              assertThat(source.evidence()).containsExactly(exactTermsSource);
              assertThat(new String(source.evidence(), StandardCharsets.UTF_8))
                  .contains(HostedTermsEncoding.digest(HostedTermsEncoding.catalog(scheduled)));
            });
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
      DraftAuthorizationFenceBinding binding, Owner owner, byte[] exactTestResult) {
    return new OwnerReadback(
        owner,
        Outcome.DEFINITIVELY_ABORTED,
        binding.operationId(),
        binding.commitId(),
        binding.fenceId(),
        binding.inputDigest(),
        binding.canonicalBytes(),
        exactTestResult);
  }

  private static AccountHostedTermsService service(
      Database db,
      Map<UUID, AccountHostedTermsService.PublicationEvidence> catalogPublications,
      Map<UUID, AccountHostedTermsService.AcceptanceAction> actions,
      Map<UUID, HostedTermsEnvironmentBinding.PublicationEvidence> bindingPublications,
      AtomicReference<HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary> boundary) {
    IndividualCreatorPartyRepository parties =
        new IndividualCreatorPartyRepository(
            db.dsl(),
            new FreshTenantIdentityAssociationRepository(db.dsl(), TEST_NAMESPACE),
            new AccountTenantCreationBootstrapOperationRepository(db.dsl()));
    return new AccountHostedTermsService(
        db.transactionManager(),
        new HostedTermsRepository(db.dsl()),
        parties,
        new DraftAuthorizationFenceRepository(db.dsl()),
        request -> {
          assertOutsideOwnerTransaction();
          return Objects.requireNonNull(
              catalogPublications.get(request), "test-only catalog fixture");
        },
        request -> {
          assertOutsideOwnerTransaction();
          return Objects.requireNonNull(
              actions.get(request), "test-only affirmative action fixture");
        },
        new HostedTermsEnvironmentBindingRepository(db.dsl()),
        request -> {
          assertOutsideOwnerTransaction();
          return Objects.requireNonNull(
              bindingPublications.get(request), "test-only owner fixture");
        },
        () -> {
          assertOutsideOwnerTransaction();
          return Objects.requireNonNull(boundary.get());
        });
  }

  private static void assertOutsideOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Owner authority must be resolved outside Account locks");
    }
  }

  private static HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary currentBoundary(
      String boundary) {
    byte[] ownerEvidence = ("test-only-boundary:" + boundary).getBytes();
    return new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
        boundary,
        "test-only-environment-owner/" + boundary,
        "test-only-current-boundary-observation/" + boundary,
        ownerEvidence,
        HostedTermsEncoding.digest(ownerEvidence));
  }

  private static HostedTermsEnvironmentBinding.PublicationEvidence bindingEvidence(
      String boundary,
      HostedTermsCatalogVersion catalog,
      UUID predecessorBindingId,
      Long predecessorSourceVersion,
      String publisher) {
    return new HostedTermsEnvironmentBinding.PublicationEvidence(
        boundary,
        catalog.hostedScopeId(),
        catalog.operatorLegalIdentity(),
        catalog.operatorIdentityVersion(),
        catalog.versionId(),
        catalog.sourceVersion(),
        publisher,
        "test-only-publication-event/" + UUID.randomUUID(),
        predecessorBindingId,
        predecessorSourceVersion);
  }

  private static AccountHostedTermsService.PublicationEvidence publication(
      UUID scope,
      byte[] document,
      HostedTermsCatalogVersion.Materiality materiality,
      Instant effectiveAt) {
    return new AccountHostedTermsService.PublicationEvidence(
        scope,
        "Test Operator Legal Name",
        1,
        document,
        materiality,
        materiality == HostedTermsCatalogVersion.Materiality.INITIAL ? null : "test-materiality",
        materiality == HostedTermsCatalogVersion.Materiality.INITIAL ? null : 1L,
        "test-only-legal-publication",
        1,
        "test-only-notice",
        1,
        effectiveAt.truncatedTo(ChronoUnit.MICROS));
  }

  private static AccountHostedTermsService.AcceptanceAction action(
      UUID requestId, UUID accountId, UUID partyId, HostedTermsCatalogVersion catalog) {
    return new AccountHostedTermsService.AcceptanceAction(
        requestId,
        true,
        accountId,
        partyId,
        catalog.hostedScopeId(),
        catalog.versionId(),
        catalog.documentDigest(),
        catalog.operatorLegalIdentity(),
        catalog.operatorIdentityVersion(),
        "test-only-authenticated-individual-action",
        1,
        OffsetDateTime.ofInstant(now(), ZoneOffset.UTC));
  }

  private static IndividualCreatorPartySource party(UUID partyId, UUID accountId) {
    return new IndividualCreatorPartySource(
        partyId,
        accountId,
        VerificationStatus.VERIFIED,
        1,
        "test-only-identity-policy",
        1L,
        "test-only-verified-identity-evidence",
        1L,
        1);
  }

  private static void insertParty(Database db, IndividualCreatorPartySource party) {
    byte[] payload = CreatorPartyEncoding.party(party);
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
            payload,
            CreatorPartyEncoding.digest(payload));
  }

  private static UUID insertAccount(Database db) {
    UUID id = UUID.randomUUID();
    db.dsl()
        .execute(
            "INSERT INTO accounts (account_uuid, username, email, password_hash) "
                + "VALUES (?, ?, ?, 'test-only-password-hash')",
            id,
            "hosted-binding-" + id.toString().substring(0, 8),
            id + "@example.test");
    return id;
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static Instant databaseNow(Database db) {
    return Objects.requireNonNull(db.dsl().fetchOne("SELECT clock_timestamp() AS database_now"))
        .get("database_now", OffsetDateTime.class)
        .toInstant()
        .truncatedTo(ChronoUnit.MICROS);
  }

  private static long count(Database db, String table) {
    return Objects.requireNonNull(db.dsl().fetchOne("SELECT count(*) AS count FROM " + table))
        .get("count", Long.class);
  }

  private static Database database(String target) throws Exception {
    String schema = "terms_binding_" + UUID.randomUUID().toString().replace("-", "");
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
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
    return db;
  }

  private static void tx(Database db, Runnable work) {
    db.transactions().executeWithoutResult(ignored -> work.run());
  }

  private record Database(
      String schema,
      DriverManagerDataSource source,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transactions) {}
}
