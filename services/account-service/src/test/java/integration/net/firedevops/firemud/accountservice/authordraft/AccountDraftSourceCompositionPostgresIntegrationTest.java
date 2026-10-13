package integration.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftSourceCompositionService;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftSourceCompositionService.ExistingSources;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsCatalogVersion;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBindingRepository;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCreatorMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapAuthorizationSource;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Account owner-row composition and transaction fences. External Game Design creation,
 * bootstrap authority, individual verification, legal publication, affirmative action and
 * environment observations are explicitly test-only fixtures, not production authorization. No
 * owner repository, membership reader, event codec or source readback is mocked.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountDraftSourceCompositionPostgresIntegrationTest {
  private static final String NAMESPACE = "draft-source-composition-proof";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void composesRealSourcesAndRereadsTheSameExactExistingEvidence() {
    Fixture f = new Fixture(true, true);
    CapturedEnvironmentBoundary environment = f.terms.captureCurrentEnvironmentBoundary();
    ExistingSources first = f.tx(() -> f.composer.readExistingSources(environment, f.tenant));
    ExistingSources second = f.tx(() -> f.composer.readExistingSources(environment, f.tenant));

    assertThat(first.membership().accountUuid()).isEqualTo(f.account);
    assertThat(first.membership().tenantUuid()).isEqualTo(f.tenant);
    assertThat(first.membership().roleSource().roles()).containsExactly("tenantAdmin");
    assertThat(first.membership().sourceEvent().gameplayAdmissionAllowed()).isFalse();
    assertThat(first.membership().sourceEvent().membershipVersion())
        .isEqualTo(Map.of(f.tenant.toString(), "2"));
    assertThat(first.issuerAccount().issuer().checkpoint().sequence()).isZero();
    assertThat(first.issuerAccount().account().checkpoint().sequence()).isZero();
    assertThat(first.issuerAccount().account().accountRepositoryInsertTransactionId()).isPositive();
    assertThat(first.issuerAccount().canonicalAccountProjection().sourceEvent()).isEmpty();
    assertThat(first.issuerAccount().canonicalIssuerProjection().sourceEvent()).isEmpty();
    assertThat(first.membership().issuanceFence())
        .isEqualTo(Long.toString(first.issuerAccount().issuanceFence().value()));
    var event =
        MembershipAuthorityEventV1Codec.verify(first.membership().sourceEvent().canonicalJson());
    assertThat(event.canonicalJson()).isEqualTo(first.membership().sourceEvent().canonicalJson());
    assertThat(first.association().creatorEvidence()).isEqualTo(f.creator);
    assertThat(first.association().partySource()).isEqualTo(f.party);
    assertThat(first.hostedTerms().terms().accountId()).isEqualTo(f.account);
    assertThat(first.hostedTerms().terms().creatorPartyId()).isEqualTo(f.party.creatorPartyId());
    assertThat(first.hostedTerms().sourceEvidence()).hasSize(3);

    assertThat(second.membership())
        .usingRecursiveComparison()
        .ignoringFields("evaluatedAt")
        .isEqualTo(first.membership());
    assertThat(second.issuerAccount()).isEqualTo(first.issuerAccount());
    assertThat(second.association()).isEqualTo(first.association());
    assertThat(second.hostedTerms()).usingRecursiveComparison().isEqualTo(first.hostedTerms());
    assertThat(f.count("account_tenant_creator_party_history")).isOne();
    assertThat(f.count("account_fresh_creator_party_association_operations")).isOne();
    assertThat(f.count("account_draft_authorization_fences")).isZero();
  }

  @Test
  void missingAssociationAndAcceptanceDenyWithoutCreatingSourcesOrDraftFences() {
    Fixture absentAssociation = new Fixture(false, true);
    var associationEnvironment = absentAssociation.terms.captureCurrentEnvironmentBoundary();
    assertThatThrownBy(
            () ->
                absentAssociation.tx(
                    () ->
                        absentAssociation.composer.readExistingSources(
                            associationEnvironment, absentAssociation.tenant)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("association is absent");
    assertThat(absentAssociation.count("account_tenant_creator_party_history")).isZero();
    assertThat(absentAssociation.count("account_fresh_creator_party_association_operations"))
        .isZero();

    Fixture absentAcceptance = new Fixture(true, false);
    var acceptanceEnvironment = absentAcceptance.terms.captureCurrentEnvironmentBoundary();
    assertThatThrownBy(
            () ->
                absentAcceptance.tx(
                    () ->
                        absentAcceptance.composer.readExistingSources(
                            acceptanceEnvironment, absentAcceptance.tenant)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("affirmative");
    assertThat(absentAcceptance.count("account_draft_authorization_fences")).isZero();
    assertThat(absentAcceptance.count("account_tenant_creator_party_history")).isOne();
  }

  @Test
  void differentCurrentEnvironmentAndUnsupportedChangedAccountSourceDenyExactReread() {
    Fixture f = new Fixture(true, true);
    var originalEnvironment = f.terms.captureCurrentEnvironmentBoundary();
    var original = f.tx(() -> f.composer.readExistingSources(originalEnvironment, f.tenant));
    f.boundary.set(testBoundary("different-test-environment"));
    var differentEnvironment = f.terms.captureCurrentEnvironmentBoundary();
    assertThatThrownBy(
            () -> f.tx(() -> f.composer.readExistingSources(differentEnvironment, f.tenant)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("binding head is unavailable");

    // The existing generic-save owner emits legacy Account events. Its real generation/fence
    // advance must be denied by the closed creator-source reader, not relabeled as current proof.
    f.tx(
        () -> {
          Account account = f.accounts.findByAccountUuidForUpdate(f.account).orElseThrow();
          account.setRole("admin");
          f.accounts.save(account);
          return null;
        });
    assertThat(
            Objects.requireNonNull(
                    f.dsl.fetchOne(
                        "SELECT generation FROM account_authority_generations WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                        f.account))
                .get("generation", Long.class))
        .isGreaterThan(original.issuerAccount().account().generation());
    assertThatThrownBy(
            () -> f.tx(() -> f.composer.readExistingSources(originalEnvironment, f.tenant)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account source event schema is unsupported");
    byte[] retainedCreatorPayload =
        f.tx(
            () ->
                f.operations
                    .findForUpdate(f.creator.accountAuthorizationOperationId())
                    .orElseThrow()
                    .creatorEvidencePayload());
    assertThat(retainedCreatorPayload)
        .containsExactly(original.membership().bootstrapReceipt().creatorEvidencePayload());
    assertThat(f.count("account_draft_authorization_fences")).isZero();
  }

  @Test
  void physicalTransactionFencesRejectBeforeReadingAndAccountLockLivesUntilCompletion() {
    Fixture f = new Fixture(true, true);
    var environment = f.terms.captureCurrentEnvironmentBoundary();
    assertThatThrownBy(() -> f.composer.readExistingSources(environment, f.tenant))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_COMMITTED");
    TransactionTemplate repeatable = new TransactionTemplate(f.manager);
    repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(
            () ->
                repeatable.execute(
                    ignored -> f.composer.readExistingSources(environment, f.tenant)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_COMMITTED");
    TransactionTemplate readOnly = new TransactionTemplate(f.manager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () ->
                readOnly.execute(ignored -> f.composer.readExistingSources(environment, f.tenant)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_COMMITTED");

    f.tx(
        () -> {
          f.composer.readExistingSources(environment, f.tenant);
          f.composer.readExistingSources(environment, f.tenant);
          assertThatThrownBy(() -> lockAccountOnIndependentConnection(f))
              .isInstanceOf(SQLException.class)
              .satisfies(
                  failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("55P03"));
          return null;
        });
    org.assertj.core.api.Assertions.assertThatCode(() -> lockAccountOnIndependentConnection(f))
        .doesNotThrowAnyException();
  }

  private static void lockAccountOnIndependentConnection(Fixture f) throws SQLException {
    try (Connection connection = f.dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET LOCAL lock_timeout = '200ms'");
        try (var query =
            connection.prepareStatement(
                "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE")) {
          query.setObject(1, f.account);
          try (var rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
          }
        }
      } finally {
        connection.rollback();
      }
    }
  }

  private static final class Fixture {
    private final DriverManagerDataSource dataSource;
    private final DataSourceTransactionManager manager;
    private final TransactionTemplate transactions;
    private final DSLContext dsl;
    private final AccountRepository accounts;
    private final AccountTenantCreationBootstrapOperationRepository operations;
    private final UUID account;
    private final UUID tenant = UUID.randomUUID();
    private final FreshTenantCreatorEvidence creator;
    private final IndividualCreatorPartySource party;
    private final AtomicReference<HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary>
        boundary = new AtomicReference<>(testBoundary("test-production"));
    private final AccountHostedTermsService terms;
    private final AccountDraftSourceCompositionService composer;

    private Fixture(boolean associate, boolean accept) {
      String schema = "draft_source_" + UUID.randomUUID().toString().replace("-", "");
      dataSource = new DriverManagerDataSource();
      dataSource.setUrl(postgres.getJdbcUrl());
      dataSource.setUsername(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      dataSource.setSchema(schema);
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
      manager = new DataSourceTransactionManager(dataSource);
      transactions = new TransactionTemplate(manager);
      transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      var generations = new AccountAuthorityGenerationRepository(dsl);
      var outbox = new AccountAuthorityOutboxRepository(dsl);
      var sources = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
      accounts = new AccountRepository(dsl, sources);
      account =
          tx(
              () -> {
                Account candidate = new Account();
                String suffix = UUID.randomUUID().toString();
                candidate.setUsername("draft-" + suffix);
                candidate.setEmail("draft-" + suffix + "@example.test");
                candidate.setPasswordHash("test-only-hash");
                return accounts.save(candidate).getAccountUuid();
              });
      creator = testCreation(account, tenant);
      var fresh = new FreshTenantIdentityAssociationRepository(dsl, NAMESPACE);
      tx(
          () -> {
            fresh.importVerified(creator.creationEvidence());
            generations.initializeTenantIfAbsent(tenant);
            return null;
          });
      var pairs = new AccountMembershipPairAuthorityRepository(dsl);
      var memberships = new AccountTenantMembershipRepository(dsl, accounts, fresh, pairs);
      var roles = new AccountTenantMembershipRoleSnapshotRepository(dsl);
      operations = new AccountTenantCreationBootstrapOperationRepository(dsl);
      var sourceReader =
          new AccountCreatorMembershipSourceReader(
              accounts,
              new AccountJoinOperationRepository(dsl),
              memberships,
              pairs,
              roles,
              fresh,
              generations,
              outbox,
              sources,
              operations);
      var beans = new StaticListableBeanFactory();
      beans.addBean("testOnlyBootstrapAuthority", testBootstrapParticipant(creator));
      var bootstrap =
          new AccountTenantCreationBootstrapService(
              accounts,
              fresh,
              sourceReader,
              pairs,
              memberships,
              roles,
              outbox,
              new AccountAuditOutboxRepository(dsl),
              operations,
              beans.getBeanProvider(AccountTenantCreationBootstrapAuthorizationSource.class));
      tx(() -> bootstrap.bootstrap(creator));
      party =
          new IndividualCreatorPartySource(
              UUID.randomUUID(),
              account,
              VerificationStatus.VERIFIED,
              1L,
              "test-only-policy",
              1L,
              "test-only-individual-verification",
              1L,
              1L);
      insertTestParty(dsl, party);
      var parties = new IndividualCreatorPartyRepository(dsl, fresh, operations);
      if (associate) {
        tx(() -> parties.associateFresh(UUID.randomUUID(), creator, party));
      }
      Map<UUID, AccountHostedTermsService.PublicationEvidence> publications = new HashMap<>();
      Map<UUID, AccountHostedTermsService.AcceptanceAction> actions = new HashMap<>();
      Map<UUID, HostedTermsEnvironmentBinding.PublicationEvidence> bindings = new HashMap<>();
      terms =
          new AccountHostedTermsService(
              manager,
              new HostedTermsRepository(dsl),
              parties,
              new DraftAuthorizationFenceRepository(dsl),
              request -> externalTestFixture(publications, request),
              request -> externalTestFixture(actions, request),
              new HostedTermsEnvironmentBindingRepository(dsl),
              request -> externalTestFixture(bindings, request),
              () -> {
                requireOutsideTransaction();
                return boundary.get();
              });
      UUID publication = UUID.randomUUID();
      publications.put(
          publication,
          new AccountHostedTermsService.PublicationEvidence(
              UUID.randomUUID(),
              "Test-only Operator Legal Name",
              1L,
              "Test-only terms".getBytes(StandardCharsets.UTF_8),
              HostedTermsCatalogVersion.Materiality.INITIAL,
              null,
              null,
              "test-only-publication-audit",
              1L,
              "test-only-notice",
              1L,
              Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS)));
      var catalog = terms.publish(publication).candidate();
      if (accept) {
        UUID action = UUID.randomUUID();
        actions.put(
            action,
            new AccountHostedTermsService.AcceptanceAction(
                action,
                true,
                account,
                party.creatorPartyId(),
                catalog.hostedScopeId(),
                catalog.versionId(),
                catalog.documentDigest(),
                catalog.operatorLegalIdentity(),
                catalog.operatorIdentityVersion(),
                "test-only-affirmative-individual-action",
                1L,
                OffsetDateTime.now(ZoneOffset.UTC)));
        terms.accept(action);
      }
      UUID binding = UUID.randomUUID();
      bindings.put(
          binding,
          new HostedTermsEnvironmentBinding.PublicationEvidence(
              "test-production",
              catalog.hostedScopeId(),
              catalog.operatorLegalIdentity(),
              catalog.operatorIdentityVersion(),
              catalog.versionId(),
              catalog.sourceVersion(),
              "test-only-environment-publisher",
              "test-only-environment-publication/" + binding,
              null,
              null));
      terms.publishEnvironmentBinding(binding);
      composer = new AccountDraftSourceCompositionService(dsl, parties, bootstrap, terms, sources);
    }

    private <T> T tx(Supplier<T> work) {
      return transactions.execute(ignored -> work.get());
    }

    private long count(String table) {
      return Objects.requireNonNull(dsl.fetchOne("SELECT count(*) FROM " + table))
          .get(0, Long.class);
    }
  }

  private static AccountTenantCreationBootstrapAuthorizationSource testBootstrapParticipant(
      FreshTenantCreatorEvidence expected) {
    ReentrantLock testOnlyFence = new ReentrantLock();
    return (actual, mutation) -> {
      if (!expected.equals(actual)
          || !TransactionSynchronizationManager.isActualTransactionActive()
          || !TransactionSynchronizationManager.isSynchronizationActive()) {
        throw new IllegalStateException(
            "Test-only bootstrap source requires exact evidence and owner transaction");
      }
      testOnlyFence.lock();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              testOnlyFence.unlock();
            }
          });
      return mutation.get();
    };
  }

  private static FreshTenantCreatorEvidence testCreation(UUID account, UUID tenant) {
    UUID request = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    UUID authorization = UUID.randomUUID();
    String key = "test-game-row-17";
    String digest =
        GameTenantCreationDigest.requestDigest(NAMESPACE, request, key, "Test game", null);
    var creation =
        new FreshTenantCreationEvidence(
            1,
            NAMESPACE,
            request,
            operation,
            digest,
            tenant,
            17L,
            key,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                NAMESPACE, request, operation, digest, tenant, 17L, key, "NEW_GAME_ROW"));
    String authorizationDigest = "sha256:" + "8".repeat(64);
    return new FreshTenantCreatorEvidence(
        1,
        creation,
        account,
        authorization,
        authorizationDigest,
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, account, authorization, authorizationDigest));
  }

  private static void insertTestParty(DSLContext dsl, IndividualCreatorPartySource party) {
    byte[] bytes = CreatorPartyEncoding.party(party);
    dsl.execute(
        "INSERT INTO account_individual_creator_party_sources "
            + "(creator_party_id, account_uuid, verification_status, identity_version, policy_reference, "
            + "policy_version, verification_evidence_reference, verification_evidence_version, "
            + "source_version, source_payload, source_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        party.creatorPartyId(),
        party.accountId(),
        party.verificationStatus().name(),
        party.identityVersion(),
        party.policyReference(),
        party.policyVersion(),
        party.verificationEvidenceReference(),
        party.verificationEvidenceVersion(),
        party.sourceVersion(),
        bytes,
        CreatorPartyEncoding.digest(bytes));
  }

  private static HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary testBoundary(
      String name) {
    byte[] bytes = ("test-only-environment-observation/" + name).getBytes(StandardCharsets.UTF_8);
    return new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
        name,
        "test-only-environment-owner",
        "test-only-observation/" + name,
        bytes,
        HostedTermsEncoding.digest(bytes));
  }

  private static <T> T externalTestFixture(Map<UUID, T> fixtures, UUID request) {
    requireOutsideTransaction();
    return Objects.requireNonNull(
        fixtures.get(request), "Exact test-only external fixture required");
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Test-only external source must be resolved outside Account locks");
    }
  }
}
