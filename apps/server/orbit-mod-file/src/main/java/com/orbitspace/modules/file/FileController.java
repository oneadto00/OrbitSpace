package com.orbitspace.modules.file;

import com.orbitspace.kernel.AuthenticatedPrincipal;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.file.FileDtos.AirdropRoleFilter;
import com.orbitspace.modules.file.FileDtos.AirdropShareDto;
import com.orbitspace.modules.file.FileDtos.AirdropShareListResponse;
import com.orbitspace.modules.file.FileDtos.AttachSpaceFileRequest;
import com.orbitspace.modules.file.FileDtos.CompleteUploadSessionRequest;
import com.orbitspace.modules.file.FileDtos.CreateAirdropRequest;
import com.orbitspace.modules.file.FileDtos.CreateDownloadSessionRequest;
import com.orbitspace.modules.file.FileDtos.CreateUploadSessionRequest;
import com.orbitspace.modules.file.FileDtos.DownloadSessionDto;
import com.orbitspace.modules.file.FileDtos.FileRecordDto;
import com.orbitspace.modules.file.FileDtos.SpaceFileListResponse;
import com.orbitspace.modules.file.FileDtos.UploadPartAck;
import com.orbitspace.modules.file.FileDtos.UploadSessionDto;
import com.orbitspace.modules.file.FileService.OpenedDownloadContent;
import com.orbitspace.security.RequireSpaceRole;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;

@RestController
public class FileController {
    private static final String EPOCH_HEADER = "X-Membership-Epoch";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String PART_SHA_HEADER = "X-Part-Sha256";

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @PostMapping("/api/v1/files/upload-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadSessionDto createUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = EPOCH_HEADER, required = false) String rawEpoch,
            @Valid @RequestBody CreateUploadSessionRequest body) {
        return files.createUploadSession(
                account.userId(), optionalEpoch(rawEpoch), idempotencyKey, body);
    }

