package net.firedevops.firemud.socialgroups.repository;

import net.firedevops.firemud.socialgroups.entity.FriendLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FriendLinkRepository extends JpaRepository<FriendLink, Long> {}
