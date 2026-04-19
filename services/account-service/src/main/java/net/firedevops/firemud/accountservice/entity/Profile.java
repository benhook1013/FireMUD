package net.firedevops.firemud.accountservice.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "profiles")
public class Profile {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id", nullable = false)
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private Account account;

  @Column(nullable = false)
  private Long tenantId;

  @Column(length = 100)
  private String displayName;

  @Column(length = 255)
  private String bio;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ProfilePresenceVisibilityPolicy presenceVisibilityPolicy =
      ProfilePresenceVisibilityPolicy.FRIENDS_ONLY;

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
