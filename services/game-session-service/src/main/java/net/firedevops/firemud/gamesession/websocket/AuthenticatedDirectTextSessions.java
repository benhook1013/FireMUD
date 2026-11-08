package net.firedevops.firemud.gamesession.websocket;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCredentialPresentation;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginOwner;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyCustody;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyScope;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Exact live backend-generation custody. Numeric session IDs and SQL observations cannot mint it.
 */
@Component
public final class AuthenticatedDirectTextSessions {
  private static final String ATTRIBUTE = AuthenticatedDirectTextSessions.class.getName();
  private static final CloseStatus GENERATION_CLOSED =
      new CloseStatus(1012, "backend_generation_closed");
  private final Map<String, Connection> current = new ConcurrentHashMap<>();
  private final Object lifecycleMonitor = new Object();
  private boolean shuttingDown;

  @PreDestroy
  public void closeAll() {
    java.util.List<Connection> connections;
    synchronized (lifecycleMonitor) {
      connections = java.util.List.copyOf(current.values());
      connections.forEach(Connection::requireExternalLifecycle);
      shuttingDown = true;
      current.clear();
    }
    connections.forEach(connection -> connection.close(GENERATION_CLOSED));
  }

  public void open(WebSocketSession socket) {
    var ingress = ingress(socket);
    Connection replacement;
    synchronized (lifecycleMonitor) {
      if (shuttingDown) throw unavailable();
      replacement =
          current.compute(
              ingress.transport().transportSessionId(),
              (id, previous) -> {
                if (previous != null) previous.requireExternalLifecycle();
                Connection next = new Connection(socket, ingress, previous);
                socket.getAttributes().put(ATTRIBUTE, next);
                return next;
              });
    }
    replacement.drainPredecessor();
    if (current.get(ingress.transport().transportSessionId()) == replacement && socket.isOpen())
      replacement.live.set(true);
    else replacement.close(GENERATION_CLOSED);
  }

  public Connection require(WebSocketSession socket) {
    Object value = socket.getAttributes().get(ATTRIBUTE);
    if (!(value instanceof Connection connection)
        || connection.socket != socket
        || current.get(connection.ingress.transport().transportSessionId()) != connection)
      throw unavailable();
    connection.requireLive();
    return connection;
  }

  public void close(WebSocketSession socket, CloseStatus status) {
    Objects.requireNonNull(status, "Close status required");
    Object value = socket.getAttributes().get(ATTRIBUTE);
    if (value instanceof Connection connection && connection.socket == socket) {
      connection.requireExternalLifecycle();
      current.remove(connection.ingress.transport().transportSessionId(), connection);
      connection.close(status);
    }
  }

  private static AuthenticatedDirectTextIngress ingress(WebSocketSession socket) {
    Object value = socket.getAttributes().get(AuthenticatedDirectTextIngress.ATTRIBUTE);
    if (!(value instanceof AuthenticatedDirectTextIngress ingress)) throw unavailable();
    return ingress;
  }

  public final class Connection implements DirectTextAuthenticatedLobbyCustody {
    private final WebSocketSession socket;
    private final AuthenticatedDirectTextIngress ingress;
    private volatile Connection predecessor;
    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock(true);
    private final AtomicBoolean live = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CountDownLatch drained = new CountDownLatch(1);
    private volatile Thread closingThread;
    private final Map<Thread, CanonicalGameplayLoginResult> borrowed = new ConcurrentHashMap<>();
    private volatile InitialGameplayLoginCredentialPresentation credentials;
    private volatile InitialGameplayLoginOwner.Outcome outcome;
    DirectTextWorldSelectionService.SelectionSnapshot menu;
    DirectTextAuthenticatedLobbyScope selectedScope;

    private Connection(
        WebSocketSession socket, AuthenticatedDirectTextIngress ingress, Connection predecessor) {
      this.socket = socket;
      this.ingress = ingress;
      this.predecessor = predecessor;
    }

