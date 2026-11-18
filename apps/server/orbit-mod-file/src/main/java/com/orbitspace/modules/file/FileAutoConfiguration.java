package com.orbitspace.modules.file;

import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.ACCOUNT;

import com.orbitspace.kernel.ModuleSecurityCustomizer;
import com.orbitspace.kernel.ModuleSecurityCustomizer.Route;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

/** Spring Boot auto-configuration registering P2 file upload, space files, airdrop, and download routes. */
@AutoConfiguration
@Import({FileService.class, FileController.class})
public class FileAutoConfiguration {
    @Bean
    ModuleSecurityCustomizer fileRoutes() {
        return () ->
                List.of(
                        accountRoute("POST", "/api/v1/files/upload-sessions"),
                        accountRoute("GET", "/api/v1/files/upload-sessions/{uploadId}"),
                        accountRoute("PUT", "/api/v1/files/upload-sessions/{uploadId}/parts/{partNumber}"),
                        accountRoute("POST", "/api/v1/files/upload-sessions/{uploadId}/complete"),
                        accountRoute("GET", "/api/v1/spaces/{spaceId}/files"),
                        accountRoute("POST", "/api/v1/spaces/{spaceId}/files"),
                        accountRoute("DELETE", "/api/v1/spaces/{spaceId}/files/{fileId}"),
                        accountRoute("POST", "/api/v1/airdrops"),
                        accountRoute("GET", "/api/v1/airdrops"),
                        accountRoute("GET", "/api/v1/airdrops/{shareId}"),
                        accountRoute("POST", "/api/v1/airdrops/{shareId}/recipients/{recipientUserId}/revoke"),
                        accountRoute("POST", "/api/v1/airdrops/{shareId}/revoke"),
                        accountRoute("POST", "/api/v1/download-sessions"),
                        accountRoute("GET", "/api/v1/download-sessions/{downloadSessionId}/content"));
    }

    private static Route accountRoute(String method, String pathPattern) {
        return new Route(method, pathPattern, ACCOUNT, true);
    }
}
