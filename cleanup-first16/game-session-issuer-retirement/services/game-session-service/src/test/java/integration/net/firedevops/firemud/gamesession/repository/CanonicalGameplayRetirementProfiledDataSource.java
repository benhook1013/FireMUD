package net.firedevops.firemud.gamesession.repository;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Delegating JDBC timing used only while the opt-in retirement cost profile is active. */
final class CanonicalGameplayRetirementProfiledDataSource extends DriverManagerDataSource {
  private static final Set<String> EXECUTION_METHODS =
      Set.of(
          "execute",
          "executeBatch",
          "executeLargeBatch",
          "executeLargeUpdate",
          "executeQuery",
          "executeUpdate");

  @Override
  public Connection getConnection() throws SQLException {
    Connection connection;
    try (var timing = CanonicalGameplayIssuerRetirementCostProbe.timing("jdbc.connection.open")) {
      connection = super.getConnection();
    }
    return wrapConnection(connection);
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    Connection connection;
    try (var timing = CanonicalGameplayIssuerRetirementCostProbe.timing("jdbc.connection.open")) {
      connection = super.getConnection(username, password);
    }
    return wrapConnection(connection);
  }

  private static Connection wrapConnection(Connection delegate) {
    if (!CanonicalGameplayIssuerRetirementCostProbe.isActive()) return delegate;
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            new DelegatingHandler(delegate) {
              @Override
              public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                if (DelegatingHandler.isObjectMethod(method))
                  return invokeObjectMethod(proxy, method, arguments);
                if (method.getName().equals("unwrap")
                    && arguments != null
                    && arguments.length == 1
                    && arguments[0] instanceof Class<?> type
                    && type.isInstance(proxy)) {
                  return proxy;
                }
                if (method.getName().equals("isWrapperFor")
                    && arguments != null
                    && arguments.length == 1
                    && arguments[0] instanceof Class<?> type
                    && type.isInstance(proxy)) {
                  return true;
                }
                String methodName = method.getName();
                if (methodName.equals("commit")
                    || methodName.equals("rollback")
                    || methodName.equals("close")) {
                  String category =
                      switch (methodName) {
                        case "commit" -> "jdbc.transaction.commit";
                        case "rollback" -> "jdbc.transaction.rollback";
                        default -> "jdbc.connection.close";
                      };
                  try (var timing = CanonicalGameplayIssuerRetirementCostProbe.timing(category)) {
                    return invokeDelegate(method, arguments);
                  }
                }
                Object result = invokeDelegate(method, arguments);
                if (result instanceof Statement statement) {
                  return wrapStatement(statement, (Connection) proxy);
                }
                return result;
              }
            });
  }

  private static Statement wrapStatement(Statement delegate, Connection connection) {
    if (!CanonicalGameplayIssuerRetirementCostProbe.isActive()) return delegate;
    Class<?> statementInterface =
        delegate instanceof CallableStatement
            ? CallableStatement.class
            : delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    return (Statement)
        Proxy.newProxyInstance(
            statementInterface.getClassLoader(),
            new Class<?>[] {statementInterface},
            new DelegatingHandler(delegate) {
              @Override
              public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                if (DelegatingHandler.isObjectMethod(method))
                  return invokeObjectMethod(proxy, method, arguments);
                if (method.getName().equals("getConnection")) return connection;
                if (method.getName().equals("unwrap")
                    && arguments != null
                    && arguments.length == 1
                    && arguments[0] instanceof Class<?> type
                    && type.isInstance(proxy)) {
                  return proxy;
                }
                if (method.getName().equals("isWrapperFor")
                    && arguments != null
                    && arguments.length == 1
                    && arguments[0] instanceof Class<?> type
                    && type.isInstance(proxy)) {
                  return true;
                }
                Object result;
                if (EXECUTION_METHODS.contains(method.getName())) {
                  try (var timing =
                      CanonicalGameplayIssuerRetirementCostProbe.timing(
                          "jdbc.statement.execute")) {
                    result = invokeDelegate(method, arguments);
                  }
                } else {
                  result = invokeDelegate(method, arguments);
                }
                if (result instanceof ResultSet resultSet) {
                  return wrapResultSet(resultSet, (Statement) proxy);
                }
                return result;
              }
            });
  }

  private static ResultSet wrapResultSet(ResultSet delegate, Statement statement) {
    if (!CanonicalGameplayIssuerRetirementCostProbe.isActive()) return delegate;
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            new DelegatingHandler(delegate) {
              @Override
              public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                if (DelegatingHandler.isObjectMethod(method))
                  return invokeObjectMethod(proxy, method, arguments);
                if (method.getName().equals("getStatement")) return statement;
                if (method.getName().equals("unwrap")
                    && arguments != null
                    && arguments.length == 1
                    && arguments[0] instanceof Class<?> type
                    && type.isInstance(proxy)) {
                  return proxy;
                }
                if (method.getName().equals("isWrapperFor")
                    && arguments != null
                    && arguments.length == 1
                    && arguments[0] instanceof Class<?> type
                    && type.isInstance(proxy)) {
                  return true;
                }
                if (method.getName().equals("next")) {
                  try (var timing =
                      CanonicalGameplayIssuerRetirementCostProbe.timing("jdbc.result.next")) {
                    return invokeDelegate(method, arguments);
                  }
                }
                return invokeDelegate(method, arguments);
              }
            });
  }

  private abstract static class DelegatingHandler implements InvocationHandler {
    private final Object delegate;

    private DelegatingHandler(Object delegate) {
      this.delegate = delegate;
    }

    public abstract Object invoke(Object proxy, Method method, Object[] arguments)
        throws Throwable;

    protected final Object invokeDelegate(Method method, Object[] arguments) throws Throwable {
      try {
        return method.invoke(delegate, arguments);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }

    protected final Object invokeObjectMethod(Object proxy, Method method, Object[] arguments) {
      return switch (method.getName()) {
        case "equals" ->
            proxy == arguments[0]
                || delegate == unwrap(arguments[0])
                || delegate.equals(unwrap(arguments[0]));
        case "hashCode" -> delegate.hashCode();
        case "toString" -> delegate.toString();
        default -> throw new IllegalStateException("Unexpected Object method: " + method);
      };
    }

    protected static boolean isObjectMethod(Method method) {
      return method.getDeclaringClass() == Object.class;
    }

    private static Object unwrap(Object value) {
      if (value == null || !Proxy.isProxyClass(value.getClass())) return value;
      InvocationHandler handler = Proxy.getInvocationHandler(value);
      return handler instanceof DelegatingHandler delegating ? delegating.delegate : value;
    }
  }
}
