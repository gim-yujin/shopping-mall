package com.shop.global.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxyMatchers;
    private final int trustedHopCount;

    public ClientIpResolver(
            @Value("${security.login-attempt.trusted-proxy-cidrs:}") List<String> trustedProxyCidrs,
            @Value("${security.login-attempt.trusted-hop-count:1}") int trustedHopCount
    ) {
        this.trustedProxyMatchers = buildMatchers(trustedProxyCidrs);
        this.trustedHopCount = Math.max(trustedHopCount, 0);
    }

    public String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            List<String> forwardedChain = parseForwardedChain(forwarded);
            if (!forwardedChain.isEmpty()) {
                return resolveFirstUntrustedFromChain(forwardedChain, remoteAddr);
            }
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            String normalized = realIp.trim();
            if (!normalized.isEmpty() && !isTrustedProxy(normalized)) {
                return normalized;
            }
        }

        return remoteAddr;
    }

    /**
     * X-Forwarded-For 체인은 "client, proxy1, proxy2" 형태이며 오른쪽이 애플리케이션에 가까운 hop이다.
     * trusted hop 수만큼 오른쪽부터 trusted proxy를 제거한 뒤, 남는 첫 번째 untrusted IP를 클라이언트 IP로 사용한다.
     */
    private String resolveFirstUntrustedFromChain(List<String> forwardedChain, String remoteAddr) {
        int trustedConsumed = 0;
        for (int i = forwardedChain.size() - 1; i >= 0; i--) {
            String candidate = forwardedChain.get(i);
            if (isTrustedProxy(candidate) && trustedConsumed < trustedHopCount) {
                trustedConsumed++;
                continue;
            }
            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
            break;
        }
        return remoteAddr;
    }

    private List<String> parseForwardedChain(String forwarded) {
        String[] split = forwarded.split(",");
        List<String> result = new ArrayList<>();
        for (String ip : split) {
            String normalized = ip.trim();
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private boolean isTrustedProxy(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank() || trustedProxyMatchers.isEmpty()) {
            return false;
        }
        return trustedProxyMatchers.stream().anyMatch(matcher -> matcher.matches(ipAddress));
    }

    private List<IpAddressMatcher> buildMatchers(List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return Collections.emptyList();
        }
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String cidr : cidrs) {
            if (cidr != null && !cidr.isBlank()) {
                matchers.add(new IpAddressMatcher(cidr.trim()));
            }
        }
        return matchers;
    }
}
