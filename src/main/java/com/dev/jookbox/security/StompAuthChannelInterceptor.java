package com.dev.jookbox.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Authenticates STOMP CONNECT frames using the native "Authorization: Bearer &lt;jwt&gt;" header
 * and authorizes SUBSCRIBE frames to only the authenticated membership's own room topics.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    public StompAuthChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // Must be the message's own mutable accessor: wrap() returns a detached copy,
        // and a setUser on a copy is never seen by the STOMP session.
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            String authorization = accessor.getFirstNativeHeader("Authorization");
            AuthenticatedMember member = jwtService.parseBearer(authorization)
                    .orElseThrow(() -> new IllegalArgumentException("Missing or invalid Authorization header"));
            accessor.setUser(new MemberAuthentication(member, authorization.substring(7)));
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            AuthenticatedMember member = currentMember(accessor);
            String destination = accessor.getDestination();
            String allowedPrefix = "/topic/rooms/" + member.roomCode() + "/";
            if (destination == null || !destination.startsWith(allowedPrefix)) {
                throw new IllegalArgumentException("Not allowed to subscribe to " + destination);
            }
        } else if (StompCommand.SEND.equals(command)) {
            // No @MessageMapping endpoints exist, and the simple broker would relay
            // a client SEND to /topic/** straight to that topic's subscribers —
            // letting any member forge queue/playback broadcasts for any room.
            throw new IllegalArgumentException("Client SEND frames are not supported");
        }
        return message;
    }

    private AuthenticatedMember currentMember(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof MemberAuthentication auth) {
            return auth.getPrincipal();
        }
        throw new IllegalArgumentException("Not authenticated");
    }
}
