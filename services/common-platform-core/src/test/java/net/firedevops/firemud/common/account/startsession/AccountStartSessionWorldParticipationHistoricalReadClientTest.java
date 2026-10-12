package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture.Values;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Configuration and call-boundary proof only; this is not socket or peer-authentication proof. */
class AccountStartSessionWorldParticipationHistoricalReadClientTest {
  @Test
  void requiresFileBackedMtlsAndConfiguredNamespace(@TempDir Path directory) throws Exception {
    var plaintext = fileBacked(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    new GrpcChannelFactory(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var classpath = fileBacked(directory);
    classpath.setPrivateKey("classpath:private.key");
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadClient(
                    new ServiceEndpointsProperties(),
                    classpath,
                    new GrpcChannelFactory(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    var client =
        new AccountStartSessionWorldParticipationHistoricalReadClient(
            new ServiceEndpointsProperties(),
            fileBacked(directory),
            new GrpcChannelFactory(),
            "world-runtime");
    try {
      assertThatThrownBy(
              () ->
                  new AccountStartSessionWorldParticipationHistoricalReadClient(
                      new ServiceEndpointsProperties(),
                      fileBacked(directory),
                      new GrpcChannelFactory(),
                      "not a namespace"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("namespace");
      assertThatThrownBy(() -> client.read(request("other-runtime")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
    } finally {
      client.close();
    }
  }

  @Test
  void requiresInitializedClientAndNoAmbientSqlOrSynchronization(@TempDir Path directory)
      throws Exception {
    var client =
        new AccountStartSessionWorldParticipationHistoricalReadClient(
            new ServiceEndpointsProperties(),
            fileBacked(directory),
            new GrpcChannelFactory(),
            "world-runtime");
    try {
      assertThatThrownBy(() -> client.read(request("world-runtime")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized");

      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.read(request("world-runtime")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient transaction");
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.read(request("world-runtime")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient transaction");
    } finally {
      TransactionSynchronizationManager.clear();
      client.close();
    }
  }

  private static AccountStartSessionWorldParticipationHistoricalReadRequest request(
      String namespace) {
    return new AccountStartSessionWorldParticipationHistoricalReadRequest(
        UUID.fromString("d1f41bfb-265a-4f3b-8b2a-124cba20ce43"),
        namespace,
        originalTuple(namespace).canonicalBytes());
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(String namespace) {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "historical-client-original-request",
            UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622"),
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(
                    UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531"), namespace),
                new StartSessionOperatorAction.Target(
                    91L, UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "synthetic historical client boundary test"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
        19L,
        StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
            preTuple,
            new Values(
                UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f"),
                "sha256:" + "a".repeat(64),
                "17",
                "CURRENT",
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"),
                1L,
                2L,
                3L,
                4L,
                5L,
                23L,
                UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc"),
                "2",
                "3")),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static CommonGrpcClientProperties fileBacked(Path directory) throws Exception {
    var properties = new CommonGrpcClientProperties();
    properties.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    properties.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "private key").toString());
    properties.setCaCert(
        Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return properties;
  }
}
