package net.firedevops.firemud.gamesession.presentation;

import java.util.Objects;

/** Small normalized envelope for player-visible output before final rendering. */
public record PlayerOutput(
    PlayerOutputKind kind,
    PlayerOutputPayload payload,
    ReplayPolicy replayPolicy,
    BriefRenderPolicy briefRenderPolicy) {
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
        BriefRenderPolicy.DEFAULT);
  }

  public static PlayerOutput message(String fallbackText, String messageKey) {
    return message(fallbackText, messageKey, java.util.Map.of());
  }

  public static PlayerOutput message(
      String fallbackText, String messageKey, java.util.Map<String, String> arguments) {
    return new PlayerOutput(
        PlayerOutputKind.MESSAGE,
        new TextMessageOutput(fallbackText, messageKey, arguments),
        ReplayPolicy.BUFFERABLE,
        BriefRenderPolicy.DEFAULT);
  }

  public static PlayerOutput view(LookViewOutput payload) {
    return new PlayerOutput(
        PlayerOutputKind.VIEW, payload, ReplayPolicy.BUFFERABLE, BriefRenderPolicy.DEFAULT);
  }

  public static PlayerOutput view(InventoryViewOutput payload) {
    return new PlayerOutput(
        PlayerOutputKind.VIEW, payload, ReplayPolicy.BUFFERABLE, BriefRenderPolicy.DEFAULT);
  }

  public static PlayerOutput view(WorldsViewOutput payload) {
    return new PlayerOutput(
        PlayerOutputKind.VIEW, payload, ReplayPolicy.NO_REPLAY, BriefRenderPolicy.DEFAULT);
  }

  public static PlayerOutput prompt(String text) {
    return prompt(text, java.util.List.of());
  }

  public static PlayerOutput prompt(String text, java.util.List<PromptField> fields) {
    return new PlayerOutput(
        PlayerOutputKind.PROMPT,
        new PromptOutput(text, fields),
        ReplayPolicy.NO_REPLAY,
        BriefRenderPolicy.ALWAYS_SHOW);
  }

  public static PlayerOutput notice(String text) {
    return new PlayerOutput(
        PlayerOutputKind.NOTICE,
        new NoticeOutput(text),
        ReplayPolicy.NO_REPLAY,
        BriefRenderPolicy.ALWAYS_SHOW);
  }

  public static PlayerOutput notice(
      String fallbackText, String messageKey, java.util.Map<String, String> arguments) {
    return new PlayerOutput(
        PlayerOutputKind.NOTICE,
        new NoticeOutput(fallbackText, messageKey, arguments),
        ReplayPolicy.NO_REPLAY,
        BriefRenderPolicy.ALWAYS_SHOW);
  }

  public static PlayerOutput error(String code, String message) {
    return new PlayerOutput(
        PlayerOutputKind.ERROR,
        new ErrorOutput(code, message),
        ReplayPolicy.NO_REPLAY,
        BriefRenderPolicy.ALWAYS_SHOW);
  }

  public static PlayerOutput error(
      String code,
      String fallbackMessage,
      String messageKey,
      java.util.Map<String, String> arguments) {
    return new PlayerOutput(
        PlayerOutputKind.ERROR,
        new ErrorOutput(code, fallbackMessage, messageKey, arguments),
        ReplayPolicy.NO_REPLAY,
        BriefRenderPolicy.ALWAYS_SHOW);
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
      case InventoryViewOutput ignored -> null;
      case WorldsViewOutput ignored -> null;
      default -> null;
    };
  }

  public boolean screenBufferEligible() {
    return replayPolicy == ReplayPolicy.BUFFERABLE && kind != PlayerOutputKind.PROMPT;
  }
}
