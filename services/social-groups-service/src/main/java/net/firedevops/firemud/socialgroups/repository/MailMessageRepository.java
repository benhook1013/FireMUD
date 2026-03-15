package net.firedevops.firemud.socialgroups.repository;

import net.firedevops.firemud.socialgroups.entity.MailMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MailMessageRepository extends JpaRepository<MailMessage, Long> {}
