package net.firedevops.firemud.common.redis.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedisScriptCatalogTest {
  private static final RedisScriptDescriptor FIRST =
      descriptor("owner-one-script/v1", "owner-one", "first:");
  private static final RedisScriptDescriptor SECOND =
      descriptor("owner-two-script/v1", "owner-two", "second:");

  @Test
  void aggregatesOwnerContributionsByUniqueScriptIdentity() {
    RedisScriptCatalog catalog =
        RedisScriptCatalog.fromContributions(
            List.of(
                contribution("owner-one", List.of(FIRST)),
                contribution("owner-two", List.of(SECOND))));

    assertThat(catalog.descriptors()).containsExactlyInAnyOrder(FIRST, SECOND);
    assertThat(catalog.require(FIRST.id())).isEqualTo(FIRST);
    assertThatThrownBy(() -> catalog.require("missing/v1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not registered");
  }

  @Test
  void rejectsDuplicateIdsAndEmptyOwnerContributions() {
    assertThatThrownBy(
            () ->
                RedisScriptCatalog.fromContributions(
                    List.of(
                        contribution("owner-one", List.of(FIRST)),
                        contribution("owner-one", List.of(FIRST)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate");
    assertThatThrownBy(
            () ->
                RedisScriptCatalog.fromContributions(List.of(contribution("owner-one", List.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be empty");
    assertThatThrownBy(
            () ->
                RedisScriptCatalog.fromContributions(
                    List.of(contribution("different-owner", List.of(FIRST)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner does not match");
  }

  private static RedisScriptContribution contribution(
      String owner, List<RedisScriptDescriptor> descriptors) {
    return new RedisScriptContribution() {
      @Override
      public String ownerId() {
        return owner;
      }

      @Override
      public List<RedisScriptDescriptor> descriptors() {
        return descriptors;
      }
    };
  }

  private static RedisScriptDescriptor descriptor(String id, String owner, String prefix) {
    return new RedisScriptDescriptor(
        id,
        owner,
        "lua/" + owner + ".lua",
        "a".repeat(64),
        RedisScriptDescriptor.RedisRole.COORDINATION,
        "account_auth_token_registry",
        List.of(new RedisScriptDescriptor.KeyDeclaration("token", prefix, "")),
        List.of("record"),
        "requires_cluster_reset",
        "session",
        "Missing registry projection denies token use until owner recovery.",
        java.util.Map.of(1, "created"));
  }
}
