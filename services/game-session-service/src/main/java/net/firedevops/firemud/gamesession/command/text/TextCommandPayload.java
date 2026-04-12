package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import org.springframework.util.StringUtils;

/** Small stable payload shapes for the first normalized text-command envelope rollout. */
public sealed interface TextCommandPayload
    permits TextCommandPayload.None,
        TextCommandPayload.Tokens,
        TextCommandPayload.Credentials,
        TextCommandPayload.RealmBrowseRequest,
        TextCommandPayload.CharacterBrowseRequest,
        TextCommandPayload.PlayRequest,
        TextCommandPayload.AfkRequest,
        TextCommandPayload.ItemReference,
        TextCommandPayload.ContainerTransfer,
        TextCommandPayload.ContainerView,
        TextCommandPayload.Message,
        TextCommandPayload.TargetedMessage,
        TextCommandPayload.Directional,
        TextCommandPayload.ViewRequest {

  record None() implements TextCommandPayload {}

  record Tokens(List<String> values) implements TextCommandPayload {
    public Tokens {
      values = List.copyOf(values == null ? List.of() : values);
    }
  }

  record Credentials(String loginName, String password, String otp) implements TextCommandPayload {}

  record RealmBrowseRequest(String worldSelector) implements TextCommandPayload {}

  record CharacterBrowseRequest(String worldSelector, String realmSelector)
      implements TextCommandPayload {}

  record PlayRequest(String worldSelector, String realmSelector, String characterSelector)
      implements TextCommandPayload {}

  record AfkRequest(Boolean enabled) implements TextCommandPayload {}

  record ItemReference(String reference, int quantity) implements TextCommandPayload {}

  record ContainerTransfer(String itemReference, int quantity, String containerReference)
      implements TextCommandPayload {}

  record ContainerView(String containerReference) implements TextCommandPayload {}

  record Message(String text) implements TextCommandPayload {}

  record TargetedMessage(String target, String message) implements TextCommandPayload {}

  record Directional(String direction) implements TextCommandPayload {}

  record ViewRequest(String viewName) implements TextCommandPayload {}

  static TextCommandPayload fromLegacy(TextCommandType type, List<String> args) {
    List<String> safeArgs = args == null ? List.of() : List.copyOf(args);
    return switch (type) {
      case NOOP, LOGOUT -> new None();
      case AFK -> new AfkRequest(true);
      case WORLDS, LOOK, QUICKLOOK, WHO, INVENTORY, EQUIPMENT -> new ViewRequest(type.name());
      case REALMS ->
          parseRealmBrowseRequest(safeArgs)
              .<TextCommandPayload>map(request -> request)
              .orElseGet(() -> safeArgs.isEmpty() ? new None() : new Tokens(safeArgs));
      case CHARS ->
          parseCharacterBrowseRequest(safeArgs)
              .<TextCommandPayload>map(request -> request)
              .orElseGet(() -> safeArgs.isEmpty() ? new None() : new Tokens(safeArgs));
      case HELP -> safeArgs.isEmpty() ? new None() : new Tokens(safeArgs);
      case GET, DROP ->
          parseQuantityAwareItemReference(safeArgs)
              .map(itemReference -> (TextCommandPayload) itemReference)
              .orElseGet(() -> safeArgs.isEmpty() ? new None() : new Tokens(safeArgs));
      case PUT, TAKE ->
          parseContainerTransfer(type, safeArgs)
              .map(transfer -> (TextCommandPayload) transfer)
              .orElseGet(() -> safeArgs.isEmpty() ? new None() : new Tokens(safeArgs));
      case CONTAINER ->
          parseContainerView(safeArgs)
              .map(view -> (TextCommandPayload) view)
              .orElseGet(() -> safeArgs.isEmpty() ? new None() : new Tokens(safeArgs));
      case WEAR, REMOVE ->
          parseQuantityAwareItemReference(safeArgs)
              .map(itemReference -> (TextCommandPayload) itemReference)
              .orElseGet(() -> safeArgs.isEmpty() ? new None() : new Tokens(safeArgs));
      case LOGIN ->
          safeArgs.size() >= 2
              ? new Credentials(
                  safeArgs.get(0), safeArgs.get(1), safeArgs.size() > 2 ? safeArgs.get(2) : "")
              : new Tokens(safeArgs);
      case PLAY ->
          parsePlayRequest(safeArgs)
              .<TextCommandPayload>map(request -> request)
              .orElseGet(() -> new Tokens(safeArgs));
      case SAY ->
          !safeArgs.isEmpty() && StringUtils.hasText(safeArgs.get(0))
              ? new Message(safeArgs.get(0))
              : new Tokens(safeArgs);
      case WHISPER, TELL ->
          safeArgs.size() >= 2
              ? new TargetedMessage(safeArgs.get(0), safeArgs.get(1))
              : new Tokens(safeArgs);
      case MOVE ->
          !safeArgs.isEmpty() && StringUtils.hasText(safeArgs.get(0))
              ? new Directional(safeArgs.get(0))
              : new Tokens(safeArgs);
      case UNKNOWN -> new Tokens(safeArgs);
    };
  }

  private static java.util.Optional<ItemReference> parseQuantityAwareItemReference(
      List<String> args) {
    if (args == null || args.isEmpty()) {
      return java.util.Optional.empty();
    }
    if (args.size() == 1) {
      String single = args.get(0) == null ? "" : args.get(0).trim();
      if (single.matches("-?\\d+")) {
        return java.util.Optional.empty();
      }
      return java.util.Optional.of(new ItemReference(single, 1));
    }
    String first = args.get(0) == null ? "" : args.get(0).trim();
    if (!first.matches("-?\\d+")) {
      return java.util.Optional.of(new ItemReference(String.join(" ", args).trim(), 1));
    }
    String reference = String.join(" ", args.subList(1, args.size())).trim();
    if (!StringUtils.hasText(reference)) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(new ItemReference(reference, Integer.parseInt(first)));
  }

  private static java.util.Optional<ContainerTransfer> parseContainerTransfer(
      TextCommandType type, List<String> args) {
    if (args == null || args.size() < 3) {
      return java.util.Optional.empty();
    }
    String preposition = type == TextCommandType.PUT ? "INTO" : "FROM";
    int prepositionIndex = indexOfIgnoreCase(args, preposition);
    if (prepositionIndex <= 0 || prepositionIndex >= args.size() - 1) {
      return java.util.Optional.empty();
    }
    List<String> itemTokens = args.subList(0, prepositionIndex);
    List<String> containerTokens = args.subList(prepositionIndex + 1, args.size());
    java.util.Optional<ItemReference> itemReference = parseQuantityAwareItemReference(itemTokens);
    if (itemReference.isEmpty()) {
      return java.util.Optional.empty();
    }
    String containerReference = String.join(" ", containerTokens).trim();
    if (!StringUtils.hasText(containerReference)) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        new ContainerTransfer(
            itemReference.orElseThrow().reference(),
            itemReference.orElseThrow().quantity(),
            containerReference));
  }

  private static java.util.Optional<ContainerView> parseContainerView(List<String> args) {
    if (args == null || args.isEmpty()) {
      return java.util.Optional.empty();
    }
    String containerReference = String.join(" ", args).trim();
    if (!StringUtils.hasText(containerReference)) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(new ContainerView(containerReference));
  }

  private static java.util.Optional<PlayRequest> parsePlayRequest(List<String> args) {
    if (args == null || args.isEmpty()) {
      return java.util.Optional.empty();
    }
    String worldSelector = normalizeSelectorToken(args.get(0));
    if (!StringUtils.hasText(worldSelector)) {
      return java.util.Optional.empty();
    }
    String realmSelector = args.size() > 1 ? normalizeSelectorToken(args.get(1)) : null;
    String characterSelector =
        args.size() > 2 ? String.join(" ", args.subList(2, args.size())).trim() : null;
    if (characterSelector != null && characterSelector.isBlank()) {
      characterSelector = null;
    }
    return java.util.Optional.of(new PlayRequest(worldSelector, realmSelector, characterSelector));
  }

  private static java.util.Optional<RealmBrowseRequest> parseRealmBrowseRequest(List<String> args) {
    if (args == null || args.isEmpty()) {
      return java.util.Optional.empty();
    }
    String worldSelector = normalizeSelectorToken(args.get(0));
    if (!StringUtils.hasText(worldSelector)) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(new RealmBrowseRequest(worldSelector));
  }

  private static java.util.Optional<CharacterBrowseRequest> parseCharacterBrowseRequest(
      List<String> args) {
    if (args == null || args.isEmpty()) {
      return java.util.Optional.empty();
    }
    String worldSelector = normalizeSelectorToken(args.get(0));
    if (!StringUtils.hasText(worldSelector)) {
      return java.util.Optional.empty();
    }
    String realmSelector = args.size() > 1 ? normalizeSelectorToken(args.get(1)) : null;
    return java.util.Optional.of(new CharacterBrowseRequest(worldSelector, realmSelector));
  }

  private static String normalizeSelectorToken(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static int indexOfIgnoreCase(List<String> args, String token) {
    if (args == null || args.isEmpty()) {
      return -1;
    }
    for (int i = 0; i < args.size(); i++) {
      String value = args.get(i);
      if (value != null && value.equalsIgnoreCase(token)) {
        return i;
      }
    }
    return -1;
  }
}
