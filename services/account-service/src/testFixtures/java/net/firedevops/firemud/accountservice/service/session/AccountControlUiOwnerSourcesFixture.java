package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;

import de.mkammerer.argon2.Argon2Factory;
import io.grpc.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftSourceCompositionService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsCatalogVersion;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBindingRepository;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsRepository;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCreatorMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapAuthorizationSource;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AcknowledgementRequirements;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.mapstruct.factory.Mappers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Account source/auth owners with explicitly stipulated upstream creator/legal inputs. */
final class AccountControlUiOwnerSourcesFixture {
  private static final String NAMESPACE = "control-ui-owner-proof";
  static final String CALLER =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/logging-admin-service";
  public static final String OTP = "test-only-original-creator-otp";
  final DSLContext dsl;
  final TransactionTemplate transactions;
  final Account account;
  final AccountRepository accounts;
  final UUID tenant;
  final UUID request = UUID.randomUUID(), callerContext = UUID.randomUUID();
  final AtomicReference<HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary> boundary =
      new AtomicReference<>(testBoundary("test-production"));
  final AtomicInteger coordinationCalls = new AtomicInteger();
  final AccountEmailLoginChallengeRepository challenges;
  final AccountHostedTermsService terms;
  final AccountControlUiAuthority authority;
  final AccountControlUiIssuanceService service;
  final DataSourceTransactionManager manager;
  final DraftAuthorizationFenceRepository fences;
  final AccountServiceImpl primary;

  /**
   * Reuses actual Account/source/auth owners while a composed test supplies signer/Redis owners.
   */
  AccountControlUiOwnerSourcesFixture(
      String jdbcUrl, String username, String password, Path root, boolean installNegativeIssuer) {
    this(jdbcUrl, username, password, root, installNegativeIssuer, UUID.randomUUID());
  }

