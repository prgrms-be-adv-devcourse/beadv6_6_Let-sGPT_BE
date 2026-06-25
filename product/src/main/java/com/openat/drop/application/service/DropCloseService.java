package com.openat.drop.application.service;

import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DropCloseService {

  private final DropRepository dropRepository;
  private final ApplicationEventPublisher eventPublisher;

  public void close(UUID dropId) {
    Drop drop = dropRepository.findById(dropId).orElse(null);
    if (drop == null || drop.getStatus() == DropStatus.CLOSE) {
      return;
    }
    drop.close();
    eventPublisher.publishEvent(new DropClosedEvent(dropId));
  }
}
