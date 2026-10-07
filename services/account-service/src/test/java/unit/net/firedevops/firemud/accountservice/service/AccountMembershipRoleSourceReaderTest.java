package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.service.AccountMembershipRoleSourceReader;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedGameplayAuthorityProjectionTest;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Owner doubles prove orchestration/source comparisons, not PostgreSQL or actor authentication. */
class AccountMembershipRoleSourceReaderTest {
  private static final UUID ACCOUNT = AccountSelectedGameplayAuthorityProjectionTest.ACCOUNT;
  private static final UUID TENANT = AccountSelectedGameplayAuthorityProjectionTest.TENANT;
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;

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
  void completeCaptureLocksAccountFirstAndCopiesMutableMembership() {
    Fixture f = new Fixture();
    var snapshot = f.read();
    assertThat(snapshot.membershipEvent().membershipVersion())
        .isEqualTo(Map.of(TENANT.toString(), "2"));
    assertThat(snapshot.membership().membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(snapshot.currentAuthority()).isEqualTo(f.authority);
    assertThat(snapshot.tenantSource()).isEqualTo(f.tenant);
    f.member.setLifecycleState("INACTIVE");
    assertThat(snapshot.membership().lifecycleState()).isEqualTo("ACTIVE");
    byte[] payload = snapshot.storedMembershipEvent().payload();
    payload[0] = 0;
    assertThat(snapshot.storedMembershipEvent().payload())
        .containsExactly(f.event.canonicalJsonUtf8());
    assertThatThrownBy(() -> snapshot.roles().roles().add("tenantAdmin"))
        .isInstanceOf(UnsupportedOperationException.class);
    var ordered =
        inOrder(f.sources, f.generations, f.tenants, f.memberships, f.pairs, f.roles, f.outbox);
    ordered.verify(f.sources).readCurrentIssuerAccountSources(ISSUER, ACCOUNT);
    ordered
        .verify(f.generations)
        .readCompositeSnapshot(ISSUER, ACCOUNT, List.of(TENANT), List.of(TENANT));
    ordered.verify(f.tenants).readCurrentByTenant(TENANT);
    ordered.verify(f.memberships).findFreshMembershipForUpdate(ACCOUNT, TENANT);
    ordered.verify(f.pairs).readForUpdate(ACCOUNT, TENANT);
    ordered.verify(f.roles).findForCanonicalUpdate(ACCOUNT, TENANT, f.provenance, 5L, 2L);
  }

  @Test
  void inactiveInvalidatedHistoryIsStorageCaptureWithoutAdmission() {
    Fixture f = new Fixture();
    f.member.setLifecycleState("INACTIVE");
    f.member.setGameplayAdmissionAllowed(false);
    f.replaceEvent(
        fields -> {
          fields.put("membershipLifecycleState", "INACTIVE");
          fields.put("gameplayAdmissionAllowed", false);
          fields.put("callerBoundAuthorityInvalidated", true);
        });
    var snapshot = f.read();
    assertThat(snapshot.membership().lifecycleState()).isEqualTo("INACTIVE");
    assertThat(snapshot.membership().gameplayAdmissionAllowed()).isFalse();
    assertThat(snapshot.pair().lastTransitionInvalidated()).isTrue();
  }

  @Test
  void independentlyAdvancedOwnersPreserveHistoricalMembershipEventExactly() {
    Fixture f = new Fixture();
    f.replaceEvent(
        fields ->
            fields.put(
                "authorityTuple",
                Map.of(
                    "issuerAuthGeneration",
                    "1",
                    "accountAuthorityGeneration",
                    "1",
                    "tenantAuthorityGeneration",
                    Map.of(TENANT.toString(), "1"),
                    "membershipAuthorityGeneration",
                    Map.of(TENANT.toString(), "1"),
                    "privateRealmGrantVersions",
                    List.of())));
    var historical = f.event;
    f.advanceIndependentOwners();
    var snapshot = f.read();
    assertThat(snapshot.currentAuthority().issuer().generation()).isEqualTo(2L);
    assertThat(snapshot.currentAuthority().account().generation()).isEqualTo(2L);
    assertThat(snapshot.currentAuthority().tenants().getFirst().generation()).isEqualTo(2L);
    assertThat(snapshot.currentAuthority().issuanceFence().value()).isEqualTo(2L);
    assertThat(snapshot.membershipEvent().authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(snapshot.membershipEvent().authorityTuple().accountAuthorityGeneration())
        .isEqualTo("1");
    assertThat(snapshot.membershipEvent().authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(TENANT.toString(), "1"));
    assertThat(snapshot.membershipEvent().eventId()).isEqualTo(historical.eventId());
    assertThat(snapshot.membershipEvent().eventDigest()).isEqualTo(historical.eventDigest());
    assertThat(snapshot.storedMembershipEvent().payload())
        .containsExactly(historical.canonicalJsonUtf8());
  }

  @Test
  void wrongMembershipIdentityProvenanceRolesAndIndependentCountersDeny() {
    for (Consumer<Fixture> change :
        List.<Consumer<Fixture>>of(
            f -> f.member.setTenantUuid(UUID.randomUUID()),
            f -> f.member.setTenantSourceOperationId(UUID.randomUUID()),
            f -> f.member.setTenantProvenanceDigest("sha256:" + "0".repeat(64)),
            f -> f.member.getAccount().setAccountUuid(UUID.randomUUID()),
            f -> f.member.getAccount().setAccountUuidSourceNumericId(9L),
            f -> f.member.setMembershipVersion(3L),
            f -> f.member.setMembershipAuthorityGeneration(2L),
            f -> f.member.setAuthorityProvenance(null),
            f ->
                when(f.roles.findForCanonicalUpdate(ACCOUNT, TENANT, f.provenance, 5L, 2L))
                    .thenReturn(
                        Optional.of(
                            new RoleSnapshot(
                                4L,
                                null,
                                5L,
                                2L,
                                List.of("player", "tenantAdmin"),
                                ACCOUNT,
                                TENANT,
                                f.provenance))),
            f ->
                when(f.roles.findForCanonicalUpdate(ACCOUNT, TENANT, f.provenance, 5L, 2L))
                    .thenReturn(Optional.empty()))) {
      Fixture f = new Fixture();
      change.accept(f);
      assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void missingPairMembershipEventOrCheckpointAndZeroEventDeny() {
    for (Consumer<Fixture> change :
        List.<Consumer<Fixture>>of(
            f ->
                when(f.memberships.findFreshMembershipForUpdate(ACCOUNT, TENANT))
                    .thenReturn(Optional.empty()),
            f -> when(f.pairs.readForUpdate(ACCOUNT, TENANT)).thenReturn(Optional.empty()),
            f ->
                when(f.pairs.readForUpdate(ACCOUNT, TENANT))
                    .thenReturn(
                        Optional.of(
                            new PairAuthority(
                                ACCOUNT,
                                TENANT,
                                f.provenance,
                                false,
                                1L,
                                1L,
                                0L,
                                null,
                                null,
                                false))),
            f -> when(f.outbox.readCheckpoint(f.stream())).thenReturn(Optional.empty()),
            f -> when(f.outbox.findEvent(f.stream(), 1L)).thenReturn(Optional.empty()),
            f ->
                when(f.outbox.readCheckpoint(f.stream()))
                    .thenReturn(
                        Optional.of(
                            new Checkpoint(
                                f.stream(),
                                1L,
                                UUID.randomUUID().toString(),
                                f.event.eventDigest()))),
            f ->
                when(f.outbox.readCheckpoint(f.stream()))
                    .thenReturn(
                        Optional.of(
                            new Checkpoint(
                                f.stream(), 2L, f.event.eventId(), f.event.eventDigest()))))) {
      Fixture f = new Fixture();
      change.accept(f);
      assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void storedEventIdentityDigestCanonicalBytesScopeAndMapCannotBeForged() {
    for (Consumer<Fixture> change :
        List.<Consumer<Fixture>>of(
            f ->
                f.stored(
                    new Event(
                        f.stream(),
                        f.event.requestId(),
                        1L,
                        UUID.randomUUID().toString(),
                        f.event.eventDigest(),
                        f.event.canonicalJsonUtf8())),
            f ->
                f.stored(
                    new Event(
                        f.stream(),
                        f.event.requestId(),
                        1L,
                        f.event.eventId(),
                        "sha256:" + "0".repeat(64),
                        f.event.canonicalJsonUtf8())),
            f ->
                f.stored(
                    new Event(
                        f.stream(),
                        "different-request",
                        1L,
                        f.event.eventId(),
                        f.event.eventDigest(),
                        f.event.canonicalJsonUtf8())),
            f ->
                f.stored(
                    new Event(
                        f.stream(),
                        f.event.requestId(),
                        1L,
                        f.event.eventId(),
                        f.event.eventDigest(),
                        (f.event.canonicalJson() + " ")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            f ->
                f.replaceEvent(
                    fields -> fields.put("membershipVersion", Map.of(TENANT.toString(), "3"))),
            f -> f.replaceEvent(fields -> fields.put("roles", List.of("player", "tenantAdmin"))),
            f -> f.replaceEvent(fields -> fields.put("issuanceFence", "2")),
            f ->
                f.install(
                    AccountSelectedGameplayAuthorityProjectionTest.member(
                        UUID.randomUUID(), TENANT, "1", "2", "1")),
            f ->
                f.stored(
                    new Event(
                        f.stream(),
                        f.event.requestId(),
                        1L,
                        f.event.eventId(),
                        f.event.eventDigest(),
                        f.event
                            .canonicalJson()
                            .replace(
                                "\"membershipVersion\":{\"" + TENANT + "\":\"2\"}",
                                "\"membershipVersion\":\"2\"")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))))) {
      Fixture f = new Fixture();
      change.accept(f);
      assertThatThrownBy(f::read).isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void currentCompositeScopeFenceAndSourceContradictionsDeny() {
    Fixture f = new Fixture();
    when(f.sources.readCurrentIssuerAccountSources(ISSUER, ACCOUNT)).thenReturn(null);
    assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
    Fixture wrongScope = new Fixture();
    wrongScope.composite(
        new CompositeSnapshot(
            wrongScope.authority.issuer(),
            wrongScope.authority.account(),
            List.of(new ScopeState(AuthorityScope.tenant(UUID.randomUUID()), 2L, 2L, null)),
            wrongScope.authority.memberships(),
            wrongScope.fence));
    assertThatThrownBy(wrongScope::read).isInstanceOf(IllegalStateException.class);
    Fixture wrongFence = new Fixture();
    wrongFence.composite(
        new CompositeSnapshot(
            wrongFence.authority.issuer(),
            wrongFence.authority.account(),
            wrongFence.authority.tenants(),
            List.of(
                new ScopeState(
                    AuthorityScope.membership(ACCOUNT, TENANT),
                    1L,
                    1L,
                    new IssuanceFence(ACCOUNT, 2L, 2L))),
            wrongFence.fence));
    assertThatThrownBy(wrongFence::read).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void noWritableRepeatableOwnerTransactionDeniesBeforeAnySourceRead() {
    Fixture f = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(
        f.sources, f.generations, f.memberships, f.pairs, f.roles, f.outbox, f.tenants);
    assertThatThrownBy(() -> f.reader.readCurrent(new UUID(0L, 0L), TENANT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static final class Fixture {
    final AccountAuthoritySourceEvidenceRepository sources =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    final AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    final AccountTenantMembershipRepository memberships =
        mock(AccountTenantMembershipRepository.class);
    final AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    final AccountTenantMembershipRoleSnapshotRepository roles =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    final AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    final AccountTenantAuthorityEventRepository tenants =
        mock(AccountTenantAuthorityEventRepository.class);
    final AccountMembershipRoleSourceReader reader =
        new AccountMembershipRoleSourceReader(
            sources, generations, memberships, pairs, roles, outbox, tenants);
    final IssuanceFence fence = new IssuanceFence(ACCOUNT, 1L, 1L);
    final net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec.Event tenant =
        AccountSelectedGameplayAuthorityProjectionTest.tenant(TENANT);
    final VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            tenant.sourceEvidence().operationId(),
            tenant.sourceEvidence().evidenceDigest());
    final AccountTenantMembership member = new AccountTenantMembership();
    CompositeSnapshot authority;
    MembershipEvent event;

    Fixture() {
      var issuer =
          new CurrentSourceEvidence(
              AuthorityScope.issuer(ISSUER),
              1L,
              1L,
              null,
              new SourceCheckpoint(
                  "account:auth-authority:v1:issuer/" + ISSUER,
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
              fence,
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
      when(sources.readCurrentIssuerAccountSources(ISSUER, ACCOUNT))
          .thenReturn(new IssuerAccountSourceSnapshot(issuer, accountSource, fence));
      composite(
          new CompositeSnapshot(
              new ScopeState(issuer.scope(), 1L, 1L, null),
              new ScopeState(accountSource.scope(), 1L, 1L, fence),
              List.of(new ScopeState(AuthorityScope.tenant(TENANT), 2L, 2L, null)),
              List.of(new ScopeState(AuthorityScope.membership(ACCOUNT, TENANT), 1L, 1L, fence)),
              fence));
      when(tenants.readCurrentByTenant(TENANT)).thenReturn(tenant);
      Account account = new Account();
      account.setId(4L);
      account.setAccountUuid(ACCOUNT);
      account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
      account.setAccountUuidSourceNumericId(4L);
      member.setAccount(account);
      member.setId(5L);
      member.setTenantUuid(TENANT);
      member.setTenantProvenanceKind(provenance.kind().name());
      member.setTenantSourceOperationId(provenance.sourceOperationId());
      member.setTenantProvenanceDigest(provenance.digest());
      member.setMembershipVersion(2L);
      member.setMembershipAuthorityGeneration(1L);
      member.setLifecycleState("ACTIVE");
      member.setGameplayAdmissionAllowed(true);
      member.setAuthorityProvenance("EXPLICIT_JOIN");
      when(memberships.findFreshMembershipForUpdate(ACCOUNT, TENANT))
          .thenReturn(Optional.of(member));
      when(roles.findForCanonicalUpdate(ACCOUNT, TENANT, provenance, 5L, 2L))
          .thenReturn(
              Optional.of(
                  new RoleSnapshot(
                      4L, null, 5L, 2L, List.of("player"), ACCOUNT, TENANT, provenance)));
      install(
          AccountSelectedGameplayAuthorityProjectionTest.member(ACCOUNT, TENANT, "1", "2", "1"));
    }

    void composite(CompositeSnapshot value) {
      authority = value;
      when(generations.readCompositeSnapshot(ISSUER, ACCOUNT, List.of(TENANT), List.of(TENANT)))
          .thenReturn(value);
    }

    void advanceIndependentOwners() {
      String issuerStream = "account:auth-authority:v1:issuer/" + ISSUER;
      String request = UUID.randomUUID().toString();
      var issuerEvent =
          net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec
              .sealIssuer(
                  new net.firedevops.firemud.common.account.authority
                      .AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                      request, request, issuerStream, "1", ISSUER, "2", "2", "SIGNER_COMPROMISE"));
      var issuer =
          new CurrentSourceEvidence(
              AuthorityScope.issuer(ISSUER),
              2L,
              2L,
              null,
              new SourceCheckpoint(
                  issuerStream,
                  1L,
                  Optional.of(issuerEvent.eventId()),
                  Optional.of(issuerEvent.eventDigest())),
              Optional.empty(),
              "ISSUER_SCOPE_INSERT",
              null,
              null,
              10L,
              null);
      var issuerProjection =
          new net.firedevops.firemud.accountservice.service.IssuerGenerationProjection(
              ISSUER,
              "2",
              "2",
              issuerStream,
              "1",
              Optional.of(issuerEvent.eventId()),
              Optional.of(issuerEvent.eventDigest()),
              Optional.of(issuerEvent.canonicalJson()));
      String accountStream = "account:auth-authority:v1:account/" + ACCOUNT;
      var accountEvent =
          net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec
              .seal(
                  Map.ofEntries(
                      Map.entry(
                          "schemaVersion",
                          net.firedevops.firemud.common.account.authority
                              .AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION),
                      Map.entry(
                          "eventType",
                          net.firedevops.firemud.common.account.authority
                              .AccountSecurityStateAuthorityEventV1Codec.EVENT_TYPE),
                      Map.entry(
                          "eventId",
                          net.firedevops.firemud.common.account.authority
                                  .AccountSecurityStateAuthorityEventV1Codec.EVENT_ID_PREFIX
                              + request),
                      Map.entry("requestId", request),
                      Map.entry("accountId", ACCOUNT.toString()),
                      Map.entry("sourceScope", "account/" + ACCOUNT),
                      Map.entry("outboxStreamKey", accountStream),
                      Map.entry("outboxSequence", "1"),
                      Map.entry("accountAuthorityGeneration", "2"),
                      Map.entry("sourceVersion", "2"),
                      Map.entry(
                          "accountSecurityCutoff",
                          Map.of(
                              "accountAuthorityGeneration",
                              "2",
                              "outboxStreamKey",
                              accountStream,
                              "outboxSequence",
                              "1")),
                      Map.entry("mutationKinds", List.of("EMAIL_LOGIN_ELIGIBILITY_CHANGED")),
                      Map.entry(
                          "accountState",
                          Map.of(
                              "emailVerified",
                              true,
                              "loginAuthModes",
                              List.of("EMAIL_OTP", "PASSWORD"),
                              "globalRoles",
                              List.of(),
                              "lifecycleState",
                              "ACTIVE"))));
      var currentFence = new IssuanceFence(ACCOUNT, 2L, 2L);
      var cutoff =
          new net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec
              .AccountSecurityCutoff("2", accountStream, "1");
      var accountSource =
          new CurrentSourceEvidence(
              AuthorityScope.account(ACCOUNT),
              2L,
              2L,
              currentFence,
              new SourceCheckpoint(
                  accountStream,
                  1L,
                  Optional.of(accountEvent.eventId()),
                  Optional.of(accountEvent.eventDigest())),
              Optional.of(cutoff),
              "ACCOUNT_REPOSITORY_INSERT",
              4L,
              "ACCOUNT_REPOSITORY_INSERT",
              11L,
              11L);
      var accountProjection =
          new net.firedevops.firemud.accountservice.service.AccountGenerationProjection(
              ACCOUNT.toString(),
              "2",
              "2",
              accountStream,
              "1",
              Optional.of(accountEvent.canonicalJson()));
      when(sources.readCurrentIssuerAccountSources(ISSUER, ACCOUNT))
          .thenReturn(
              new IssuerAccountSourceSnapshot(
                  issuer, accountSource, currentFence, accountProjection, issuerProjection));
      composite(
          new CompositeSnapshot(
              new ScopeState(issuer.scope(), 2L, 2L, null),
              new ScopeState(accountSource.scope(), 2L, 2L, currentFence),
              authority.tenants(),
              List.of(
                  new ScopeState(AuthorityScope.membership(ACCOUNT, TENANT), 1L, 1L, currentFence)),
              currentFence));
    }

    void install(MembershipEvent value) {
      event = value;
      when(pairs.readForUpdate(ACCOUNT, TENANT))
          .thenReturn(
              Optional.of(
                  new PairAuthority(
                      ACCOUNT,
                      TENANT,
                      provenance,
                      true,
                      2L,
                      1L,
                      1L,
                      event.eventId(),
                      event.eventDigest(),
                      event.callerBoundAuthorityInvalidated())));
      when(outbox.readCheckpoint(stream()))
          .thenReturn(
              Optional.of(new Checkpoint(stream(), 1L, event.eventId(), event.eventDigest())));
      stored(
          new Event(
              stream(),
              event.requestId(),
              1L,
              event.eventId(),
              event.eventDigest(),
              event.canonicalJsonUtf8()));
    }

    void stored(Event value) {
      when(outbox.findEvent(stream(), 1L)).thenReturn(Optional.of(value));
    }

    void replaceEvent(Consumer<Map<String, Object>> change) {
      try {
        Map<String, Object> fields =
            new LinkedHashMap<>(
                JsonMapper.builder()
                    .build()
                    .readValue(event.canonicalJson(), new TypeReference<Map<String, Object>>() {}));
        fields.remove("eventDigest");
        change.accept(fields);
        install(MembershipAuthorityEventV1Codec.seal(fields));
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }

    String stream() {
      return "account:auth-authority:v1:membership/" + ACCOUNT + "/" + TENANT;
    }

    net.firedevops.firemud.accountservice.dto.AccountMembershipRoleSourceSnapshot read() {
      return reader.readCurrent(ACCOUNT, TENANT);
    }
  }
}
