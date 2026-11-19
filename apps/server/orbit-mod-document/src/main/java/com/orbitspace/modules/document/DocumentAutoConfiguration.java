package com.orbitspace.modules.document;

import static com.orbitspace.modules.document.DocumentController.*;

import com.orbitspace.kernel.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.*;

@AutoConfiguration
@Import({DocumentStore.class, DocumentController.class, DocumentTransformService.class,
        DocumentCollabService.class, DocumentVersionService.class, DocumentCommentService.class})
public class DocumentAutoConfiguration {
    @Bean("aiWorkerInput")
    Function<AsyncJobPort.Job, Map<String, Object>> aiWorkerInput(DocumentAiService ai) {
        return ai::workerInput;
    }
    @Bean("aiResultTarget")
    DocumentAiService aiResultTarget(DocumentStore store, DocumentVersionService versions,
            DocumentPdfService pdf,
            org.springframework.beans.factory.ObjectProvider<AiSummaryGatewayPort> gateway) {
        return new DocumentAiService(store, versions, pdf, gateway);
    }
    @Bean("pdfResultTarget")
    DocumentPdfService pdfResultTarget(DocumentStore store, FileQuotaAndGrantPort files, AsyncJobPort jobs) {
        return new DocumentPdfService(store, files, jobs);
    }

    @Bean
    ModuleSecurityCustomizer documentRoutes() {
        Map<String, List<String>> paths = Map.of("GET",
                List.of("/documents", DOC + "/saved-boundary", DOC + "/versions", VERSION,
                        VERSION + "/markdown", DOC + "/comments/{commentId}",
                        PDF + "/pdf-comments/{commentId}", PDF + "/pdf-index", "/ai/config", AI),
                "POST",
                List.of("/documents", DOC + "/collab-ticket", DOC + "/versions", DOC + "/quote-versions",
                        VERSION + "/restore-preview", VERSION + "/restore", DOC + "/markdown",
                        DOC + "/comments", PDF + "/pdf-comments", PDF + "/pdf-index", "/ai/summaries",
                        AI + "/commit"),
                "DELETE", List.of(DOC), "PUT", List.of("/ai/config"));
        return ()
                       -> paths.entrySet()
                                  .stream()
                                  .flatMap(e
                                          -> e.getValue().stream().map(path
                                                  -> new ModuleSecurityCustomizer.Route(e.getKey(),
                                                          "/api/v1/spaces/{spaceId}" + path,
                                                          ModuleSecurityCustomizer.Access.ACCOUNT, true)))
                                  .toList();
    }
}
