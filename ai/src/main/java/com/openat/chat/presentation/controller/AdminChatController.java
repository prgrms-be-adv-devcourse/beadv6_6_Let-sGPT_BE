package com.openat.chat.presentation.controller;

import com.openat.chat.application.dto.ChatCapabilitiesInfo;
import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.service.AdminChatService;
import com.openat.chat.presentation.dto.ChatRequest;
import com.openat.chat.presentation.sse.AdminChatStreamCoordinator;
import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai/chats")
public class AdminChatController {

  private final AdminChatService chatService;
  private final AdminChatStreamCoordinator streamCoordinator;

  public AdminChatController(
      AdminChatService chatService, AdminChatStreamCoordinator streamCoordinator) {
    this.chatService = chatService;
    this.streamCoordinator = streamCoordinator;
  }

  @GetMapping("/capabilities")
  public ChatCapabilitiesInfo getCapabilities(Authentication authentication) {
    requireAdministrator(authentication);
    return chatService.getCapabilities();
  }

  @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chat(Authentication authentication, @Valid @RequestBody ChatRequest request) {
    requireAdministrator(authentication);

    ChatCommand command =
        new ChatCommand(
            UUID.randomUUID(),
            authentication.getName(),
            authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toUnmodifiableSet()),
            request.message(),
            previousTurn(request));
    return streamCoordinator.open(command);
  }

  private ChatCommand.PreviousTurn previousTurn(ChatRequest request) {
    if (request.previousTurn() == null) {
      return null;
    }
    return new ChatCommand.PreviousTurn(
        request.previousTurn().question(), request.previousTurn().answer());
  }

  private void requireAdministrator(Authentication authentication) {
    if (authentication == null
        || authentication.getAuthorities().stream()
            .noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
      throw new BusinessException(CommonErrorCode.FORBIDDEN);
    }
  }
}
