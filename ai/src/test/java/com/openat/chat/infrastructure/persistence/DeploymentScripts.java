package com.openat.chat.infrastructure.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 배포 스크립트를 실제로 실행해 동작을 검증하는 테스트 실행기.
 *
 * <p>인터프리터를 못 찾으면 테스트를 조용히 통과시키지 않고 예외로 실패한다. CD는 bash와 python3으로 병합 소유권을 집행하므로, 둘 중 하나가 없는 환경에서
 * 초록불이 나오면 검증이 사라진 것을 아무도 모른다.
 */
final class DeploymentScripts {

  private DeploymentScripts() {}

  record Result(int exitCode, String output) {}

  static String python() throws IOException, InterruptedException {
    return locate("python3", List.of("python3", "python"), "Python 3");
  }

  static String bash() throws IOException, InterruptedException {
    return locate(
        "bash",
        windows()
            ? List.of(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                "bash")
            : List.of("bash", "/bin/bash", "/usr/bin/bash"),
        "bash");
  }

  static String git() throws IOException, InterruptedException {
    return locate("git", List.of("git", "/usr/bin/git"), "git version");
  }

  private static String locate(String name, List<String> candidates, String expected)
      throws IOException, InterruptedException {
    for (String candidate : candidates) {
      try {
        ProcessBuilder processBuilder = new ProcessBuilder(candidate, "--version");
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String version = readOutput(process);
        if (process.waitFor(10, TimeUnit.SECONDS)
            && process.exitValue() == 0
            && version.contains(expected)) {
          return candidate;
        }
      } catch (IOException ignored) {
        // 다음 표준 위치를 확인한다.
      }
    }
    throw new IllegalStateException(
        name + "을 찾지 못해 배포 스크립트 동작을 검증할 수 없습니다(확인한 후보: " + candidates + ").");
  }

  static Result run(Path workingDirectory, Map<String, String> environment, List<String> command)
      throws IOException, InterruptedException {
    ProcessBuilder processBuilder = new ProcessBuilder(new ArrayList<>(command));
    processBuilder.directory(workingDirectory.toFile());
    processBuilder.redirectErrorStream(true);
    processBuilder.environment().putAll(environment);
    Process process = processBuilder.start();
    if (!process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("배포 스크립트가 제한 시간 안에 끝나지 않았습니다: " + command);
    }
    return new Result(process.exitValue(), readOutput(process));
  }

  /** Git Bash는 드라이브 문자 경로를 못 받으므로 POSIX 경로로 바꿔 넘긴다. */
  static String toScriptPath(Path path) {
    String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
    if (normalized.length() >= 3 && normalized.charAt(1) == ':') {
      return "/" + Character.toLowerCase(normalized.charAt(0)) + normalized.substring(2);
    }
    return normalized;
  }

  private static boolean windows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
  }

  private static String readOutput(Process process) throws IOException {
    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }
}
