package com.luyu.agent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.luyu.agent.config.AuthProperties;

/**
 * JWT 签发与验签（HS256，自实现）。
 * <p>
 * 不引 jjwt/Nimbus：两者官方 Jackson 绑定均基于 Jackson 2，与本项目的
 * Boot 4 + Jackson 3（tools.jackson）环境会引入第二套 databind；而本平台
 * 仅需 HS256 + 三个 claim（sub/tid/exp），手写 ~100 行更干净。
 * <p>
 * payload JSON 采用手拼而非序列化库：sub/tid 在签发前经过
 * {@link #SAFE_ID} 白名单校验（无引号/转义字符，无注入面），iat/exp 为数字。
 * 验签用 {@link MessageDigest#isEqual} 常时比较，防时序侧信道。
 */
@Service
public class JwtService {

    /** 签发与解析共用的标识白名单：单段安全字符（与 RpgSaveMemoryController.SAFE_ID 同口径） */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 固定 header：{"alg":"HS256","typ":"JWT"} 的 base64url */
    private static final String HEADER_B64 =
            Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final long ttlSeconds;

    @Autowired
    public JwtService(AuthProperties properties) {
        this.secret = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = properties.getTtl().toSeconds();
    }

    /**
     * 签发 token。
     *
     * @param userId   用户 ID（须满足 SAFE_ID）
     * @param tenantId 租户 ID（须满足 SAFE_ID）
     * @return compact JWT（header.payload.signature）
     */
    public String issue(String userId, String tenantId) {
        requireSafe(userId, "userId");
        requireSafe(tenantId, "tenantId");
        long now = Instant.now().getEpochSecond();
        // sub/tid 已过白名单校验，手拼 JSON 无注入风险
        String payload = "{\"sub\":\"" + userId + "\",\"tid\":\"" + tenantId
                + "\",\"iat\":" + now + ",\"exp\":" + (now + ttlSeconds) + "}";
        String payloadB64 = b64(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = HEADER_B64 + "." + payloadB64;
        return signingInput + "." + b64(hmac(signingInput));
    }

    /**
     * 验签并解析 token。
     *
     * @return 解析出的声明（sub/tid）；签名不符或已过期返回 null
     */
    public Claims verify(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(signingInput);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            return null;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return parseClaims(payload);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 验签通过后从 payload JSON 提取 sub/tid 并校验 exp。格式不符一律返回 null（不抛）。 */
    private Claims parseClaims(String payload) {
        String sub = extract(payload, "\"sub\":\"");
        String tid = extract(payload, "\"tid\":\"");
        if (sub == null || tid == null || !SAFE_ID.matcher(sub).matches() || !SAFE_ID.matcher(tid).matches()) {
            return null;
        }
        int expStart = payload.indexOf("\"exp\":");
        if (expStart < 0) {
            return null;
        }
        long exp;
        try {
            int end = expStart + 6;
            while (end < payload.length() && (Character.isDigit(payload.charAt(end)) || payload.charAt(end) == '-')) {
                end++;
            }
            exp = Long.parseLong(payload.substring(expStart + 6, end));
        } catch (NumberFormatException e) {
            return null;
        }
        if (Instant.now().getEpochSecond() >= exp) {
            return null;
        }
        return new Claims(sub, tid);
    }

    /** 从 JSON 字符串提取 "key":"value" 形式的字符串值；不存在返回 null。 */
    private static String extract(String json, String keyWithQuotes) {
        int start = json.indexOf(keyWithQuotes);
        if (start < 0) {
            return null;
        }
        int valueStart = start + keyWithQuotes.length();
        int end = json.indexOf('"', valueStart);
        if (end < 0) {
            return null;
        }
        return json.substring(valueStart, end);
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void requireSafe(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 含非法字符: " + value);
        }
    }

    /** 验签成功的 token 声明 */
    public record Claims(String userId, String tenantId) {
    }
}
