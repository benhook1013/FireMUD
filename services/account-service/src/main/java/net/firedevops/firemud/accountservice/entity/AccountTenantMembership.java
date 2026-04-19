package net.firedevops.firemud.accountservice.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "account_tenant_membership")
public class AccountTenantMembership {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id", nullable = false)
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private Account account;

  @Column(nullable = false)
  private Long tenantId;

  @Column(name = "gameplay_admission_allowed", nullable = false)
  private boolean gameplayAdmissionAllowed = true;

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
