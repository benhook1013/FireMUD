package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.accountservice.dto.AccountGameplayPublicAdmissionSourceSnapshot;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import net.firedevops.firemud.accountservice.dto.AccountMembershipRoleSourceSnapshot;
import net.firedevops.firemud.accountservice.dto.AccountMembershipRoleSourceSnapshot.MembershipSource;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayTokenIdentityFenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.service.AccountGameplayPublicAdmissionSourceReader;
import net.firedevops.firemud.accountservice.service.AccountMembershipRoleSourceReader;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedGameplayAuthorityProjectionTest;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner API doubles prove exact composition and guards, not SQL or token authentication. */
class AccountGameplayPublicAdmissionSourceReaderTest {
  private static final UUID ACCOUNT = AccountSelectedGameplayAuthorityProjectionTest.ACCOUNT;
  private static final UUID TENANT = AccountSelectedGameplayAuthorityProjectionTest.TENANT;

  @BeforeEach
  void transaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void composesExactExistingOwnerReadsInAccountFirstOrder() {
    Fixture fixture = new Fixture();

    AccountGameplayPublicAdmissionSourceSnapshot result = fixture.read();

    assertThat(result.membershipSource()).isSameAs(fixture.membership);
    assertThat(result.accountLifecycleState()).isEqualTo(AccountLifecycleState.ACTIVE);
    assertThat(result.entitlementSource()).isEqualTo(fixture.entitlement);
    assertThat(result.tokenIdentityFence()).isEqualTo(fixture.activeFence);
    var ordered =
        inOrder(fixture.memberships, fixture.accounts, fixture.entitlements, fixture.tokenFences);
    ordered.verify(fixture.memberships).readCurrent(ACCOUNT, TENANT);
    ordered.verify(fixture.accounts).findByAccountUuid(ACCOUNT);
    ordered.verify(fixture.entitlements).readCurrent(TENANT);
    ordered.verify(fixture.entitlements).revalidate(fixture.entitlement);
    ordered.verify(fixture.tokenFences).requireActiveForUpdate(fixture.identity);
    verifyNoMoreInteractions(
        fixture.memberships, fixture.accounts, fixture.entitlements, fixture.tokenFences);
  }

  @Test
  void nonActiveAccountLifecycleStatesDenyBeforeEntitlementOrTokenReads() {
    for (AccountLifecycleState state :
        List.of(
            AccountLifecycleState.SECURITY_LOCKED,
            AccountLifecycleState.DEACTIVATED_PENDING_DELETE,
            AccountLifecycleState.DELETED)) {
      Fixture fixture = new Fixture();
      fixture.account.setLifecycleState(state);

      assertThatThrownBy(fixture::read)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Current Account lifecycle does not allow gameplay admission");
      verifyNoInteractions(fixture.entitlements, fixture.tokenFences);
    }
  }

  @Test
  void missingOrMismatchedPersistedAccountIdentityAndProvenanceDeny() {
    List<Consumer<Fixture>> changes =
        List.of(
            f -> when(f.accounts.findByAccountUuid(ACCOUNT)).thenReturn(Optional.empty()),
            f -> f.account.setAccountUuid(UUID.randomUUID()),
            f -> f.account.setId(99L),
            f -> f.account.setAccountUuidProvenance(null),
            f ->
                f.account.setAccountUuidProvenance(
                    AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT),
            f -> f.account.setAccountUuidSourceNumericId(99L),
            f -> f.account.setAccountUuidSourceNumericId(null));

    for (Consumer<Fixture> change : changes) {
      Fixture fixture = new Fixture();
      change.accept(fixture);

      assertDenied(fixture);
      verifyNoInteractions(fixture.entitlements, fixture.tokenFences);
    }
  }

  @Test
  void changedTenantBillingCheckpointFenceScopeVersionLifecycleRolesAndFlagsDeny() {
    List<Consumer<Fixture>> changes =
        List.of(
            f ->
                f.replaceEntitlement(
                    entitlement(
                        AccountSelectedGameplayAuthorityProjectionTest.tenant(UUID.randomUUID()))),
            f -> f.updateEntitlement(fields -> fields.tenantBillingSequence++),
            f -> f.updateEntitlement(fields -> fields.tenantAuthorityOutboxSequence++),
            f -> f.identityWithIssuanceFence(2L),
            f ->
                f.replaceMembership(
                    member -> copyMember(member, UUID.randomUUID(), TENANT, null, null)),
            f -> f.updateEntitlement(fields -> fields.tenantAuthorityGeneration++),
            f ->
                f.replaceMembership(
                    member -> copyMember(member, ACCOUNT, TENANT, "INACTIVE", null)),
            f ->
                f.replaceMembership(
                    member -> copyMember(member, ACCOUNT, TENANT, null, List.of("tenantAdmin"))),
            f ->
                f.replaceMembership(
                    member -> copyMember(member, ACCOUNT, TENANT, null, null, false)),
            f -> f.updateEntitlement(fields -> fields.gameplayAvailable = false),
            f -> f.updateEntitlement(fields -> fields.allowNewGameplayBindings = false));

    for (Consumer<Fixture> change : changes) {
      Fixture fixture = new Fixture();
      change.accept(fixture);
      assertDenied(fixture);
    }
  }

