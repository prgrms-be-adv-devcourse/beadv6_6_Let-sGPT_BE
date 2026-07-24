package com.openat.chat.application.port;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.port.AdminChatInferencePort.ToolInvocation;
import com.openat.chat.domain.query.InternalDataDomain;
import java.util.List;
import java.util.Set;

public interface AdminInitialToolPort {

  InitialToolResult execute(
      ChatCommand command,
      List<ToolInvocation> invocations,
      ChatEventSink sink,
      ChatRequestDeadline deadline);

  record InitialToolResult(
      Set<InternalDataDomain> domains,
      List<EvidenceSegment> evidence,
      boolean schemaSelectionRequested,
      boolean schemaSelectionFailed) {

    public InitialToolResult {
      domains = domains == null ? Set.of() : Set.copyOf(domains);
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
  }
}
