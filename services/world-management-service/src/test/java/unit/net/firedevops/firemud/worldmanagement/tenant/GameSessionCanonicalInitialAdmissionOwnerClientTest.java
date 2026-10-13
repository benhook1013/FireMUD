package net.firedevops.firemud.worldmanagement.tenant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local lifecycle and guard checks using file-path and channel doubles; not physical TLS proof. */
class GameSessionCanonicalInitialAdmissionOwnerClientTest {
  private static final String NAMESPACE = "world-test";

  @Test
  void requiresExplicitInitializationAndClosePermanentlyDisablesTheClient(@TempDir Path directory)
      throws Exception {
    var client = client(directory, NAMESPACE);
    HoldIdentity identity = identity(NAMESPACE);
    try {
      assertThrows(
          FinalizationDeniedException.class,
          () ->
              client.verifyAndHold(
                  identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED));

      client.init();
      client.init();
      assertThrows(
          FinalizationDeniedException.class,
          () ->
              client.verifyAndHold(
                  identity("other-world"),
                  GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED));

      client.close();
      assertThrows(
          FinalizationDeniedException.class,
          () ->
              client.verifyAndHold(
                  identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED));
      assertThrows(IllegalStateException.class, client::init);
    } finally {
      client.close();
    }
  }

  @Test
  void rejectsBothFormsOfAmbientTransactionBeforeAnyOwnerRead(@TempDir Path directory)
      throws Exception {
    var client = client(directory, NAMESPACE);
    HoldIdentity identity = identity(NAMESPACE);
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThrows(
            FinalizationDeniedException.class,
            () ->
                client.verifyAndHold(
                    identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED));
        assertThrows(
            FinalizationDeniedException.class, () -> client.verifyAndHoldObserved(identity));
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }

      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThrows(
            FinalizationDeniedException.class,
            () ->
                client.verifyAndHold(
                    identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED));
        assertThrows(
            FinalizationDeniedException.class, () -> client.verifyAndHoldObserved(identity));
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
    } finally {
      client.close();
    }
  }

  @Test
  void requiresReadableFileBackedMtlsConfiguration(@TempDir Path directory) throws Exception {
    CommonGrpcClientProperties plaintext = fileBackedProperties(directory);
    plaintext.setPlaintext(true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GameSessionCanonicalInitialAdmissionOwnerClient(
                new ServiceEndpointsProperties(), plaintext, channelFactory(), NAMESPACE));

    CommonGrpcClientProperties classpath = fileBackedProperties(directory);
    classpath.setCertChain("classpath:cert.pem");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GameSessionCanonicalInitialAdmissionOwnerClient(
                new ServiceEndpointsProperties(), classpath, channelFactory(), NAMESPACE));

    CommonGrpcClientProperties missing = fileBackedProperties(directory);
    missing.setPrivateKey(directory.resolve("missing-key.pem").toString());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GameSessionCanonicalInitialAdmissionOwnerClient(
                new ServiceEndpointsProperties(), missing, channelFactory(), NAMESPACE));
  }

  private static GameSessionCanonicalInitialAdmissionOwnerClient client(
      Path directory, String namespace) throws IOException {
    return new GameSessionCanonicalInitialAdmissionOwnerClient(
        new ServiceEndpointsProperties(),
        fileBackedProperties(directory),
        channelFactory(),
        namespace);
  }

  private static CommonGrpcClientProperties fileBackedProperties(Path directory)
      throws IOException {
    Path cert = Files.writeString(directory.resolve("client-cert.pem"), "test certificate path");
    Path key = Files.writeString(directory.resolve("client-key.pem"), "test private-key path");
    Path ca = Files.writeString(directory.resolve("ca-cert.pem"), "test CA path");
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain(cert.toString());
    properties.setPrivateKey(key.toString());
    properties.setCaCert(ca.toString());
    return properties;
  }

  private static GrpcChannelFactory channelFactory() {
    return new GrpcChannelFactory() {
      @Override
      public ManagedChannel buildChannel(
          String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
          throws SSLException {
        return mock(ManagedChannel.class);
      }
    };
  }

  private static HoldIdentity identity(String namespace) {
    Request request =
        new Request(
            namespace,
            uuid(1),
            "test-world",
            uuid(2),
            uuid(3),
            "SHARED",
            uuid(4),
            uuid(5),
            8L,
            "canonical-admission-42",
            "a".repeat(64),
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            17L,
            null);
    return new HoldIdentity(request, uuid(6), uuid(7));
  }

  private static UUID uuid(long suffix) {
    return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
  }
}
