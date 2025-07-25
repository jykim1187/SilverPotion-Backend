package com.silverpotion.chatserver.chat.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

public class CustomHandshakeHandler extends DefaultHandshakeHandler {
    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                      WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String loginId = servletRequest.getServletRequest().getParameter("loginId");
            if (loginId != null) {
                System.out.println("CustomHandshakeHandler loginId : "+loginId);
                return () -> loginId; // 익명 Principal 리턴
            }
        }
        return null;
    }
}
