package net.firedevops.firemud.common.account.authority;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Strict RFC 8785 codec for Account-owned issuer and Account authority-source events. */
public final class AccountAuthoritySourceEventV1Codec {
  public static final String SCHEMA_VERSION = "account-auth-authority-source-event/v1";
  public static final String EVENT_STREAM_PREFIX = "account:auth-authority:v1:";
  public static final String ISSUER_EVENT_TYPE = "ISSUER_AUTHORITY_CHANGED";
  public static final String ACCOUNT_EVENT_TYPE = "ACCOUNT_AUTHORITY_CHANGED";

  private static final Set<String> ISSUER_FIELDS =
      Set.of(
          "schemaVersion",
          "eventType",
          "eventId",
          "requestId",
          "outboxStreamKey",
          "outboxSequence",
          "sourceScope",
          "issuerId",
          "issuerAuthGeneration",
          "sourceVersion",
          "mutationKind");
  private static final Set<String> ACCOUNT_FIELDS =
      Set.of(
          "schemaVersion",
          "eventType",
          "eventId",
          "requestId",
          "outboxStreamKey",
          "outboxSequence",
          "sourceScope",
          "accountId",
          "accountAuthorityGeneration",
          "sourceVersion",
          "issuanceFence",
          "issuanceFenceSourceVersion",
          "mutationKinds",
          "accountState",
          "accountSecurityCutoff");
  private static final Set<String> ACCOUNT_STATE_FIELDS =
      Set.of("emailVerified", "loginAuthModes", "globalRole", "lifecycleState");
  private static final Set<String> CUTOFF_FIELDS =
      Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence");
  private static final Set<String> ACCOUNT_MUTATIONS =
      Set.of(
          "EMAIL_LOGIN_ELIGIBILITY_CHANGED",
          "GLOBAL_ROLE_CHANGED",
          "LIFECYCLE_STATE_CHANGED",
          "LOGIN_AUTH_MODES_CHANGED",
          "PASSWORD_RESET");
  private static final Set<String> ISSUER_MUTATIONS =
      Set.of("POST_RESTORE_HARDENING", "SIGNER_COMPROMISE", "ISSUER_SECURITY_CHANGE");
  private static final Set<String> LOGIN_MODES = Set.of("EMAIL_OTP", "PASSWORD");
  private static final Set<String> LIFECYCLE_STATES =
      Set.of("ACTIVE", "DEACTIVATED_PENDING_DELETE", "DELETED", "SECURITY_LOCKED");
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern DIGEST_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern ROLE_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,127}");
  private static final Pattern POSITIVE_DECIMAL_PATTERN = Pattern.compile("[1-9][0-9]{0,18}");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private AccountAuthoritySourceEventV1Codec() {}

  /** Seals one exact issuer mutation; issuer events never carry Account cutoffs or fences. */
  public static IssuerEvent sealIssuer(IssuerPreimage preimage) {
    Objects.requireNonNull(preimage, "issuer event preimage is required");
    ObjectNode object = issuerNode(preimage);
    validateIssuer(object);
    String digest = digest(object);
    ObjectNode wire = object.deepCopy();
    wire.put("eventDigest", digest);
    return issuerEvidence(wire, digest, canonicalJson(wire));
  }

  /** Seals one Account authority mutation with its exact same-event security cutoff. */
  public static AccountEvent sealAccount(AccountPreimage preimage) {
    Objects.requireNonNull(preimage, "Account event preimage is required");
    ObjectNode object = accountNode(preimage);
    validateAccount(object);
    String digest = digest(object);
    ObjectNode wire = object.deepCopy();
    wire.put("eventDigest", digest);
    return accountEvidence(wire, digest, canonicalJson(wire));
  }

  /** Strictly verifies one wire payload and rejects unknown, duplicate, or noncanonical fields. */
  public static SourceEvent verify(String wireJson) {
    Objects.requireNonNull(wireJson, "source event JSON is required");
    final JsonNode tree;
    try {
      Rfc8785CanonicalJson.canonicalizeUtf8(wireJson);
      tree = JSON.readTree(wireJson);
    } catch (IOException malformed) {
      throw invalid("event", "JSON is malformed", malformed);
    }
    if (!(tree instanceof ObjectNode wire)) throw invalid("event", "must be an object");
    String type = text(wire, "eventType");
    String digest = text(wire, "eventDigest");
    if (!DIGEST_PATTERN.matcher(digest).matches()) {
      throw invalid("eventDigest", "must be canonical lowercase SHA-256");
    }
    ObjectNode preimage = wire.deepCopy();
    preimage.remove("eventDigest");
    if (ISSUER_EVENT_TYPE.equals(type)) {
      exactFields(preimage, ISSUER_FIELDS);
      validateIssuer(preimage);
    } else if (ACCOUNT_EVENT_TYPE.equals(type)) {
      exactFields(preimage, ACCOUNT_FIELDS);
      validateAccount(preimage);
    } else {
      throw invalid("eventType", "is not a declared Account source event type");
    }
    String computed = digest(preimage);
    if (!MessageDigest.isEqual(
        digest.getBytes(StandardCharsets.US_ASCII), computed.getBytes(StandardCharsets.US_ASCII))) {
      throw invalid("eventDigest", "does not match the canonical preimage");
    }
    if (!canonicalJson(wire).equals(wireJson)) {
      throw invalid("event", "wire JSON is not the exact canonical encoding");
    }
    return ISSUER_EVENT_TYPE.equals(type)
        ? issuerEvidence(wire, digest, wireJson)
        : accountEvidence(wire, digest, wireJson);
  }

  private static ObjectNode issuerNode(IssuerPreimage event) {
    ObjectNode object = JSON.createObjectNode();
    object.put("schemaVersion", SCHEMA_VERSION);
    object.put("eventType", ISSUER_EVENT_TYPE);
    object.put("eventId", event.eventId());
    object.put("requestId", event.requestId());
    object.put("outboxStreamKey", event.outboxStreamKey());
    object.put("outboxSequence", event.outboxSequence());
    object.put("sourceScope", "issuer/" + event.issuerId());
    object.put("issuerId", event.issuerId());
    object.put("issuerAuthGeneration", event.issuerAuthGeneration());
    object.put("sourceVersion", event.sourceVersion());
    object.put("mutationKind", event.mutationKind());
    return object;
  }

  private static ObjectNode accountNode(AccountPreimage event) {
    ObjectNode object = JSON.createObjectNode();
    object.put("schemaVersion", SCHEMA_VERSION);
    object.put("eventType", ACCOUNT_EVENT_TYPE);
    object.put("eventId", event.eventId());
    object.put("requestId", event.requestId());
    object.put("outboxStreamKey", event.outboxStreamKey());
    object.put("outboxSequence", event.outboxSequence());
    object.put("sourceScope", "account/" + event.accountId());
    object.put("accountId", event.accountId());
    object.put("accountAuthorityGeneration", event.accountAuthorityGeneration());
    object.put("sourceVersion", event.sourceVersion());
    object.put("issuanceFence", event.issuanceFence());
    object.put("issuanceFenceSourceVersion", event.issuanceFenceSourceVersion());
    if (event.mutationKinds() == null) {
      object.putNull("mutationKinds");
    } else {
      var mutations = object.putArray("mutationKinds");
      event.mutationKinds().forEach(mutations::add);
    }
    ObjectNode accountState = object.putObject("accountState");
    accountState.put("emailVerified", event.accountState().emailVerified());
    if (event.accountState().loginAuthModes() == null) {
      accountState.putNull("loginAuthModes");
    } else {
      var modes = accountState.putArray("loginAuthModes");
      event.accountState().loginAuthModes().forEach(modes::add);
    }
    accountState.put("globalRole", event.accountState().globalRole());
    accountState.put("lifecycleState", event.accountState().lifecycleState());
    ObjectNode cutoff = object.putObject("accountSecurityCutoff");
    cutoff.put("accountAuthorityGeneration", event.accountAuthorityGeneration());
    cutoff.put("outboxStreamKey", event.outboxStreamKey());
    cutoff.put("outboxSequence", event.outboxSequence());
    return object;
  }

  private static void validateIssuer(ObjectNode event) {
    exactFields(event, ISSUER_FIELDS);
    exact(event, "schemaVersion", SCHEMA_VERSION);
    exact(event, "eventType", ISSUER_EVENT_TYPE);
    boundedText(event, "eventId", 512);
    boundedText(event, "requestId", 512);
    String issuer = boundedText(event, "issuerId", 512);
    if (issuer.isBlank() || !issuer.equals(issuer.strip())) {
      throw invalid("issuerId", "must be bounded nonblank canonical text");
    }
    String stream = EVENT_STREAM_PREFIX + "issuer/" + issuer;
    exact(event, "sourceScope", "issuer/" + issuer);
    exact(event, "outboxStreamKey", stream);
    long sequence = positiveDecimal(event, "outboxSequence");
    long expectedVersion = nextVersionForSequence(sequence);
    if (positiveDecimal(event, "issuerAuthGeneration") != expectedVersion
        || positiveDecimal(event, "sourceVersion") != expectedVersion) {
      throw invalid("issuerAuthGeneration", "must exactly follow the initialized source baseline");
    }
    String mutation = text(event, "mutationKind");
    if (!ISSUER_MUTATIONS.contains(mutation)) {
      throw invalid("mutationKind", "is not a declared issuer authority mutation");
    }
  }

  private static void validateAccount(ObjectNode event) {
    exactFields(event, ACCOUNT_FIELDS);
    exact(event, "schemaVersion", SCHEMA_VERSION);
    exact(event, "eventType", ACCOUNT_EVENT_TYPE);
    boundedText(event, "eventId", 512);
    boundedText(event, "requestId", 512);
    String accountId = canonicalUuid(event, "accountId");
    String stream = EVENT_STREAM_PREFIX + "account/" + accountId;
    exact(event, "sourceScope", "account/" + accountId);
    exact(event, "outboxStreamKey", stream);
    long sequence = positiveDecimal(event, "outboxSequence");
    long expectedVersion = nextVersionForSequence(sequence);
    if (positiveDecimal(event, "accountAuthorityGeneration") != expectedVersion
        || positiveDecimal(event, "sourceVersion") != expectedVersion
        || positiveDecimal(event, "issuanceFence") != expectedVersion
        || positiveDecimal(event, "issuanceFenceSourceVersion") != expectedVersion) {
      throw invalid(
          "accountAuthorityGeneration", "must exactly follow the initialized source baseline");
    }

    JsonNode kindsNode = event.get("mutationKinds");
    if (kindsNode == null || !kindsNode.isArray() || kindsNode.isEmpty()) {
      throw invalid("mutationKinds", "must be a nonempty required array");
    }
    String previous = null;
    for (JsonNode kindNode : kindsNode) {
      if (!kindNode.isTextual()) throw invalid("mutationKinds", "entries must be strings");
      String kind = kindNode.textValue();
      if (!ACCOUNT_MUTATIONS.contains(kind)
          || (previous != null && previous.compareTo(kind) >= 0)) {
        throw invalid("mutationKinds", "must be a sorted set of declared mutation kinds");
      }
      previous = kind;
    }

    JsonNode stateNode = event.get("accountState");
    if (!(stateNode instanceof ObjectNode state)) {
      throw invalid("accountState", "must be an object");
    }
    exactFields(state, ACCOUNT_STATE_FIELDS);
    if (!state.path("emailVerified").isBoolean()) {
      throw invalid("accountState.emailVerified", "must be a boolean");
    }
    validateStringSet(state.get("loginAuthModes"), LOGIN_MODES, "accountState.loginAuthModes");
    String role = nullableText(state, "globalRole");
    if (role != null && !ROLE_PATTERN.matcher(role).matches()) {
      throw invalid("accountState.globalRole", "must be a bounded Account role identifier");
    }
    String lifecycle = text(state, "lifecycleState");
    if (!LIFECYCLE_STATES.contains(lifecycle)) {
      throw invalid("accountState.lifecycleState", "is unsupported");
    }

    JsonNode cutoffNode = event.get("accountSecurityCutoff");
    if (!(cutoffNode instanceof ObjectNode cutoff)) {
      throw invalid("accountSecurityCutoff", "must be an object for Account authority changes");
    }
    exactFields(cutoff, CUTOFF_FIELDS);
    exact(cutoff, "accountAuthorityGeneration", text(event, "accountAuthorityGeneration"));
    exact(cutoff, "outboxStreamKey", stream);
    exact(cutoff, "outboxSequence", text(event, "outboxSequence"));
  }

  private static void validateStringSet(JsonNode node, Set<String> allowed, String path) {
    if (node == null || !node.isArray() || node.isEmpty()) {
      throw invalid(path, "must be a nonempty required array");
    }
    String previous = null;
    for (JsonNode item : node) {
      if (!item.isTextual()
          || !allowed.contains(item.textValue())
          || (previous != null && previous.compareTo(item.textValue()) >= 0)) {
        throw invalid(path, "must be a sorted set of declared values");
      }
      previous = item.textValue();
    }
  }

  private static IssuerEvent issuerEvidence(ObjectNode event, String digest, String canonicalJson) {
    return new IssuerEvent(
        text(event, "eventId"),
        text(event, "requestId"),
        text(event, "outboxStreamKey"),
        text(event, "outboxSequence"),
        text(event, "issuerId"),
        text(event, "issuerAuthGeneration"),
        text(event, "sourceVersion"),
        text(event, "mutationKind"),
        digest,
        canonicalJson);
  }

  private static AccountEvent accountEvidence(
      ObjectNode event, String digest, String canonicalJson) {
    ObjectNode state = (ObjectNode) event.get("accountState");
    JsonNode cutoffNode = event.get("accountSecurityCutoff");
    if (!(cutoffNode instanceof ObjectNode cutoff)) {
      throw invalid("accountSecurityCutoff", "must be an object for Account authority changes");
    }
    List<String> mutationKinds = new ArrayList<>();
    event.get("mutationKinds").forEach(item -> mutationKinds.add(item.textValue()));
    List<String> loginModes = new ArrayList<>();
    state.get("loginAuthModes").forEach(item -> loginModes.add(item.textValue()));
    return new AccountEvent(
        text(event, "eventId"),
        text(event, "requestId"),
        text(event, "outboxStreamKey"),
        text(event, "outboxSequence"),
        text(event, "accountId"),
        text(event, "accountAuthorityGeneration"),
        text(event, "sourceVersion"),
        text(event, "issuanceFence"),
        text(event, "issuanceFenceSourceVersion"),
        mutationKinds,
        new AccountState(
            state.get("emailVerified").booleanValue(),
            loginModes,
            nullableText(state, "globalRole"),
            text(state, "lifecycleState")),
        new AccountSecurityCutoff(
            text(cutoff, "accountAuthorityGeneration"),
            text(cutoff, "outboxStreamKey"),
            text(cutoff, "outboxSequence")),
        digest,
        canonicalJson);
  }

  private static void exactFields(ObjectNode object, Set<String> expected) {
    if (!object.fieldNames().hasNext() && !expected.isEmpty()) {
      throw invalid("event", "required fields are absent");
    }
    for (String field : expected) {
      if (!object.has(field)) throw invalid(field, "is required");
    }
    object
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!expected.contains(field)) throw invalid(field, "is not declared by this schema");
            });
  }

  private static void exact(ObjectNode object, String field, String expected) {
    if (!expected.equals(text(object, field)))
      throw invalid(field, "does not match its exact scope");
  }

  private static String canonicalUuid(ObjectNode object, String field) {
    String value = text(object, field);
    if (!UUID_PATTERN.matcher(value).matches()) {
      throw invalid(field, "must be a canonical lowercase non-nil UUID");
    }
    UUID uuid = UUID.fromString(value);
    if (!uuid.toString().equals(value)
        || uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L) {
      throw invalid(field, "must be a canonical lowercase non-nil UUID");
    }
    return value;
  }

  private static long positiveDecimal(ObjectNode object, String field) {
    String value = text(object, field);
    if (!POSITIVE_DECIMAL_PATTERN.matcher(value).matches()
        || new BigInteger(value).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
      throw invalid(field, "must be a positive canonical decimal string in the owner range");
    }
    return Long.parseLong(value);
  }

  private static long nextVersionForSequence(long sequence) {
    try {
      return Math.addExact(sequence, 1L);
    } catch (ArithmeticException overflow) {
      throw invalid("outboxSequence", "has no representable initialized-source successor");
    }
  }

  private static String boundedText(ObjectNode object, String field, int maximum) {
    String value = text(object, field);
    if (value.isBlank() || value.length() > maximum || !value.equals(value.strip())) {
      throw invalid(field, "must be bounded, nonblank, and canonical text");
    }
    return value;
  }

  private static String text(ObjectNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) throw invalid(field, "must be a string");
    return value.textValue();
  }

  private static String nullableText(ObjectNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null) throw invalid(field, "is required");
    if (value.isNull()) return null;
    if (!value.isTextual()) throw invalid(field, "must be a string or null");
    return value.textValue();
  }

  private static String digest(ObjectNode preimage) {
    try {
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(preimage));
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder(64);
      for (byte value : hash) {
        hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
        hex.append(Character.forDigit(value & 0x0f, 16));
      }
      return "sha256:" + hex;
    } catch (IOException | NoSuchAlgorithmException failure) {
      throw new IllegalStateException("Unable to digest Account authority source event", failure);
    }
  }

  private static String canonicalJson(ObjectNode node) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(node)),
          StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalArgumentException("Account authority event cannot be serialized", failure);
    }
  }

  private static IllegalArgumentException invalid(String path, String detail) {
    return invalid(path, detail, null);
  }

  private static IllegalArgumentException invalid(String path, String detail, Throwable cause) {
    return new IllegalArgumentException(
        "Invalid Account authority source event " + path + ": " + detail, cause);
  }

  public sealed interface SourceEvent permits IssuerEvent, AccountEvent {
    String eventId();

    String requestId();

    String outboxStreamKey();

    String outboxSequence();

    String eventDigest();

    String canonicalJson();

    default byte[] canonicalJsonUtf8() {
      return canonicalJson().getBytes(StandardCharsets.UTF_8);
    }
  }

  public record IssuerPreimage(
      String eventId,
      String requestId,
      String outboxStreamKey,
      String outboxSequence,
      String issuerId,
      String issuerAuthGeneration,
      String sourceVersion,
      String mutationKind) {}

  public record AccountPreimage(
      String eventId,
      String requestId,
      String outboxStreamKey,
      String outboxSequence,
      String accountId,
      String accountAuthorityGeneration,
      String sourceVersion,
      String issuanceFence,
      String issuanceFenceSourceVersion,
      List<String> mutationKinds,
      AccountState accountState) {
    public AccountPreimage {
      mutationKinds = mutationKinds == null ? null : List.copyOf(mutationKinds);
      Objects.requireNonNull(accountState, "Account source state is required");
    }
  }

  public record AccountState(
      boolean emailVerified,
      List<String> loginAuthModes,
      String globalRole,
      String lifecycleState) {
    public AccountState {
      loginAuthModes = loginAuthModes == null ? null : List.copyOf(loginAuthModes);
    }
  }

  public record AccountSecurityCutoff(
      String accountAuthorityGeneration, String outboxStreamKey, String outboxSequence) {}

  public record IssuerEvent(
      String eventId,
      String requestId,
      String outboxStreamKey,
      String outboxSequence,
      String issuerId,
      String issuerAuthGeneration,
      String sourceVersion,
      String mutationKind,
      String eventDigest,
      String canonicalJson)
      implements SourceEvent {}

  public record AccountEvent(
      String eventId,
      String requestId,
      String outboxStreamKey,
      String outboxSequence,
      String accountId,
      String accountAuthorityGeneration,
      String sourceVersion,
      String issuanceFence,
      String issuanceFenceSourceVersion,
      List<String> mutationKinds,
      AccountState accountState,
      AccountSecurityCutoff accountSecurityCutoff,
      String eventDigest,
      String canonicalJson)
      implements SourceEvent {
    public AccountEvent {
      mutationKinds = List.copyOf(mutationKinds);
      Objects.requireNonNull(accountState);
      Objects.requireNonNull(accountSecurityCutoff);
    }
  }
}
