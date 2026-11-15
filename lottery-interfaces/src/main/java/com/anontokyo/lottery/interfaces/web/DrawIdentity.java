package com.anontokyo.lottery.interfaces.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在 HTTP 边界解析可信身份。本机演示身份只存在于对应的 Servlet 会话中。 */
@Component
public class DrawIdentity {
    private static final String LOOPBACK = "127.0.0.1";
    private static final String SESSION_USER = DrawIdentity.class.getName() + ".userId";
    private static final List<String> DEMO_USERS = Arrays.asList("demo-a", "demo-b", "demo-c");

    private final boolean demoEnabled;
    private final String fixedDemoUser;

    public DrawIdentity(@Value("${lottery.draw.local-demo-enabled:false}") boolean demoEnabled,
                        @Value("${lottery.draw.local-demo-user:}") String fixedDemoUser,
                        @Value("${server.address:}") String serverAddress) {
        if ((demoEnabled || !fixedDemoUser.isEmpty()) && !LOOPBACK.equals(serverAddress)) {
            throw new IllegalStateException("抽奖演示账户要求应用显式绑定 127.0.0.1");
        }
        this.demoEnabled = demoEnabled;
        this.fixedDemoUser = fixedDemoUser;
    }

    String currentUser(HttpServletRequest request) {
        // 已认证的 Principal 优先于会话内的演示身份。
        Principal principal = request.getUserPrincipal();
        if (principal != null) return principal.getName();
        if (!isLocalRequest(request)) return "";
        if (!demoEnabled) return fixedDemoUser;

        // 读取时不创建会话。尚未主动切换的浏览器使用演示白名单首项。
        HttpSession session = request.getSession(false);
        String userId = session == null ? null : (String) session.getAttribute(SESSION_USER);
        return userId == null ? DEMO_USERS.get(0) : userId;
    }

    String switchDemoUser(HttpServletRequest request, String userId) {
        if (!canSwitch(request)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!DEMO_USERS.contains(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知演示账户");
        }
        // 校验白名单后才创建或更新当前浏览器的会话。
        request.getSession().setAttribute(SESSION_USER, userId);
        return userId;
    }

    ResponseEntity<Map<String, Object>> response(HttpServletRequest request, String userId) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("userId", userId);
        if (canSwitch(request)) identity.put("demoAccounts", DEMO_USERS);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identity);
    }

    private boolean canSwitch(HttpServletRequest request) {
        return demoEnabled && request.getUserPrincipal() == null && isLocalRequest(request);
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        // 检查实际接入地址和 Host，避免外部绑定及通过其他域名访问本机演示入口。
        return LOOPBACK.equals(request.getLocalAddr())
                && (LOOPBACK.equals(request.getServerName()) || "localhost".equalsIgnoreCase(request.getServerName()));
    }
}
