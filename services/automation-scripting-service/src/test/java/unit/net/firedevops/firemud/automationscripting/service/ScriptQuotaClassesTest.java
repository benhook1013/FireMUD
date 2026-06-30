package net.firedevops.firemud.automationscripting.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScriptQuotaClassesTest {

  @Test
  void normalizeDefaultsUnknownOrBlankQuotaClassesToStandardRuntime() {
    assertThat(ScriptQuotaClasses.normalize(null)).isEqualTo(ScriptQuotaClasses.STANDARD_RUNTIME);
    assertThat(ScriptQuotaClasses.normalize("  ")).isEqualTo(ScriptQuotaClasses.STANDARD_RUNTIME);
    assertThat(ScriptQuotaClasses.normalize("unexpected"))
        .isEqualTo(ScriptQuotaClasses.STANDARD_RUNTIME);
  }

  @Test
  void normalizeAcceptsCanonicalQuotaClassesAfterTrimming() {
    assertThat(ScriptQuotaClasses.normalize(" STANDARD_RUNTIME "))
        .isEqualTo(ScriptQuotaClasses.STANDARD_RUNTIME);
    assertThat(ScriptQuotaClasses.normalize(" PUBLISH_READINESS "))
        .isEqualTo(ScriptQuotaClasses.PUBLISH_READINESS);
  }

  @Test
  void quotaPredicatesFailClosedForUnknownValues() {
    assertThat(ScriptQuotaClasses.consumesLiveScriptQuota("unexpected")).isTrue();
    assertThat(ScriptQuotaClasses.consumesLiveTenantBudget("unexpected")).isTrue();
    assertThat(ScriptQuotaClasses.usesPublishReadinessCapacity("unexpected")).isFalse();
  }
}
