package net.firedevops.firemud.accountservice.service.session;

import java.util.Objects;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;

/**
 * Unregistered Account producer for a distinct selected-publication order. Authenticates the
 * current initial creator through the real issuance owner and retains the immutable Draft selection
 * before World freezes it. World capture and correlation are a separate later boundary. No remote
 * calls, terminalization, or activation occur here.
 */
public final class AccountPublicationAuthorizationService {
  private final AccountControlUiActorService actors;
  private final DraftAuthorizationFenceRepository fences;
  private final AccountPublicationAuthorizationRepository publications;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Internal Account owner transaction collaborators.")
  public AccountPublicationAuthorizationService(
      AccountControlUiActorService actors,
      DraftAuthorizationFenceRepository fences,
      AccountPublicationAuthorizationRepository publications) {
    this.actors = Objects.requireNonNull(actors);
    this.fences = Objects.requireNonNull(fences);
    this.publications = Objects.requireNonNull(publications);
  }

  public AccountPublicationAuthorizationBinding authorize(
      String compactJwt,
      AuthoredDraftPublishSelectionBinding selection,
      CapturedEnvironmentBoundary environment) {
    Objects.requireNonNull(selection);
    return actors.withCurrent(
        compactJwt,
        selection.intent().canonicalTenantId(),
        environment,
        current -> {
          fences.lockProducerSourcesNowait(current.source().sources());
          return publications.authorize(
              new AccountPublicationAuthorizationBinding.PreallocationInput(
                  current.stored().accountId, selection),
              current,
              () -> fences.requirePublicationAdmission(current.source().sources()));
        });
  }
}
