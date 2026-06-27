package com.openat.drop.infrastructure.schedule;

import com.openat.drop.application.service.DropCloseService;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DropBootstrapRunner implements ApplicationRunner {

  private final DropRepository dropRepository;
  private final DropScheduler dropScheduler;
  private final DropCloseService dropCloseService;

  @Override
  public void run(ApplicationArguments args) {
    Instant now = Instant.now();
    for (Drop drop : dropRepository.findAllByStatus(DropStatus.REGISTERED)) {
      boolean alreadyPastClose = drop.getCloseAt() != null && !drop.getCloseAt().isAfter(now);
      if (alreadyPastClose) {
        dropCloseService.close(drop.getId());
      } else {
        dropScheduler.schedule(drop.getId(), drop.getOpenAt(), drop.getCloseAt());
      }
    }
  }
}