  AccountControlUiOwnerSourcesFixture(
      String jdbcUrl,
      String username,
      String password,
      Path root,
      boolean installNegativeIssuer,
      UUID tenant) {
    this.tenant = Objects.requireNonNull(tenant);
    String schema = "control_ui_owner_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("filesystem:" + accountMigrations())
        .load()
        .migrate();
    manager = new DataSourceTransactionManager(dataSource);
    transactions = new TransactionTemplate(manager);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    accounts = new AccountRepository(dsl, sourceEvidence);
    account =
        tx(
            () -> {
              Account candidate = new Account();
              String suffix = UUID.randomUUID().toString();
              candidate.setUsername("owner-" + suffix);
              candidate.setEmail(suffix + "@example.test");
              candidate.setPasswordHash(hash("test-only-disabled-password"));
              candidate.setLoginAuthModes("EMAIL_OTP");
              return accounts.save(candidate);
            });
    var creator = testCreation(account.getAccountUuid(), tenant);
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
    var bootstrapOperations = new AccountTenantCreationBootstrapOperationRepository(dsl);
    var reader =
        new AccountCreatorMembershipSourceReader(
            accounts,
            new AccountJoinOperationRepository(dsl),
            memberships,
            pairs,
            roles,
            fresh,
            generations,
            outbox,
            sourceEvidence,
            bootstrapOperations);
    var beans = new StaticListableBeanFactory();
    AccountTenantCreationBootstrapAuthorizationSource externalBootstrap =
        (actual, mutation) -> {
          if (!creator.equals(actual)
              || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Exact test-only external bootstrap fixture required");
          }
          return mutation.get();
        };
    beans.addBean("testOnlyBootstrapAuthority", externalBootstrap);
    var bootstrap =
        new AccountTenantCreationBootstrapService(
            accounts,
            fresh,
            reader,
            pairs,
            memberships,
            roles,
            outbox,
            new AccountAuditOutboxRepository(dsl),
            bootstrapOperations,
            beans.getBeanProvider(AccountTenantCreationBootstrapAuthorizationSource.class));
    tx(() -> bootstrap.bootstrap(creator));
    var party =
        new IndividualCreatorPartySource(
            UUID.randomUUID(),
            account.getAccountUuid(),
            VerificationStatus.VERIFIED,
            1L,
            "test-only-policy",
            1L,
            "test-only-verification",
            1L,
            1L);
    insertTestParty(dsl, party);
    var parties = new IndividualCreatorPartyRepository(dsl, fresh, bootstrapOperations);
    tx(() -> parties.associateFresh(UUID.randomUUID(), creator, party));
    fences = new DraftAuthorizationFenceRepository(dsl);
    Map<UUID, AccountHostedTermsService.PublicationEvidence> publications = new HashMap<>();
    Map<UUID, AccountHostedTermsService.AcceptanceAction> actions = new HashMap<>();
    Map<UUID, HostedTermsEnvironmentBinding.PublicationEvidence> bindings = new HashMap<>();
    terms =
        new AccountHostedTermsService(
            manager,
            new HostedTermsRepository(dsl),
            parties,
            fences,
            id -> externalFixture(publications, id),
            id -> externalFixture(actions, id),
            new HostedTermsEnvironmentBindingRepository(dsl),
            id -> externalFixture(bindings, id),
            () -> {
              requireOutsideTransaction();
              return boundary.get();
            });
    UUID publication = UUID.randomUUID();
    publications.put(
        publication,
        new AccountHostedTermsService.PublicationEvidence(
            UUID.randomUUID(),
            "Test-only Operator",
            1L,
            bytes("Test-only initial terms"),
            HostedTermsCatalogVersion.Materiality.INITIAL,
            null,
            null,
            "test-only-publication-audit",
            1L,
            "test-only-notice",
            1L,
            Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS)));
    var catalog = terms.publish(publication).candidate();
    UUID action = UUID.randomUUID();
    actions.put(
        action,
        new AccountHostedTermsService.AcceptanceAction(
            action,
            true,
            account.getAccountUuid(),
            party.creatorPartyId(),
            catalog.hostedScopeId(),
            catalog.versionId(),
            catalog.documentDigest(),
            catalog.operatorLegalIdentity(),
            catalog.operatorIdentityVersion(),
            "test-only-affirmative-action",
            1L,
            OffsetDateTime.now(ZoneOffset.UTC)));
    terms.accept(action);
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
    authority =
        new AccountControlUiAuthority(
            new AccountDraftSourceCompositionService(
                dsl, parties, bootstrap, terms, sourceEvidence));
    challenges = new AccountEmailLoginChallengeRepository(dsl);
    tx(
        () -> {
          var challenge = new AccountEmailLoginChallenge();
          var now = LocalDateTime.now().withNano(0);
          challenge.setAccountId(account.getId());
          challenge.setCodeHash(hash(OTP));
          challenge.setExpiresAt(now.plusMinutes(5));
          challenge.setResendAvailableAt(now.plusMinutes(1));
          challenge.setCreatedAt(now);
          challenge.setUpdatedAt(now);
          return challenges.save(challenge);
        });
    var primaryTarget =
        new AccountServiceImpl(
            accounts,
            null,
            null,
            null,
            challenges,
            null,
            null,
            Mappers.getMapper(AccountMapper.class),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            manager);
    // Exercise the real MANDATORY interceptor: noRollbackFor must not poison the owner callback.
    var primaryProxy = new ProxyFactory(primaryTarget);
    primaryProxy.setProxyTargetClass(true);
    primaryProxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    primary = (AccountServiceImpl) primaryProxy.getProxy();
    if (!installNegativeIssuer) {
      service = null;
      return;
    }
    var signerRepository = new AccountJwtSignerDesiredStateRepository(dsl);
    var trust = new AccountJwtSignerMaterializerTrustBinding(false, "");
    var api = new AccountJwtJwksApiBinding();
    var signer =
        new AccountControlUiSignerOwner(
            signerRepository, trust, api, root.resolve("private"), root.resolve("public"));
    var operations = new AccountControlUiIssuanceRepository(dsl);
    var crypto =
        new AccountControlUiResponseCryptography(
            new AccountControlUiKeyring(root.resolve("custody")), Clock.systemUTC());
    var registry =
        new AccountControlUiCoordination(
            () -> {
              coordinationCalls.incrementAndGet();
              throw new IllegalStateException("No physical Redis fixture in this negative proof");
            },
            new AcknowledgementRequirements(1, 1, 1000),
            RedisScriptCatalog.fromContributions(List.of()));
    var publicSource =
        new AccountJwtJwksTrustedSource(
            new AccountJwtJwksConfigMapClient(api),
            trust,
            signerRepository,
            new AccountJwtJwksPublicationRepository(dsl, signerRepository),
            manager);
    var actors =
        new AccountControlUiActorService(
            operations,
            authority,
            signer,
            registry,
            publicSource,
            fences,
            manager,
            Clock.systemUTC());
    service =
        new AccountControlUiIssuanceService(
            primary,
            operations,
            authority,
            fences,
            signer,
            crypto,
            registry,
            actors,
            manager,
            Clock.systemUTC(),
            CALLER);
  }

  AccountControlUiIssuanceService.Request request(String secret) {
    return new AccountControlUiIssuanceService.Request(
        request, account.getEmail(), secret, tenant, callerContext);
  }

  <T> T tx(Supplier<T> action) {
    return transactions.execute(ignored -> action.get());
  }

  /** Uses the real owner to advance generation, fence and source event with the scalar role. */
  Account changeRoleToAdmin() {
    Account current = accounts.findById(account.getId()).orElseThrow();
    current.setRole("admin");
    return accounts.save(current);
  }

  void assertNoIssuance() {
    assertThat(dsl.fetchCount(DSL.table("account_control_ui_issuance_operations"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("account_control_ui_response_envelopes"))).isZero();
    assertThat(coordinationCalls).hasValue(0);
  }

  private static FreshTenantCreatorEvidence testCreation(UUID account, UUID tenant) {
    UUID request = UUID.randomUUID(),
        operation = UUID.randomUUID(),
        authorization = UUID.randomUUID();
    String key = "test-only-game-row-17";
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
    byte[] source = CreatorPartyEncoding.party(party);
    dsl.execute(
        "INSERT INTO account_individual_creator_party_sources"
            + " (creator_party_id, account_uuid, verification_status, identity_version, policy_reference, policy_version,"
            + " verification_evidence_reference, verification_evidence_version, source_version, source_payload, source_digest)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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

  static HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary testBoundary(String name) {
    byte[] source = bytes("test-only-environment-observation/" + name);
    return new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
        name,
        "test-only-environment-owner",
        "test-only-observation/" + name,
        source,
        HostedTermsEncoding.digest(source));
  }

  private static <T> T externalFixture(Map<UUID, T> values, UUID request) {
    requireOutsideTransaction();
    return Objects.requireNonNull(
        values.get(request), "Exact test-only external evidence required");
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "External fixture observation must precede Account owner locks");
    }
  }

  static PeerScope withPeer(String uri) {
    var attached =
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow());
    return new PeerScope(attached.attach(), attached);
  }

  record PeerScope(Context previous, Context attached) implements AutoCloseable {
    @Override
    public void close() {
      attached.detach(previous);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String hash(String value) {
    char[] secret = value.toCharArray();
    var argon = Argon2Factory.create();
    try {
      return argon.hash(2, 4096, 1, secret);
    } finally {
      argon.wipeArray(secret);
    }
  }

  private static Path accountMigrations() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path migrations = current.resolve("services/account-service/src/main/resources/db/migration");
      if (java.nio.file.Files.isDirectory(migrations)) return migrations;
      current = current.getParent();
    }
    throw new IllegalStateException("Exact Account migration directory is required");
  }
}
