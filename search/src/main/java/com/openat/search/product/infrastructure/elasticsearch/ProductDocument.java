package com.openat.search.product.infrastructure.elasticsearch;

import com.openat.search.product.domain.model.Category;
import com.openat.search.product.domain.model.Product;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

@Document(indexName = "products", createIndex = false, writeTypeHint = WriteTypeHint.FALSE)
public record ProductDocument(
    @Id String id,
    @Field(type = FieldType.Text) String name,
    @Field(type = FieldType.Text) String description,
    @Field(type = FieldType.Keyword) String categoryId,
    @Field(type = FieldType.Keyword) String categoryName,
    @Field(type = FieldType.Text) String sellerName,
    @Field(type = FieldType.Long) Long price,
    @Field(type = FieldType.Keyword) String thumbnailKey,
    @Field(type = FieldType.Text) String imgDescription,
    @Field(
            type = FieldType.Dense_Vector,
            dims = 1536,
            index = true,
            knnSimilarity = KnnSimilarity.COSINE)
        float[] embedding,
    @Field(type = FieldType.Date, format = DateFormat.date_time) Instant createdAt,
    @Field(type = FieldType.Date, format = DateFormat.date_time) Instant updatedAt,
    @Field(type = FieldType.Date, format = DateFormat.date_time) Instant deletedAt) {

  public static ProductDocument from(Product product) {
    Category category = product.getCategory();
    return new ProductDocument(
        product.getId().toString(),
        product.getName(),
        product.getDescription(),
        category != null ? category.getId().toString() : null,
        category != null ? category.getName() : null,
        null,
        product.getPrice(),
        product.getThumbnailKey(),
        product.getImgDescription(),
        null,
        product.getCreatedAt(),
        product.getUpdatedAt(),
        null);
  }

  public ProductDocument withEmbedding(float[] embedding) {
    return new ProductDocument(
        id,
        name,
        description,
        categoryId,
        categoryName,
        sellerName,
        price,
        thumbnailKey,
        imgDescription,
        embedding,
        createdAt,
        updatedAt,
        deletedAt);
  }

  public ProductDocument withImgDescription(String imgDescription) {
    return new ProductDocument(
        id,
        name,
        description,
        categoryId,
        categoryName,
        sellerName,
        price,
        thumbnailKey,
        imgDescription,
        embedding,
        createdAt,
        updatedAt,
        deletedAt);
  }

  public ProductDocument withDeletedAt(Instant deletedAt) {
    return new ProductDocument(
        id,
        name,
        description,
        categoryId,
        categoryName,
        sellerName,
        price,
        thumbnailKey,
        imgDescription,
        embedding,
        createdAt,
        updatedAt,
        deletedAt);
  }
}
