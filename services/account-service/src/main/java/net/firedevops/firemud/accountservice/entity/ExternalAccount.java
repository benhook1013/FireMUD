package net.firedevops.firemud.accountservice.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Data;

@Data
public class ExternalAccount {
  private Long id;
  private Account account;
  private Long tenantId;
  private String provider;
  private String externalId;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public Account getAccount() {
    return account;
  }

  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "JPA association stored directly")
  public void setAccount(Account account) {
    this.account = account;
  }
}
