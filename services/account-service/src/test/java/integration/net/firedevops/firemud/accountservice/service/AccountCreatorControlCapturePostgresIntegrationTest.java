package integration.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository.InitialAssociationReadback;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.dto.AccountTenantCreationBootstrapResult;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCreatorMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.CreatorControlCaptureSources;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapAuthorizationSource;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.test.TestContainerImages;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the owner-verified creator-control capture against committed Account storage. */
@Testcontainers(disabledWithoutDocker = true)
class AccountCreatorControlCapturePostgresIntegrationTest {
  private static final String NAMESPACE = "creator-control-capture-proof";
  private static final String ISSUER = "firemud-account-service";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void capturesExactCommittedCreatorSourcesAndRequiresAnAccessibleOriginalReceipt() {
    UUID bootstrapRequestId = UUID.randomUUID();
    var fixture = new Fixture(bootstrapRequestId);
    FreshTenantCreationEvidence creation = tenantCreation();
    UUID tenantId = creation.canonicalTenantId();
    UUID accountId = fixture.account.getAccountUuid();
    String authorizationDigest = "sha256:" + "a".repeat(64);
    var creator =
        new FreshTenantCreatorEvidence(
            1,
            creation,
            accountId,
            bootstrapRequestId,
            authorizationDigest,
            FreshTenantCreatorDigest.evidenceDigest(
                1, creation, accountId, bootstrapRequestId, authorizationDigest));
    var party =
        new IndividualCreatorPartySource(
            UUID.randomUUID(),
            accountId,
            VerificationStatus.VERIFIED,
            1L,
            "fixture-policy",
            1L,
            "fixture-verification",
            1L,
            1L);
    UUID partyRequestId = UUID.randomUUID();

    var committed =
        Objects.requireNonNull(
            fixture.transaction.execute(
                ignored -> {
                  fixture.tenants.importVerified(creation);
                  fixture.generations.initializeTenantIfAbsent(tenantId);
                  var bootstrap = fixture.bootstrap.bootstrap(creator);
                  fixture.insertControlledIndividualSource(party);
                  var resolved =
                      fixture.parties.readIndividualSource(party.creatorPartyId(), accountId);
                  var association =
                      fixture.parties.associateFresh(partyRequestId, creator, resolved);
                  return new CommittedSources(
                      bootstrap, new InitialAssociationReadback(creator, association, party));
                }));

    var first = fixture.capture(fixture.bootstrap, tenantId);
    assertThat(first.party()).isEqualTo(committed.party());
    assertExactSources(first.creator(), committed.bootstrap(), fixture.account, creation);

    assertThatThrownBy(() -> fixture.capture(fixture.bootstrapWithUnavailableReceipt, tenantId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Committed creator-bootstrap receipt is absent");

    var recovered = fixture.capture(fixture.bootstrap, tenantId);
    assertThat(recovered)
        .usingRecursiveComparison()
        .ignoringFields("creator.evaluatedAt")
        .isEqualTo(first);
  }

  private static void assertExactSources(
      CreatorControlCaptureSources captured,
      AccountTenantCreationBootstrapResult committed,
      Account account,
      FreshTenantCreationEvidence creation) {
    UUID accountId = account.getAccountUuid();
    UUID tenantId = creation.canonicalTenantId();
    String prefix = MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX;
    String stream = prefix + "membership/" + accountId + "/" + tenantId;
    Map<String, String> generation = Map.of(tenantId.toString(), "1");
    Map<String, String> version = Map.of(tenantId.toString(), "2");
    var provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            creation.operationId(),
            creation.evidenceDigest());
    var receipt = captured.bootstrapReceipt();
    var event = captured.sourceEvent();

    assertThat(captured.accountUuid()).isEqualTo(accountId);
    assertThat(captured.tenantUuid()).isEqualTo(tenantId);
    assertThat(captured.creationSource()).isEqualTo(creation);
    assertThat(captured.membershipVersion()).isEqualTo(version);
    assertThat(captured.authorityTuple())
        .isEqualTo(
            new AuthorityTuple(
                "1", "1", generation, generation, List.of(), Optional.empty(), Optional.empty()));
    assertThat(receipt.status()).isEqualTo("COMMITTED");
    assertThat(receipt.result()).isEqualTo(committed);
    assertThat(receipt.creationOperationId()).isEqualTo(creation.operationId());
    assertThat(receipt.creationRequestId()).isEqualTo(creation.creationRequestId());
    assertThat(receipt.membershipRolesPayload())
        .containsExactly("[\"tenantAdmin\"]".getBytes(StandardCharsets.UTF_8));
    assertThat(receipt.eventPayload()).containsExactly(event.canonicalJsonUtf8());
    assertThat(captured.roleSource())
        .isEqualTo(
            new RoleSnapshot(
                account.getId(),
                null,
                Objects.requireNonNull(receipt.membershipId()),
                2L,
                List.of("tenantAdmin"),
                accountId,
                tenantId,
                provenance));
    assertThat(captured.pairSource())
        .isEqualTo(
            new PairAuthority(
                accountId,
                tenantId,
                provenance,
                true,
                2L,
                1L,
                1L,
                committed.eventId(),
                committed.eventDigest(),
                false));

    assertThat(event.eventId()).isEqualTo(committed.eventId());
    assertThat(event.eventDigest()).isEqualTo(committed.eventDigest());
    assertThat(event.requestId()).isEqualTo(committed.operationId().toString());
    assertThat(event.outboxStreamKey()).isEqualTo(stream);
    assertThat(event.outboxSequence()).isEqualTo("1");
    assertThat(event.accountId()).isEqualTo(accountId.toString());
    assertThat(event.tenantId()).isEqualTo(tenantId.toString());
    assertThat(event.membershipVersion()).isEqualTo(version);
    assertThat(event.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(event.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(event.roles()).containsExactly("tenantAdmin");
    assertThat(event.gameplayAdmissionAllowed()).isFalse();
    assertThat(event.callerBoundAuthorityInvalidated()).isFalse();
    assertThat(event.authorityTuple()).isEqualTo(captured.authorityTuple());
    assertThat(event.issuanceFence()).isEqualTo(captured.issuanceFence());

    assertThat(captured.outboxCheckpoints())
        .containsExactly(
            new OutboxCheckpointEntry(prefix + "account/" + accountId, "0"),
            new OutboxCheckpointEntry(prefix + "issuer/" + ISSUER, "0"),
            new OutboxCheckpointEntry(stream, "1"),
            new OutboxCheckpointEntry(prefix + "tenant/" + tenantId, "0"));
    assertThat(captured.outboxSourceEvidence())
        .containsExactly(
            new OutboxSourceEvidence(
                stream, "1", committed.eventId(), committed.eventDigest(), event.canonicalJson()));
  }

  private static FreshTenantCreationEvidence tenantCreation() {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    String key = "creator-capture-game";
    String disposition = "NEW_GAME_ROW";
    String requestDigest =
        GameTenantCreationDigest.requestDigest(NAMESPACE, requestId, key, "Creator Capture", null);
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        requestId,
        operationId,
        requestDigest,
        tenantId,
        19L,
        key,
        disposition,
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE, requestId, operationId, requestDigest, tenantId, 19L, key, disposition));
  }

  private record CommittedSources(
      AccountTenantCreationBootstrapResult bootstrap, InitialAssociationReadback party) {}

  private record CapturedSources(
      CreatorControlCaptureSources creator, InitialAssociationReadback party) {}

  private static final class Fixture {
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final Account account;
    private final FreshTenantIdentityAssociationRepository tenants;
    private final AccountAuthorityGenerationRepository generations;
    private final IndividualCreatorPartyRepository parties;
    private final AccountTenantCreationBootstrapService bootstrap;
    private final AccountTenantCreationBootstrapService bootstrapWithUnavailableReceipt;

    private Fixture(UUID unavailableReceiptId) {
      String schema = "creator_capture_" + UUID.randomUUID().toString().replace("-", "");
      var source =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      source.setSchema(schema);
      Flyway.configure()
          .dataSource(source)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
      dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
      transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
      transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      tenants = new FreshTenantIdentityAssociationRepository(dsl, NAMESPACE);
      generations = new AccountAuthorityGenerationRepository(dsl);
      var outbox = new AccountAuthorityOutboxRepository(dsl);
      var sources = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
      var accounts = new AccountRepository(dsl, sources);
      var pairs = new AccountMembershipPairAuthorityRepository(dsl);
      var memberships = new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);
      var roles = new AccountTenantMembershipRoleSnapshotRepository(dsl);
      var operations = new AccountTenantCreationBootstrapOperationRepository(dsl);
      var reader =
          new AccountCreatorMembershipSourceReader(
              accounts,
              new AccountJoinOperationRepository(dsl),
              memberships,
              pairs,
              roles,
              tenants,
              generations,
              outbox,
              sources,
              operations);
      var providers = new StaticListableBeanFactory();
      providers.addBean(
          "controlledCreatorAuthorization",
          (AccountTenantCreationBootstrapAuthorizationSource)
              (evidence, mutation) -> mutation.get());
      var authorization =
          providers.getBeanProvider(AccountTenantCreationBootstrapAuthorizationSource.class);
      var audit = new AccountAuditOutboxRepository(dsl);
      bootstrap =
          new AccountTenantCreationBootstrapService(
              accounts,
              tenants,
              reader,
              pairs,
              memberships,
              roles,
              outbox,
              audit,
              operations,
              authorization);
      // The real lookup still takes its row lock. Only the negative capture loses its result.
      var unavailableReceipt =
          new AccountTenantCreationBootstrapOperationRepository(dsl) {
            @Override
            public Optional<StoredOperation> findForUpdate(UUID requestId) {
              var persisted = super.findForUpdate(requestId);
              if (unavailableReceiptId.equals(requestId)) {
                assertThat(persisted).isPresent();
                return Optional.empty();
              }
              return persisted;
            }
          };
      bootstrapWithUnavailableReceipt =
          new AccountTenantCreationBootstrapService(
              accounts,
              tenants,
              reader,
              pairs,
              memberships,
              roles,
              outbox,
              audit,
              unavailableReceipt,
              authorization);
      parties = new IndividualCreatorPartyRepository(dsl, tenants, operations);

      var input = new Account();
      input.setUsername("creator-capture");
      input.setEmail("creator-capture@example.test");
      input.setPasswordHash("fixture-password-hash");
      input.setRole("player");
      account = Objects.requireNonNull(transaction.execute(ignored -> accounts.save(input)));
    }

    private void insertControlledIndividualSource(IndividualCreatorPartySource party) {
      byte[] payload = CreatorPartyEncoding.party(party);
      dsl.execute(
          "INSERT INTO account_individual_creator_party_sources "
              + "(creator_party_id, account_uuid, verification_status, identity_version, "
              + "policy_reference, policy_version, verification_evidence_reference, "
              + "verification_evidence_version, source_version, source_payload, source_digest) "
              + "VALUES (?, ?, 'VERIFIED', 1, ?, 1, ?, 1, 1, ?, ?)",
          party.creatorPartyId(),
          party.accountId(),
          party.policyReference(),
          party.verificationEvidenceReference(),
          payload,
          CreatorPartyEncoding.digest(payload));
    }

    private CapturedSources capture(AccountTenantCreationBootstrapService owner, UUID tenantId) {
      return Objects.requireNonNull(
          transaction.execute(
              ignored -> {
                var scope = parties.lockExistingInitialAssociationScope(tenantId);
                var creator =
                    owner.readExistingCreatorControlCaptureSources(scope.accountId(), tenantId);
                return new CapturedSources(
                    creator, parties.readExistingInitialAssociation(tenantId));
              }));
    }
  }
}
