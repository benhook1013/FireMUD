package net.firedevops.firemud.gamesession.presentation;

import java.util.Objects;

/** Small normalized envelope for player-visible output before final rendering. */
public record PlayerOutput(
    PlayerOutputKind kind,
    PlayerOutputPayload payload,
    ReplayPolicy replayPolicy,
    BriefRenderPolicy briefRenderPolicy,
    boolean protocolBlock) {
  public PlayerOutput {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    Objects.requireNonNull(replayPolicy, "replayPolicy must not be null");
    Objects.requireNonNull(briefRenderPolicy, "briefRenderPolicy must not be null");
  }

  public static PlayerOutput message(String text) {
    return new PlayerOutput(
        PlayerOutputKind.MESSAGE,
        new TextMessageOutput(text),
        ReplayPolicy.BUFFERABLE,
        BriefRenderPolicy.DEFAULT,
        false);
  }

  public static PlayerOutput view(String text) {
    return new PlayerOutput(
        PlayerOutputKind.VIEW,
        new TextMessageOutput(text),
        ReplayPolicy.BUFFERABLE,
        BriefRenderPolicy.DEFAULT,
        false);
  }

  public static PlayerOutput protocolView(String protocolText) {
    return new PlayerOutput(
        PlayerOutputKind.VIEW,
        new TextMessageOutput(protocolText),
        ReplayPolicy.BUFFERABLE,
        BriefRenderPolicy.DEFAULT,
        true);
  }

  public static PlayerOutput view(LookViewOutput payload) {
    return new PlayerOutput(
        PlayerOutputKind.VIEW, payload, ReplayPolicy.BUFFERABLE, BriefRenderPolicy.DEFAULT, false);
  }

  public static PlayerOutput prompt(String text) {
    return new PlayerOutput(
        PlayerOutputKind.PROMPT,
        new PromptOutput(text),
        ReplayPolicy.NO_REPLAY,
        BriefRenderPolicy.ALWAYS_SHOW,
        false);
  }

  public static PlayerOutput notice(String text) {
    return new PlayerOutput(
        PlayerOutputKind.NOTICE,
        new NoticeOutput(text),
        ReplayPolicy.NO_REPLAY,
        BriefRenderPolicy.ALWAYS_SHOW,
        false);
  }

  public static PlayerOutput notice(String text, boolean protocolBlock) {
    return new PlayerOutput(
        PlayerOutputKind.NOTICE,
        new NoticeOutput(text),
        ReplayPolicy.NO_REPLAY,
        BriefRenderPolicy.ALWAYS_SHOW,
        protocolBlock);
  }

  public static PlayerOutput error(String code, String message) {
    return new PlayerOutput(
        PlayerOutputKind.ERROR,
        new ErrorOutput(code, message),
        ReplayPolicy.NO_REPLAY,
        BriefRenderPolicy.ALWAYS_SHOW,
        false);
  }

  public String text() {
    return switch (payload) {
      case TextMessageOutput message -> message.text();
      case PromptOutput prompt -> prompt.text();
      case NoticeOutput notice -> notice.text();
      case ErrorOutput error ->
          "ERROR "
              + error.code()
              + (error.message() == null || error.message().isBlank() ? "" : " " + error.message());
      case LookViewOutput ignored -> null;
      default -> null;
    };
  }

  public boolean screenBufferEligible() {
    return replayPolicy == ReplayPolicy.BUFFERABLE && kind != PlayerOutputKind.PROMPT;
  }
}
