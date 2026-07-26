package com.openat.chat.application.port;

import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.port.AdminChatInferencePort.QueryBinding;
import java.util.List;

public interface AdminAnalyticsExecutionPort {

  List<EvidenceSegment> execute(List<QueryBinding> bindings, ChatRequestDeadline deadline);
}
