package com.openat.member.infrastructure.security;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Base64(DER)로 저장된 RSA 키 문자열을 {@link RSAPrivateKey}/{@link RSAPublicKey} 빈으로 변환한다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtKeyConfig {

    @Bean
    public RSAPrivateKey rsaPrivateKey(JwtProperties jwtProperties) {
        byte[] decoded = decode(jwtProperties.privateKey(), "JWT_PRIVATE_KEY");
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("JWT_PRIVATE_KEY가 올바른 RSA PKCS8 DER(Base64)이 아닙니다.", e);
        }
    }

    @Bean
    public RSAPublicKey rsaPublicKey(JwtProperties jwtProperties) {
        byte[] decoded = decode(jwtProperties.publicKey(), "JWT_PUBLIC_KEY");
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(decoded));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("JWT_PUBLIC_KEY가 올바른 RSA X.509 DER(Base64)이 아닙니다.", e);
        }
    }

    private byte[] decode(String base64Der, String envName) {
        if (base64Der == null || base64Der.isBlank()) {
            throw new IllegalStateException(envName + " 환경변수가 설정되어 있지 않습니다. .env를 확인하세요.");
        }
        return Base64.getDecoder().decode(base64Der);
    }
}
