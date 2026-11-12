package net.firedevops.firemud.loggingadmin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CreateReportRequestTest {
  private static final String ACCOUNT = "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1";

  @Test
  void acceptsCanonicalReporterAndOptionalTarget() {
    assertThat(new CreateReportRequest(1L, ACCOUNT, null, "BUG", "detail").targetAccountId())
        .isNull();
    assertThat(new CreateReportRequest(1L, ACCOUNT, ACCOUNT, "BUG", "detail").reporterAccountId())
        .isEqualTo(ACCOUNT);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "1",
        "00000000-0000-0000-0000-000000000000",
        "B8D093F7-CB70-40ED-9FAC-3C82D4BF28F1",
        " b8d093f7-cb70-40ed-9fac-3c82d4bf28f1"
      })
  void rejectsNonCanonicalReporter(String reporter) {
    assertThatThrownBy(() -> new CreateReportRequest(1L, reporter, null, "BUG", "detail"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "1",
        "00000000-0000-0000-0000-000000000000",
        "B8D093F7-CB70-40ED-9FAC-3C82D4BF28F1"
      })
  void rejectsNonCanonicalPresentTarget(String target) {
    assertThatThrownBy(() -> new CreateReportRequest(1L, ACCOUNT, target, "BUG", "detail"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
