package com.openat.search.product.application.service;

import com.openat.search.product.infrastructure.image.InferenceServerImageClient;
import com.openat.search.product.presentation.dto.AiImageAnalyzeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiImageService {

  private final InferenceServerImageClient inferenceServerImageClient;
  private final RestClient restClient;
  // local
  private final String imgUrlHost = "http://localhost:8000/api/v1/products/images/";


  @Transactional(readOnly = true)
  public AiImageAnalyzeResponse analyze(MultipartFile image, String prompt) {
    if (image == null || image.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image is required");
    }

    String usedPrompt = normalizeAnalyzePrompt(prompt);
    String answer = inferenceServerImageClient.analyzeImage(image, usedPrompt);
    return new AiImageAnalyzeResponse(usedPrompt, answer);
  }

  @Transactional(readOnly = true)
  public AiImageAnalyzeResponse analyzeImageUrl(String imgurl, String prompt) {
    if (imgurl == null || imgurl.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "imgurl is required");
    }

    MultipartFile image = downloadAsMultipartFile(imgUrlHost + imgurl);
    return analyze(image, prompt);
  }

  private MultipartFile downloadAsMultipartFile(String imgurl) {
    URI uri = toImageUri(imgurl);
    ResponseEntity<byte[]> response;
    try {
      response = restClient.get().uri(uri).retrieve().toEntity(byte[].class);
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to download image url", e);
    }

    byte[] content = response.getBody();
    if (content == null || content.length == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image url returned empty body");
    }

    String filename = filenameOf(uri);
    String contentType =
        Optional.ofNullable(response.getHeaders().getContentType())
            .map(MediaType::toString)
            .orElseGet(
                () ->
                    MediaTypeFactory.getMediaType(filename)
                        .map(MediaType::toString)
                        .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE));

    return new DownloadedImageMultipartFile("image", filename, contentType, content);
  }

  private URI toImageUri(String imgurl) {
    if (imgurl == null || imgurl.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "imgurl is required");
    }

    try {
      URI uri = new URI(imgurl.trim());
      String scheme = uri.getScheme();
      if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "imgurl scheme must be http or https");
      }
      return uri;
    } catch (URISyntaxException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "imgurl is invalid", e);
    }
  }

  private String filenameOf(URI uri) {
    String path = uri.getPath();
    if (path == null || path.isBlank() || path.endsWith("/")) {
      return "image";
    }

    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private String normalizeAnalyzePrompt(String prompt) {
    return (prompt == null || prompt.isBlank())
        ? "너는 직업이 MD 야. 벡터 검색으로 사용하기 위해 최적화된 이미지 분석 해줘."
        : prompt.trim();
  }

  private String normalizeGeneratePrompt(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required");
    }
    return prompt.trim();
  }

  private String normalizeSize(String size) {
    return (size == null || size.isBlank()) ? "1024x1024" : size.trim();
  }

  private record DownloadedImageMultipartFile(
      String name, String originalFilename, String contentType, byte[] content)
      implements MultipartFile {

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getOriginalFilename() {
      return originalFilename;
    }

    @Override
    public String getContentType() {
      return contentType;
    }

    @Override
    public boolean isEmpty() {
      return content.length == 0;
    }

    @Override
    public long getSize() {
      return content.length;
    }

    @Override
    public byte[] getBytes() {
      return content;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
      Files.write(dest.toPath(), content);
    }
  }
}
