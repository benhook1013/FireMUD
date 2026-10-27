package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Native Redis source proof for the final-CAS script with controlled closed payloads.
 *
 * <p>This exercises the Redis linearization and exact replay/refusal behavior only. It does not
 * construct typed candidate, Account, issuer, credential, SQL, or final-owner readback authority.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewFinalAdmissionLuaIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final int PORT = 6379;
  private static final String TAG = "g2972-0123456789abcdef0123456789abcdef";
  private static final String TENANT = "11111111-1111-4111-8111-111111111111";
  private static final String ACCOUNT = "22222222-2222-4222-8222-222222222222";
  private static final String NAMESPACE = "33333333-3333-4333-8333-333333333333";
  private static final String CHARACTER = "44444444-4444-4444-8444-444444444444";
  private static final String INSTANCE = "55555555-5555-4555-8555-555555555555";
  private static final String SESSION = "66666666-6666-4666-8666-666666666666";
  private static final String GENERATION = "1";
  private static final long DURATION_MILLIS = 120_000L;
  private static final byte[] ISSUER_PAYLOAD = new byte[] {0, 1, 2, 0, (byte) 0xff, 13, 10};
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(PORT);

  private RedisClient administrator;
  private RedisClient ownerClient;
  private StatefulRedisConnection<byte[], byte[]> adminConnection;
  private StatefulRedisConnection<byte[], byte[]> ownerConnection;
  private RedisCommands<byte[], byte[]> admin;
  private RedisCommands<byte[], byte[]> owner;
  private String scriptSha;

  @BeforeEach
  void setUp() throws Exception {
    administrator = createClient(null, null);
    adminConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    admin = adminConnection.sync();
    admin.flushdb();
    admin.aclSetuser(
        PRINCIPAL,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(PASSWORD)
            .keyPattern("session:game:*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.TYPE)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.GET)
            .addCommand(CommandType.SET)
            .addCommand(CommandType.SISMEMBER)
            .addCommand(CommandType.HGET)
            .addCommand(CommandType.HLEN)
            .addCommand(CommandType.TIME));
    ownerClient = createClient(PRINCIPAL, PASSWORD);
    ownerConnection = ownerClient.connect(ByteArrayCodec.INSTANCE);
    owner = ownerConnection.sync();

    var descriptor =
        new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution()
            .descriptors()
            .iterator()
            .next();
    var catalog =
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution()));
    assertThat(catalog.require(descriptor.scriptId())).isEqualTo(descriptor);
    byte[] script;
    try (InputStream input =
        getClass().getClassLoader().getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) {
        throw new IllegalStateException("Final admission script resource is absent");
      }
      script = input.readAllBytes();
    }
    assertThat(HexFormatHolder.sha256(script)).isEqualTo(descriptor.sha256());
    scriptSha = owner.scriptLoad(script);
    assertThat(HexFormatHolder.sha1(script)).isEqualTo(scriptSha);
    assertThat(owner.aclWhoami()).isEqualTo(PRINCIPAL);
  }

  @AfterEach
  void tearDown() {
    if (ownerConnection != null) ownerConnection.close();
    if (adminConnection != null) adminConnection.close();
    if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
  }

  @Test
  void firstCasStoresServerTimesAndExactReplayPreservesEveryPostimage() throws Exception {
    ScriptCase testCase = seed();
    Map<String, byte[]> accountFields = accountPhysicalFields();
    Map<String, byte[]> issuerFields = issuerPhysicalFields();

    assertThat(eval(testCase)).isEqualTo(1L);
    byte[] finalized = admin.get(ascii(testCase.keys()[0]));
    assertThat(finalized).isNotNull().isNotEqualTo(testCase.arguments()[8]);
    var finalizedNode = JSON.readTree(new String(finalized, StandardCharsets.UTF_8));
    long admissionAt = Long.parseLong(finalizedNode.get("admissionAt").stringValue());
    long continuity = Long.parseLong(finalizedNode.get("continuityBindingExpiresAt").stringValue());
    assertThat(admissionAt).isPositive();
    assertThat(continuity - admissionAt).isEqualTo(DURATION_MILLIS);
    assertThat(admin.pttl(ascii(testCase.keys()[0]))).isEqualTo(-1L);
    assertThat(admin.get(ascii(testCase.keys()[1]))).containsExactly(testCase.arguments()[9]);
    assertThat(admin.sismember(ascii(testCase.keys()[2]), ascii(SESSION))).isTrue();
    assertThat(admin.sismember(ascii(testCase.keys()[3]), ascii(SESSION))).isTrue();
    assertThat(admin.pttl(ascii(testCase.keys()[2]))).isEqualTo(-1L);
    assertThat(admin.pttl(ascii(testCase.keys()[3]))).isEqualTo(-1L);

    assertThat(eval(testCase)).isEqualTo(2L);
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(finalized);
    assertHash(testCase.keys()[4], accountFields);
    assertHash(testCase.keys()[5], issuerFields);

    byte[][] expiredArguments = testCase.arguments().clone();
    expiredArguments[14] = ascii(Long.toString(System.currentTimeMillis() - 1L));
    assertThat(eval(new ScriptCase(testCase.keys(), expiredArguments))).isEqualTo(-1L);
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(finalized);
  }

  @Test
  void missingIndexClosedBarrierAndStaleCandidateRefuseWithoutRepairOrWrite() throws Exception {
    ScriptCase testCase = seed();
    assertThat(eval(testCase)).isEqualTo(1L);
    byte[] finalized = admin.get(ascii(testCase.keys()[0]));
    byte[] accountIndex = ascii(testCase.keys()[2]);
    byte[] accountBarrier = ascii(testCase.keys()[4]);
    byte[][] accountIndexKeys = {accountIndex};

    assertThat(admin.del(accountIndexKeys)).isEqualTo(1L);
    assertThat(eval(testCase)).isEqualTo(-1L);
    assertThat(admin.type(accountIndex)).isEqualTo("none");
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(finalized);
    assertThat(admin.sismember(ascii(testCase.keys()[3]), ascii(SESSION))).isTrue();

    admin.sadd(accountIndex, ascii(SESSION));
    byte[] characterIndex = ascii(testCase.keys()[1]);
    byte[][] characterIndexKeys = {characterIndex};
    admin.del(characterIndexKeys);
    assertThat(eval(testCase)).isEqualTo(-1L);
    assertThat(admin.type(characterIndex)).isEqualTo("none");
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(finalized);
    assertThat(admin.sismember(ascii(testCase.keys()[2]), ascii(SESSION))).isTrue();
    admin.set(characterIndex, testCase.arguments()[9]);

    byte[] tenantIndex = ascii(testCase.keys()[3]);
    byte[][] tenantIndexKeys = {tenantIndex};
    admin.del(tenantIndexKeys);
    assertThat(eval(testCase)).isEqualTo(-1L);
    assertThat(admin.type(tenantIndex)).isEqualTo("none");
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(finalized);
    assertThat(admin.sismember(ascii(testCase.keys()[2]), ascii(SESSION))).isTrue();
    admin.sadd(tenantIndex, ascii(SESSION));

    admin.set(accountIndex, ascii("wrong-type"));
    assertThat(eval(testCase)).isEqualTo(-1L);
    assertThat(admin.type(accountIndex)).isEqualTo("string");
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(finalized);

    admin.del(accountIndexKeys);
    admin.sadd(accountIndex, ascii(SESSION));
    admin.hset(accountBarrier, ascii("state"), ascii("CLOSED"));
    assertThat(eval(testCase)).isEqualTo(-1L);
    assertThat(admin.hget(accountBarrier, ascii("state"))).containsExactly(ascii("CLOSED"));
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(finalized);

    admin.hset(accountBarrier, ascii("state"), ascii("OPEN"));
    admin.hset(accountBarrier, ascii("ownerFenceEpoch"), ascii("1"));
    assertThat(eval(testCase)).isEqualTo(-1L);
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(finalized);

    admin.hset(accountBarrier, ascii("ownerFenceEpoch"), ascii("0"));
    byte[] issuerBarrier = ascii(testCase.keys()[5]);
    admin.hset(issuerBarrier, ascii("state"), ascii("CLOSED"));
    assertThat(eval(testCase)).isEqualTo(-1L);
    assertThat(admin.hget(issuerBarrier, ascii("state"))).containsExactly(ascii("CLOSED"));
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(finalized);

    admin.hset(issuerBarrier, ascii("state"), ascii("OPEN"));
    admin.hset(issuerBarrier, ascii("admissionEpoch"), ascii("2"));
    assertThat(eval(testCase)).isEqualTo(-1L);
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(finalized);

    admin.hset(issuerBarrier, ascii("admissionEpoch"), ascii("1"));
    admin.set(ascii(testCase.keys()[0]), ascii("foreign-newer-session"));
    byte[] foreign = admin.get(ascii(testCase.keys()[0]));
    assertThat(eval(testCase)).isEqualTo(-1L);
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(foreign);
  }

  @Test
  void expiredDeadlineRefusesWithoutChangingCandidateOrAnyIndex() throws Exception {
    ScriptCase testCase = seed();
    byte[] candidate = admin.get(ascii(testCase.keys()[0]));
    byte[][] arguments = testCase.arguments().clone();
    arguments[14] = ascii(Long.toString(System.currentTimeMillis() - 1L));

    assertThat(eval(new ScriptCase(testCase.keys(), arguments))).isEqualTo(-1L);
    assertThat(admin.get(ascii(testCase.keys()[0]))).containsExactly(candidate);
    assertThat(admin.sismember(ascii(testCase.keys()[2]), ascii(SESSION))).isTrue();
    assertThat(admin.sismember(ascii(testCase.keys()[3]), ascii(SESSION))).isTrue();
  }

  @Test
  void resetLossRefusesWithoutRecreatingCandidateIndexesOrBarriers() throws Exception {
    ScriptCase testCase = seed();
    admin.flushdb();

    assertThat(eval(testCase)).isEqualTo(-1L);
    for (String key : testCase.keys()) {
      assertThat(admin.type(ascii(key))).isEqualTo("none");
    }
  }

  private ScriptCase seed() throws Exception {
    String sessionKey = "session:game:{" + TAG + "}:" + INSTANCE + ":" + SESSION;
    String characterKey =
        "session:game:index:character:{" + TAG + "}:" + NAMESPACE + ":" + CHARACTER;
    String accountTenantKey = "session:game:index:account-tenant:{" + TAG + "}:" + ACCOUNT;
    String tenantKey = "session:game:index:tenant:{" + TAG + "}";
    String accountBarrierKey =
        "session:game:account-admission:{" + TAG + "}:" + ACCOUNT + ":" + NAMESPACE;
    String issuerBarrierKey =
        "session:game:issuer-admission:{"
            + TAG
            + "}:firemud-account-service:"
            + TENANT
            + ":"
            + NAMESPACE;
    String[] keys =
        new String[] {
          sessionKey, characterKey, accountTenantKey, tenantKey, accountBarrierKey, issuerBarrierKey
        };
    long now = System.currentTimeMillis();
    Map<String, Object> lease = leaseEvidence(now + 60_000L);
    Map<String, Object> candidate = candidate(lease);
    byte[] candidateBytes = canonicalBytes(candidate);
    byte[] characterBytes =
        ascii(
            "{\"sessionId\":\""
                + SESSION
                + "\",\"gameInstanceId\":\""
                + INSTANCE
                + "\",\"bindingGeneration\":\""
                + GENERATION
                + "\"}");
    byte[] templateBytes = canonicalBytes(template(candidate, lease));
    Map<String, String> account = accountFields();
    Map<String, String> issuer = issuerMetadata();
    byte[] accountJson = canonicalBytes(account);
    byte[] issuerJson = canonicalBytes(issuer);
    byte[][] arguments =
        new byte[][] {
          ascii(TENANT),
          ascii(ACCOUNT),
          ascii(NAMESPACE),
          ascii(CHARACTER),
          ascii(INSTANCE),
          ascii(SESSION),
          ascii(GENERATION),
          ascii(TAG),
          candidateBytes,
          characterBytes,
          templateBytes,
          ascii(Long.toString(DURATION_MILLIS)),
          accountJson,
          issuerJson,
          ascii(Long.toString(now + 60_000L)),
          ISSUER_PAYLOAD.clone()
        };

    admin.set(ascii(sessionKey), candidateBytes);
    admin.set(ascii(characterKey), characterBytes);
    admin.sadd(ascii(accountTenantKey), ascii(SESSION));
    admin.sadd(ascii(tenantKey), ascii(SESSION));
    admin.hset(ascii(accountBarrierKey), encoded(account));
    Map<String, byte[]> issuerPhysical = new LinkedHashMap<>();
    issuer.forEach((name, value) -> issuerPhysical.put(name, ascii(value)));
    issuerPhysical.put("payload", ISSUER_PAYLOAD.clone());
    issuerPhysical.put("payloadDigest", ascii("sha256:" + "f".repeat(64)));
    admin.hset(ascii(issuerBarrierKey), encodedBytes(issuerPhysical));
    return new ScriptCase(keys, arguments);
  }

  private static Map<String, Object> candidate(Map<String, Object> lease) {
    Map<String, Object> binding =
        Map.of(
            "accountId", ACCOUNT,
            "tenantId", TENANT,
            "playableStateNamespaceId", NAMESPACE,
            "characterId", CHARACTER,
            "gameInstanceId", INSTANCE,
            "sessionId", SESSION,
            "issuerId", "firemud-account-service");
    Map<String, Object> transition = Map.of("bindingGeneration", GENERATION);
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("purpose", "original-new-non-admitting-candidate/v1");
    value.put("binding", binding);
    value.put("transition", transition);
    value.put("leaseEvidence", lease);
    return value;
  }

  private static Map<String, Object> leaseEvidence(long expiresAt) {
    return Map.of(
        "authorityTuple", Map.of(),
        "membershipBaseline", Map.of(),
        "outboxCheckpoints", List.of(),
        "issuanceFence", "7",
        "expiresAt", Long.toString(expiresAt));
  }

  private static Map<String, Object> template(
      Map<String, Object> candidate, Map<String, Object> lease) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schemaVersion", 2);
    value.put("admissionState", "COMPLETE");
    value.put("originalCandidate", candidate);
    value.put("bindingGeneration", GENERATION);
    value.put("accountIndexState", "ACKNOWLEDGED");
    value.put("issuerIndexState", "ACKNOWLEDGED");
    value.put("regionBridgeState", "ACKNOWLEDGED");
    value.put("admissionAt", "__ORIGINAL_NEW_ADMISSION_AT_MS__");
    value.put("authTokenHash", "a".repeat(64));
    long nowSeconds = System.currentTimeMillis() / 1000L;
    value.put("authTokenIssuedAt", Long.toString(nowSeconds - 10L));
    value.put("authTokenExpiresAt", Long.toString(nowSeconds + 290L));
    value.put("tokenGeneration", "1");
    value.put("issuanceFence", lease.get("issuanceFence"));
    value.put("rebindHandle", "A".repeat(43));
    value.put("continuityBindingExpiresAt", "__ORIGINAL_NEW_CONTINUITY_EXPIRES_AT_MS__");
    value.put("membershipBaseline", lease.get("membershipBaseline"));
    value.put("authorityTuple", lease.get("authorityTuple"));
    value.put("outboxCheckpoints", lease.get("outboxCheckpoints"));
    value.put("canonicalSessionDurationMillis", Long.toString(DURATION_MILLIS));
    return value;
  }

  private static Map<String, String> accountFields() {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("schema", "gs_account_admission_barrier/v1");
    fields.put("accountId", ACCOUNT);
    fields.put("tenantId", TENANT);
    fields.put("playableStateNamespaceId", NAMESPACE);
    fields.put("tenantGameplayTag", TAG);
    fields.put("ownerOperationId", "77777777-7777-4777-8777-777777777777");
    fields.put("ownerFenceEpoch", "0");
    fields.put("incarnationDigest", "sha256:" + "b".repeat(64));
    fields.put("state", "OPEN");
    return fields;
  }

  private static Map<String, String> issuerMetadata() {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("schema", "gs_issuer_admission_barrier/v1");
    fields.put("issuerId", "firemud-account-service");
    fields.put("tenantId", TENANT);
    fields.put("playableStateNamespaceId", NAMESPACE);
    fields.put("tenantGameplayTag", TAG);
    fields.put("state", "OPEN");
    fields.put("admissionEpoch", "1");
    fields.put("ownerOperationId", "88888888-8888-4888-8888-888888888888");
    fields.put("initializerOperationId", "88888888-8888-4888-8888-888888888888");
    fields.put("initializerEpoch", "1");
    fields.put("initializerDigest", "sha256:" + "c".repeat(64));
    fields.put("payloadDigest", "sha256:" + "f".repeat(64));
    return fields;
  }

  private void assertHash(String key, Map<String, byte[]> expected) {
    byte[] redisKey = ascii(key);
    assertThat(admin.hlen(redisKey)).isEqualTo((long) expected.size());
    expected.forEach(
        (field, value) -> assertThat(admin.hget(redisKey, ascii(field))).containsExactly(value));
  }

  private static Map<String, byte[]> accountPhysicalFields() {
    Map<String, byte[]> fields = new LinkedHashMap<>();
    accountFields().forEach((name, value) -> fields.put(name, ascii(value)));
    return fields;
  }

  private static Map<String, byte[]> issuerPhysicalFields() {
    Map<String, byte[]> fields = new LinkedHashMap<>();
    issuerMetadata().forEach((name, value) -> fields.put(name, ascii(value)));
    fields.put("payload", ISSUER_PAYLOAD.clone());
    return fields;
  }

  private long eval(ScriptCase testCase) {
    byte[][] keyBytes = new byte[testCase.keys().length][];
    for (int index = 0; index < keyBytes.length; index++)
      keyBytes[index] = ascii(testCase.keys()[index]);
    return owner.evalsha(scriptSha, ScriptOutputType.INTEGER, keyBytes, testCase.arguments());
  }

  private RedisClient createClient(String username, String password) {
    RedisURI.Builder builder =
        RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(PORT))
            .withTimeout(Duration.ofSeconds(3));
    if (username != null) builder.withAuthentication(username, password);
    RedisClient client = RedisClient.create(builder.build());
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  private static byte[] canonicalBytes(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static Map<byte[], byte[]> encoded(Map<String, String> values) {
    Map<byte[], byte[]> result = new LinkedHashMap<>();
    values.forEach((field, value) -> result.put(ascii(field), ascii(value)));
    return result;
  }

  private static Map<byte[], byte[]> encodedBytes(Map<String, byte[]> values) {
    Map<byte[], byte[]> result = new LinkedHashMap<>();
    values.forEach((field, value) -> result.put(ascii(field), value));
    return result;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private record ScriptCase(String[] keys, byte[][] arguments) {}

  private static final class HexFormatHolder {
    private static String sha256(byte[] value) throws Exception {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String sha1(byte[] value) throws Exception {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value));
    }
  }
}
