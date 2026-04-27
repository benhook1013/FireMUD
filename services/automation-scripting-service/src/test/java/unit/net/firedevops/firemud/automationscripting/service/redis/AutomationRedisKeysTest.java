package net.firedevops.firemud.automationscripting.service.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AutomationRedisKeysTest {
  @Test
  void queueKeysAreInstanceScoped() {
    assertThat(AutomationRedisKeys.automationQueue("tenant-1", "instance-1", "entity-1"))
        .isEqualTo("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1");
  }

  @Test
  void quotaKeysUseCanonicalAutomationPrefix() {
    assertThat(AutomationRedisKeys.automationQuota("tenant-1", "script-1"))
        .isEqualTo("automation:quota:tenant-1:script-1");
  }

  @Test
  void tenantBudgetKeysUseCanonicalAutomationPrefix() {
    assertThat(AutomationRedisKeys.automationTenantBudget("tenant-1", "normal"))
        .isEqualTo("automation:tenant-budget:tenant-1:tier:normal");
  }

  @Test
  void dryRunCapacityKeysUseCanonicalAutomationPrefix() {
    assertThat(AutomationRedisKeys.automationDryRunCapacityCounter("tenant-1"))
        .isEqualTo("automation:test:capacity:tenant-1:tenant");
    assertThat(AutomationRedisKeys.automationDryRunCapacityLease("tenant-1", "99"))
        .isEqualTo("automation:test:capacity:tenant-1:lease:99");
  }

  @Test
  void blankPartsAreRejected() {
    assertThatThrownBy(() -> AutomationRedisKeys.automationQueue("tenant-1", "", "entity-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gameInstanceId");
  }
}
