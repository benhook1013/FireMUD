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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
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
  private static final String TRANSITION = "99999999-9999-4999-8999-999999999999";
  private static final String ATTEMPT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final long DURATION_MILLIS = 120_000L;
  private static final byte[] ISSUER_PAYLOAD = new byte[] {0, 1, 2, 0, (byte) 0xff, 13, 10};
  private static final JsonMapper JSON = JsonMapper.builder().build();
  // Immutable pre-FENCE production source, gzip/base64 encoded to keep the pinned fixture compact.
  // Decode and verify its original descriptor digest before loading it into actual Redis.
  private static final String PRE_FENCE_LUA_SHA256 =
      "224ffb4457c1c6113b1c76b0b9ac1e42c5016b3d51a9c1b1ac51688d2731acbc";
  private static final String PRE_FENCE_LUA_GZIP_BASE64 =
      """
      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
      """;


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
  private String preFenceScriptSha;

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
            .addCommand(CommandType.HSET)
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
    byte[] preFenceScript = predecessorLuaForOwnerProof();
    preFenceScriptSha = owner.scriptLoad(preFenceScript);
    assertThat(HexFormatHolder.sha1(preFenceScript)).isEqualTo(preFenceScriptSha);
    assertThat(HexFormatHolder.sha1(script)).isEqualTo(scriptSha);
    assertThat(owner.aclWhoami()).isEqualTo(PRINCIPAL);
  }

  static byte[] predecessorLuaForOwnerProof() throws Exception {
    byte[] preFenceScript;
    try (var input =
        new GZIPInputStream(
            new ByteArrayInputStream(
                Base64.getMimeDecoder().decode(PRE_FENCE_LUA_GZIP_BASE64)))) {
      preFenceScript = input.readAllBytes();
    }
    assertThat(HexFormatHolder.sha256(preFenceScript)).isEqualTo(PRE_FENCE_LUA_SHA256);
    return preFenceScript;
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

  @Test
  void absentExecutionFenceInstallsOnlyTheExactPersistentDenyWithoutRecreatingRuntimeState()
      throws Exception {
    ScriptCase original = seed(false);
    for (int index = 0; index < 6; index++)
      admin.del(new byte[][] {ascii(original.keys()[index])});
    Map<String, byte[]> before = physicalImage(original);

    // No credential, template, OPEN barrier, candidate or index is needed to deny future execution.
    byte[][] arguments = action(original, "FENCE").arguments().clone();
    arguments[8] = ascii("unavailable-candidate");
    arguments[10] = ascii("unavailable-template");
    arguments[12] = ascii("unavailable-account");
    arguments[13] = ascii("unavailable-issuer");
    assertThat(eval(new ScriptCase(original.keys(), arguments))).isEqualTo(5L);
    assertHash(original.keys()[6], capacityPhysicalFields(original, "FENCED_INDETERMINATE"));
    assertThat(admin.pttl(ascii(original.keys()[6]))).isEqualTo(-1L);
    assertPhysicalImage(original, before, 6);

    Map<String, byte[]> denied = physicalImage(original);
    assertThat(eval(expired(action(original, "FENCE")))).isEqualTo(5L);
    assertPhysicalImage(original, denied, 7);
  }

  @Test
  void authorizedFenceChangesOnlyStateAndEveryDelayedCurrentOrPinnedOldCallRefuses()
      throws Exception {
    ScriptCase original = seed();
    Map<String, byte[]> before = physicalImage(original);

    assertThat(eval(action(original, "FENCE"))).isEqualTo(5L);
    assertHash(original.keys()[6], capacityPhysicalFields(original, "FENCED_INDETERMINATE"));
    assertPhysicalImage(original, before, 6);
    Map<String, byte[]> denied = physicalImage(original);
    // These are the actual immutable old production bytes, not a reconstruction of its guards.
    assertThat(eval(preFenceScriptSha, action(original, "INSTALL"))).isEqualTo(-1L);
    assertThat(eval(preFenceScriptSha, original)).isEqualTo(-1L);
    assertThat(eval(preFenceScriptSha, action(original, "CLOSE"))).isEqualTo(-1L);
    assertThat(eval(action(original, "INSTALL"))).isEqualTo(-1L);
    assertThat(eval(original)).isEqualTo(-1L);
    assertThat(eval(action(original, "CLOSE"))).isEqualTo(-1L);
    assertThat(eval(action(original, "FENCE"))).isEqualTo(5L);
    assertThat(eval(expired(action(original, "FENCE")))).isEqualTo(5L);
    assertPhysicalImage(original, denied, 7);
    for (String key : original.keys()) assertThat(admin.pttl(ascii(key))).isEqualTo(-1L);
  }

  @Test
  void fenceBeforeDelayedPinnedOldInstallationPreventsItFromInstallingAuthorization()
      throws Exception {
    ScriptCase original = seed(false);
    assertThat(eval(action(original, "FENCE"))).isEqualTo(5L);
    Map<String, byte[]> denied = physicalImage(original);

    assertThat(eval(preFenceScriptSha, action(original, "INSTALL"))).isEqualTo(-1L);
    assertThat(eval(preFenceScriptSha, original)).isEqualTo(-1L);
    assertPhysicalImage(original, denied, 7);
  }

  @Test
  void finalBeforeFencePreservesEveryCommittedByteHashAndControllerTimeIncludingExpiredReplay()
      throws Exception {
    ScriptCase original = seed();
    assertThat(eval(original)).isEqualTo(1L);
    Map<String, byte[]> committed = physicalImage(original);

    assertThat(eval(action(original, "FENCE"))).isEqualTo(2L);
    assertThat(eval(expired(action(original, "FENCE")))).isEqualTo(2L);
    byte[][] credentialFree = action(original, "FENCE").arguments().clone();
    credentialFree[11] = ascii("1");
    assertThat(eval(new ScriptCase(original.keys(), credentialFree))).isEqualTo(2L);
    assertThat(eval(expired(new ScriptCase(original.keys(), credentialFree)))).isEqualTo(2L);
    assertThat(eval(preFenceScriptSha, original)).isEqualTo(2L);
    assertPhysicalImage(original, committed, 7);
    assertThat(admin.hlen(ascii(original.keys()[6]))).isEqualTo(6L);
    for (String key : original.keys()) assertThat(admin.pttl(ascii(key))).isEqualTo(-1L);
  }

  @Test
  void alreadyClosedFenceIsReadOnlyAndNeverReclassifiesTheHistoricalOutcome() throws Exception {
    ScriptCase original = seed();
    assertThat(eval(action(original, "CLOSE"))).isEqualTo(4L);
    Map<String, byte[]> closed = physicalImage(original);

    assertThat(eval(action(original, "FENCE"))).isEqualTo(4L);
    assertThat(eval(expired(action(original, "FENCE")))).isEqualTo(4L);
    assertPhysicalImage(original, closed, 7);
    assertHash(original.keys()[6], capacityPhysicalFields(original, "CLOSED"));
  }

  @Test
  void expiredFirstFenceRefusesBothAbsentAndAuthorizedWithoutAnyMutation() throws Exception {
    for (boolean install : new boolean[] {false, true}) {
      admin.flushdb();
      ScriptCase original = seed(install);
      Map<String, byte[]> before = physicalImage(original);

      assertThat(eval(expired(action(original, "FENCE")))).isEqualTo(-1L);
      assertPhysicalImage(original, before, 7);
      assertThat(admin.pttl(ascii(original.keys()[6]))).isEqualTo(install ? -1L : -2L);
    }
  }

  @Test
  void fenceRefusesWrongTypeTtlIdentityAndEveryUnexpectedPhysicalShapeWithoutMutation()
      throws Exception {
    for (String fault :
        List.of(
            "type",
            "ttl",
            "binding",
            "digest",
            "extra-field",
            "missing-state",
            "unknown-state",
            "fenced-extra-field",
            "closed-extra-field",
            "committed-missing-fields",
            "committed-foreign-field",
            "committed-invalid-hash",
            "committed-invalid-time")) {
      admin.flushdb();
      ScriptCase original = seed();
      byte[] key = ascii(original.keys()[6]);
      switch (fault) {
        case "type" -> {
          admin.del(new byte[][] {key});
          admin.set(key, ascii("foreign-type"));
        }
        case "ttl" -> admin.pexpire(key, 60_000L);
        case "binding" -> admin.hset(key, ascii("binding"), ascii("different-envelope"));
        case "digest" -> admin.hset(key, ascii("digest"), ascii("sha256:" + "0".repeat(64)));
        case "extra-field" -> admin.hset(key, ascii("unexpected"), ascii("foreign"));
        case "missing-state" -> admin.hdel(key, ascii("state"));
        case "unknown-state" -> admin.hset(key, ascii("state"), ascii("FOREIGN"));
        case "fenced-extra-field", "closed-extra-field" -> {
          admin.hset(
              key,
              ascii("state"),
              ascii(fault.equals("fenced-extra-field") ? "FENCED_INDETERMINATE" : "CLOSED"));
          admin.hset(key, ascii("unexpected"), ascii("foreign"));
        }
        case "committed-missing-fields" -> admin.hset(key, ascii("state"), ascii("COMMITTED"));
        case "committed-foreign-field", "committed-invalid-hash", "committed-invalid-time" -> {
          assertThat(eval(original)).isEqualTo(1L);
          if (fault.equals("committed-foreign-field")) {
            byte[] value = admin.hget(key, ascii("sessionSha1"));
            admin.hdel(key, ascii("sessionSha1"));
            admin.hset(key, ascii("foreignSha1"), value);
          } else if (fault.equals("committed-invalid-hash")) {
            admin.hset(key, ascii("sessionSha1"), ascii("invalid"));
          } else {
            admin.hset(key, ascii("continuityExpiresAt"), ascii("1"));
          }
        }
        default -> throw new IllegalStateException("Unknown fixture fault: " + fault);
      }
      Map<String, byte[]> before = physicalImage(original);

      assertThat(eval(action(original, "FENCE"))).as(fault).isEqualTo(-1L);
      assertPhysicalImage(original, before, 7);
      if (fault.equals("ttl")) assertThat(admin.pttl(key)).isPositive();
      else assertThat(admin.pttl(key)).isEqualTo(-1L);
    }
  }

  @Test
  void fenceRequiresTheExactCompleteEnvelopeAndCanonicalKeyIdentityBeforeWriting() throws Exception {
    for (String fault :
        List.of("missing-field", "extra-field", "wrong-tenant", "invalid-fence", "wrong-key")) {
      admin.flushdb();
      ScriptCase original = seed(false);
      byte[][] arguments = action(original, "FENCE").arguments().clone();
      String[] keys = original.keys().clone();
      var envelope = (tools.jackson.databind.node.ObjectNode) JSON.readTree(arguments[16]);
      switch (fault) {
        case "missing-field" -> envelope.remove("reservationId");
        case "extra-field" -> envelope.put("unexpected", "foreign");
        case "wrong-tenant" -> envelope.put("tenantId", ACCOUNT);
        case "invalid-fence" -> envelope.put("selectedOwnerFence", "0");
        case "wrong-key" -> keys[6] = keys[6] + ":foreign";
        default -> throw new IllegalStateException("Unknown fixture fault: " + fault);
      }
      arguments[16] = canonicalBytes(envelope);
      arguments[18] = ascii("sha256:" + HexFormatHolder.sha256(arguments[16]));
      Map<String, byte[]> before = physicalImage(original);

      assertThat(eval(new ScriptCase(keys, arguments))).as(fault).isEqualTo(-2L);
      assertPhysicalImage(original, before, 7);
      assertThat(admin.type(ascii(keys[6]))).isEqualTo("none");
    }
  }

  private Map<String, byte[]> physicalImage(ScriptCase original) {
    Map<String, byte[]> image = new LinkedHashMap<>();
    for (String key : original.keys()) image.put(key, admin.dump(ascii(key)));
    return image;
  }

  private void assertPhysicalImage(
      ScriptCase original, Map<String, byte[]> expected, int keyCount) {
    for (int index = 0; index < keyCount; index++) {
      String key = original.keys()[index];
      if (expected.get(key) == null) assertThat(admin.dump(ascii(key))).as(key).isNull();
      else assertThat(admin.dump(ascii(key))).as(key).containsExactly(expected.get(key));
    }
  }

  private static Map<String, byte[]> capacityPhysicalFields(ScriptCase original, String state) {
    return Map.of(
        "binding", original.arguments()[16],
        "digest", original.arguments()[18],
        "state", ascii(state));
  }

  private static ScriptCase expired(ScriptCase original) {
    byte[][] arguments = original.arguments().clone();
    arguments[14] = ascii("1");
    return new ScriptCase(original.keys(), arguments);
  }

  private ScriptCase seed() throws Exception {
    return seed(true);
  }

  private ScriptCase seed(boolean install) throws Exception {
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
          sessionKey,
          characterKey,
          accountTenantKey,
          tenantKey,
          accountBarrierKey,
          issuerBarrierKey,
          "session:game:capacity-execution:{" + TAG + "}:" + ATTEMPT
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
    byte[] capacityBytes = canonicalBytes(capacity(now + 60_000L));
    String capacityDigest = "sha256:" + HexFormatHolder.sha256(capacityBytes);
    var sessionTemplate = template(candidate, lease);
    sessionTemplate.put("capacityExecutionDigest", capacityDigest);
    byte[] templateBytes = canonicalBytes(sessionTemplate);
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
          ISSUER_PAYLOAD.clone(),
          capacityBytes,
          ascii("FINAL"),
          ascii(capacityDigest)
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
    ScriptCase result = new ScriptCase(keys, arguments);
    if (install) assertThat(eval(action(result, "INSTALL"))).isEqualTo(3L);
    return result;
  }

  // Deliberately controlled envelope. Only actual Account composition qualifies its authority.
  private static Map<String, String> capacity(long expiresAt) {
    return Map.ofEntries(
        Map.entry("schema", "gs-original-new-capacity-execution/v1"),
        Map.entry("tenantId", TENANT),
        Map.entry("requestId", UUID.randomUUID().toString()),
        Map.entry("admissionId", TRANSITION),
        Map.entry("admissionAttemptId", ATTEMPT),
        Map.entry("reservationId", UUID.randomUUID().toString()),
        Map.entry("reservationFence", "1"),
        Map.entry("capacityRearmFence", "1"),
        Map.entry("selectedOwnerId", "controlled-owner"),
        Map.entry("selectedOwnerFence", "1"),
        Map.entry("transitionId", TRANSITION),
        Map.entry("expiresAtMillis", Long.toString(expiresAt)));
  }

  private static ScriptCase action(ScriptCase source, String action) {
    byte[][] arguments = source.arguments().clone();
    arguments[17] = ascii(action);
    return new ScriptCase(source.keys(), arguments);
  }

  @Test
  void lostFinalReplyReplaysTheActualSessionHashAndControllerTimesWithoutNegativeClosure()
      throws Exception {
    ScriptCase original = seed();
    eval(original); // Deliberately discard the final-CAS reply, then inspect actual state.
    byte[] actual = admin.get(ascii(original.keys()[0]));
    byte[] admittedAt = admin.hget(ascii(original.keys()[6]), ascii("admissionAt"));
    byte[] expiresAt = admin.hget(ascii(original.keys()[6]), ascii("continuityExpiresAt"));
    assertThat(admin.hget(ascii(original.keys()[6]), ascii("sessionSha1")))
        .containsExactly(ascii(HexFormatHolder.sha1(actual)));
    byte[][] expiredClose = action(original, "CLOSE").arguments().clone();
    expiredClose[14] = ascii(Long.toString(System.currentTimeMillis() - 1L));
    assertThat(eval(new ScriptCase(original.keys(), expiredClose))).isEqualTo(2L);
    assertThat(eval(original)).isEqualTo(2L);
    assertThat(admin.get(ascii(original.keys()[0]))).containsExactly(actual);
    assertThat(admin.hget(ascii(original.keys()[6]), ascii("admissionAt")))
        .containsExactly(admittedAt);
    assertThat(admin.hget(ascii(original.keys()[6]), ascii("continuityExpiresAt")))
        .containsExactly(expiresAt);
  }

  @Test
  void expiredCloseCannotMutateButFreshClosureAndExpiredReplayRemainAvailable() throws Exception {
    ScriptCase original = seed();
    Map<String, byte[]> execution = new LinkedHashMap<>();
    admin
        .hgetall(ascii(original.keys()[6]))
        .forEach((field, value) -> execution.put(new String(field, StandardCharsets.UTF_8), value));
    byte[][] expired = action(original, "CLOSE").arguments().clone();
    expired[14] = ascii(Long.toString(System.currentTimeMillis() - 1L));
    var late = new ScriptCase(original.keys(), expired);
    assertThat(eval(late)).isEqualTo(-1L);
    assertHash(original.keys()[6], execution);
    assertHash(original.keys()[4], accountPhysicalFields());
    assertHash(original.keys()[5], issuerPhysicalFields());
    assertThat(admin.get(ascii(original.keys()[0]))).containsExactly(original.arguments()[8]);
    assertThat(admin.get(ascii(original.keys()[1]))).containsExactly(original.arguments()[9]);
    assertThat(admin.scard(ascii(original.keys()[2]))).isEqualTo(1L);
    assertThat(admin.scard(ascii(original.keys()[3]))).isEqualTo(1L);
    assertThat(admin.sismember(ascii(original.keys()[2]), ascii(SESSION))).isTrue();
    assertThat(admin.sismember(ascii(original.keys()[3]), ascii(SESSION))).isTrue();
    for (String key : original.keys()) assertThat(admin.pttl(ascii(key))).isEqualTo(-1L);
    byte[][] fresh = action(original, "CLOSE").arguments().clone();
    fresh[14] = ascii(Long.toString(System.currentTimeMillis() + 5000L));
    assertThat(eval(new ScriptCase(original.keys(), fresh))).isEqualTo(4L);
    execution.put("state", ascii("CLOSED"));
    assertHash(original.keys()[6], execution);
    assertThat(eval(late)).isEqualTo(4L);
    assertHash(original.keys()[6], execution);
    assertThat(admin.get(ascii(original.keys()[0]))).containsExactly(original.arguments()[8]);
  }

  @Test
  void permanentClosureSurvivesLostReplyAndPreventsEveryDelayedSameAttemptFinal() throws Exception {
    ScriptCase original = seed();
    eval(action(original, "CLOSE")); // Lose the successful reply deliberately.
    assertThat(admin.hget(ascii(original.keys()[6]), ascii("state")))
        .containsExactly(ascii("CLOSED"));
    assertThat(eval(action(original, "CLOSE"))).isEqualTo(4L);
    assertThat(eval(action(original, "INSTALL"))).isEqualTo(4L);
    assertThat(eval(original)).isEqualTo(4L);
    assertThat(admin.get(ascii(original.keys()[0]))).containsExactly(original.arguments()[8]);
    assertThat(admin.hlen(ascii(original.keys()[6]))).isEqualTo(3L);
  }

  @Test
  void concurrentFinalAndIrreversibleClosureHaveExactlyOneDecisiveWinner() throws Exception {
    ScriptCase original = seed();
    var start = new java.util.concurrent.CountDownLatch(1);
    var finalized =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              try {
                start.await();
                return eval(original);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
              }
            });
    var closed =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              try {
                start.await();
                return eval(action(original, "CLOSE"));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
              }
            });
    start.countDown();
    long finalResult = finalized.get(5, java.util.concurrent.TimeUnit.SECONDS);
    long closeResult = closed.get(5, java.util.concurrent.TimeUnit.SECONDS);
    if (finalResult == 1L) {
      assertThat(closeResult).isEqualTo(2L);
      assertThat(admin.hget(ascii(original.keys()[6]), ascii("state")))
          .containsExactly(ascii("COMMITTED"));
      assertThat(eval(original)).isEqualTo(2L);
      assertThat(admin.hget(ascii(original.keys()[6]), ascii("sessionSha1")))
          .containsExactly(ascii(HexFormatHolder.sha1(admin.get(ascii(original.keys()[0])))));
    } else {
      assertThat(finalResult).isEqualTo(4L);
      assertThat(closeResult).isEqualTo(4L);
      assertThat(admin.get(ascii(original.keys()[0]))).containsExactly(original.arguments()[8]);
      assertThat(eval(original)).isEqualTo(4L);
    }
  }

  @Test
  void missingExecutionAndChangedFenceNeverAuthorizeOrProveNoCommit() throws Exception {
    ScriptCase original = seed();
    byte[] candidate = admin.get(ascii(original.keys()[0]));
    byte[][] stale = original.arguments().clone();
    var staleCapacity =
        (tools.jackson.databind.node.ObjectNode) JSON.readTree(original.arguments()[16]);
    staleCapacity.put("reservationFence", "2");
    stale[16] = canonicalBytes(staleCapacity);
    String staleDigest = "sha256:" + HexFormatHolder.sha256(stale[16]);
    stale[18] = ascii(staleDigest);
    var staleTemplate = (tools.jackson.databind.node.ObjectNode) JSON.readTree(stale[10]);
    staleTemplate.put("capacityExecutionDigest", staleDigest);
    stale[10] = canonicalBytes(staleTemplate);
    assertThat(eval(new ScriptCase(original.keys(), stale))).isEqualTo(-1L);
    admin.del(new byte[][] {ascii(original.keys()[6])});
    assertThat(eval(original)).isEqualTo(-1L);
    assertThat(eval(action(original, "CLOSE"))).isEqualTo(-1L);
    assertThat(admin.get(ascii(original.keys()[0]))).containsExactly(candidate);
    assertThat(admin.type(ascii(original.keys()[6]))).isEqualTo("none");
  }

  @Test
  void elapsedReservationRefusesTheFinalCasButStillNeedsExplicitClosure() throws Exception {
    ScriptCase original = seed();
    // Replace only this controlled fixture's complete matching envelope before INSTALL.
    admin.del(new byte[][] {ascii(original.keys()[6])});
    var capacity = new LinkedHashMap<>(capacity(System.currentTimeMillis() + 1000L));
    byte[][] args = original.arguments().clone();
    args[16] = canonicalBytes(capacity);
    String digest = "sha256:" + HexFormatHolder.sha256(args[16]);
    args[18] = ascii(digest);
    var template = JSON.readTree(new String(args[10], StandardCharsets.UTF_8));
    ((tools.jackson.databind.node.ObjectNode) template).put("capacityExecutionDigest", digest);
    args[10] = canonicalBytes(template);
    ScriptCase expiring = new ScriptCase(original.keys(), args);
    assertThat(eval(action(expiring, "INSTALL"))).isEqualTo(3L);
    Thread.sleep(
        Math.max(
            1L,
            Long.parseLong(capacity.get("expiresAtMillis")) - System.currentTimeMillis() + 20L));
    assertThat(eval(expiring)).isEqualTo(-1L);
    assertThat(admin.hget(ascii(original.keys()[6]), ascii("state")))
        .containsExactly(ascii("AUTHORIZED"));
    assertThat(eval(action(expiring, "CLOSE"))).isEqualTo(4L);
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
    value.put("bindingDecisionId", TRANSITION);
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
    return eval(scriptSha, testCase);
  }

  private long eval(String sha, ScriptCase testCase) {
    byte[][] keyBytes = new byte[testCase.keys().length][];
    for (int index = 0; index < keyBytes.length; index++)
      keyBytes[index] = ascii(testCase.keys()[index]);
    return owner.evalsha(sha, ScriptOutputType.INTEGER, keyBytes, testCase.arguments());
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
