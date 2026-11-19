package com.orbitspace.modules.chat;

import com.orbitspace.kernel.AuthenticatedPrincipal;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.chat.ChatDtos.AdvanceReadRequest;
import com.orbitspace.modules.chat.ChatDtos.ChangeDiscussionRequest;
import com.orbitspace.modules.chat.ChatDtos.Discussion;
import com.orbitspace.modules.chat.ChatDtos.EventPage;
import com.orbitspace.modules.chat.ChatDtos.ForwardCommentRequest;
import com.orbitspace.modules.chat.ChatDtos.HistoryPage;
import com.orbitspace.modules.chat.ChatDtos.MessageAck;
import com.orbitspace.modules.chat.ChatDtos.ReadCursorResponse;
import com.orbitspace.modules.chat.ChatDtos.ResolveCardsRequest;
import com.orbitspace.modules.chat.ChatDtos.ResourceCardResult;
import com.orbitspace.modules.chat.ChatDtos.RoomInfo;
import com.orbitspace.modules.chat.ChatDtos.SendMessageRequest;
import com.orbitspace.security.RequireSpaceRole;
import com.orbitspace.security.WebSocketSecurity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST controller implementing the 8 OpenAPI main-room chat endpoints. */
@RestController
public class ChatController {
    private static final String EPOCH_HEADER = "X-Membership-Epoch";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ChatService chat;
    private final WebSocketSecurity wsSecurity;

    public ChatController(ChatService chat, WebSocketSecurity wsSecurity) {
        this.chat = chat;
        this.wsSecurity = wsSecurity;
    }

    @GetMapping("/api/v1/spaces/{spaceId}/chat")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public RoomInfo getRoom(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            HttpServletRequest request) {
        return chat.getRoom(spaceId, account.userId(), membershipEpoch(request));
    }

    @PostMapping("/api/v1/spaces/{spaceId}/chat/ws-ticket")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public ResponseEntity<WebSocketSecurity.Ticket> issueWsTicket(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            HttpServletRequest request) {
        WebSocketSecurity.Ticket ticket =
                wsSecurity.issue(account, spaceId, membershipEpoch(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ticket);
    }

    @PostMapping("/api/v1/spaces/{spaceId}/chat/messages")
    @RequireSpaceRole(SpaceRole.MEMBER)
    public MessageAck sendMessage(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @Valid @RequestBody SendMessageRequest body,
            HttpServletRequest request) {
        return chat.sendMessage(account.userId(), spaceId, membershipEpoch(request), body);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/chat/messages")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public HistoryPage getMessageHistory(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @RequestParam(value = "beforeSeq", required = false) Long beforeSeq,
            @RequestParam(value = "throughSeq", required = false) Long throughSeq,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        return chat.getMessageHistory(
                spaceId, account.userId(), membershipEpoch(request), beforeSeq, throughSeq, limit);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/chat/events")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public EventPage getRoomEvents(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @RequestParam("afterEventSeq") Long afterEventSeq,
            @RequestParam(value = "throughEventSeq", required = false) Long throughEventSeq,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        return chat.getRoomEvents(
                spaceId,
                account.userId(),
                membershipEpoch(request),
                afterEventSeq,
                throughEventSeq,
                limit);
    }

    @PutMapping("/api/v1/spaces/{spaceId}/chat/read")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public ReadCursorResponse advanceLastRead(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @Valid @RequestBody AdvanceReadRequest body,
            HttpServletRequest request) {
        return chat.advanceLastRead(spaceId, account.userId(), membershipEpoch(request), body);
    }

    @PutMapping("/api/v1/spaces/{spaceId}/chat/messages/{messageId}/discussion")
    @RequireSpaceRole(SpaceRole.MEMBER)
    public Discussion changeDiscussion(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String messageId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ChangeDiscussionRequest body,
            HttpServletRequest request) {
        return chat.changeDiscussion(
                spaceId,
                account.userId(),
                membershipEpoch(request),
                messageId,
                idempotencyKey,
                body);
    }

    @PostMapping("/api/v1/spaces/{spaceId}/chat/cards/resolve")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public ResponseEntity<List<ResourceCardResult>> resolveResourceCards(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @Valid @RequestBody ResolveCardsRequest body,
            HttpServletRequest request) {
        List<ResourceCardResult> results =
                chat.resolveResourceCards(spaceId, account.userId(), membershipEpoch(request), body);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(results);
    }

    @PostMapping(
            "/api/v1/spaces/{spaceId}/documents/{documentId}/comments/{commentId}/forward-chat")
    @RequireSpaceRole(SpaceRole.MEMBER)
    public MessageAck forwardDocumentComment(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String documentId,
            @PathVariable String commentId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ForwardCommentRequest body,
            HttpServletRequest request) {
        return chat.forwardDocumentComment(
                spaceId,
                documentId,
                commentId,
                account.userId(),
                membershipEpoch(request),
                idempotencyKey,
                body);
    }

    @PostMapping(
            "/api/v1/spaces/{spaceId}/files/{fileId}/versions/{fileVersionId}/pdf-comments/{commentId}/forward-chat")
    @RequireSpaceRole(SpaceRole.MEMBER)
    public MessageAck forwardPdfComment(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String fileId,
            @PathVariable String fileVersionId,
            @PathVariable String commentId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ForwardCommentRequest body,
            HttpServletRequest request) {
        return chat.forwardPdfComment(
                spaceId,
                fileId,
                fileVersionId,
                commentId,
                account.userId(),
                membershipEpoch(request),
                idempotencyKey,
                body);
    }

    private static long membershipEpoch(HttpServletRequest request) {
        try {
            return Long.parseLong(request.getHeader(EPOCH_HEADER));
        } catch (NumberFormatException ex) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }
    }
}
