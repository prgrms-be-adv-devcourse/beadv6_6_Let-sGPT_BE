package com.openat.search.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openat.search.product.infrastructure.image.InferenceServerImageClient;
import com.openat.search.product.infrastructure.vector.InferenceServerProductEmbeddingGenerator;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestClient;

class InferenceServerClientsLiveTest {

  @Test
  void callsActualInferenceGatewayWithSearchModuleClients() throws Exception {
    String baseUrl = requiredEnvironment("INFERENCE_LIVE_BASE_URL");
    String apiKey = requiredEnvironment("INFERENCE_LIVE_API_KEY");
    RestClient restClient = RestClient.create();

    InferenceServerProductEmbeddingGenerator embeddingGenerator =
        new InferenceServerProductEmbeddingGenerator(
            restClient, baseUrl, apiKey, "text-embedding-3-small");
    InferenceServerImageClient imageClient =
        new InferenceServerImageClient(restClient, baseUrl, apiKey, "gpt-5.4-nano");

    float[] embedding = embeddingGenerator.generate("빨간색 원형 상품").orElseThrow();
    String description =
        imageClient.analyzeImage(
            new MockMultipartFile("image", "red-product.png", "image/png", redPng()),
            "상품 검색어로 설명해 줘");

    assertThat(embedding).hasSize(1536);
    assertThat(description).isNotBlank();
  }

  private String requiredEnvironment(String name) {
    String value = System.getenv(name);
    assumeTrue(value != null && !value.isBlank(), () -> name + " is required for live test");
    return value;
  }

  private byte[] redPng() throws Exception {
    BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.RED);
      graphics.fillOval(8, 8, 48, 48);
    } finally {
      graphics.dispose();
    }

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }
}
