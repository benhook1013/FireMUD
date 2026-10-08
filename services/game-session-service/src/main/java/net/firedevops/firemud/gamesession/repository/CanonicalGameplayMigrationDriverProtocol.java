package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;

/** Bounded, closed-shape stdin/stdout protocol used only by the trusted finite migration runner. */
final class CanonicalGameplayMigrationDriverProtocol implements AutoCloseable {
  static final String SCHEMA = "firemud.gs.migration-driver/v1";
  static final int MAX_FRAME_BYTES = 32 * 1024;
  static final int MAX_REOBSERVATIONS = 128;
  static final Duration INITIAL_FRAME_TIMEOUT = Duration.ofSeconds(30);
  static final Duration OUTPUT_FRAME_TIMEOUT = Duration.ofSeconds(30);
  static final Duration OBSERVATION_REPLY_TIMEOUT = Duration.ofSeconds(600);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern HOST =
      Pattern.compile(
          "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*");
  private static final Set<String> IDENTITY_FIELDS =
      Set.of(
          "kubernetesClusterUid",
          "kubernetesNamespaceUid",
          "producerPodUid",
          "producerContainerId",
          "producerNodeUid",
          "postgresSystemIdentifier",
          "postgresDatabaseOid",
          "postgresStorageUid",
          "redisRunId",
          "redisStorageUid");
  private static final Set<String> INITIAL_FIELDS =
      Set.of(
          "schema",
          "kind",
          "operation",
          "cohortId",
          "legacyWriterFence",
          "storageIdentity",
          "fenceDigest",
          "redisHost",
          "redisPort");
  private static final Set<String> POSTGRES_OBSERVATION_FIELDS =
      Set.of(
          "systemIdentifier",
          "databaseOid",
          "serverAddress",
          "serverPort",
          "listenAddresses",
          "hbaFile",
          "unixSocketDirectories",
          "rolesDigest",
          "hbaRulesDigest");
  private static final Set<String> REDIS_OBSERVATION_FIELDS =
      Set.of("runId", "tlsEnabled", "tlsPort", "trustCaSha256");
  private static final Set<String> REPLY_FIELDS =
      Set.of(
          "schema",
          "kind",
          "sequence",
          "nonce",
          "cohortId",
          "legacyWriterFence",
          "storageIdentity",
          "fenceDigest");

  private final InputStream input;
  private final OutputStream output;
  private final ObjectMapper mapper;
  private final ExecutorService reader;
  private final ExecutorService writer;
  private final Duration outputFrameTimeout;
  private final Set<UUID> issuedNonces = new HashSet<>();
  private long sequence;
  private boolean initialized;

  CanonicalGameplayMigrationDriverProtocol(InputStream input, OutputStream output) {
    this(input, output, OUTPUT_FRAME_TIMEOUT);
  }

