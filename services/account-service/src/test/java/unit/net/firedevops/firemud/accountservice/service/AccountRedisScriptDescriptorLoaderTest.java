package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import org.junit.jupiter.api.Test;

class AccountRedisScriptDescriptorLoaderTest {
  @Test
  void sixOwnerResourcesUseCanonicalClosedMetadata() {
    var descriptors = new java.util.ArrayList<RedisScriptDescriptor>();
    descriptors.addAll(new AccountGenerationProjectionRedisScriptContribution().descriptors());
    descriptors.addAll(new AccountGameplayDelegationRedisScriptContribution().descriptors());

    assertThat(descriptors).hasSize(6);
    assertThat(descriptors).extracting(RedisScriptDescriptor::scriptId).doesNotHaveDuplicates();
    for (var descriptor : descriptors) {
      assertThat(descriptor.owner()).isEqualTo("account-service");
      assertThat(descriptor.principal()).isEqualTo("account_coord_app");
      assertThat(descriptor.role()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
      assertThat(descriptor.category()).isEqualTo(RedisScriptDescriptor.ScriptCategory.SESSION_CAS);
      assertThat(descriptor.resetSensitivity())
          .isEqualTo(RedisScriptDescriptor.ResetSensitivity.CLUSTER);
      assertThat(descriptor.compatibilityLevel())
          .isEqualTo(RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET);
      assertThat(descriptor.lossClass())
          .isEqualTo(RedisScriptDescriptor.LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP);
      assertThat(descriptor.supportedCoexistence())
          .isEqualTo(List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "v1")));
      assertThat(descriptor.legacyPayloadShape()).isNull();
      assertThat(descriptor.keys()).hasSize(1);
      assertThat(descriptor.keys().getFirst().hashTagDeclaration())
          .isEqualTo(RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED);
    }
  }

  @Test
  void deniesUnknownDuplicateMissingAndMalformedFields() throws Exception {
    String source;
    try (var input =
        getClass().getResourceAsStream("/redis/scripts/account-generation-projection-cas.json")) {
      source =
          new String(
              java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThatThrownBy(() -> parse(source.replaceFirst("\\{", "{\"unknown\":true,")))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> parse(source.replaceFirst("\\{", "{\"scriptId\":\"duplicate.v1\",")))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> parse(source.replace("\"principal\": \"account_coord_app\",", "")))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> parse(source.replace("\"CLUSTER\"", "\"UNSUPPORTED\"")))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> parse(source.replace("\"expectedMode\"", "7")))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> parse(source + "{}")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> parse("{")).isInstanceOf(RuntimeException.class);
  }

  @Test
  void deniesEmptyAndOversizeInputBeforeParsing() {
    assertThatThrownBy(() -> AccountRedisScriptDescriptorLoader.parse(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountRedisScriptDescriptorLoader.parse(
                    new byte[AccountRedisScriptDescriptorLoader.MAX_DESCRIPTOR_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RedisScriptDescriptor parse(String source) {
    return AccountRedisScriptDescriptorLoader.parse(source.getBytes(StandardCharsets.UTF_8));
  }
}
