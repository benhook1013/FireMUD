package net.firedevops.firemud.socialgroups.repository;

import net.firedevops.firemud.socialgroups.entity.GuildStorageItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GuildStorageItemRepository extends JpaRepository<GuildStorageItem, Long> {}
