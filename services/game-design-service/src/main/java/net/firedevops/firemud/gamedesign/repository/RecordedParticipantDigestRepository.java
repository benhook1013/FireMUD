package net.firedevops.firemud.gamedesign.repository;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.RecordedParticipantDigest;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RecordedParticipantDigestRepository
    extends JpaRepository<RecordedParticipantDigest, Long> {
  Optional<RecordedParticipantDigest>
      findByTenantIdAndPublishTypeAndParticipantKeyAndAppliedCommitId(
          String tenantId,
          PublishType publishType,
          PublishParticipantKey participantKey,
          String appliedCommitId);
}
