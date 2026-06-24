package com.cyc.cyctest.agent.rag.ingest;

import com.cyc.cyctest.agent.rag.KnowledgeCorpus;
import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.RuntimeKnowledgeIndex;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档摄入服务（生产级）。
 * <p>
 * 分块策略：Spring AI {@link TokenTextSplitter}（基于 token 计数，默认 800 token/块）。
 * 相比字符分割，token 分割与 Embedding 模型（bge-m3）的输入限制天然对齐。
 * <p>
 * 支持格式：
 * - 文本、Markdown、HTML、PDF、Office、CSV 等：通过 Apache Tika 自动检测并提取
 * <p>
 * 写入目标：Redis VectorStore（Spring AI RedisVectorStore）。
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private static final int CHUNK_SIZE_TOKENS = 800;
    private static final int MIN_CHUNK_CHARS = 50;
    private static final int MAX_EXTRACTED_CHARS = 5_000_000;

    private final VectorStore vectorStore;
    private final RuntimeKnowledgeIndex runtimeKnowledgeIndex;
    private final TokenTextSplitter splitter;
    private final AutoDetectParser parser;

    /** 已索引文档的元信息（仅内存，不需要持久化） */
    private final Map<String, IndexedDocument> indexedDocuments = new ConcurrentHashMap<>();

    public DocumentIngestionService(VectorStore vectorStore, RuntimeKnowledgeIndex runtimeKnowledgeIndex) {
        this.vectorStore = vectorStore;
        this.runtimeKnowledgeIndex = runtimeKnowledgeIndex;
        this.parser = new AutoDetectParser();
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
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        ExtractedDocument extracted = new ExtractedDocument(content, Map.of(
                "contentType", "text/plain",
                "parser", "plain-text"
        ));
        return indexExtracted(filename, bytes, extracted, domainCode, subDomainCode);
    }

    /**
     * 摄入任意 Tika 可识别文档字节流，提取正文与元数据后再分块。
     */
    public IndexedDocument ingestFile(String filename, byte[] bytes, String domainCode, String subDomainCode) {
        ExtractedDocument extracted = extractWithTika(bytes, filename);
        return indexExtracted(filename, bytes, extracted, domainCode, subDomainCode);
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
        IndexedDocument indexed = indexedDocuments.remove(docId);
        List<String> chunkIds = runtimeKnowledgeIndex.deleteDocument(docId);
        if (indexed != null && indexed.chunkIds() != null && !indexed.chunkIds().isEmpty()) {
            chunkIds = indexed.chunkIds();
        }
        if (!chunkIds.isEmpty()) {
            vectorStore.delete(chunkIds);
        }
        return true;
    }

    public List<IndexedDocument> listDocuments() {
        return new ArrayList<>(indexedDocuments.values());
    }

    // -------- 内部方法 --------

    private IndexedDocument indexExtracted(String filename, byte[] bytes, ExtractedDocument extracted,
                                           String domainCode, String subDomainCode) {
        String checksum = sha256(bytes);
        String docId = checksum.substring(0, 16);
        List<Document> chunks = splitText(extracted.text(), docId, filename, domainCode, subDomainCode,
                checksum, bytes.length, extracted.metadata());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空或无法分块: " + filename);
        }

        vectorStore.add(chunks);
        runtimeKnowledgeIndex.upsertDocument(docId, toKnowledgeChunks(chunks));

        IndexedDocument indexed = new IndexedDocument(
                docId,
                filename,
                chunks.size(),
                domainCode,
                subDomainCode,
                String.valueOf(chunks.getFirst().getMetadata().getOrDefault("contentType", "unknown")),
                checksum,
                chunks.stream().map(Document::getId).toList()
        );
        indexedDocuments.put(docId, indexed);
        log.info("文档 {} 已索引，docId={}，共 {} 块，领域: {}，类型: {}",
                filename, docId, chunks.size(), domainCode, indexed.format());
        return indexed;
    }

    private List<Document> splitText(String content, String docId, String filename,
                                     String domainCode, String subDomainCode, String checksum,
                                     int byteSize, Map<String, Object> extractedMetadata) {
        if (content == null || content.isBlank()) return List.of();

        Map<String, Object> baseMetadata = new HashMap<>();
        baseMetadata.put("docId", docId);
        baseMetadata.put("filename", filename);
        baseMetadata.put("domainCode", domainCode != null ? domainCode : "general");
        baseMetadata.put("subDomainCode", subDomainCode != null ? subDomainCode : "general");
        baseMetadata.put("source", "user-upload");
        baseMetadata.put("checksum", checksum);
        baseMetadata.put("byteSize", byteSize);
        baseMetadata.put("extractedChars", content.length());
        baseMetadata.put("ingestedAt", Instant.now().toString());
        baseMetadata.putAll(extractedMetadata);

        Document source = new Document(content, baseMetadata);
        List<Document> splitDocuments = splitter.apply(List.of(source));

        List<Document> chunks = new ArrayList<>();
        for (int i = 0; i < splitDocuments.size(); i++) {
            Document split = splitDocuments.get(i);
            Map<String, Object> chunkMetadata = new HashMap<>(split.getMetadata());
            chunkMetadata.put("chunkIndex", i);
            chunkMetadata.put("totalChunks", splitDocuments.size());
            chunkMetadata.put("title", filename + " #" + (i + 1));
            chunks.add(new Document(docId + "-" + i, split.getText(), chunkMetadata));
        }
        return chunks;
    }

    private ExtractedDocument extractWithTika(byte[] bytes, String filename) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        BodyContentHandler handler = new BodyContentHandler(new WriteOutContentHandler(MAX_EXTRACTED_CHARS));
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            parser.parse(input, handler, metadata, new ParseContext());
            String text = handler.toString();
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文档未提取到可索引文本: " + filename);
            }
            Map<String, Object> tikaMetadata = new HashMap<>();
            for (String name : metadata.names()) {
                String value = metadata.get(name);
                if (value != null && !value.isBlank()) {
                    tikaMetadata.put("tika." + name, value);
                }
            }
            tikaMetadata.put("contentType", metadata.get(Metadata.CONTENT_TYPE) != null
                    ? metadata.get(Metadata.CONTENT_TYPE)
                    : "application/octet-stream");
            tikaMetadata.put("parser", parser.getClass().getSimpleName());
            log.info("Tika 解析完成: {}，类型: {}，字符数: {}",
                    filename, tikaMetadata.get("contentType"), text.length());
            return new ExtractedDocument(text, tikaMetadata);
        } catch (IOException | SAXException | TikaException e) {
            throw new IllegalArgumentException("文档解析失败: " + filename + " - " + e.getMessage(), e);
        }
    }

    private List<KnowledgeChunk> toKnowledgeChunks(List<Document> documents) {
        return documents.stream()
                .map(doc -> {
                    Map<String, Object> meta = doc.getMetadata();
                    return new KnowledgeChunk(
                            doc.getId(),
                            String.valueOf(meta.getOrDefault("docId", doc.getId())),
                            String.valueOf(meta.getOrDefault("domainCode", "general")),
                            String.valueOf(meta.getOrDefault("subDomainCode", "general")),
                            String.valueOf(meta.getOrDefault("title", "")),
                            doc.getText(),
                            0,
                            meta
                    );
                })
                .toList();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    public record IndexedDocument(
            String docId,
            String filename,
            int chunkCount,
            String domainCode,
            String subDomainCode,
            String format,
            String checksum,
            List<String> chunkIds
    ) {
    }

    private record ExtractedDocument(
            String text,
            Map<String, Object> metadata
    ) {
    }
}
