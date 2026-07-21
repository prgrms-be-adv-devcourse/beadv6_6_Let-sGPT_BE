package com.openat.order.infrastructure.client;

@FunctionalInterface
public interface RetrySleeper {
  void sleep(long milliseconds) throws InterruptedException;
}
