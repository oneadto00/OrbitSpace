package com.orbitspace.modules.chat;

import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.ACCOUNT;
import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.PUBLIC;

import com.orbitspace.kernel.ModuleSecurityCustomizer;
import com.orbitspace.kernel.ModuleSecurityCustomizer.Route;
import com.orbitspace.security.WebSocketSecurity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Spring Boot auto-configuration registering chat routes, WebSocket endpoint, and Redis fan-out. */
@AutoConfiguration
@EnableWebSocket
@Import({ChatService.class, ChatController.class, ChatWsHandler.class})
public class ChatAutoConfiguration {
    @Bean
    ModuleSecurityCustomizer chatRoutes() {
        return () -> List.of(
                accountRoute("GET", "/api/v1/spaces/{spaceId}/chat"),
                accountRoute("POST", "/api/v1/spaces/{spaceId}/chat/ws-ticket"),
                accountRoute("POST", "/api/v1/spaces/{spaceId}/chat/messages"),
                accountRoute("GET", "/api/v1/spaces/{spaceId}/chat/messages"),
                accountRoute("GET", "/api/v1/spaces/{spaceId}/chat/events"),
                accountRoute("PUT", "/api/v1/spaces/{spaceId}/chat/read"),
                accountRoute("PUT", "/api/v1/spaces/{spaceId}/chat/messages/{messageId}/discussion"),
                accountRoute("POST", "/api/v1/spaces/{spaceId}/chat/cards/resolve"),
                accountRoute(
                        "POST",
                        "/api/v1/spaces/{spaceId}/documents/{documentId}/comments/{commentId}/forward-chat"),
                accountRoute(
                        "POST",
                        "/api/v1/spaces/{spaceId}/files/{fileId}/versions/{fileVersionId}/pdf-comments/{commentId}/forward-chat"),
                new Route("GET", "/ws/chat", PUBLIC, true));
    }

    @Bean
    WebSocketConfigurer chatWebSocketConfigurer(
            WebSocketSecurity webSocketSecurity,
            ChatWsHandler chatWsHandler,
            @Value("${orbit.public-origin}") String publicOrigin) {
        return registry -> registry
                .addHandler(webSocketSecurity.guarded(chatWsHandler), "/ws/chat")
                .addInterceptors(webSocketSecurity)
                .setAllowedOrigins(publicOrigin);
    }

    @Bean
    RedisMessageListenerContainer chatRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, ChatWsHandler chatWsHandler) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                chatWsHandler, new ChannelTopic(ChatService.ROOM_EVENTS_CHANNEL));
        return container;
    }

    @Bean("orbitChatThreadReply")
    Function<Map<String, Object>, Map<String, Object>> orbitChatThreadReply(
            ChatService chatService) {
        return chatService::replyToDiscussionThread;
    }

    private static Route accountRoute(String method, String pathPattern) {
        return new Route(method, pathPattern, ACCOUNT, true);
    }
}
