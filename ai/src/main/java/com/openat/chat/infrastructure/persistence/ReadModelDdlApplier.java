package com.openat.chat.infrastructure.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class ReadModelDdlApplier {

  private static final String PASSWORD_PLACEHOLDER = ":'AI_QUERY_DB_PASSWORD'";
  private static final Resource DDL_RESOURCE =
      new ClassPathResource("db/read-model/01-ai-read-model.sql");

  private final DataSource adminDataSource;

  public ReadModelDdlApplier(@Qualifier("dataSource") DataSource adminDataSource) {
    this.adminDataSource = adminDataSource;
  }

  public void apply(String queryPassword) {
    if (queryPassword == null || queryPassword.isBlank()) {
      throw new IllegalArgumentException("AI 조회 비밀번호가 비어 있어.");
    }
    try (Connection connection = adminDataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String ddl = renderDdl(connection, queryPassword);
        try (Statement statement = connection.createStatement()) {
          statement.execute("SET LOCAL lock_timeout = '5s'");
          statement.execute("SET LOCAL statement_timeout = '30s'");
          statement.execute(ddl);
        }
        connection.commit();
      } catch (SQLException | IOException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException | IOException exception) {
      throw new IllegalStateException("로컬 AI read-model 적용에 실패했어.", exception);
    }
  }

  private String renderDdl(Connection connection, String queryPassword)
      throws SQLException, IOException {
    String ddl = DDL_RESOURCE.getContentAsString(StandardCharsets.UTF_8);
    int placeholderIndex = ddl.indexOf(PASSWORD_PLACEHOLDER);
    if (placeholderIndex < 0 || placeholderIndex != ddl.lastIndexOf(PASSWORD_PLACEHOLDER)) {
      throw new IllegalStateException("AI read-model 비밀번호 placeholder 계약이 올바르지 않아.");
    }
    return ddl.replace(PASSWORD_PLACEHOLDER, quoteLiteral(connection, queryPassword));
  }

  private String quoteLiteral(Connection connection, String value) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT quote_literal(?)")) {
      statement.setString(1, value);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new SQLException("PostgreSQL이 비밀번호 SQL literal을 반환하지 않았어.");
        }
        return resultSet.getString(1);
      }
    }
  }

  private void rollback(Connection connection, Exception originalException) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      originalException.addSuppressed(rollbackException);
    }
  }
}
