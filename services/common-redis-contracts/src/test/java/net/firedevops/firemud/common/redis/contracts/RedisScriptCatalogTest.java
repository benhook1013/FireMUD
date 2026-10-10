package net.firedevops.firemud.common.redis.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisScriptCatalogTest {
  @Test
  void aggregatesContributionsAndRequiresExactScriptIdentity() {
    RedisScriptDescriptor account = descriptor("account.control-ui.v1", "account-service");
    RedisScriptDescriptor session = descriptor("session.tick-lock.v1", "game-session-service");
    RedisScriptCatalog catalog =
        RedisScriptCatalog.fromContributions(
            List.of(
                contribution("account-service", List.of(account)),
                contribution("game-session-service", List.of(session))));

    assertThat(catalog.require("account.control-ui.v1")).isSameAs(account);
    assertThat(catalog.descriptors()).containsExactlyInAnyOrder(account, session);
    assertThatThrownBy(() -> catalog.require("account.control-ui.v2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not registered");
  }

  @Test
  void rejectsDuplicateScriptIdentityAndContributionOwnerMismatch() {
    RedisScriptDescriptor first = descriptor("account.control-ui.v1", "account-service");
    RedisScriptDescriptor duplicate = descriptor("account.control-ui.v1", "account-service");
    assertThatThrownBy(
            () ->
                RedisScriptCatalog.fromContributions(
                    List.of(
                        contribution("account-service", List.of(first)),
                        contribution("account-service", List.of(duplicate)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate Redis script descriptor identity");

    assertThatThrownBy(
            () ->
                RedisScriptCatalog.fromContributions(
                    List.of(contribution("game-session-service", List.of(first)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner does not match contribution");
  }

  @Test
  void rejectsNullAndEmptyContributionsAndNullDescriptors() {
    assertThatThrownBy(() -> RedisScriptCatalog.fromContributions(null))
        .isInstanceOf(NullPointerException.class);
    assertThat(RedisScriptCatalog.fromContributions(List.of()).descriptors()).isEmpty();
    assertThatThrownBy(
            () ->
                RedisScriptCatalog.fromContributions(Arrays.asList((RedisScriptContribution) null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                RedisScriptCatalog.fromContributions(
                    List.of(contribution("account-service", List.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be empty");
    assertThatThrownBy(
            () ->
                RedisScriptCatalog.fromContributions(
                    List.of(
                        contribution(
                            "account-service", Arrays.asList((RedisScriptDescriptor) null)))))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                RedisScriptCatalog.fromContributions(
                    List.of(contribution("account-service", null))))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsContributionAndDescriptorCountOverflow() {
    List<RedisScriptContribution> tooManyContributions = new ArrayList<>();
    for (int index = 0; index <= RedisScriptCatalog.MAX_CONTRIBUTIONS; index++) {
      tooManyContributions.add(
          contribution(
              "account-service",
              List.of(descriptor("account.script-" + index + ".v1", "account-service"))));
    }
    assertThatThrownBy(() -> RedisScriptCatalog.fromContributions(tooManyContributions))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contribution count exceeds its bound");

    List<RedisScriptDescriptor> tooManyDescriptors = new ArrayList<>();
    for (int index = 0; index <= RedisScriptCatalog.MAX_DESCRIPTORS; index++) {
      tooManyDescriptors.add(descriptor("account.script-" + index + ".v1", "account-service"));
    }
    assertThatThrownBy(
            () ->
                RedisScriptCatalog.fromContributions(
                    List.of(contribution("account-service", tooManyDescriptors))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("descriptor count exceeds its bound");
  }

  private static RedisScriptContribution contribution(
      String owner, Collection<RedisScriptDescriptor> descriptors) {
    return new RedisScriptContribution() {
      @Override
      public String ownerId() {
        return owner;
      }

      @Override
      public Collection<RedisScriptDescriptor> descriptors() {
        return descriptors == null ? null : new ArrayList<>(descriptors);
      }
    };
  }

  private static RedisScriptDescriptor descriptor(String scriptId, String owner) {
    return new RedisScriptDescriptor(
        scriptId,
        "redis/" + owner + "/script.lua",
        "a".repeat(64),
        owner,
        "account_coord_app",
        RedisScriptDescriptor.RedisRole.COORDINATION,
        List.of(
            new RedisScriptDescriptor.KeySpec(
                "record",
                "session:control:",
                RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
        List.of(new RedisScriptDescriptor.ArgumentSpec("requestId")),
        RedisScriptDescriptor.ScriptCategory.SESSION_CAS,
        List.of(
            new RedisScriptDescriptor.OutcomeSpec(
                "OK",
                RedisScriptDescriptor.OutcomeCategory.SUCCESS,
                RedisScriptDescriptor.MutationEffect.MUTATING)),
        RedisScriptDescriptor.ResetSensitivity.CLUSTER,
        RedisScriptDescriptor.LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP,
        "A lost projection denies the operation until owner recovery.",
        RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET,
        List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "v1")),
        null);
  }
}
