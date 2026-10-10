package net.firedevops.firemud.accountservice.hostedterms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsCatalogVersion.Materiality;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding.PublicationEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Isolated PostgreSQL proof for exact V86 predecessor/Draft source binding. All hosted catalog,
 * publisher, legal identity, and environment-owner values here are explicitly synthetic fixtures;
 * these tests prove only the Account-owned SQL guard and do not prove external owner authority.
 */
class HostedTermsEnvironmentBindingFencePostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void initialBindingNeedsNoInventedPredecessorSourceChange() {
    TestContext context = context();
    CatalogSources catalogs = seedCatalogs(context);
    String boundary = "test-environment-" + UUID.randomUUID();
    HostedTermsEnvironmentBinding binding =
        publishInitialBinding(context, boundary, catalogs.first());

    assertThat(binding.predecessorBindingId()).isNull();
    assertThat(binding.sourceVersion()).isEqualTo(1L);
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_draft_authorization_source_changes")
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void predecessorBindingCommitsWithTheExactSettledEnvironmentAndCatalogSourceSet() {
    TestContext context = context();
    CatalogSources catalogs = seedCatalogs(context);
    String boundary = "test-environment-" + UUID.randomUUID();
    HostedTermsEnvironmentBinding predecessor =
        publishInitialBinding(context, boundary, catalogs.first());

    HostedTermsEnvironmentBinding committed =
        publishSuccessor(context, boundary, predecessor, catalogs, ChangeShape.EXACT);

    assertThat(committed.predecessorBindingId()).isEqualTo(predecessor.bindingId());
    assertThat(committed.sourceVersion()).isEqualTo(2L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_draft_authorization_changed_scopes changed "
                        + "JOIN account_draft_authorization_source_changes change USING (change_id) "
                        + "WHERE change.change_id = ? AND change.status = 'SOURCE_COMMITTED'",
                    committed.publicationRequestId())
                .fetchOne(0, Long.class))
        .isEqualTo(3L);
  }

  @Test
  void predecessorBindingRejectsWaitingWrongIdentityAndInexactSourceSets() {
    for (ChangeShape shape :
        List.of(
            ChangeShape.WAITING,
            ChangeShape.WRONG_CHANGE_ID,
            ChangeShape.WRONG_BOUNDARY,
            ChangeShape.MISSING_PREDECESSOR_CATALOG,
            ChangeShape.MISSING_TARGET_CATALOG,
            ChangeShape.EXTRA_SCOPE)) {
      TestContext context = context();
      CatalogSources catalogs = seedCatalogs(context);
      String boundary = "test-environment-" + UUID.randomUUID();
      HostedTermsEnvironmentBinding predecessor =
          publishInitialBinding(context, boundary, catalogs.first());

      assertThatThrownBy(() -> publishSuccessor(context, boundary, predecessor, catalogs, shape))
          .as("V86 rejects %s source-change fixture", shape)
          .isInstanceOf(TransactionSystemException.class)
          .rootCause()
          .isInstanceOf(PSQLException.class)
          .hasMessageContaining("fully settled exact Draft source change");
    }
  }

  private CatalogSources seedCatalogs(TestContext context) {
    HostedTermsRepository repository = new HostedTermsRepository(context.dsl());
    CatalogVersion first = insertInitialCatalog(context, repository);
    CatalogVersion second = insertInitialCatalog(context, repository);
    return new CatalogSources(first, second);
  }

  private CatalogVersion insertInitialCatalog(
      TestContext context, HostedTermsRepository repository) {
    UUID scopeId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    String legalIdentity = "TEST FIXTURE ONLY - no legal identity claim";
    byte[] document =
        "synthetic fixture only; not legal terms, review, or a publication"
            .getBytes(StandardCharsets.UTF_8);
    Instant effectiveAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
    AccountHostedTermsService.PublicationEvidence evidence =
        new AccountHostedTermsService.PublicationEvidence(
            scopeId,
            legalIdentity,
            1L,
            document,
            Materiality.INITIAL,
            null,
            null,
            "TEST FIXTURE ONLY - synthetic publication reference",
            1L,
            "TEST FIXTURE ONLY - synthetic notice reference",
            1L,
            effectiveAt);
    byte[] request = HostedTermsEncoding.publication(requestId, evidence);
    HostedTermsCatalogVersion candidate =
        new HostedTermsCatalogVersion(
            UUID.randomUUID(),
            scopeId,
            null,
            legalIdentity,
            1L,
            document,
            HostedTermsEncoding.digest(document),
            1L,
            1L,
            Materiality.INITIAL,
            null,
            null,
            evidence.publicationEvidenceReference(),
            evidence.publicationEvidenceVersion(),
            evidence.noticeEvidenceReference(),
            evidence.noticeEvidenceVersion(),
            effectiveAt);

    context
        .transaction()
        .executeWithoutResult(
            status -> {
              repository.ensureScope(scopeId);
              repository.claimPublication(requestId, scopeId, request);
              repository.lockScope(scopeId);
              repository.insertCandidate(candidate);
              repository.markScheduled(requestId, candidate);
              repository.completePublication(
                  requestId, candidate, HostedTermsEncoding.catalog(candidate), null);
            });
    return new CatalogVersion(candidate);
  }

  private HostedTermsEnvironmentBinding publishInitialBinding(
      TestContext context, String boundary, CatalogVersion catalog) {
    HostedTermsEnvironmentBindingRepository repository =
        new HostedTermsEnvironmentBindingRepository(context.dsl());
    UUID requestId = UUID.randomUUID();
    PublicationEvidence evidence = publicationEvidence(boundary, catalog, null);
    byte[] request = HostedTermsEnvironmentBindingEncoding.publication(requestId, evidence);
    HostedTermsEnvironmentBinding[] candidate = new HostedTermsEnvironmentBinding[1];
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              repository.ensureHead(boundary);
              repository.claimPublication(requestId, boundary, request);
              repository.lockHead(boundary);
              candidate[0] =
                  repository.insertCandidate(UUID.randomUUID(), requestId, evidence, null);
              repository.completePublication(requestId, candidate[0], null, null);
            });
    return candidate[0];
  }

  private HostedTermsEnvironmentBinding publishSuccessor(
      TestContext context,
      String boundary,
      HostedTermsEnvironmentBinding predecessor,
      CatalogSources catalogs,
      ChangeShape shape) {
    HostedTermsEnvironmentBindingRepository bindings =
        new HostedTermsEnvironmentBindingRepository(context.dsl());
    DraftAuthorizationFenceRepository fences = new DraftAuthorizationFenceRepository(context.dsl());
    UUID requestId = UUID.randomUUID();
    UUID changeId = shape == ChangeShape.WRONG_CHANGE_ID ? UUID.randomUUID() : requestId;
    PublicationEvidence evidence = publicationEvidence(boundary, catalogs.second(), predecessor);
    byte[] request = HostedTermsEnvironmentBindingEncoding.publication(requestId, evidence);
    HostedTermsEnvironmentBinding[] committed = new HostedTermsEnvironmentBinding[1];

    context
        .transaction()
        .executeWithoutResult(
            status -> {
              bindings.claimPublication(requestId, boundary, request);
              HostedTermsEnvironmentBindingRepository.HeadSnapshot head =
                  bindings.lockHead(boundary);
              if (!predecessor.equals(head.current())) {
                throw new IllegalStateException("Test predecessor differs from exact binding head");
              }
              HostedTermsEnvironmentBinding candidate =
                  bindings.insertCandidate(UUID.randomUUID(), requestId, evidence, head.current());
              SourceEvidence predecessorSource = predecessor.sourceEvidence();
              SourceEvidence priorCatalogSource = catalogSource(catalogs.first());
              SourceEvidence targetCatalogSource = catalogSource(catalogs.second());
              List<SourceEvidence> expectedSources =
                  List.of(predecessorSource, priorCatalogSource, targetCatalogSource);
              List<SourceEvidence> changedSources =
                  changedSources(shape, expectedSources, predecessor);
              DraftAuthorizationFenceRepository.SourceChange change =
                  new DraftAuthorizationFenceRepository.SourceChange(
                      changeId,
                      changedSources,
                      HostedTermsEnvironmentBindingEncoding.receipt(candidate));
              if (!fences.requestSourceChange(change) || !fences.sourceMutationPermitted(change)) {
                throw new IllegalStateException("Run-owned Draft source fixture did not settle");
              }
              bindings.markOwnerSettlementPending(requestId, candidate, change.canonicalBytes());
              bindings.completePublication(
                  requestId, candidate, change.canonicalBytes(), head.current());
              if (shape != ChangeShape.WAITING) {
                fences.markSourceCommitted(change);
              }
              committed[0] = candidate;
            });
    return committed[0];
  }

  private List<SourceEvidence> changedSources(
      ChangeShape shape,
      List<SourceEvidence> expectedSources,
      HostedTermsEnvironmentBinding predecessor) {
    List<SourceEvidence> sources = new ArrayList<>(expectedSources);
    return switch (shape) {
      case EXACT, WAITING, WRONG_CHANGE_ID -> sources;
      case WRONG_BOUNDARY -> {
        sources.removeIf(source -> source.key().equals(predecessor.sourceEvidence().key()));
        sources.add(
            new SourceEvidence(
                SourceKind.HOSTED_TERMS,
                "environment-boundary/wrong-fixture-boundary",
                null,
                Long.toString(predecessor.sourceVersion()),
                null,
                null,
                HostedTermsEnvironmentBindingEncoding.receipt(predecessor)));
        yield sources;
      }
      case MISSING_PREDECESSOR_CATALOG -> List.of(expectedSources.get(0), expectedSources.get(2));
      case MISSING_TARGET_CATALOG -> List.of(expectedSources.get(0), expectedSources.get(1));
      case EXTRA_SCOPE -> {
        sources.add(
            new SourceEvidence(
                SourceKind.CREATOR_PARTY,
                "test-only-extra-scope",
                null,
                "1",
                null,
                null,
                "synthetic extra source only".getBytes(StandardCharsets.UTF_8)));
        yield sources;
      }
    };
  }

  private SourceEvidence catalogSource(CatalogVersion catalog) {
    HostedTermsCatalogVersion version = catalog.version();
    return new SourceEvidence(
        SourceKind.HOSTED_TERMS,
        version.hostedScopeId().toString(),
        Long.toString(version.materialGeneration()),
        Long.toString(version.sourceVersion()),
        null,
        null,
        HostedTermsEncoding.catalog(version));
  }

  private PublicationEvidence publicationEvidence(
      String boundary, CatalogVersion catalog, HostedTermsEnvironmentBinding predecessor) {
    HostedTermsCatalogVersion version = catalog.version();
    return new PublicationEvidence(
        boundary,
        version.hostedScopeId(),
        version.operatorLegalIdentity(),
        version.operatorIdentityVersion(),
        version.versionId(),
        version.sourceVersion(),
        "TEST FIXTURE ONLY - not an authenticated publisher",
        "TEST FIXTURE ONLY - synthetic publication event",
        predecessor == null ? null : predecessor.bindingId(),
        predecessor == null ? null : predecessor.sourceVersion());
  }

  private TestContext context() {
    String schema = "hosted_terms_fence_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
    TestContext context =
        new TestContext(
            schema,
            dataSource,
            DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    return context;
  }

  private record CatalogVersion(HostedTermsCatalogVersion version) {}

  private record CatalogSources(CatalogVersion first, CatalogVersion second) {}

  private record TestContext(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction) {}

  private enum ChangeShape {
    EXACT,
    WAITING,
    WRONG_CHANGE_ID,
    WRONG_BOUNDARY,
    MISSING_PREDECESSOR_CATALOG,
    MISSING_TARGET_CATALOG,
    EXTRA_SCOPE
  }
}
