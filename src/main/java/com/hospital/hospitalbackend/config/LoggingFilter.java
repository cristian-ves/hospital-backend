package com.hospital.hospitalbackend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class LoggingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        System.out.println("[PING] " + httpRequest.getMethod() + " " + httpRequest.getRequestURI() + " from " + httpRequest.getRemoteAddr());
        chain.doFilter(request, response);
    }
}