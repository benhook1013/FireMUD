package net.firedevops.firemud.socialgroups.repository;

import net.firedevops.firemud.socialgroups.entity.AccountFriendLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for account-level friend links. */
@Repository
public interface AccountFriendLinkRepository extends JpaRepository<AccountFriendLink, Long> {}
