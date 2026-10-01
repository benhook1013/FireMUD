package unit.net.firedevops.firemud.accountservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountGameplayConnectSourceVerifier;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountCommittedConnectSourceReaderGuardTest {
  private static final String WORKLOAD_NAMESPACE = "account-unit";
  private static final AccountConnectTokenIssuanceIdentity IDENTITY =
      new AccountConnectTokenIssuanceIdentity(
          101L,
          UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
          "connect-scope-1",
          "request-1");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-04-06T12:00:00Z"), ZoneOffset.UTC);

  private AccountConnectTokenIssuanceRepository issuanceRepository;
  private AccountRepository accountRepository;
  private AccountTenantIdentityResolver tenantIdentityResolver;
  private FreshTenantIdentityAssociationRepository freshTenantIdentityRepository;
  private AccountJoinOperationRepository joinOperationRepository;
  private AccountEnvelopeCrypto envelopeCrypto;
  private AccountGameplayConnectSourceVerifier sourceVerifier;
  private AccountCommittedConnectSourceReader reader;
  private GrpcPeerIdentity expectedGameSessionPeer;

  @BeforeEach
  void setUp() throws Exception {
    issuanceRepository = mock(AccountConnectTokenIssuanceRepository.class);
    accountRepository = mock(AccountRepository.class);
    tenantIdentityResolver = mock(AccountTenantIdentityResolver.class);
    freshTenantIdentityRepository = mock(FreshTenantIdentityAssociationRepository.class);
    joinOperationRepository = mock(AccountJoinOperationRepository.class);
    envelopeCrypto = mock(AccountEnvelopeCrypto.class);
    sourceVerifier = mock(AccountGameplayConnectSourceVerifier.class);
    expectedGameSessionPeer = peer(WORKLOAD_NAMESPACE, "game-session-service");

    var gatewayKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    reader =
        new AccountCommittedConnectSourceReader(
            issuanceRepository,
            accountRepository,
            tenantIdentityResolver,
            freshTenantIdentityRepository,
            joinOperationRepository,
            envelopeCrypto,
            sourceVerifier,
            Map.of("gateway-test", gatewayKey),
            CLOCK,
            WORKLOAD_NAMESPACE);
  }

  @Test
  void missingPeerIsDeniedBeforeAnyRepositoryOrCredentialInteraction() {
    AdminAuthorizationException failure =
        assertThrows(
            AdminAuthorizationException.class,
            () -> withoutPeer(() -> reader.read(IDENTITY, "unparsed-gateway-context")));

    assertEquals(
        "Committed Account source readback requires the exact Game Session workload identity",
        failure.getMessage());
    verifyNoCollaboratorInteractions();
  }

  @Test
  void connectSourceIdentityRequiresNonNilCanonicalTenantUuid() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccountConnectTokenIssuanceIdentity(
                IDENTITY.accountId(), new UUID(0L, 0L), "scope", "request"));
  }

  @Test
  void wrongServicePeerIsDeniedBeforeAnyRepositoryOrCredentialInteraction() {
    assertPeerDeniedBeforeInteractions(peer(WORKLOAD_NAMESPACE, "world-management-service"));
  }

  @Test
  void wrongNamespaceGameSessionPeerIsDeniedBeforeAnyRepositoryOrCredentialInteraction() {
    assertPeerDeniedBeforeInteractions(peer("other-account-unit", "game-session-service"));
  }

  @Test
  void exactPeerWithoutOwnerTransactionIsDeniedBeforeAnyRepositoryOrCredentialInteraction() {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                withPeer(
                    expectedGameSessionPeer,
                    () -> reader.read(IDENTITY, "unparsed-gateway-context")));

    assertEquals(
        "Committed Account source readback requires an active owner transaction",
        failure.getMessage());
    verifyNoCollaboratorInteractions();
  }

  @Test
  void historicalReadRequiresExactPeerAndOwnerTransactionBeforeAnyInteraction() {
    AdminAuthorizationException missingPeer =
        assertThrows(
            AdminAuthorizationException.class,
            () -> withoutPeer(() -> reader.readHistorical(IDENTITY, "unparsed-gateway-context")));
    assertEquals(
        "Committed Account source readback requires the exact Game Session workload identity",
        missingPeer.getMessage());
    verifyNoCollaboratorInteractions();

    AdminAuthorizationException wrongPeer =
        assertThrows(
            AdminAuthorizationException.class,
            () ->
                withPeer(
                    peer(WORKLOAD_NAMESPACE, "world-management-service"),
                    () -> reader.readHistorical(IDENTITY, "unparsed-gateway-context")));
    assertEquals(
        "Committed Account source readback requires the exact Game Session workload identity",
        wrongPeer.getMessage());
    verifyNoCollaboratorInteractions();

    IllegalStateException missingTransaction =
        assertThrows(
            IllegalStateException.class,
            () ->
                withPeer(
                    expectedGameSessionPeer,
                    () -> reader.readHistorical(IDENTITY, "unparsed-gateway-context")));
    assertEquals(
        "Committed Account source readback requires an active owner transaction",
        missingTransaction.getMessage());
    verifyNoCollaboratorInteractions();
  }

  private void assertPeerDeniedBeforeInteractions(GrpcPeerIdentity peer) {
    AdminAuthorizationException failure =
        assertThrows(
            AdminAuthorizationException.class,
            () -> withPeer(peer, () -> reader.read(IDENTITY, "unparsed-gateway-context")));

    assertEquals(
        "Committed Account source readback requires the exact Game Session workload identity",
        failure.getMessage());
    verifyNoCollaboratorInteractions();
  }

  private void verifyNoCollaboratorInteractions() {
    verifyNoInteractions(
        issuanceRepository,
        accountRepository,
        tenantIdentityResolver,
        freshTenantIdentityRepository,
        joinOperationRepository,
        envelopeCrypto,
        sourceVerifier);
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static <T> T withPeer(GrpcPeerIdentity peer, Supplier<T> action) {
    Context scoped = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = scoped.attach();
    try {
      return action.get();
    } finally {
      scoped.detach(previous);
    }
  }

  private static <T> T withoutPeer(Supplier<T> action) {
    Context previous = Context.ROOT.attach();
    try {
      return action.get();
    } finally {
      Context.ROOT.detach(previous);
    }
  }
}
