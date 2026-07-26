package com.openat.chat.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReadModelDdlApplierTest {

  private DataSource dataSource;
  private Connection connection;
  private PreparedStatement literalStatement;
  private ResultSet literalResult;
  private Statement ddlStatement;
  private ReadModelDdlApplier applier;

  @BeforeEach
  void setUp() throws SQLException {
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    literalStatement = mock(PreparedStatement.class);
    literalResult = mock(ResultSet.class);
    ddlStatement = mock(Statement.class);

    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("SELECT quote_literal(?)")).thenReturn(literalStatement);
    when(literalStatement.executeQuery()).thenReturn(literalResult);
    when(literalResult.next()).thenReturn(true);
    when(literalResult.getString(1)).thenReturn("'local''password'");
    when(connection.createStatement()).thenReturn(ddlStatement);
    applier = new ReadModelDdlApplier(dataSource);
  }

  @Test
  @DisplayName("조회 비밀번호를 PostgreSQL literal로 안전하게 바꿔 한 트랜잭션에 적용한다")
  void apply_quotesPasswordAndCommitsSingleTransaction() throws SQLException {
    applier.apply("local'password");

    verify(connection).setAutoCommit(false);
    verify(literalStatement).setString(1, "local'password");
    verify(connection).commit();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(ddlStatement, org.mockito.Mockito.times(3)).execute(sql.capture());
    assertThat(sql.getAllValues()).hasSize(3);
    assertThat(sql.getAllValues().subList(0, 2))
        .containsExactly("SET LOCAL lock_timeout = '5s'", "SET LOCAL statement_timeout = '30s'");
    assertThat(sql.getAllValues().get(2))
        .contains("ALTER ROLE ai_query_app PASSWORD 'local''password'")
        .doesNotContain(":'AI_QUERY_DB_PASSWORD'");
  }

  @Test
  @DisplayName("DDL 적용 실패 시 변경을 커밋하지 않고 전체 트랜잭션을 롤백한다")
  void apply_rollsBackWhenDdlFails() throws SQLException {
    when(ddlStatement.execute(anyString()))
        .thenReturn(false)
        .thenReturn(false)
        .thenThrow(new SQLException("ddl failed"));

    assertThatThrownBy(() -> applier.apply("local'password"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("로컬 AI read-model 적용에 실패했어.");

    verify(connection).rollback();
  }
}
