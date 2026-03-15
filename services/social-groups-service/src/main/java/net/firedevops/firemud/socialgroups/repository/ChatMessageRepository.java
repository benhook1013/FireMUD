package net.firedevops.firemud.socialgroups.repository;

import net.firedevops.firemud.socialgroups.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {}