    /** Each original outcome is installed only on the exact still-live receiving connection. */
    public void login(InitialGameplayLoginOwner owner, String email, char[] ownedSecret) {
      if (!lifetime.writeLock().tryLock()) {
        java.util.Arrays.fill(ownedSecret, '\0');
        throw unavailable();
      }
      try {
        requireLive();
        if (outcome != null) {
          outcome.close();
          outcome = null;
        }
        menu = null;
        selectedScope = null;
        credentials =
            InitialGameplayLoginCredentialPresentation.fromAuthenticatedTcp(
                ingress, email, ownedSecret);
        requireLive();
        InitialGameplayLoginOwner.Outcome received =
            Objects.requireNonNull(owner).receive(credentials);
        outcome = received;
        credentials = null;
        if (received.status() != InitialGameplayLoginOwner.Status.INSTALLED_NON_ADMITTING)
          throw new LoginRejectedException(received.failureCode().orElse("UNAVAILABLE"));
        requireLive();
      } finally {
        java.util.Arrays.fill(ownedSecret, '\0');
        if (credentials != null) {
          credentials.close();
          credentials = null;
        }
        if (!live.get() && outcome != null) outcome.close();
        lifetime.writeLock().unlock();
      }
    }

    /** Borrows the genuine installed result and pins this exact transport for the entire action. */
    public <T> T withLogin(
        BiFunction<InitialGameplayLoginAttemptCoordinates, CanonicalGameplayLoginResult, T>
            action) {
      lifetime.readLock().lock();
      try {
        requireLive();
        InitialGameplayLoginOwner.Outcome owned = outcome;
        if (owned == null) throw unavailable();
        return owned.withOwnedPrivateResult(
            result -> {
              if (borrowed.putIfAbsent(Thread.currentThread(), result) != null) throw unavailable();
              try {
                requireLive();
                T answer = action.apply(owned.coordinates(), result);
                requireLive();
                return answer;
              } finally {
                borrowed.remove(Thread.currentThread(), result);
              }
            });
      } finally {
        lifetime.readLock().unlock();
      }
    }

    @Override
    public <T> T withCurrent(
        InitialGameplayLoginAttemptCoordinates original,
        CanonicalGameplayLoginResult borrowedResult,
        Function<ObservedDirectTextTransport, T> action) {
      requireLive();
      if (borrowed.get(Thread.currentThread()) != borrowedResult
          || outcome == null
          || !outcome.coordinates().equals(original)) throw unavailable();
      T answer = Objects.requireNonNull(action).apply(ingress.transport());
      requireLive();
      return answer;
    }

    public boolean isCurrent() {
      return live.get()
          && !closing.get()
          && socket.isOpen()
          && current.get(ingress.transport().transportSessionId()) == this;
    }

    private void requireLive() {
      if (!isCurrent()) throw unavailable();
    }

    private void close(CloseStatus status) {
      requireExternalLifecycle();
      if (!closing.compareAndSet(false, true)) {
        while (closingThread == null) Thread.onSpinWait();
        if (closingThread == Thread.currentThread()) return;
        boolean interrupted = false;
        while (true) {
          try {
            drained.await();
            break;
          } catch (InterruptedException interruption) {
            interrupted = true;
          }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return;
      }
      closingThread = Thread.currentThread();
      live.set(false);
      var presentation = credentials;
      if (presentation != null) presentation.close();
      try {
        drainPredecessor();
        lifetime.writeLock().lock();
        try {
          if (outcome != null) outcome.close();
          outcome = null;
          menu = null;
          selectedScope = null;
        } finally {
          lifetime.writeLock().unlock();
        }
        try {
          if (socket.isOpen()) socket.close(status);
        } catch (IOException ignored) {
          // Custody was already revoked and drained before the best-effort transport close.
        }
      } finally {
        drained.countDown();
      }
    }

    private void requireExternalLifecycle() {
      for (Connection retained = this; retained != null; retained = retained.predecessor) {
        if (retained.lifetime.getReadHoldCount() > 0
            || retained.lifetime.isWriteLockedByCurrentThread()) throw unavailable();
      }
    }

    private void drainPredecessor() {
      var previous = predecessor;
      if (previous != null) {
        previous.close(GENERATION_CLOSED);
        predecessor = null;
      }
    }
  }

  static final class LoginRejectedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final String code;

    private LoginRejectedException(String code) {
      super("Authenticated gameplay LOGIN was rejected");
      this.code = code;
    }

    String code() {
      return code;
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Current authenticated TCP LOGIN custody is unavailable");
  }
}
