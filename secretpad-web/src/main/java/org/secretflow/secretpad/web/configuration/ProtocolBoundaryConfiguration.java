package org.secretflow.secretpad.web.configuration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.secretflow.secretpad.service.protocol.ProtocolConfigService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Enforce fixed-node capabilities at the API boundary, including direct requests. */
@Configuration
@RequiredArgsConstructor
public class ProtocolBoundaryConfiguration implements WebMvcConfigurer, HandlerInterceptor {
    private final ProtocolConfigService protocols;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1alpha1/fate/**", "/api/v1alpha1/paddledtx/**",
                "/api/v1bfia/**", "/api/v1alpha1/graph/**", "/api/v1alpha1/project/**",
                "/api/v1alpha1/job/**").order(-100);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String required = path.startsWith("/api/v1alpha1/fate/") ? "fate"
                : path.startsWith("/api/v1alpha1/paddledtx/") ? "paddledtx"
                : path.startsWith("/api/v1bfia/") ? "bfia" : "secretflow";
        if (protocols.getProtocolConfig().getSupportedProtocols().contains(required)) return true;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":{\"code\":403,\"msg\":\"Protocol is disabled on this node\"}}");
        return false;
    }
}
