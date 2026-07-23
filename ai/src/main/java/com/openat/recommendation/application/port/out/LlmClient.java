package com.openat.recommendation.application.port.out;

public interface LlmClient {

  String complete(String prompt);
}
