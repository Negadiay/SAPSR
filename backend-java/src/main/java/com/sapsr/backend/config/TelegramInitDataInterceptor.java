package com.sapsr.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

@Component
public class TelegramInitDataInterceptor implements HandlerInterceptor {

    private final String botToken;

    public TelegramInitDataInterceptor(@Value("${telegram.bot.token}") String botToken) {
        this.botToken = botToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String initData = request.getHeader("Authorization");

        if (initData == null || initData.isBlank()) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Missing Authorization header (initData)\"}");
            return false;
        }

        try {
            Map<String, String> params = parseInitData(initData);
            String hash = params.remove("hash");

            if (hash == null) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"No hash in initData\"}");
                return false;
            }

            if (!verifyHash(params, hash)) {
                response.setStatus(403);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"Invalid initData signature\"}");
                return false;
            }

            String user = params.get("user");
            if (user != null) {
                Long telegramId = extractTelegramId(user);
                if (telegramId != null) {
                    request.setAttribute("telegram_id", telegramId);
                }
            }

            return true;
        } catch (Exception e) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Invalid initData format\"}");
            return false;
        }
    }

    private Map<String, String> parseInitData(String initData) {
        Map<String, String> params = new TreeMap<>();
        String[] pairs = initData.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private boolean verifyHash(Map<String, String> params, String hash) throws Exception {
        String[] sortedEntries = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        String dataCheckString = String.join("\n", sortedEntries);

        Mac hmacSha256 = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeyForToken = new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmacSha256.init(secretKeyForToken);
        byte[] secretKey = hmacSha256.doFinal(botToken.getBytes(StandardCharsets.UTF_8));

        Mac hmacData = Mac.getInstance("HmacSHA256");
        hmacData.init(new SecretKeySpec(secretKey, "HmacSHA256"));
        byte[] calculatedHash = hmacData.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));

        String calculatedHex = bytesToHex(calculatedHash);
        return calculatedHex.equals(hash);
    }

    private Long extractTelegramId(String userJson) {
        int idIdx = userJson.indexOf("\"id\"");
        if (idIdx < 0) return null;
        int colon = userJson.indexOf(':', idIdx);
        if (colon < 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = colon + 1; i < userJson.length(); i++) {
            char c = userJson.charAt(i);
            if (Character.isDigit(c)) sb.append(c);
            else if (sb.length() > 0) break;
        }
        return sb.length() > 0 ? Long.parseLong(sb.toString()) : null;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
