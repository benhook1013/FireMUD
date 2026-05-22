package net.firedevops.firemud.accountservice.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CurrencyBalance {
  private Long id;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private Account account;

  private String currencyCode;
  private Long balance;
  private Long tenantId;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Account reference is managed by JPA and intentionally exposed")
  public Account getAccount() {
    return account;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Account reference is managed by JPA and intentionally stored")
  public void setAccount(Account account) {
    this.account = account;
  }
}