    @GetMapping("/api/v1/files/upload-sessions/{uploadId}")
    public UploadSessionDto getUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String uploadId) {
        return files.getUploadSession(account.userId(), uploadId);
    }

    @PutMapping(
            value = "/api/v1/files/upload-sessions/{uploadId}/parts/{partNumber}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public UploadPartAck uploadPart(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String uploadId,
            @PathVariable int partNumber,
            @RequestHeader(value = PART_SHA_HEADER, required = false) String partSha256,
            HttpServletRequest request)
            throws IOException {
        return files.uploadPart(
                account.userId(), uploadId, partNumber, partSha256, request.getInputStream());
    }

    @PostMapping("/api/v1/files/upload-sessions/{uploadId}/complete")
    public FileRecordDto completeUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String uploadId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CompleteUploadSessionRequest body) {
        return files.completeUploadSession(account.userId(), uploadId, idempotencyKey, body);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/files")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public SpaceFileListResponse listSpaceFiles(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            HttpServletRequest request) {
        return files.listSpaceFiles(spaceId, account.userId(), requiredEpoch(request));
    }

    @PostMapping("/api/v1/spaces/{spaceId}/files")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireSpaceRole(SpaceRole.MEMBER)
    public FileRecordDto attachSpaceFile(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AttachSpaceFileRequest body,
            HttpServletRequest request) {
        return files.attachSpaceFile(
                spaceId, account.userId(), requiredEpoch(request), idempotencyKey, body.uploadId());
    }

    @DeleteMapping("/api/v1/spaces/{spaceId}/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireSpaceRole(SpaceRole.MEMBER)
    public void deleteSpaceFile(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String fileId,
            HttpServletRequest request) {
        files.deleteSpaceFile(spaceId, fileId, account.userId(), requiredEpoch(request));
    }

    @PostMapping("/api/v1/airdrops")
    @ResponseStatus(HttpStatus.CREATED)
    public AirdropShareDto createAirdrop(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = EPOCH_HEADER, required = false) String rawEpoch,
            @Valid @RequestBody CreateAirdropRequest body) {
        return files.createAirdrop(account.userId(), optionalEpoch(rawEpoch), idempotencyKey, body);
    }

    @GetMapping("/api/v1/airdrops")
    public AirdropShareListResponse listAirdrops(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @RequestParam(value = "role", required = false) AirdropRoleFilter role) {
        return files.listAirdrops(account.userId(), role);
    }

    @GetMapping("/api/v1/airdrops/{shareId}")
    public AirdropShareDto getAirdrop(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String shareId) {
        return files.getAirdrop(account.userId(), shareId);
    }

    @PostMapping("/api/v1/airdrops/{shareId}/recipients/{recipientUserId}/revoke")
    public AirdropShareDto revokeAirdropRecipient(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String shareId,
            @PathVariable String recipientUserId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        return files.revokeAirdropRecipient(
                account.userId(), shareId, recipientUserId, idempotencyKey);
    }

    @PostMapping("/api/v1/airdrops/{shareId}/revoke")
    public AirdropShareDto revokeAirdropShare(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String shareId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        return files.revokeAirdropShare(account.userId(), shareId, idempotencyKey);
    }

    @PostMapping("/api/v1/download-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public DownloadSessionDto createDownloadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = EPOCH_HEADER, required = false) String rawEpoch,
            @Valid @RequestBody CreateDownloadSessionRequest body) {
        return files.createDownloadSession(
                account.userId(), optionalEpoch(rawEpoch), idempotencyKey, body);
    }

    @GetMapping("/api/v1/download-sessions/{downloadSessionId}/content")
    public ResponseEntity<StreamingResponseBody> downloadContent(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String downloadSessionId,
            @RequestHeader(value = EPOCH_HEADER, required = false) String rawEpoch,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        Long headerEpoch = optionalEpoch(rawEpoch);
        OpenedDownloadContent opened =
                files.openDownloadContent(
                        account.userId(),
                        downloadSessionId,
                        headerEpoch,
                        rangeHeader);
        long totalSize = opened.file().sizeBytes();
        long contentLength =
                totalSize == 0 ? 0L : (opened.endByte() - opened.startByte() + 1);

        StreamingResponseBody body =
                out -> {
                    long sent = 0L;
                    long sinceCheck = 0L;
                    try (InputStream in = opened.stream()) {
                        byte[] buf = new byte[65536];
                        for (int read; (read = in.read(buf)) != -1; ) {
                            if (sinceCheck >= 1_048_576L) {
                                files.recheckDownloadAccess(
                                        downloadSessionId, account.userId(), headerEpoch);
                                sinceCheck = 0L;
                            }
                            out.write(buf, 0, read);
                            sent += read;
                            sinceCheck += read;
                        }
                    } finally {
                        if (sent > 0) {
                            files.recordDownloadBytesSent(downloadSessionId, sent);
                        }
                    }
                };

        ResponseEntity.BodyBuilder builder =
                opened.partial() ? ResponseEntity.status(HttpStatus.PARTIAL_CONTENT) : ResponseEntity.ok();
        builder.cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        FileSecuritySupport.safeDownloadContentDisposition(opened.file().fileName()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(contentLength);
        if (opened.partial()) {
            builder.header(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes " + opened.startByte() + "-" + opened.endByte() + "/" + totalSize);
        }
        return builder.body(body);
    }

    private static long requiredEpoch(HttpServletRequest request) {
        try {
            return Long.parseLong(request.getHeader(EPOCH_HEADER));
        } catch (NumberFormatException ex) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }
    }

    private static Long optionalEpoch(String rawEpoch) {
        if (rawEpoch == null || rawEpoch.isBlank()) {
            return null;
        }
        try {
            long val = Long.parseLong(rawEpoch.strip());
            if (val < 1) {
                throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
            }
            return val;
        } catch (NumberFormatException ex) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }
    }
}
