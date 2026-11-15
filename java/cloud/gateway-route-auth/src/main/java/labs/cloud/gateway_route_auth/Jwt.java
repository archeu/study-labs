package labs.cloud.gateway_route_auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最小的 HS256 JWT：签发和校验各十几行，只为看清网关在第 2 站做了什么。
 * 生产用 Spring Security 的 ReactiveJwtDecoder（或 JJWT），并用非对称密钥（RS256 / ES256）。
 */
public final class Jwt {
    static final byte[] KEY = "j10-lab-secret-change-me-32-bytes!!".getBytes(StandardCharsets.UTF_8);
    static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    static final Pattern SUB = Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]+)\"");
    static final Pattern EXP = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");

    public static String sign(String sub, long expEpochSec) {
        String h = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String p = ENC.encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + expEpochSec + "}").getBytes(StandardCharsets.UTF_8));
        return h + "." + p + "." + ENC.encodeToString(hmac(h + "." + p));
    }

    /** 校验通过返回 sub，否则返回 null */
    public static String verify(String token, long nowEpochSec) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        byte[] expect = hmac(parts[0] + "." + parts[1]);
        byte[] got;
        try { got = Base64.getUrlDecoder().decode(parts[2]); } catch (IllegalArgumentException e) { return null; }
        if (!MessageDigest.isEqual(expect, got)) return null;            // 常量时间比较
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        Matcher e = EXP.matcher(payload), s = SUB.matcher(payload);
        if (!e.find() || Long.parseLong(e.group(1)) < nowEpochSec || !s.find()) return null;
        return s.group(1);
    }

    static byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    /** run.sh 用：java ... Jwt 1024 3600 → 打印一个 1 小时后过期的 token */
    public static void main(String[] args) {
        System.out.println(sign(args[0], System.currentTimeMillis() / 1000 + Long.parseLong(args[1])));
    }
}
