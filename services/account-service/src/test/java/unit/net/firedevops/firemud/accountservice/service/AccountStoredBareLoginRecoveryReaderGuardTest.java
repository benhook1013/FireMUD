package unit.net.firedevops.firemud.accountservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import java.security.KeyPairGenerator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeIdentity;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader;
import net.firedevops.firemud.accountservice.service.AccountStoredBareLoginRecoveryReader;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountStoredBareLoginRecoveryReaderGuardTest {
  private static final String WORKLOAD_NAMESPACE = "account-unit";
  private static final AccountBareLoginExchangeIdentity IDENTITY =
      new AccountBareLoginExchangeIdentity(
          UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
          101L,
          202L,
          "connect-scope-1",
          "exchange-request-1");
  private static final byte[] REQUEST_DIGEST = new byte[32];

  private AccountBareLoginExchangeRepository exchangeRepository;
  private AccountJoinOperationRepository joinOperationRepository;
  private AccountEnvelopeCrypto envelopeCrypto;
  private AccountCommittedConnectSourceReader committedSourceReader;
  private AccountStoredBareLoginRecoveryReader reader;
  private GrpcPeerIdentity expectedGameSessionPeer;

  @BeforeEach
  void setUp() throws Exception {
    exchangeRepository = mock(AccountBareLoginExchangeRepository.class);
    joinOperationRepository = mock(AccountJoinOperationRepository.class);
    envelopeCrypto = mock(AccountEnvelopeCrypto.class);
    committedSourceReader = mock(AccountCommittedConnectSourceReader.class);
    expectedGameSessionPeer = peer(WORKLOAD_NAMESPACE, "game-session-service");
    var gatewayKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    reader =
        new AccountStoredBareLoginRecoveryReader(
            exchangeRepository,
            joinOperationRepository,
            envelopeCrypto,
            committedSourceReader,
            Map.of("gateway-test", gatewayKey),
            WORKLOAD_NAMESPACE);
  }

  @Test
  void missingPeerIsDeniedBeforeAnyRepositoryOrCredentialInteraction() {
    AdminAuthorizationException failure =
        assertThrows(
            AdminAuthorizationException.class,
            () -> withoutPeer(() -> reader.readHistorical(IDENTITY, REQUEST_DIGEST.clone())));

    assertEquals(
        "Stored bare LOGIN history readback requires the exact Game Session workload identity",
        failure.getMessage());
    verifyNoCollaboratorInteractions();
  }

  @Test
  void wrongServiceOrNamespacePeerIsDeniedBeforeAnyInteraction() {
    assertPeerDenied(peer(WORKLOAD_NAMESPACE, "account-service"));
    assertPeerDenied(peer("other-account-unit", "game-session-service"));
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
                    () -> reader.readHistorical(IDENTITY, REQUEST_DIGEST.clone())));

    assertEquals(
        "Stored bare LOGIN history readback requires an active owner transaction",
        failure.getMessage());
    verifyNoCollaboratorInteractions();
  }

  @Test
  void correlationRequiresExactPeerAndOwnerTransactionBeforeAnyCollaboratorInteraction() {
    AccountConnectTokenIssuanceIdentity freshIdentity =
        new AccountConnectTokenIssuanceIdentity(
            IDENTITY.accountId(), IDENTITY.tenantId(), "fresh-connect-scope", "fresh-request");

    AdminAuthorizationException peerFailure =
        assertThrows(
            AdminAuthorizationException.class,
            () ->
                withPeer(
                    peer(WORKLOAD_NAMESPACE, "account-service"),
                    () ->
                        reader.readCorrelation(
                            IDENTITY, REQUEST_DIGEST.clone(), freshIdentity, "signed-context")));
    assertEquals(
        "Stored bare LOGIN history readback requires the exact Game Session workload identity",
        peerFailure.getMessage());
    verifyNoCollaboratorInteractions();

    IllegalStateException transactionFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                withPeer(
                    expectedGameSessionPeer,
                    () ->
                        reader.readCorrelation(
                            IDENTITY, REQUEST_DIGEST.clone(), freshIdentity, "signed-context")));
    assertEquals(
        "Stored bare LOGIN history readback requires an active owner transaction",
        transactionFailure.getMessage());
    verifyNoCollaboratorInteractions();
  }

  private void assertPeerDenied(GrpcPeerIdentity peer) {
    AdminAuthorizationException failure =
        assertThrows(
            AdminAuthorizationException.class,
            () -> withPeer(peer, () -> reader.readHistorical(IDENTITY, REQUEST_DIGEST.clone())));

    assertEquals(
        "Stored bare LOGIN history readback requires the exact Game Session workload identity",
        failure.getMessage());
    verifyNoCollaboratorInteractions();
  }

  private void verifyNoCollaboratorInteractions() {
    verifyNoInteractions(
        exchangeRepository, joinOperationRepository, envelopeCrypto, committedSourceReader);
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
