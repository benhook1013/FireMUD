package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.util.StringUtils;

/** Parsed representation of a single text command line. */
public record TextCommand(
    String commandId,
    TextCommandType type,
    List<String> args,
    String rawLine,
    String aliasUsed,
    TextCommandPayload payload) {
  public TextCommand {
    Objects.requireNonNull(commandId, "commandId must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(args, "args must not be null");
    Objects.requireNonNull(rawLine, "rawLine must not be null");
    payload = payload == null ? TextCommandPayload.fromLegacy(type, args) : payload;
    args = List.copyOf(args);
  }

  public TextCommand(TextCommandType type, List<String> args, String rawLine) {
    this(
        defaultCommandId(type),
        type,
        args,
        rawLine,
        extractAlias(rawLine),
        TextCommandPayload.fromLegacy(type, args));
  }

  public TextCommand(
      TextCommandType type,
      List<String> args,
      String rawLine,
      String aliasUsed,
      TextCommandPayload payload) {
    this(defaultCommandId(type), type, args, rawLine, aliasUsed, payload);
  }

  public Optional<TextCommandPayload.Credentials> credentialsPayload() {
    return payload instanceof TextCommandPayload.Credentials credentials
        ? Optional.of(credentials)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.RealmBrowseRequest> realmBrowsePayload() {
    return payload instanceof TextCommandPayload.RealmBrowseRequest browseRequest
        ? Optional.of(browseRequest)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.CharacterBrowseRequest> characterBrowsePayload() {
    return payload instanceof TextCommandPayload.CharacterBrowseRequest browseRequest
        ? Optional.of(browseRequest)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.PlayRequest> playRequestPayload() {
    return payload instanceof TextCommandPayload.PlayRequest playRequest
        ? Optional.of(playRequest)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.ItemReference> itemReferencePayload() {
    return payload instanceof TextCommandPayload.ItemReference itemReference
        ? Optional.of(itemReference)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.ContainerTransfer> containerTransferPayload() {
    return payload instanceof TextCommandPayload.ContainerTransfer transfer
        ? Optional.of(transfer)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.ContainerView> containerViewPayload() {
    return payload instanceof TextCommandPayload.ContainerView containerView
        ? Optional.of(containerView)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.Message> messagePayload() {
    return payload instanceof TextCommandPayload.Message message
        ? Optional.of(message)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.TargetedMessage> targetedMessagePayload() {
    return payload instanceof TextCommandPayload.TargetedMessage targetedMessage
        ? Optional.of(targetedMessage)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.Directional> directionalPayload() {
    return payload instanceof TextCommandPayload.Directional directional
        ? Optional.of(directional)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.ViewRequest> viewRequestPayload() {
    return payload instanceof TextCommandPayload.ViewRequest viewRequest
        ? Optional.of(viewRequest)
        : Optional.empty();
  }

  public Optional<TextCommandPayload.AuthoredActionInvocation> authoredActionPayload() {
    return payload instanceof TextCommandPayload.AuthoredActionInvocation invocation
        ? Optional.of(invocation)
        : Optional.empty();
  }

  private static String extractAlias(String rawLine) {
    if (!StringUtils.hasText(rawLine)) {
      return "";
    }
    String trimmed = rawLine.trim();
    int firstSpace = trimmed.indexOf(' ');
    return firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
  }

  private static String defaultCommandId(TextCommandType type) {
    return type.name().toLowerCase(java.util.Locale.ROOT);
  }
}
