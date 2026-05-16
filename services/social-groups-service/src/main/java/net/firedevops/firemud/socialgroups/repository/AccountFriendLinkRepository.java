package net.firedevops.firemud.socialgroups.repository;

import java.util.List;
import net.firedevops.firemud.socialgroups.entity.AccountFriendLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for account-level friend links. */
@Repository
public interface AccountFriendLinkRepository extends JpaRepository<AccountFriendLink, Long> {
  List<AccountFriendLink> findByTenantIdAndAccountIdAndStatus(
      Long tenantId, Long accountId, String status);

  java.util.Optional<AccountFriendLink> findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
      Long tenantId, Long accountId, Long friendAccountId, String status);
}
