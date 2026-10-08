package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CanonicalGameplayMigrationDriverProtocolTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FENCE_DIGEST = "sha256:" + "1".repeat(64);
  private static final CanonicalGameplayLegacyMigrationStorageIdentity STORAGE_IDENTITY =
      new CanonicalGameplayLegacyMigrationStorageIdentity(
          "kind-cluster-incarnation",
          "namespace-incarnation",
          "postgres-pod-uid",
          "containerd://postgres-container-id",
          "node-incarnation",
          "12345678901234567890",
          4_294_967_295L,
          "namespace-incarnation/postgres-pod-uid/data",
          "a".repeat(40),
          "namespace-incarnation/redis-pod-uid/data");

  @Test
  void initializationAcceptsExactClosedShapeAndUnsignedDatabaseOid() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (CanonicalGameplayMigrationDriverProtocol protocol =
        new CanonicalGameplayMigrationDriverProtocol(input(initializationJson()), output)) {
      var initialization = protocol.readInitialization();

      assertThat(initialization.cohortId())
          .isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000001"));
      assertThat(initialization.legacyWriterFence())
          .isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000002"));
      assertThat(initialization.storageIdentity()).isEqualTo(STORAGE_IDENTITY);
      assertThat(initialization.storageIdentity().postgresDatabaseOid()).isEqualTo(4_294_967_295L);
      assertThat(initialization.fenceDigest()).isEqualTo(FENCE_DIGEST);
    }
  }

  @Test
  void initializationRejectsUnknownFieldsAndDuplicateJsonProperties() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    String withUnknownField =
        initializationJson().replace("\"redisPort\":6380", "\"redisPort\":6380,\"trusted\":true");
    try (CanonicalGameplayMigrationDriverProtocol protocol =
        new CanonicalGameplayMigrationDriverProtocol(input(withUnknownField), output)) {
      assertThatThrownBy(protocol::readInitialization)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("missing or unknown fields");
    }

    String withDuplicateField =
        initializationJson()
            .replace(
                "\"schema\":\"firemud.gs.migration-driver/v1\",",
                "\"schema\":\"firemud.gs.migration-driver/v1\",\"schema\":\"firemud.gs.migration-driver/v1\",");
    try (CanonicalGameplayMigrationDriverProtocol protocol =
        new CanonicalGameplayMigrationDriverProtocol(
            input(withDuplicateField), new ByteArrayOutputStream())) {
      assertThatThrownBy(protocol::readInitialization)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("malformed");
    }
  }

  @Test
  void initializationRejectsAnOversizedUtf8Frame() {
    byte[] oversized = new byte[CanonicalGameplayMigrationDriverProtocol.MAX_FRAME_BYTES + 1];
    java.util.Arrays.fill(oversized, (byte) ' ');
    try (CanonicalGameplayMigrationDriverProtocol protocol =
        new CanonicalGameplayMigrationDriverProtocol(
            new ByteArrayInputStream(oversized), new ByteArrayOutputStream())) {
      assertThatThrownBy(protocol::readInitialization)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("malformed");
    }
  }

  @Test
  @Timeout(5)
  void reobservationEmitsFreshNonceAndAcceptsOnlyTheExactBoundEcho() throws Exception {
    PipedInputStream driverInput = new PipedInputStream(4096);
    PipedOutputStream factoryOutput = new PipedOutputStream(driverInput);
    PipedInputStream factoryInput = new PipedInputStream(4096);
    PipedOutputStream driverOutput = new PipedOutputStream(factoryInput);
    factoryOutput.write(initializationJson().getBytes(StandardCharsets.UTF_8));
    factoryOutput.write('\n');
    factoryOutput.flush();
    AtomicReference<JsonNode> challenge = new AtomicReference<>();
    Thread responder =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(factoryInput, StandardCharsets.UTF_8))) {
                JsonNode request = MAPPER.readTree(reader.readLine());
                challenge.set(request);
                ObjectNode response = MAPPER.createObjectNode();
                response.put("schema", CanonicalGameplayMigrationDriverProtocol.SCHEMA);
                response.put("kind", "observed");
                response.put("sequence", request.path("sequence").longValue());
                response.put("nonce", request.path("nonce").textValue());
                response.put("cohortId", request.path("cohortId").textValue());
                response.put("legacyWriterFence", request.path("legacyWriterFence").textValue());
                response.set("storageIdentity", storageIdentityJson());
                response.put("fenceDigest", request.path("fenceDigest").textValue());
                factoryOutput.write(MAPPER.writeValueAsBytes(response));
                factoryOutput.write('\n');
                factoryOutput.flush();
              } catch (Exception failure) {
                throw new AssertionError(
                    "test factory could not answer the driver challenge", failure);
              }
            },
            "migration-driver-protocol-test-factory");
    try (CanonicalGameplayMigrationDriverProtocol protocol =
        new CanonicalGameplayMigrationDriverProtocol(driverInput, driverOutput)) {
      // The responder reads the driver's re-observation frame after initialization is consumed.
      var initialization = protocol.readInitialization();
      responder.start();
      protocol.requireFreshObservation(initialization, localObservation());
      responder.join(1000);

      assertThat(responder.isAlive()).isFalse();
      assertThat(challenge.get()).isNotNull();
      assertThat(fields(challenge.get()))
          .containsExactlyInAnyOrder(
              "schema",
              "kind",
              "sequence",
              "nonce",
              "cohortId",
              "legacyWriterFence",
              "storageIdentityDigest",
              "fenceDigest",
              "postgres",
              "redis");
      assertThat(challenge.get().path("sequence").longValue()).isEqualTo(1L);
      assertThat(challenge.get().path("nonce").textValue())
          .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
      assertThat(challenge.get().path("storageIdentityDigest").textValue())
          .isEqualTo(STORAGE_IDENTITY.digest());
      assertThat(fields(challenge.get().path("postgres")))
          .containsExactlyInAnyOrder(
              "systemIdentifier",
              "databaseOid",
              "serverAddress",
              "serverPort",
              "listenAddresses",
              "hbaFile",
              "unixSocketDirectories",
              "rolesDigest",
              "hbaRulesDigest");
      assertThat(fields(challenge.get().path("redis")))
          .containsExactlyInAnyOrder("runId", "tlsEnabled", "tlsPort", "trustCaSha256");
    } finally {
      responder.interrupt();
      factoryOutput.close();
      driverOutput.close();
    }
  }

  @Test
  @Timeout(5)
  void reobservationRejectsWrongNonce() throws Exception {
    assertResponseRejected(
        response -> response.put("nonce", "00000000-0000-4000-8000-000000000099"),
        "nonce did not match");
  }

  @Test
  @Timeout(5)
  void reobservationRejectsMissingFields() throws Exception {
    assertResponseRejected(response -> response.remove("fenceDigest"), "missing or unknown fields");
  }

  @Test
  @Timeout(5)
  void reobservationRejectsChangedStorageIdentity() throws Exception {
    assertResponseRejected(
        response ->
            ((ObjectNode) response.get("storageIdentity")).put("producerPodUid", "other-pod"),
        "different physical storage identity");
  }

  @Test
  @Timeout(5)
  void reobservationRejectsReplayOfThePriorBoundReply() throws Exception {
    PipedInputStream driverInput = new PipedInputStream(4096);
    PipedOutputStream factoryOutput = new PipedOutputStream(driverInput);
    PipedInputStream factoryInput = new PipedInputStream(4096);
    PipedOutputStream driverOutput = new PipedOutputStream(factoryInput);
    factoryOutput.write(initializationJson().getBytes(StandardCharsets.UTF_8));
    factoryOutput.write('\n');
    factoryOutput.flush();
    AtomicReference<Throwable> responderFailure = new AtomicReference<>();
    Thread responder =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(factoryInput, StandardCharsets.UTF_8))) {
                JsonNode firstRequest = MAPPER.readTree(reader.readLine());
                byte[] firstReply = MAPPER.writeValueAsBytes(boundReply(firstRequest));
                factoryOutput.write(firstReply);
                factoryOutput.write('\n');
                factoryOutput.flush();
                reader.readLine();
                factoryOutput.write(firstReply);
                factoryOutput.write('\n');
                factoryOutput.flush();
              } catch (Throwable failure) {
                responderFailure.set(failure);
              }
            },
            "migration-driver-replay-test-factory");
    try (CanonicalGameplayMigrationDriverProtocol protocol =
        new CanonicalGameplayMigrationDriverProtocol(driverInput, driverOutput)) {
      var initialization = protocol.readInitialization();
      responder.start();
      protocol.requireFreshObservation(initialization, localObservation());
      assertThatThrownBy(() -> protocol.requireFreshObservation(initialization, localObservation()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("sequence did not match");
      responder.join(1000);
      assertThat(responder.isAlive()).isFalse();
      assertThat(responderFailure.get()).isNull();
    } finally {
      responder.interrupt();
      factoryOutput.close();
      driverOutput.close();
    }
  }

  @Test
  @Timeout(5)
  void reobservationRejectsEofBeforeTheBoundReply() {
    try (CanonicalGameplayMigrationDriverProtocol protocol =
        new CanonicalGameplayMigrationDriverProtocol(
            input(initializationJson()), new ByteArrayOutputStream())) {
      var initialization = protocol.readInitialization();
      assertThatThrownBy(() -> protocol.requireFreshObservation(initialization, localObservation()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("EOF");
    }
  }

  @Test
  @Timeout(5)
  void protocolOutputWriteHasAnIndependentBoundedDeadline() throws InterruptedException {
    BlockingOutputStream output = new BlockingOutputStream();
    try (CanonicalGameplayMigrationDriverProtocol protocol =
        new CanonicalGameplayMigrationDriverProtocol(
            input(initializationJson()), output, java.time.Duration.ofMillis(500))) {
      AtomicReference<Throwable> driverFailure = new AtomicReference<>();
      CountDownLatch completed = new CountDownLatch(1);
      Thread driver =
          new Thread(
              () -> {
                try {
                  var initialization = protocol.readInitialization();
                  protocol.requireFreshObservation(initialization, localObservation());
                } catch (Throwable failure) {
                  driverFailure.set(failure);
                } finally {
                  completed.countDown();
                }
              },
              "migration-driver-blocked-output-test");
      driver.setDaemon(true);
      driver.start();

      assertThat(output.writeStarted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(driverFailure.get())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("output timed out");
    }
  }

  private static void assertResponseRejected(Consumer<ObjectNode> mutation, String expectedMessage)
      throws Exception {
    PipedInputStream driverInput = new PipedInputStream(4096);
    PipedOutputStream factoryOutput = new PipedOutputStream(driverInput);
    PipedInputStream factoryInput = new PipedInputStream(4096);
    PipedOutputStream driverOutput = new PipedOutputStream(factoryInput);
    factoryOutput.write(initializationJson().getBytes(StandardCharsets.UTF_8));
    factoryOutput.write('\n');
    factoryOutput.flush();
    AtomicReference<Throwable> responderFailure = new AtomicReference<>();
    Thread responder =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(factoryInput, StandardCharsets.UTF_8))) {
                JsonNode request = MAPPER.readTree(reader.readLine());
                ObjectNode response = boundReply(request);
                mutation.accept(response);
                factoryOutput.write(MAPPER.writeValueAsBytes(response));
                factoryOutput.write('\n');
                factoryOutput.flush();
              } catch (Throwable failure) {
                responderFailure.set(failure);
              }
            },
            "migration-driver-invalid-reply-test-factory");
    try (CanonicalGameplayMigrationDriverProtocol protocol =
        new CanonicalGameplayMigrationDriverProtocol(driverInput, driverOutput)) {
      var initialization = protocol.readInitialization();
      responder.start();
      assertThatThrownBy(() -> protocol.requireFreshObservation(initialization, localObservation()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(expectedMessage);
      responder.join(1000);
      assertThat(responder.isAlive()).isFalse();
      assertThat(responderFailure.get()).isNull();
    } finally {
      responder.interrupt();
      factoryOutput.close();
      driverOutput.close();
    }
  }

  private static ObjectNode boundReply(JsonNode request) {
    ObjectNode response = MAPPER.createObjectNode();
    response.put("schema", CanonicalGameplayMigrationDriverProtocol.SCHEMA);
    response.put("kind", "observed");
    response.put("sequence", request.path("sequence").longValue());
    response.put("nonce", request.path("nonce").textValue());
    response.put("cohortId", request.path("cohortId").textValue());
    response.put("legacyWriterFence", request.path("legacyWriterFence").textValue());
    response.set("storageIdentity", storageIdentityJson());
    response.put("fenceDigest", request.path("fenceDigest").textValue());
    return response;
  }

  private static final class BlockingOutputStream extends java.io.OutputStream {
    private final CountDownLatch writeStarted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void write(int value) throws java.io.IOException {
      block();
    }

    @Override
    public void write(byte[] value, int offset, int length) throws java.io.IOException {
      block();
    }

    private void block() throws java.io.IOException {
      writeStarted.countDown();
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new java.io.IOException("interrupted", interrupted);
      }
    }
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> fields = new HashSet<>();
    node.fieldNames().forEachRemaining(fields::add);
    return fields;
  }

  private static CanonicalGameplayMigrationDriverProtocol.LocalObservation localObservation() {
    Map<String, Object> postgres = new LinkedHashMap<>();
    postgres.put("systemIdentifier", STORAGE_IDENTITY.postgresSystemIdentifier());
    postgres.put("databaseOid", STORAGE_IDENTITY.postgresDatabaseOid());
    postgres.put("serverAddress", null);
    postgres.put("serverPort", null);
    postgres.put("listenAddresses", "");
    postgres.put("hbaFile", "/var/lib/postgresql/data/pg_hba.conf");
    postgres.put("unixSocketDirectories", "/var/run/postgresql");
    postgres.put("rolesDigest", "sha256:" + "2".repeat(64));
    postgres.put("hbaRulesDigest", "sha256:" + "3".repeat(64));
    Map<String, Object> redis = new LinkedHashMap<>();
    redis.put("runId", STORAGE_IDENTITY.redisRunId());
    redis.put("tlsEnabled", true);
    redis.put("tlsPort", 6380);
    redis.put("trustCaSha256", "sha256:" + "4".repeat(64));
    return new CanonicalGameplayMigrationDriverProtocol.LocalObservation(postgres, redis);
  }

  private static ByteArrayInputStream input(String frame) {
    return new ByteArrayInputStream((frame + "\n").getBytes(StandardCharsets.UTF_8));
  }

  private static String initializationJson() {
    try {
      ObjectNode root = MAPPER.createObjectNode();
      root.put("schema", CanonicalGameplayMigrationDriverProtocol.SCHEMA);
      root.put("kind", "initialize");
      root.put("operation", "migrate-empty-cohort");
      root.put("cohortId", "00000000-0000-4000-8000-000000000001");
      root.put("legacyWriterFence", "00000000-0000-4000-8000-000000000002");
      root.set("storageIdentity", storageIdentityJson());
      root.put("fenceDigest", FENCE_DIGEST);
      root.put("redisHost", "redis-0.redis.game-session.svc.cluster.local");
      root.put("redisPort", 6380);
      return MAPPER.writeValueAsString(root);
    } catch (Exception failure) {
      throw new AssertionError("test initialization could not be serialized", failure);
    }
  }

  private static ObjectNode storageIdentityJson() {
    ObjectNode identity = MAPPER.createObjectNode();
    identity.put("kubernetesClusterUid", STORAGE_IDENTITY.kubernetesClusterUid());
    identity.put("kubernetesNamespaceUid", STORAGE_IDENTITY.kubernetesNamespaceUid());
    identity.put("producerPodUid", STORAGE_IDENTITY.producerPodUid());
    identity.put("producerContainerId", STORAGE_IDENTITY.producerContainerId());
    identity.put("producerNodeUid", STORAGE_IDENTITY.producerNodeUid());
    identity.put("postgresSystemIdentifier", STORAGE_IDENTITY.postgresSystemIdentifier());
    identity.put("postgresDatabaseOid", STORAGE_IDENTITY.postgresDatabaseOid());
    identity.put("postgresStorageUid", STORAGE_IDENTITY.postgresStorageUid());
    identity.put("redisRunId", STORAGE_IDENTITY.redisRunId());
    identity.put("redisStorageUid", STORAGE_IDENTITY.redisStorageUid());
    return identity;
  }
}
