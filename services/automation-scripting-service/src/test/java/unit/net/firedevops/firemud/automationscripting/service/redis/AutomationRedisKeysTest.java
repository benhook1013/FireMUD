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
        .isEqualTo("automation:test:capacity:{tenant-1}:tenant");
    assertThat(AutomationRedisKeys.automationDryRunCapacityLease("tenant-1", "99"))
        .isEqualTo("automation:test:capacity:{tenant-1}:lease:99");
    assertThat(AutomationRedisKeys.automationDryRunClusterCapacityCounter())
        .isEqualTo("automation:test:capacity:{automation-test-capacity}:cluster");
    assertThat(AutomationRedisKeys.automationDryRunClusterCapacityLease("tenant-1", "99"))
        .isEqualTo("automation:test:capacity:{automation-test-capacity}:cluster:lease:tenant-1:99");
    assertThat(hashTag(AutomationRedisKeys.automationDryRunCapacityCounter("tenant-1")))
        .isEqualTo(hashTag(AutomationRedisKeys.automationDryRunCapacityLease("tenant-1", "99")));
    assertThat(hashTag(AutomationRedisKeys.automationDryRunClusterCapacityCounter()))
        .isEqualTo(
            hashTag(AutomationRedisKeys.automationDryRunClusterCapacityLease("tenant-1", "99")));
    assertThat(AutomationRedisKeys.automationReadinessCapacityCounter("tenant-1"))
        .isEqualTo("automation:readiness:capacity:{tenant-1}:tenant");
    assertThat(AutomationRedisKeys.automationReadinessCapacityLease("tenant-1", "99"))
        .isEqualTo("automation:readiness:capacity:{tenant-1}:lease:99");
    assertThat(AutomationRedisKeys.automationReadinessClusterCapacityCounter())
        .isEqualTo("automation:readiness:capacity:{automation-readiness-capacity}:cluster");
    assertThat(AutomationRedisKeys.automationReadinessClusterCapacityLease("tenant-1", "99"))
        .isEqualTo(
            "automation:readiness:capacity:{automation-readiness-capacity}:cluster:lease:tenant-1:99");
    assertThat(hashTag(AutomationRedisKeys.automationReadinessCapacityCounter("tenant-1")))
        .isEqualTo(hashTag(AutomationRedisKeys.automationReadinessCapacityLease("tenant-1", "99")));
    assertThat(hashTag(AutomationRedisKeys.automationReadinessClusterCapacityCounter()))
        .isEqualTo(
            hashTag(AutomationRedisKeys.automationReadinessClusterCapacityLease("tenant-1", "99")));
  }

  @Test
  void blankPartsAreRejected() {
    assertThatThrownBy(() -> AutomationRedisKeys.automationQueue("tenant-1", "", "entity-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gameInstanceId");
  }

  private static String hashTag(String key) {
    int start = key.indexOf('{');
    int end = key.indexOf('}', start);
    return key.substring(start, end + 1);
  }
}
