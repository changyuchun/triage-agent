package com.cyc.cyctest.agent.rag.ingest;

import com.cyc.cyctest.agent.rag.KnowledgeCorpus;
import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档摄入服务（生产级）。
 * <p>
 * 分块策略：Spring AI {@link TokenTextSplitter}（基于 token 计数，默认 800 token/块）。
 * 相比字符分割，token 分割与 Embedding 模型（bge-m3）的输入限制天然对齐。
 * <p>
 * 支持格式：
 * - 纯文本（.txt）、Markdown（.md）：直接读取
 * - PDF（.pdf）：通过 Apache PDFBox 提取文本
 * <p>
 * 写入目标：Redis VectorStore（Spring AI RedisVectorStore）。
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private static final int CHUNK_SIZE_TOKENS = 800;
    private static final int MIN_CHUNK_CHARS = 50;

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;

    /** 已索引文档的元信息（仅内存，不需要持久化） */
    private final Map<String, IndexedDocument> indexedDocuments = new ConcurrentHashMap<>();

    public DocumentIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE_TOKENS)
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(MIN_CHUNK_CHARS)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
    }

    // -------- 公共 API --------

    /**
     * 摄入纯文本内容（TXT / MD）。
     */
    public IndexedDocument ingest(String filename, String content, String domainCode, String subDomainCode) {
        String docId = generateDocId();
        List<Document> chunks = splitText(content, docId, filename, domainCode, subDomainCode);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空或无法分块: " + filename);
        }
        vectorStore.add(chunks);
        IndexedDocument indexed = new IndexedDocument(docId, filename, chunks.size(), domainCode, subDomainCode, "text");
        indexedDocuments.put(docId, indexed);
        log.info("文档 {} 已索引，共 {} 块，领域: {}", filename, chunks.size(), domainCode);
        return indexed;
    }

    /**
     * 摄入 PDF 文件字节流（使用 Apache PDFBox 提取文本后再分块）。
     */
    public IndexedDocument ingestPdf(String filename, byte[] pdfBytes, String domainCode, String subDomainCode) {
        String text = extractPdfText(pdfBytes, filename);
        return ingest(filename, text, domainCode, subDomainCode);
    }

    /**
     * 将硬编码的 KnowledgeCorpus 灌入 VectorStore（幂等，重复调用只写新文档）。
     */
    public void ingestBuiltinCorpus(KnowledgeCorpus corpus) {
        List<Document> documents = new ArrayList<>();
        for (KnowledgeChunk chunk : corpus.chunks()) {
            // 使用 chunk.docId() 作为确定性 ID，避免重复索引
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("docId", chunk.docId());
            metadata.put("domainCode", chunk.domainCode());
            metadata.put("subDomainCode", chunk.subDomainCode());
            metadata.put("title", chunk.title());
            metadata.put("source", "builtin-demo-kb");
            documents.add(new Document(chunk.docId(), chunk.content(), metadata));
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            log.info("内置知识库已加载，共 {} 条", documents.size());
        }
    }

    public boolean deleteDocument(String docId) {
        indexedDocuments.remove(docId);
        return true;
    }

    public List<IndexedDocument> listDocuments() {
        return new ArrayList<>(indexedDocuments.values());
    }

    // -------- 内部方法 --------

    private List<Document> splitText(String content, String docId, String filename,
                                      String domainCode, String subDomainCode) {
        if (content == null || content.isBlank()) return List.of();

        Map<String, Object> baseMetadata = new HashMap<>();
        baseMetadata.put("docId", docId);
        baseMetadata.put("filename", filename);
        baseMetadata.put("domainCode", domainCode != null ? domainCode : "general");
        baseMetadata.put("subDomainCode", subDomainCode != null ? subDomainCode : "general");
        baseMetadata.put("source", "user-upload");

        Document source = new Document(content, baseMetadata);
        List<Document> chunks = splitter.apply(List.of(source));

        // 补充 chunkIndex 和 title 元数据
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).getMetadata().put("chunkIndex", i);
            chunks.get(i).getMetadata().put("totalChunks", chunks.size());
            chunks.get(i).getMetadata().put("title", filename + " #" + (i + 1));
        }
        return chunks;
    }

    private String extractPdfText(byte[] pdfBytes, String filename) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            log.info("PDF {} 解析完成，页数: {}，字符数: {}", filename, doc.getNumberOfPages(), text.length());
            return text;
        } catch (IOException e) {
            throw new IllegalArgumentException("PDF 解析失败: " + filename + " - " + e.getMessage(), e);
        }
    }

    private static String generateDocId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public record IndexedDocument(
            String docId,
            String filename,
            int chunkCount,
            String domainCode,
            String subDomainCode,
            String format
    ) {
    }
}
