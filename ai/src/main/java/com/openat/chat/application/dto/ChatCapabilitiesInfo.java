package com.openat.chat.application.dto;

import java.util.List;

public record ChatCapabilitiesInfo(
    boolean prototype,
    int maxMessageLength,
    String notice,
    List<ChatCapabilityInfo> capabilities) {}
