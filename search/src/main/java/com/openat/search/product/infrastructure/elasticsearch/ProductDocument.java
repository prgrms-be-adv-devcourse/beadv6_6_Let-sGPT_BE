package com.openat.search.product.infrastructure.elasticsearch;

import com.openat.search.product.domain.model.Category;
import com.openat.search.product.domain.model.Product;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.Instant;

@Document(indexName = "products", createIndex = false, writeTypeHint = WriteTypeHint.FALSE)
public record ProductDocument(
    @Id String id,
    @Field(type = FieldType.Text) String name,
    @Field(type = FieldType.Text) String description,
    @Field(type = FieldType.Text) String categoryName,
    @Field(type = FieldType.Long) Long price,
    @Field(type = FieldType.Keyword) String thumbnailKey,
    @Field(
            type = FieldType.Dense_Vector,
            dims = 1536,
            index = true,
            knnSimilarity = KnnSimilarity.COSINE)
        float[] embedding,
    @Field(type = FieldType.Date, format = DateFormat.date_time) Instant createdAt,
    @Field(type = FieldType.Date, format = DateFormat.date_time) Instant updatedAt) {

  public static ProductDocument from(Product product) {
    Category category = product.getCategory();
    return new ProductDocument(
        product.getId().toString(),
        product.getName(),
        product.getDescription(),
        category != null ? category.getName() : null,
        product.getPrice(),
        product.getThumbnailKey(),
        null,
        product.getCreatedAt(),
        product.getUpdatedAt());
  }

  public ProductDocument withEmbedding(float[] embedding) {
    return new ProductDocument(
        id, name, description, categoryName, price, thumbnailKey, embedding, createdAt, updatedAt);
  }
}
