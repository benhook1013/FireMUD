package net.firedevops.firemud.gamesession.command.text;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class TextCommandDispatcher {
  private final Map<TextCommandDispatchGroup, TextCommandDispatchHandler> handlers;

  TextCommandDispatcher(List<TextCommandDispatchHandler> handlers) {
    Objects.requireNonNull(handlers, "handlers must not be null");
    EnumMap<TextCommandDispatchGroup, TextCommandDispatchHandler> handlerMap =
        new EnumMap<>(TextCommandDispatchGroup.class);
    for (TextCommandDispatchHandler handler : handlers) {
      TextCommandDispatchHandler previous = handlerMap.put(handler.group(), handler);
      if (previous != null) {
        throw new IllegalStateException(
            "Duplicate dispatch handler registered for group " + handler.group());
      }
    }
    this.handlers = Map.copyOf(handlerMap);
  }

  TextCommandInterpretationResult dispatch(
      TextCommandDispatchGroup group, TextCommandDispatchRequest request) {
    TextCommandDispatchHandler handler = handlers.get(group);
    if (handler == null) {
      throw new IllegalArgumentException("No dispatch handler registered for group " + group);
    }
    return handler.handle(request);
  }
}