  @Test
  void changedExactTenantSourceAndBillingReceiptIdentitiesDeny() {
    for (Consumer<EntitlementFields> change :
        List.<Consumer<EntitlementFields>>of(
            fields -> fields.sourceEvidence = differentSourceEvidence(),
            fields -> fields.tenantAuthoritySourceVersion++,
            fields -> fields.tenantAuthorityEventId = UUID.randomUUID(),
            fields -> fields.tenantAuthorityEventDigest = "sha256:" + "0".repeat(64),
            fields -> fields.eventId = UUID.randomUUID(),
            fields -> {
              fields.eventDigest = "sha256:" + "0".repeat(64);
              fields.snapshotIdentity = fields.eventDigest;
            })) {
      Fixture fixture = new Fixture();
      fixture.updateEntitlement(change);
      assertDenied(fixture);
    }
  }

  @Test
  void missingOrNullOwnerEvidenceFailsClosed() {
    Fixture missingMembership = new Fixture();
    when(missingMembership.memberships.readCurrent(ACCOUNT, TENANT)).thenReturn(null);
    assertDenied(missingMembership);
    verifyNoInteractions(missingMembership.entitlements, missingMembership.tokenFences);

    Fixture missingEntitlement = new Fixture();
    when(missingEntitlement.entitlements.readCurrent(TENANT)).thenReturn(null);
    assertDenied(missingEntitlement);
    verifyNoInteractions(missingEntitlement.tokenFences);

    Fixture missingRevalidation = new Fixture();
    when(missingRevalidation.entitlements.revalidate(missingRevalidation.entitlement))
        .thenReturn(null);
    assertDenied(missingRevalidation);
    verifyNoInteractions(missingRevalidation.tokenFences);

    Fixture missingFence = new Fixture();
    when(missingFence.tokenFences.requireActiveForUpdate(missingFence.identity)).thenReturn(null);
    assertDenied(missingFence);
  }

  @Test
  void inactiveSqlFenceAndChangedRevalidatedSnapshotDeny() {
    Fixture inactive = new Fixture();
    when(inactive.tokenFences.requireActiveForUpdate(inactive.identity))
        .thenReturn(
            new AccountGameplayTokenIdentityFence(
                inactive.identity, 2L, State.PENDING, UUID.randomUUID(), "0".repeat(64)));
    assertDenied(inactive);

    Fixture changedDuringRevalidation = new Fixture();
    EntitlementFields changedFields = new EntitlementFields(changedDuringRevalidation.entitlement);
    changedFields.entitlementVersion++;
    DemoTenantEntitlementSnapshot changed = changedFields.build();
    when(changedDuringRevalidation.entitlements.revalidate(changedDuringRevalidation.entitlement))
        .thenReturn(changed);
    assertDenied(changedDuringRevalidation);
    verifyNoInteractions(changedDuringRevalidation.tokenFences);
  }

