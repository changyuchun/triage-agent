package com.cyc.cyctest.agent.rag.ingest;

import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.KnowledgeModels.RetrieveRequest;
import com.cyc.cyctest.agent.rag.KnowledgeRetriever;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库管理 REST API。
 * <p>
 * 支持格式：
 * - 文件上传（.txt / .md / .pdf）  POST /api/knowledge/upload
 * - 纯文本提交                      POST /api/knowledge/upload-text
 * - 文档列表                        GET  /api/knowledge/documents
 * - 删除文档                        DELETE /api/knowledge/documents/{docId}
 * - 检索测试                        GET  /api/knowledge/search
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Set<String> PDF_EXTENSIONS = Set.of(".pdf");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".txt", ".md", ".text", ".markdown");

    private final DocumentIngestionService ingestionService;
    private final KnowledgeRetriever knowledgeRetriever;

    public KnowledgeController(DocumentIngestionService ingestionService,
                                KnowledgeRetriever knowledgeRetriever) {
        this.ingestionService = ingestionService;
        this.knowledgeRetriever = knowledgeRetriever;
    }

    /**
     * 上传并索引文档（支持 .txt / .md / .pdf）。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "domain", defaultValue = "general") String domain,
            @RequestParam(value = "subDomain", defaultValue = "general") String subDomain) {
        try {
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "untitled";
            String ext = getExtension(filename).toLowerCase();
            DocumentIngestionService.IndexedDocument doc;

            if (PDF_EXTENSIONS.contains(ext)) {
                doc = ingestionService.ingestPdf(filename, file.getBytes(), domain, subDomain);
            } else if (TEXT_EXTENSIONS.contains(ext) || ext.isEmpty()) {
                String content = new String(file.getBytes(), StandardCharsets.UTF_8);
                doc = ingestionService.ingest(filename, content, domain, subDomain);
            } else {
                return Map.of("success", false, "message",
                        "不支持的文件格式 " + ext + "，支持：.txt .md .pdf");
            }

            return Map.of(
                    "success", true,
                    "docId", doc.docId(),
                    "filename", doc.filename(),
                    "chunks", doc.chunkCount(),
                    "format", doc.format(),
                    "message", "文档已索引，共 " + doc.chunkCount() + " 个分块"
            );
        } catch (IOException e) {
            return Map.of("success", false, "message", "文件读取失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 上传纯文本内容并索引。
     */
    @PostMapping("/upload-text")
    public Map<String, Object> uploadText(@RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "");
        String filename = body.getOrDefault("filename", "text-input");
        String domain = body.getOrDefault("domain", "general");
        String subDomain = body.getOrDefault("subDomain", "general");

        if (content.isBlank()) {
            return Map.of("success", false, "message", "内容不能为空");
        }

        DocumentIngestionService.IndexedDocument doc = ingestionService.ingest(filename, content, domain, subDomain);
        return Map.of(
                "success", true,
                "docId", doc.docId(),
                "filename", doc.filename(),
                "chunks", doc.chunkCount(),
                "message", "文本已索引，共 " + doc.chunkCount() + " 个分块"
        );
    }

    /**
     * 列出已索引文档。
     */
    @GetMapping("/documents")
    public List<DocumentIngestionService.IndexedDocument> listDocuments() {
        return ingestionService.listDocuments();
    }

    /**
     * 删除文档。
     */
    @DeleteMapping("/documents/{docId}")
    public Map<String, Object> deleteDocument(@PathVariable String docId) {
        boolean deleted = ingestionService.deleteDocument(docId);
        return Map.of("success", deleted, "docId", docId);
    }

    /**
     * 语义检索测试。
     */
    @GetMapping("/search")
    public List<SearchResult> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) String domain) {
        RetrieveRequest request = new RetrieveRequest(query, domain, null, Map.of(), topK);
        List<KnowledgeChunk> chunks = knowledgeRetriever.retrieve(request);
        return chunks.stream()
                .map(c -> new SearchResult(
                        c.chunkId(), c.title(), c.content(), c.score(),
                        c.domainCode(), c.subDomainCode(),
                        c.metadata() != null ? String.valueOf(c.metadata().getOrDefault("source", "")) : ""
                ))
                .toList();
    }

    private static String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot >= 0 ? filename.substring(lastDot) : "";
    }

    public record SearchResult(
            String chunkId,
            String title,
            String content,
            double score,
            String domainCode,
            String subDomainCode,
            String source
    ) {
    }
}
