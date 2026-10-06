package net.firedevops.firemud.common.redis.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RedisScriptDescriptorTest {
  private static final String VALID_DESCRIPTOR =
      """
      {
        "schemaVersion": 1,
        "id": "account-game-session-delegation-pending-register/v1",
        "owner": "account-service",
        "scriptResource": "lua/register-pending.lua",
        "sourceSha256": "%s",
        "redisRole": "coordination",
        "category": "account_auth_token_registry",
        "keys": [{"name":"tokenRecord","allowedPrefix":"session:auth:token:","requiredHashTag":""}],
        "arguments": ["recordUtf8", "absoluteExpiryEpochMillis"],
        "resetSensitivity": "requires_cluster_reset",
        "lossOutcomeClass": "session",
        "tailLossBehavior": "Missing registry projection denies token use until Account recovery.",
        "outcomes": {"1":"created", "0":"exact_retry", "-1":"conflict"}
      }
      """;

  @Test
  void parsesBoundedOwnerDescriptorAndPreservesExactInvocationContract() {
    RedisScriptDescriptor descriptor =
        RedisScriptDescriptor.parse(
            VALID_DESCRIPTOR.formatted("a".repeat(64)).getBytes(StandardCharsets.UTF_8));

    assertThat(descriptor.id()).isEqualTo("account-game-session-delegation-pending-register/v1");
    assertThat(descriptor.redisRole()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
    assertThat(descriptor.keys())
        .containsExactly(
            new RedisScriptDescriptor.KeyDeclaration("tokenRecord", "session:auth:token:", ""));
    assertThat(descriptor.arguments()).containsExactly("recordUtf8", "absoluteExpiryEpochMillis");
    assertThat(descriptor.outcomes()).containsEntry(1, "created").containsEntry(0, "exact_retry");
  }

  @Test
  void rejectsUnknownDuplicateNoncanonicalAndUnboundedDescriptorShapes() {
    String valid = VALID_DESCRIPTOR.formatted("b".repeat(64));
    assertThatThrownBy(
            () ->
                RedisScriptDescriptor.parse(
                    valid
                        .replace("\"outcomes\":", "\"unexpected\":true, \"outcomes\":")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RedisScriptDescriptor.parse(
                    valid
                        .replace(
                            "\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"schemaVersion\": 1,")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RedisScriptDescriptor.parse(
                    valid
                        .replace("coordination", "coordination-plus")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RedisScriptDescriptor.parse(
                    new byte[RedisScriptDescriptor.MAX_DESCRIPTOR_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void permitsEmptyHashTagButRejectsMissingNullAndMalformedNonemptyDeclarations() {
    String valid = VALID_DESCRIPTOR.formatted("c".repeat(64));
    assertThat(
            RedisScriptDescriptor.parse(valid.getBytes(StandardCharsets.UTF_8)).keys().getFirst())
        .isEqualTo(
            new RedisScriptDescriptor.KeyDeclaration("tokenRecord", "session:auth:token:", ""));

    assertInvalid(valid.replace("\"requiredHashTag\":\"\"", "\"requiredHashTag\":null"));
    assertInvalid(valid.replace(",\"requiredHashTag\":\"\"", ""));
    assertInvalid(valid.replace("\"requiredHashTag\":\"\"", "\"requiredHashTag\":\"not-a-tag\""));
  }

  private static void assertInvalid(String descriptor) {
    assertThatThrownBy(
            () -> RedisScriptDescriptor.parse(descriptor.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