  CanonicalGameplayMigrationDriverProtocol(
      InputStream input, OutputStream output, Duration outputFrameTimeout) {
    this.input = Objects.requireNonNull(input, "input");
    this.output = Objects.requireNonNull(output, "output");
    this.outputFrameTimeout = Objects.requireNonNull(outputFrameTimeout, "outputFrameTimeout");
    if (outputFrameTimeout.isZero() || outputFrameTimeout.isNegative()) {
      throw new IllegalArgumentException("output frame timeout must be positive");
    }
    JsonFactory factory =
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    mapper =
        new ObjectMapper(factory)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    reader =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "gs-migration-driver-stdin");
              thread.setDaemon(true);
              return thread;
            });
    writer =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "gs-migration-driver-stdout");
              thread.setDaemon(true);
              return thread;
            });
  }

  Initialization readInitialization() {
    if (initialized) {
      throw reject("migration driver protocol was already initialized");
    }
    JsonNode root = requireObject(readFrame(INITIAL_FRAME_TIMEOUT), "initialize request");
    requireExactFields(root, INITIAL_FIELDS, "initialize request");
    requireTextEquals(root, "schema", SCHEMA);
    requireTextEquals(root, "kind", "initialize");
    requireTextEquals(root, "operation", "migrate-empty-cohort");
    UUID cohortId = parseUuid(root, "cohortId");
    UUID legacyWriterFence = parseUuid(root, "legacyWriterFence");
    if (!isUuidV4(cohortId) || !isUuidV4(legacyWriterFence)) {
      throw reject(
          "migration initialization requires persisted UUIDv4 cohort and fence identities");
    }
    CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity =
        parseStorageIdentity(root.get("storageIdentity"));
    String fenceDigest = requireSha256(root, "fenceDigest");
    String redisHost = requireText(root, "redisHost");
    if (!HOST.matcher(redisHost).matches()) {
      throw reject("migration Redis host is not canonical DNS text");
    }
    JsonNode redisPort = root.get("redisPort");
    if (redisPort == null
        || !redisPort.isIntegralNumber()
        || !redisPort.canConvertToInt()
        || redisPort.intValue() != 6380) {
      throw reject("migration Redis port must be exactly 6380");
    }
    initialized = true;
    return new Initialization(
        cohortId, legacyWriterFence, storageIdentity, fenceDigest, redisHost, 6380);
  }

  void requireFreshObservation(Initialization initialization, LocalObservation observation) {
    Objects.requireNonNull(initialization, "initialization");
    Objects.requireNonNull(observation, "observation");
    if (!initialized) {
      throw reject("migration driver protocol is not initialized");
    }
    if (sequence >= MAX_REOBSERVATIONS) {
      throw reject("migration driver exceeded its live re-observation bound");
    }
    sequence++;
    UUID nonce = UUID.randomUUID();
    if (nonce.equals(new UUID(0L, 0L)) || !issuedNonces.add(nonce)) {
      throw reject("migration driver could not issue a fresh protocol nonce");
    }

    Map<String, Object> request = new LinkedHashMap<>();
    request.put("schema", SCHEMA);
    request.put("kind", "reobserve");
    request.put("sequence", sequence);
    request.put("nonce", nonce.toString());
    request.put("cohortId", initialization.cohortId().toString());
    request.put("legacyWriterFence", initialization.legacyWriterFence().toString());
    request.put("storageIdentityDigest", initialization.storageIdentity().digest());
    request.put("fenceDigest", initialization.fenceDigest());
    request.put(
        "postgres", validatedObservation(observation.postgres(), POSTGRES_OBSERVATION_FIELDS));
    request.put("redis", validatedObservation(observation.redis(), REDIS_OBSERVATION_FIELDS));
    writeFrame(request);

    JsonNode reply = requireObject(readFrame(OBSERVATION_REPLY_TIMEOUT), "fresh observation reply");
    requireExactFields(reply, REPLY_FIELDS, "fresh observation reply");
    requireTextEquals(reply, "schema", SCHEMA);
    requireTextEquals(reply, "kind", "observed");
    requireLongEquals(reply, "sequence", sequence);
    requireTextEquals(reply, "nonce", nonce.toString());
    requireTextEquals(reply, "cohortId", initialization.cohortId().toString());
    requireTextEquals(reply, "legacyWriterFence", initialization.legacyWriterFence().toString());
    requireTextEquals(reply, "fenceDigest", initialization.fenceDigest());
    CanonicalGameplayLegacyMigrationStorageIdentity observedIdentity =
        parseStorageIdentity(reply.get("storageIdentity"));
    if (!initialization.storageIdentity().equals(observedIdentity)) {
      throw reject("trusted runner observed a different physical storage identity");
    }
  }

  private Map<String, Object> validatedObservation(
      Map<String, Object> values, Set<String> expectedFields) {
    Objects.requireNonNull(values, "local observation");
    if (!values.keySet().equals(expectedFields)) {
      throw reject("local observation fields do not match the closed protocol shape");
    }
    return values;
  }

  private JsonNode readFrame(Duration timeout) {
    Future<byte[]> frame = reader.submit(this::readFrameBytes);
    try {
      byte[] bytes = frame.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (bytes == null || bytes.length == 0) {
        throw reject("migration driver protocol reached EOF or an empty frame");
      }
      String json = decodeUtf8(bytes);
      JsonNode node = mapper.readTree(json);
      if (node == null) {
        throw reject("migration driver protocol frame is empty");
      }
      return node;
    } catch (TimeoutException timedOut) {
      frame.cancel(true);
      throw reject("migration driver protocol frame timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      frame.cancel(true);
      throw reject("migration driver protocol was interrupted");
    } catch (ExecutionException | IOException malformed) {
      throw reject("migration driver protocol frame is malformed");
    }
  }

  private byte[] readFrameBytes() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    while (true) {
      int next = input.read();
      if (next < 0) {
        return null;
      }
      if (next == '\n') {
        byte[] frame = bytes.toByteArray();
        if (frame.length > 0 && frame[frame.length - 1] == '\r') {
          return java.util.Arrays.copyOf(frame, frame.length - 1);
        }
        return frame;
      }
      if (bytes.size() >= MAX_FRAME_BYTES) {
        throw new IOException("frame exceeds bound");
      }
      bytes.write(next);
    }
  }

  private String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw reject("migration driver protocol frame is not canonical UTF-8");
    }
  }

  private void writeFrame(Map<String, Object> value) {
    byte[] frame;
    try {
      byte[] encoded = mapper.writeValueAsBytes(value);
      if (encoded.length == 0 || encoded.length > MAX_FRAME_BYTES) {
        throw reject("migration driver protocol output exceeded its frame bound");
      }
      frame = java.util.Arrays.copyOf(encoded, encoded.length + 1);
      frame[frame.length - 1] = '\n';
    } catch (IOException serializationFailure) {
      throw reject("migration driver protocol output could not be encoded");
    }
    Future<?> writing =
        writer.submit(
            () -> {
              try {
                output.write(frame);
                output.flush();
              } catch (IOException failed) {
                throw new java.io.UncheckedIOException(failed);
              }
            });
    try {
      writing.get(outputFrameTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException timedOut) {
      writing.cancel(true);
      writer.shutdownNow();
      throw reject("migration driver protocol output timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      writing.cancel(true);
      writer.shutdownNow();
      throw reject("migration driver protocol output was interrupted");
    } catch (ExecutionException failed) {
      throw reject("migration driver protocol output failed");
    }
  }

  private static JsonNode requireObject(JsonNode node, String label) {
    if (node == null || !node.isObject()) {
      throw reject(label + " must be one JSON object");
    }
    return node;
  }

  private static void requireExactFields(JsonNode node, Set<String> expected, String label) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw reject(label + " has missing or unknown fields");
    }
  }

  private static void requireTextEquals(JsonNode node, String field, String expected) {
    if (!node.has(field)
        || !node.get(field).isTextual()
        || !expected.equals(node.get(field).textValue())) {
      throw reject("migration driver protocol field " + field + " did not match");
    }
  }

  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw reject("migration driver protocol field " + field + " must be text");
    }
    String text = value.textValue();
    if (text.isBlank()
        || !text.equals(text.strip())
        || text.chars().anyMatch(Character::isISOControl)) {
      throw reject("migration driver protocol field " + field + " is not canonical text");
    }
    return text;
  }

  private static UUID parseUuid(JsonNode node, String field) {
    String text = requireText(node, field);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) {
        throw reject("migration driver protocol field " + field + " is not a canonical UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw reject("migration driver protocol field " + field + " is not a canonical UUID");
    }
  }

  private static boolean isUuidV4(UUID value) {
    return value.version() == 4 && value.variant() == 2 && !value.equals(new UUID(0L, 0L));
  }

  private static String requireSha256(JsonNode node, String field) {
    String value = requireText(node, field);
    if (!SHA256.matcher(value).matches()) {
      throw reject("migration driver protocol field " + field + " is not a SHA-256 digest");
    }
    return value;
  }

  private static void requireLongEquals(JsonNode node, String field, long expected) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() != expected) {
      throw reject("migration driver protocol field " + field + " did not match");
    }
  }

  private static CanonicalGameplayLegacyMigrationStorageIdentity parseStorageIdentity(
      JsonNode node) {
    requireObject(node, "storageIdentity");
    requireExactFields(node, IDENTITY_FIELDS, "storageIdentity");
    String clusterUid = requireText(node, "kubernetesClusterUid");
    String namespaceUid = requireText(node, "kubernetesNamespaceUid");
    String podUid = requireText(node, "producerPodUid");
    String containerId = requireText(node, "producerContainerId");
    String nodeUid = requireText(node, "producerNodeUid");
    String postgresSystemIdentifier = requireText(node, "postgresSystemIdentifier");
    JsonNode databaseOid = node.get("postgresDatabaseOid");
    if (databaseOid == null
        || !databaseOid.isIntegralNumber()
        || !databaseOid.canConvertToLong()
        || databaseOid.longValue() <= 0L
        || databaseOid.longValue() > 0xffff_ffffL) {
      throw reject("storageIdentity.postgresDatabaseOid must be an unsigned 32-bit JSON integer");
    }
    String postgresStorageUid = requireText(node, "postgresStorageUid");
    String redisRunId = requireText(node, "redisRunId");
    String redisStorageUid = requireText(node, "redisStorageUid");
    try {
      return new CanonicalGameplayLegacyMigrationStorageIdentity(
          clusterUid,
          namespaceUid,
          podUid,
          containerId,
          nodeUid,
          postgresSystemIdentifier,
          databaseOid.longValue(),
          postgresStorageUid,
          redisRunId,
          redisStorageUid);
    } catch (IllegalArgumentException invalid) {
      throw reject("migration driver storage identity is invalid");
    }
  }

  private static IllegalStateException reject(String message) {
    return new IllegalStateException(message);
  }

  @Override
  public void close() {
    reader.shutdownNow();
    writer.shutdownNow();
    try {
      input.close();
    } catch (IOException ignored) {
      // The finite driver denies the operation regardless; no stream error details are exposed.
    }
  }

  record Initialization(
      UUID cohortId,
      UUID legacyWriterFence,
      CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity,
      String fenceDigest,
      String redisHost,
      int redisPort) {
    Initialization {
      Objects.requireNonNull(cohortId, "cohortId");
      Objects.requireNonNull(legacyWriterFence, "legacyWriterFence");
      Objects.requireNonNull(storageIdentity, "storageIdentity");
      Objects.requireNonNull(fenceDigest, "fenceDigest");
      Objects.requireNonNull(redisHost, "redisHost");
    }
  }

  record LocalObservation(Map<String, Object> postgres, Map<String, Object> redis) {
    LocalObservation {
      postgres =
          java.util.Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(postgres, "postgres")));
      redis =
          java.util.Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(redis, "redis")));
    }
  }
}
