package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import net.firedevops.firemud.common.security.JwtClaims;

public record CreateReportRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @NotBlank String reporterAccountId,
    String targetAccountId,
    @NotNull @Size(max = 20) String type,
    @NotNull @Size(max = 255) String description) {
  public CreateReportRequest {
    reporterAccountId = JwtClaims.requireAccountId(reporterAccountId, "reporterAccountId");
    if (targetAccountId != null) {
      targetAccountId = JwtClaims.requireAccountId(targetAccountId, "targetAccountId");
    }
  }
}