  @Test
  void transactionAndIsolationGuardRunsBeforeAnyOwnerRead() {
    for (Consumer<Fixture> invalidTransaction :
        List.<Consumer<Fixture>>of(
            f -> TransactionSynchronizationManager.setActualTransactionActive(false),
            f -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true),
            f ->
                TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                    TransactionDefinition.ISOLATION_READ_COMMITTED))) {
      Fixture fixture = new Fixture();
      invalidTransaction.accept(fixture);
      assertDenied(fixture);
      verifyNoInteractions(
          fixture.memberships, fixture.accounts, fixture.entitlements, fixture.tokenFences);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
  }

  @Test
  void onlyExactCanonicalAccountAndTenantSelectorsAreAccepted() {
    Fixture fixture = new Fixture();
    assertThatThrownBy(
            () -> fixture.reader.readCurrent(UUID.randomUUID(), TENANT, fixture.identity))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(
        fixture.memberships, fixture.accounts, fixture.entitlements, fixture.tokenFences);
  }

  @Test
  void acceptsSerializableOwnerTransaction() {
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_SERIALIZABLE);

    assertThat(new Fixture().read()).isNotNull();
  }

  private static void assertDenied(Fixture fixture) {
    assertThatThrownBy(fixture::read).isInstanceOf(RuntimeException.class);
  }

  private static AccountMembershipRoleSourceSnapshot copyMember(
      AccountMembershipRoleSourceSnapshot source,
      UUID accountId,
      UUID tenantId,
      String lifecycle,
      List<String> roleIdentifiers) {
    return copyMember(source, accountId, tenantId, lifecycle, roleIdentifiers, null);
  }

  private static AccountMembershipRoleSourceSnapshot copyMember(
      AccountMembershipRoleSourceSnapshot source,
      UUID accountId,
      UUID tenantId,
      String lifecycle,
      List<String> roleIdentifiers,
      Boolean gameplayAllowed) {
    MembershipSource old = source.membership();
    MembershipSource member =
        new MembershipSource(
            old.membershipId(),
            old.accountRowId(),
            accountId,
            old.accountIdentityProvenance(),
            old.accountSourceNumericId(),
            tenantId,
            old.tenantProvenance(),
            lifecycle == null ? old.lifecycleState() : lifecycle,
            gameplayAllowed == null ? old.gameplayAdmissionAllowed() : gameplayAllowed,
            old.membershipVersion(),
            old.membershipAuthorityGeneration(),
            old.authorityProvenance());
    RoleSnapshot oldRoles = source.roles();
    RoleSnapshot roles =
        roleIdentifiers == null
            ? oldRoles
            : new RoleSnapshot(
                oldRoles.accountId(),
                oldRoles.tenantId(),
                oldRoles.membershipId(),
                oldRoles.snapshotVersion(),
                roleIdentifiers,
                oldRoles.accountUuid(),
                oldRoles.tenantUuid(),
                oldRoles.tenantProvenance());
    return new AccountMembershipRoleSourceSnapshot(
        source.issuerAccountSources(),
        source.currentAuthority(),
        source.tenantSource(),
        source.pair(),
        member,
        roles,
        source.membershipCheckpoint(),
        source.storedMembershipEvent(),
        source.membershipEvent());
  }

  private static final class Fixture {
    final AccountMembershipRoleSourceReader memberships =
        mock(AccountMembershipRoleSourceReader.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final AccountDemoTenantEntitlementRepository entitlements =
        mock(AccountDemoTenantEntitlementRepository.class);
    final AccountGameplayTokenIdentityFenceRepository tokenFences =
        mock(AccountGameplayTokenIdentityFenceRepository.class);
    final Account account = account();
    final AccountGameplayPublicAdmissionSourceReader reader =
        new AccountGameplayPublicAdmissionSourceReader(
            memberships, accounts, entitlements, tokenFences);
    TokenIdentity identity =
        new TokenIdentity(
            ACCOUNT,
            UUID.fromString("efcfc7ed-1019-4ef7-9f27-87218fe0e911"),
            UUID.fromString("f1b119cf-095f-4f03-b571-0fdd3d749c57"),
            "a".repeat(64),
            UUID.fromString("a1c51e75-ec05-4d9b-9ce0-965e775a9ac4"),
            1_800_000_000L,
            1L,
            1L);
    final AccountGameplayTokenIdentityFence activeFence =
        new AccountGameplayTokenIdentityFence(identity, 1L, State.ACTIVE, null, null);
    final AccountMembershipRoleSourceSnapshot membership = membershipSnapshot();
    DemoTenantEntitlementSnapshot entitlement =
        entitlement(AccountSelectedGameplayAuthorityProjectionTest.tenant(TENANT));

    Fixture() {
      when(memberships.readCurrent(ACCOUNT, TENANT)).thenReturn(membership);
      when(accounts.findByAccountUuid(ACCOUNT)).thenReturn(Optional.of(account));
      when(entitlements.readCurrent(TENANT)).thenReturn(entitlement);
      when(entitlements.revalidate(entitlement)).thenReturn(entitlement);
      when(tokenFences.requireActiveForUpdate(identity)).thenReturn(activeFence);
    }

    private static Account account() {
      Account account = new Account();
      account.setId(4L);
      account.setAccountUuid(ACCOUNT);
      account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
      account.setAccountUuidSourceNumericId(4L);
      return account;
    }

    AccountGameplayPublicAdmissionSourceSnapshot read() {
      return reader.readCurrent(ACCOUNT, TENANT, identity);
    }

    void identityWithIssuanceFence(long fence) {
      // A mismatched identity reaches the same owner read but cannot match Account's source fence.
      TokenIdentity changed =
          new TokenIdentity(
              identity.accountId(),
              identity.operationId(),
              identity.issuanceRequestId(),
              identity.tokenSha256(),
              identity.tokenJti(),
              identity.notBeforeEpochSecond(),
              identity.tokenGeneration(),
              fence);
      when(tokenFences.requireActiveForUpdate(changed))
          .thenReturn(new AccountGameplayTokenIdentityFence(changed, 1L, State.ACTIVE, null, null));
      identity = changed;
    }

    void replaceMembership(UnaryOperator<AccountMembershipRoleSourceSnapshot> change) {
      AccountMembershipRoleSourceSnapshot changed = change.apply(membership);
      when(memberships.readCurrent(ACCOUNT, TENANT)).thenReturn(changed);
    }

    void updateEntitlement(Consumer<EntitlementFields> change) {
      EntitlementFields fields = new EntitlementFields(entitlement);
      change.accept(fields);
      entitlement = fields.build();
      when(entitlements.readCurrent(TENANT)).thenReturn(entitlement);
      when(entitlements.revalidate(entitlement)).thenReturn(entitlement);
    }

    void replaceEntitlement(DemoTenantEntitlementSnapshot changed) {
      entitlement = changed;
      when(entitlements.readCurrent(TENANT)).thenReturn(entitlement);
      when(entitlements.revalidate(entitlement)).thenReturn(entitlement);
    }

    private AccountMembershipRoleSourceSnapshot membershipSnapshot() {
      String issuer = GameSessionAccountDelegationProfile.ISSUER;
      var issuanceFence = new IssuanceFence(ACCOUNT, 1L, 1L);
      var issuerSource =
          new CurrentSourceEvidence(
              AuthorityScope.issuer(issuer),
              1L,
              1L,
              null,
              new SourceCheckpoint(
                  "account:auth-authority:v1:issuer/" + issuer,
                  0L,
                  Optional.empty(),
                  Optional.empty()),
              Optional.empty(),
              "ISSUER_SCOPE_INSERT",
              null,
              null,
              10L,
              null);
      var accountSource =
          new CurrentSourceEvidence(
              AuthorityScope.account(ACCOUNT),
              1L,
              1L,
              issuanceFence,
              new SourceCheckpoint(
                  "account:auth-authority:v1:account/" + ACCOUNT,
                  0L,
                  Optional.empty(),
                  Optional.empty()),
              Optional.empty(),
              "ACCOUNT_REPOSITORY_INSERT",
              4L,
              "ACCOUNT_REPOSITORY_INSERT",
              11L,
              11L);
      var sources = new IssuerAccountSourceSnapshot(issuerSource, accountSource, issuanceFence);
      var authority =
          new CompositeSnapshot(
              new ScopeState(AuthorityScope.issuer(issuer), 1L, 1L, null),
              new ScopeState(AuthorityScope.account(ACCOUNT), 1L, 1L, issuanceFence),
              List.of(new ScopeState(AuthorityScope.tenant(TENANT), 2L, 2L, null)),
              List.of(
                  new ScopeState(
                      AuthorityScope.membership(ACCOUNT, TENANT), 1L, 1L, issuanceFence)),
              issuanceFence);
      TenantAuthorityEventV1Codec.Event tenant =
          AccountSelectedGameplayAuthorityProjectionTest.tenant(TENANT);
      var provenance =
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              tenant.sourceEvidence().operationId(),
              tenant.sourceEvidence().evidenceDigest());
      var memberEvent =
          AccountSelectedGameplayAuthorityProjectionTest.member(ACCOUNT, TENANT, "1", "2", "1");
      String membershipStream = "account:auth-authority:v1:membership/" + ACCOUNT + "/" + TENANT;
      var pair =
          new PairAuthority(
              ACCOUNT,
              TENANT,
              provenance,
              true,
              2L,
              1L,
              1L,
              memberEvent.eventId(),
              memberEvent.eventDigest(),
              false);
      var roles =
          new RoleSnapshot(4L, null, 5L, 2L, List.of("player"), ACCOUNT, TENANT, provenance);
      var member =
          new MembershipSource(
              5L,
              4L,
              ACCOUNT,
              AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
              4L,
              TENANT,
              provenance,
              "ACTIVE",
              true,
              2L,
              1L,
              "EXPLICIT_JOIN");
      var checkpoint =
          new Checkpoint(membershipStream, 1L, memberEvent.eventId(), memberEvent.eventDigest());
      var stored =
          new Event(
              membershipStream,
              memberEvent.requestId(),
              1L,
              memberEvent.eventId(),
              memberEvent.eventDigest(),
              memberEvent.canonicalJsonUtf8());
      return new AccountMembershipRoleSourceSnapshot(
          sources, authority, tenant, pair, member, roles, checkpoint, stored, memberEvent);
    }
  }

  private static DemoTenantEntitlementSnapshot entitlement(
      TenantAuthorityEventV1Codec.Event tenant) {
    return new DemoTenantEntitlementSnapshot(
        tenant.tenantId(),
        tenant.sourceEvidence(),
        "NON_PAID_DEMO",
        "ACTIVE",
        null,
        false,
        true,
        true,
        true,
        true,
        new net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest.Quotas(
            1L, 1L, 1L),
        1L,
        tenant.tenantAuthorityGeneration(),
        tenant.tenantAuthoritySourceVersion(),
        tenant.tenantBillingStreamKey(),
        tenant.tenantBillingSequence(),
        tenant.tenantBillingEventId(),
        tenant.tenantBillingEventDigest(),
        tenant.tenantBillingEventDigest(),
        tenant.outboxStreamKey(),
        tenant.outboxSequence(),
        tenant.eventId(),
        tenant.eventDigest());
  }

  private static FreshTenantCreationEvidence differentSourceEvidence() {
    var source = AccountSelectedGameplayAuthorityProjectionTest.tenant(TENANT).sourceEvidence();
    long changedRowId = source.sourceGameRowId() + 1L;
    String changedTenantKey = source.sourceGameTenantKey() + "-different";
    return new FreshTenantCreationEvidence(
        source.schemaVersion(),
        source.targetNamespace(),
        source.creationRequestId(),
        source.operationId(),
        source.requestDigest(),
        source.canonicalTenantId(),
        changedRowId,
        changedTenantKey,
        source.provenanceKind(),
        GameTenantCreationDigest.evidenceDigest(
            source.targetNamespace(),
            source.creationRequestId(),
            source.operationId(),
            source.requestDigest(),
            source.canonicalTenantId(),
            changedRowId,
            changedTenantKey,
            source.provenanceKind()));
  }

  private static final class EntitlementFields {
    UUID canonicalTenantId;
    net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence sourceEvidence;
    boolean gameplayAvailable;
    boolean allowNewGameplayBindings;
    long entitlementVersion;
    long tenantAuthorityGeneration;
    long tenantAuthoritySourceVersion;
    String outboxStreamKey;
    long tenantBillingSequence;
    UUID eventId;
    String eventDigest;
    String snapshotIdentity;
    String tenantAuthorityOutboxStreamKey;
    long tenantAuthorityOutboxSequence;
    UUID tenantAuthorityEventId;
    String tenantAuthorityEventDigest;

    EntitlementFields(DemoTenantEntitlementSnapshot source) {
      canonicalTenantId = source.canonicalTenantId();
      sourceEvidence = source.sourceEvidence();
      gameplayAvailable = source.gameplayAvailable();
      allowNewGameplayBindings = source.allowNewGameplayBindings();
      entitlementVersion = source.entitlementVersion();
      tenantAuthorityGeneration = source.tenantAuthorityGeneration();
      tenantAuthoritySourceVersion = source.tenantAuthoritySourceVersion();
      outboxStreamKey = source.outboxStreamKey();
      tenantBillingSequence = source.tenantBillingSequence();
      eventId = source.eventId();
      eventDigest = source.eventDigest();
      snapshotIdentity = source.snapshotIdentity();
      tenantAuthorityOutboxStreamKey = source.tenantAuthorityOutboxStreamKey();
      tenantAuthorityOutboxSequence = source.tenantAuthorityOutboxSequence();
      tenantAuthorityEventId = source.tenantAuthorityEventId();
      tenantAuthorityEventDigest = source.tenantAuthorityEventDigest();
    }

    DemoTenantEntitlementSnapshot build() {
      return new DemoTenantEntitlementSnapshot(
          canonicalTenantId,
          sourceEvidence,
          "NON_PAID_DEMO",
          "ACTIVE",
          null,
          false,
          gameplayAvailable,
          true,
          allowNewGameplayBindings,
          true,
          new net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest.Quotas(
              1L, 1L, 1L),
          entitlementVersion,
          tenantAuthorityGeneration,
          tenantAuthoritySourceVersion,
          outboxStreamKey,
          tenantBillingSequence,
          eventId,
          eventDigest,
          snapshotIdentity,
          tenantAuthorityOutboxStreamKey,
          tenantAuthorityOutboxSequence,
          tenantAuthorityEventId,
          tenantAuthorityEventDigest);
    }
  }
}
