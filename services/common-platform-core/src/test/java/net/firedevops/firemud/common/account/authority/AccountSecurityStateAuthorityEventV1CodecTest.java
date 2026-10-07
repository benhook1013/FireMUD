package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AccountSecurityStateAuthorityEventV1CodecTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REQUEST = "22222222-2222-4222-8222-222222222222";
  private static final String STREAM = "account:auth-authority:v1:account/" + ACCOUNT;
  private static final String DIGEST =
      "sha256:6e6b82b81006e447301262b666825022462f4298a8b3fc0d44ce4a73895b3374";
  private static final String CANONICAL_PREIMAGE =
      "{\"accountAuthorityGeneration\":\"2\",\"accountId\":\""
          + ACCOUNT
          + "\",\"accountSecurityCutoff\":{\"accountAuthorityGeneration\":\"2\","
          + "\"outboxSequence\":\"1\",\"outboxStreamKey\":\""
          + STREAM
          + "\"},\"accountState\":{\"emailVerified\":true,\"globalRoles\":[],"
          + "\"lifecycleState\":\"ACTIVE\",\"loginAuthModes\":[\"EMAIL_OTP\",\"PASSWORD\"]},"
          + "\"eventId\":\"account-security-state-event-v1:"
          + REQUEST
          + "\",\"eventType\":\"ACCOUNT_SECURITY_STATE_CHANGED\","
          + "\"mutationKinds\":[\"EMAIL_LOGIN_ELIGIBILITY_CHANGED\",\"LOGIN_AUTH_MODES_CHANGED\"],"
          + "\"outboxSequence\":\"1\",\"outboxStreamKey\":\""
          + STREAM
          + "\",\"requestId\":\""
          + REQUEST
          + "\",\"schemaVersion\":\"account-auth-account-security-state-event/v1\","
          + "\"sourceScope\":\"account/"
          + ACCOUNT
          + "\",\"sourceVersion\":\"2\"}";
  private static final String CANONICAL_WIRE =
      CANONICAL_PREIMAGE.replace("\"eventId\":", "\"eventDigest\":\"" + DIGEST + "\",\"eventId\":");

  @Test
  void fixedCombinedFamilyVectorSealsAndVerifiesExactCanonicalBytesAndHash() throws Exception {
    var sealed = seal(preimage());
    var verified = AccountSecurityStateAuthorityEventV1Codec.verify(CANONICAL_WIRE);
    assertThat(sealed.eventDigest()).isEqualTo(DIGEST);
    assertThat(sealed.canonicalJson()).isEqualTo(CANONICAL_WIRE);
    assertThat(verified.canonicalJsonUtf8())
        .isEqualTo(CANONICAL_WIRE.getBytes(StandardCharsets.UTF_8));
    assertThat(verified.accountId()).isEqualTo(ACCOUNT);
    assertThat(verified.requestId()).isEqualTo(REQUEST);
    assertThat(verified.sourceScope()).isEqualTo("account/" + ACCOUNT);
    assertThat(verified.accountState().globalRoles()).isEmpty();
    assertThat(verified.mutationKinds())
        .containsExactly("EMAIL_LOGIN_ELIGIBILITY_CHANGED", "LOGIN_AUTH_MODES_CHANGED");
    assertThat(verified.accountSecurityCutoff().outboxSequence()).isEqualTo("1");
  }

  @Test
  void positiveCountersBeyondLongArePreservedWithoutNumericConversion() throws Exception {
    ObjectNode value = preimage();
    String huge = "18446744073709551616000000000000000001";
    value.put("accountAuthorityGeneration", huge);
    value.put("sourceVersion", huge);
    value.put("outboxSequence", huge);
    cutoff(value).put("accountAuthorityGeneration", huge);
    cutoff(value).put("outboxSequence", huge);
    var sealed = seal(value);
    var verified = AccountSecurityStateAuthorityEventV1Codec.verify(sealed.canonicalJson());
    assertThat(verified.accountAuthorityGeneration()).isEqualTo(huge);
    assertThat(verified.sourceVersion()).isEqualTo(huge);
    assertThat(verified.outboxSequence()).isEqualTo(huge);
    assertThat(verified.accountSecurityCutoff().accountAuthorityGeneration()).isEqualTo(huge);
  }

  @Test
  void everyDeclaredRootAndNestedFieldIsRequiredAndBoundToOriginalDigest() throws Exception {
    ObjectNode wire = (ObjectNode) JSON.readTree(CANONICAL_WIRE);
    for (String section : List.of("", "accountState", "accountSecurityCutoff")) {
      ObjectNode sectionNode = section.isEmpty() ? wire : (ObjectNode) wire.get(section);
      List<String> fields = new ArrayList<>();
      sectionNode.fieldNames().forEachRemaining(fields::add);
      for (String field : fields) {
        ObjectNode missing = wire.deepCopy();
        node(missing, section).remove(field);
        reject(missing);
        ObjectNode changed = wire.deepCopy();
        node(changed, section).putNull(field);
        reject(changed);
      }
    }
  }

  @Test
  void supportedSemanticChangesRequireNewDigest() throws Exception {
    List<Consumer<ObjectNode>> changes =
        List.of(
            value -> {
              String request = "33333333-3333-4333-8333-333333333333";
              value.put("requestId", request);
              value.put("eventId", "account-security-state-event-v1:" + request);
            },
            value -> {
              String account = "44444444-4444-4444-8444-444444444444";
              String stream = "account:auth-authority:v1:account/" + account;
              value.put("accountId", account);
              value.put("sourceScope", "account/" + account);
              value.put("outboxStreamKey", stream);
              cutoff(value).put("outboxStreamKey", stream);
            },
            value -> {
              value.put("accountAuthorityGeneration", "3");
              cutoff(value).put("accountAuthorityGeneration", "3");
            },
            value -> value.put("sourceVersion", "3"),
            value -> {
              value.put("outboxSequence", "2");
              cutoff(value).put("outboxSequence", "2");
            },
            value -> state(value).put("emailVerified", false),
            value -> state(value).putArray("loginAuthModes").add("PASSWORD"),
            value -> state(value).putArray("globalRoles").add("platformAdmin"),
            value -> state(value).put("lifecycleState", "SECURITY_LOCKED"),
            value -> value.putArray("mutationKinds").add("GLOBAL_ROLE_CHANGED"));
    for (Consumer<ObjectNode> change : changes) {
      ObjectNode changed = preimage();
      change.accept(changed);
      var sealed = seal(changed);
      assertThat(sealed.eventDigest()).isNotEqualTo(DIGEST);
      changed.put("eventDigest", DIGEST);
      assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.verify(changed.toString()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("eventDigest");
    }
  }

  @Test
  void undeclaredFencesSecretsAndScalarRolesAreRejectedAtEveryObjectBoundary() throws Exception {
    ObjectNode wire = (ObjectNode) JSON.readTree(CANONICAL_WIRE);
    for (String section : List.of("", "accountState", "accountSecurityCutoff")) {
      for (String extra :
          List.of(
              "issuanceFence",
              "issuanceFenceSourceVersion",
              "authorityTuple",
              "passwordHash",
              "password",
              "globalRole",
              "unknown")) {
        ObjectNode changed = wire.deepCopy();
        node(changed, section).put(extra, "injected");
        reject(changed);
      }
    }
  }

  @Test
  void invalidCounterGrammarAndNumericAliasesAreRejected() throws Exception {
    for (String section : List.of("", "accountSecurityCutoff")) {
      List<String> fields =
          section.isEmpty()
              ? List.of("accountAuthorityGeneration", "sourceVersion", "outboxSequence")
              : List.of("accountAuthorityGeneration", "outboxSequence");
      for (String field : fields) {
        for (String invalid : List.of("", "0", "01", "+1", "-1", "1.0", "1e2", " 1", "1 ")) {
          ObjectNode changed = (ObjectNode) JSON.readTree(CANONICAL_WIRE);
          node(changed, section).put(field, invalid);
          reject(changed);
        }
        ObjectNode numeric = (ObjectNode) JSON.readTree(CANONICAL_WIRE);
        node(numeric, section).put(field, 1);
        reject(numeric);
      }
    }
  }

  @Test
  void unsupportedAndUnsortedDuplicateArraysAreRejectedBeforeSealing() throws Exception {
    for (String field : List.of("mutationKinds", "loginAuthModes", "globalRoles")) {
      String section = field.equals("mutationKinds") ? "" : "accountState";
      List<List<String>> invalid =
          switch (field) {
            case "mutationKinds" ->
                List.of(
                    List.of(),
                    List.of("PASSWORD_RESET"),
                    List.of("LOGOUT_ALL"),
                    List.of("GLOBAL_ROLE_CHANGED", "EMAIL_LOGIN_ELIGIBILITY_CHANGED"),
                    List.of("GLOBAL_ROLE_CHANGED", "GLOBAL_ROLE_CHANGED"));
            case "loginAuthModes" ->
                List.of(
                    List.of(),
                    List.of("password"),
                    List.of("PASSKEY"),
                    List.of("PASSWORD", "EMAIL_OTP"),
                    List.of("PASSWORD", "PASSWORD"));
            default ->
                List.of(
                    List.of("player"),
                    List.of("admin"),
                    List.of(""),
                    List.of("support", "platformAdmin"),
                    List.of("support", "support"));
          };
      for (List<String> values : invalid) {
        ObjectNode changed = preimage();
        var array = node(changed, section).putArray(field);
        values.forEach(array::add);
        assertThatThrownBy(() -> seal(changed)).isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void exactPolicyVocabularyAndCombinedFamilySetAreSupported() throws Exception {
    ObjectNode value = preimage();
    value
        .putArray("mutationKinds")
        .add("EMAIL_LOGIN_ELIGIBILITY_CHANGED")
        .add("GLOBAL_ROLE_CHANGED")
        .add("LIFECYCLE_STATE_CHANGED")
        .add("LOGIN_AUTH_MODES_CHANGED");
    state(value).putArray("globalRoles").add("billingAdmin").add("platformAdmin").add("support");
    for (String lifecycle :
        List.of("ACTIVE", "SECURITY_LOCKED", "DEACTIVATED_PENDING_DELETE", "DELETED")) {
      state(value).put("lifecycleState", lifecycle);
      assertThat(seal(value).mutationKinds()).hasSize(4);
    }
    for (String unsupported : List.of("active", "SUSPENDED", "")) {
      state(value).put("lifecycleState", unsupported);
      assertThatThrownBy(() -> seal(value)).isInstanceOf(IllegalArgumentException.class);
    }
    state(value).put("lifecycleState", "ACTIVE");
    state(value).put("emailVerified", "true");
    assertThatThrownBy(() -> seal(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void wrongIdentitiesScopeCutoffAndDiscriminatorsAreDenied() throws Exception {
    List<Consumer<ObjectNode>> changes =
        List.of(
            value -> value.put("schemaVersion", "account-auth-authority-source-event/v1"),
            value -> value.put("eventType", "PASSWORD_RESET_COMMITTED"),
            value -> value.put("eventId", "account-security-state-event-v1:other"),
            value -> value.put("requestId", "00000000-0000-0000-0000-000000000000"),
            value -> value.put("accountId", "11111111-1111-4111-8111-11111111111A"),
            value -> value.put("sourceScope", "issuer/other"),
            value -> value.put("outboxStreamKey", STREAM + "other"),
            value -> cutoff(value).put("outboxStreamKey", STREAM + "other"),
            value -> cutoff(value).put("outboxSequence", "2"),
            value -> cutoff(value).put("accountAuthorityGeneration", "3"));
    for (Consumer<ObjectNode> change : changes) {
      ObjectNode changed = preimage();
      change.accept(changed);
      assertThatThrownBy(() -> seal(changed)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void duplicatePropertiesTrailingTokensAndNonObjectsAreDenied() {
    for (String invalid :
        List.of(
            CANONICAL_WIRE.replace(
                "\"emailVerified\":true", "\"emailVerified\":true,\"emailVerified\":false"),
            CANONICAL_WIRE.replace(
                "\"sourceVersion\":\"2\"", "\"sourceVersion\":\"2\",\"sourceVersion\":\"2\""),
            CANONICAL_WIRE + " {}",
            "[]",
            "null")) {
      assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.verify(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void propertyReorderingCanonicalizesAndEvidenceDoesNotExposeMutableStorage() throws Exception {
    ObjectNode reordered = JSON.createObjectNode();
    ObjectNode original = (ObjectNode) JSON.readTree(CANONICAL_WIRE);
    List<String> fields = new ArrayList<>();
    original.fieldNames().forEachRemaining(fields::add);
    java.util.Collections.reverse(fields);
    fields.forEach(field -> reordered.set(field, original.get(field)));
    var evidence = AccountSecurityStateAuthorityEventV1Codec.verify(reordered.toString());
    assertThat(evidence.canonicalJson()).isEqualTo(CANONICAL_WIRE);
    byte[] bytes = evidence.canonicalJsonUtf8();
    bytes[0] = 0;
    assertThat(evidence.canonicalJsonUtf8())
        .isEqualTo(CANONICAL_WIRE.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> evidence.mutationKinds().add("GLOBAL_ROLE_CHANGED"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> evidence.accountState().loginAuthModes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> evidence.accountState().globalRoles().add("support"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static ObjectNode preimage() throws Exception {
    return (ObjectNode) JSON.readTree(CANONICAL_PREIMAGE);
  }

  private static ObjectNode node(ObjectNode root, String section) {
    return section.isEmpty() ? root : (ObjectNode) root.get(section);
  }

  private static ObjectNode state(ObjectNode root) {
    return node(root, "accountState");
  }

  private static ObjectNode cutoff(ObjectNode root) {
    return node(root, "accountSecurityCutoff");
  }

  private static AccountSecurityStateAuthorityEventV1Codec.AccountSecurityStateAuthorityEvent seal(
      ObjectNode value) {
    Map<String, Object> fields = JSON.convertValue(value, new TypeReference<>() {});
    return AccountSecurityStateAuthorityEventV1Codec.seal(fields);
  }

  private static void reject(ObjectNode value) {
    assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.verify(value.toString()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
