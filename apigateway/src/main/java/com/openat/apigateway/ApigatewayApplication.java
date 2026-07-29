package com.openat.apigateway;

import com.openat.apigateway.admission.AdmissionProperties;
import com.openat.apigateway.config.ConcurrencyLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AdmissionProperties.class, ConcurrencyLimitProperties.class})
public class ApigatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApigatewayApplication.class, args);
	}

}
