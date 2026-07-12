package net.firedevops.firemud.gamesession.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.gamesession.repository.ResumeTranscriptEntryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DurableResumeTranscriptExpiryJobTest {
  private final ResumeTranscriptEntryRepository repository =
      Mockito.mock(ResumeTranscriptEntryRepository.class);
  private final DurableResumeTranscriptExpiryJob job =
      new DurableResumeTranscriptExpiryJob(repository);

  @Test
  void drainsFullBatchesUpToTheConfiguredRunBound() {
    when(repository.deleteExpiredBefore(any(), eq(500))).thenReturn(500, 2);

    job.purgeExpiredTranscripts();

    verify(repository, org.mockito.Mockito.times(2)).deleteExpiredBefore(any(), eq(500));
  }
}
