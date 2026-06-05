package com.ib.arb.broker;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Kraken HMAC-SHA512 request signing shared by order and position clients. */
public final class KrakenAuth {

    /** Shared monotonically increasing nonce counter — all callers use the same API key.
     *  Using microsecond-scale values (ms * 1000) ensures each restart starts well above
     *  where the previous run left off, preventing EAPI:Invalid nonce after restarts. */
    private static final AtomicLong NONCE = new AtomicLong(System.currentTimeMillis() * 1000L);

    private KrakenAuth() {}

    /** Returns the next nonce, guaranteed strictly greater than the previous call. */
    public static String nextNonce() {
        return String.valueOf(NONCE.incrementAndGet());
    }

    /**
     * Signs a Kraken private API request.
     * Algorithm: {@code Base64( HMAC-SHA512( Base64Decode(secret), path + SHA256(nonce + postData) ) )}
     */
    public static String sign(String path, String nonce, String postData, String apiSecret) throws Exception {
        var sha256 = MessageDigest.getInstance("SHA-256");
        var sha256Hash = sha256.digest((nonce + postData).getBytes(StandardCharsets.UTF_8));

        var mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(apiSecret), "HmacSHA512"));
        mac.update(path.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(mac.doFinal(sha256Hash));
    }

    /** URL-encodes a map of parameters into {@code key=value&key=value} form. */
    public static String encodeForm(Map<String, String> params) {
        var sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
