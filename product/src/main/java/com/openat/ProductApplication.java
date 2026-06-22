package com.openat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(
    basePackages = "com.openat",
    excludeFilters = {
      @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
      @ComponentScan.Filter(
          type = FilterType.CUSTOM,
          classes = AutoConfigurationExcludeFilter.class),
      @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.openat\\.common\\..*")
    })
public class ProductApplication {
  public static void main(String[] args) {
    SpringApplication.run(ProductApplication.class, args);
  }
}
