package net.firedevops.firemud.gamedesign.repository;

import net.firedevops.firemud.gamedesign.entity.PublishAttemptParticipantDigest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublishAttemptParticipantDigestRepository
    extends JpaRepository<PublishAttemptParticipantDigest, Long> {
  void deleteByPublishAttemptId(Long publishAttemptId);
}
