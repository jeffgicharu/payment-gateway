package com.gateway.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-SHA256 request signing for API security.
 * Merchants sign requests with their API secret; the gateway verifies.
 */
@Component
public class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    public String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC signing failed", e);
        }
    }

    public boolean verify(String data, String signature, String secret) {
        String expected = sign(data, secret);
        return expected.equals(signature);
    }

    /**
     * Build the signing payload: timestamp + method + path + body
     */
    public String buildSigningPayload(String timestamp, String method, String path, String body) {
        return timestamp + "." + method + "." + path + "." + (body != null ? body : "");
    }
}
