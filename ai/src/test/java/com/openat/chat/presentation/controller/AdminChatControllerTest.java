package com.openat.chat.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openat.chat.application.dto.ChatCapabilitiesInfo;
import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.service.AdminChatService;
import com.openat.chat.infrastructure.config.AiSecurityConfig;
import com.openat.chat.presentation.sse.AdminChatStreamCoordinator;
import com.openat.common.config.CommonWebAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(AdminChatController.class)
@AutoConfigureMockMvc
@Import({
  CommonWebAutoConfiguration.class,
  AiSecurityConfig.class,
  AdminChatControllerTest.SecurityBootstrap.class
})
class AdminChatControllerTest {

  @TestConfiguration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class SecurityBootstrap {}

  @Autowired MockMvc mockMvc;

  @MockitoBean AdminChatService chatService;

  @MockitoBean AdminChatStreamCoordinator streamCoordinator;

  @MockitoBean JwtDecoder jwtDecoder;

  @Test
  @DisplayName("Bearer JWT의 ADMIN 권한으로 챗봇 기능 상태를 조회한다")
  void getCapabilities_adminJwt_returnsCapabilities() throws Exception {
    // given
    ChatCapabilitiesInfo capabilities = new ChatCapabilitiesInfo(false, 2000, "안내", List.of());
    given(chatService.getCapabilities()).willReturn(capabilities);
    given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-token", "admin-id", "ADMIN"));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/ai/chats/capabilities")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.prototype").value(false))
        .andExpect(jsonPath("$.maxMessageLength").value(2000));
  }

  @Test
  @DisplayName("ADMIN 권한이 없는 JWT는 챗봇 기능 상태 조회를 거부한다")
  void getCapabilities_nonAdminJwt_returnsForbidden() throws Exception {
    // given
    given(jwtDecoder.decode("user-token")).willReturn(jwt("user-token", "user-id", "USER"));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/ai/chats/capabilities")
                .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("인증 없이 X-User 헤더만 위조해도 챗봇 진입을 거부한다")
  void getCapabilities_spoofedHeaders_returnsUnauthorized() throws Exception {
    // when & then
    mockMvc
        .perform(
            get("/api/v1/ai/chats/capabilities")
                .header("X-User-Id", "admin-id")
                .header("X-User-Roles", "ROLE_ADMIN"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("ADMIN JWT라도 빈 질문은 SSE 시작 전에 거부한다")
  void chat_blankMessage_returnsBadRequest() throws Exception {
    // given
    given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-token", "admin-id", "ADMIN"));

    // when & then
    mockMvc
        .perform(
            post("/api/v1/ai/chats")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"message\":\"   \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("질문은 양끝 공백을 제거한 뒤 2,000자 제한을 검증한다")
  void chat_messageWithOuterSpaces_validatesNormalizedLength() throws Exception {
    // given
    given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-token", "admin-id", "ADMIN"));
    given(streamCoordinator.open(any(ChatCommand.class))).willReturn(new SseEmitter());
    String normalized = "가".repeat(AdminChatService.MAX_MESSAGE_LENGTH);

    // when & then
    mockMvc
        .perform(
            post("/api/v1/ai/chats")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"message\":\" " + normalized + " \"}"))
        .andExpect(status().isOk())
        .andExpect(request().asyncStarted());

    ArgumentCaptor<ChatCommand> command = ArgumentCaptor.forClass(ChatCommand.class);
    verify(streamCoordinator).open(command.capture());
    assertThat(command.getValue().message()).isEqualTo(normalized);
  }

  @Test
  @DisplayName("질문은 양끝 공백 제거 뒤 2,000자를 넘으면 거부한다")
  void chat_normalizedMessageOverLimit_returnsBadRequest() throws Exception {
    // given
    given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-token", "admin-id", "ADMIN"));
    String normalized = "가".repeat(AdminChatService.MAX_MESSAGE_LENGTH + 1);

    // when & then
    mockMvc
        .perform(
            post("/api/v1/ai/chats")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"message\":\" " + normalized + " \"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(streamCoordinator);
  }

  @Test
  @DisplayName("직전 완료 대화 한 턴을 공백 제거 후 후속 질문 문맥으로 전달한다")
  void chat_previousTurn_mapsBoundedContext() throws Exception {
    given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-token", "admin-id", "ADMIN"));
    given(streamCoordinator.open(any(ChatCommand.class))).willReturn(new SseEmitter());

    mockMvc
        .perform(
            post("/api/v1/ai/chats")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(
                    """
                    {
                      "message": " 그중 가장 많이 팔린 상품은? ",
                      "previousTurn": {
                        "question": " 지난달 주문 추이는? ",
                        "answer": " 지난달 주문은 40건이야. "
                      }
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(request().asyncStarted());

    ArgumentCaptor<ChatCommand> command = ArgumentCaptor.forClass(ChatCommand.class);
    verify(streamCoordinator).open(command.capture());
    assertThat(command.getValue().message()).isEqualTo("그중 가장 많이 팔린 상품은?");
    assertThat(command.getValue().previousTurnContext())
        .get()
        .satisfies(
            previous -> {
              assertThat(previous.question()).isEqualTo("지난달 주문 추이는?");
              assertThat(previous.answer()).isEqualTo("지난달 주문은 40건이야.");
            });
  }

  @Test
  @DisplayName("이전 대화가 제한을 넘으면 SSE 시작 전에 거부한다")
  void chat_previousTurnOverLimit_returnsBadRequest() throws Exception {
    given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-token", "admin-id", "ADMIN"));
    String oversizedAnswer = "가".repeat(AdminChatService.MAX_PREVIOUS_ANSWER_LENGTH + 1);

    mockMvc
        .perform(
            post("/api/v1/ai/chats")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(
                    "{\"message\":\"후속 질문\",\"previousTurn\":{\"question\":\"이전 질문\","
                        + "\"answer\":\""
                        + oversizedAnswer
                        + "\"}}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(streamCoordinator);
  }

  private Jwt jwt(String tokenValue, String subject, String role) {
    return Jwt.withTokenValue(tokenValue)
        .header("alg", "none")
        .subject(subject)
        .claim("roles", List.of(role))
        .build();
  }
}
