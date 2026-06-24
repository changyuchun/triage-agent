package com.cyc.cyctest.agent.rag.ingest;

import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.KnowledgeModels.RetrieveRequest;
import com.cyc.cyctest.agent.rag.KnowledgeRetriever;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理 REST API。
 * <p>
 * 支持格式：
 * - 文件上传（Tika 自动检测：PDF / Office / HTML / CSV / TXT / MD 等）  POST /api/knowledge/upload
 * - 纯文本提交                      POST /api/knowledge/upload-text
 * - 文档列表                        GET  /api/knowledge/documents
 * - 删除文档                        DELETE /api/knowledge/documents/{docId}
 * - 检索测试                        GET  /api/knowledge/search
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final DocumentIngestionService ingestionService;
    private final KnowledgeRetriever knowledgeRetriever;

    public KnowledgeController(DocumentIngestionService ingestionService,
                                KnowledgeRetriever knowledgeRetriever) {
        this.ingestionService = ingestionService;
        this.knowledgeRetriever = knowledgeRetriever;
    }

    /**
     * 上传并索引文档。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "domain", defaultValue = "general") String domain,
            @RequestParam(value = "subDomain", defaultValue = "general") String subDomain) {
        try {
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "untitled";
            DocumentIngestionService.IndexedDocument doc =
                    ingestionService.ingestFile(filename, file.getBytes(), domain, subDomain);

            return Map.of(
                    "success", true,
                    "docId", doc.docId(),
                    "filename", doc.filename(),
                    "chunks", doc.chunkCount(),
                    "format", doc.format(),
                    "checksum", doc.checksum(),
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
                "checksum", doc.checksum(),
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
