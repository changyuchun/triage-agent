# 生产级 RAG 开发流程、问题与优化路径

## 1. 本项目里的 RAG 链路

当前项目采用 Spring Boot + Spring AI + Redis VectorStore + Apache Tika：

1. 文档上传：`POST /api/knowledge/upload`
2. 文档解析：Apache Tika `AutoDetectParser` 自动识别 PDF、Office、HTML、CSV、TXT、Markdown 等格式，抽取正文和元数据
3. 文档分块：Spring AI `TokenTextSplitter` 按 token 分块，避免 embedding 输入超限
4. 双索引写入：
   - Dense index：`VectorStore.add()` 写入 Redis VectorStore
   - Sparse index：`RuntimeKnowledgeIndex` 写入 BM25 运行时索引
5. 检索：
   - Query Rewriting 生成多角度查询
   - BM25 负责关键词、错误码、订单号、专有名词召回
   - VectorStore 负责语义相似召回
   - RRF 融合排序
6. 回答生成：Agent 使用召回证据合成答案，低置信度时走澄清或工具查询

对应代码：

- 文档解析与入库：`src/main/java/com/cyc/cyctest/agent/rag/ingest/DocumentIngestionService.java`
- 知识库 API：`src/main/java/com/cyc/cyctest/agent/rag/ingest/KnowledgeController.java`
- 运行时 BM25 索引：`src/main/java/com/cyc/cyctest/agent/rag/RuntimeKnowledgeIndex.java`
- BM25：`src/main/java/com/cyc/cyctest/agent/rag/Bm25Retriever.java`
- 混合检索：`src/main/java/com/cyc/cyctest/agent/rag/vector/SpringAiKnowledgeRetriever.java`

## 2. 为什么用 Tika，而不是自己解析 PDF / Word

生产知识库文档来源很杂：PDF、Word、PPT、Excel、HTML、邮件、CSV、压缩包内文件、不同编码的文本。只用 PDFBox 或 POI 会让摄入层不断膨胀，且格式检测、元数据、异常文件处理都要自己补。

Apache Tika 的角色是文档摄入层的统一抽象：

- 自动检测 MIME 类型，不完全依赖文件后缀
- 抽取正文
- 抽取标题、作者、创建时间、页数、内容类型等元数据
- 底层复用 PDFBox、POI 等成熟解析器

本项目用 Tika 后，`KnowledgeController` 不再按后缀分支处理 PDF/TXT，而是统一交给 `DocumentIngestionService.ingestFile()`。

## 3. 生产级 RAG 的标准开发流程

### 阶段一：知识摄入

目标不是“能上传”，而是“可追踪、可重放、可删除、可评估”。

关键动作：

- 文件落库或对象存储，记录租户、权限、版本、来源、checksum
- Tika 解析正文与 metadata
- 清洗 boilerplate：页眉页脚、目录、广告、重复版权声明
- 分块并保留结构信息：标题、章节、页码、表格位置、chunkIndex
- 按稳定 docId/chunkId 写入向量库，支持幂等更新
- 同步写入 sparse index，支持 BM25/关键词检索

本项目实现了 checksum docId、稳定 chunkId、Tika metadata、dense+sparse 双索引。

### 阶段二：检索

生产 RAG 很少只靠一次向量检索。典型做法是：

- Query rewrite：补全缩写、改写多角度查询
- Hybrid search：BM25 + vector
- Metadata filter：按 domain、subDomain、租户、权限、时间过滤
- Rerank：用 cross-encoder 或 LLM 对候选证据重排
- Evidence threshold：低分不强答，转澄清或工具查询

本项目已有 Multi-Query、BM25、VectorStore、RRF。下一步可以加 reranker。

### 阶段三：生成

生成层重点是“受证据约束”：

- prompt 中要求引用 evidence
- 禁止使用未检索到的内部事实
- 对冲突证据做显式说明
- 对低置信问题回答“缺少证据”，而不是编造
- 对订单、支付、营销类问题优先调用工具，知识库只做背景解释

### 阶段四：评估与迭代

RAG 优化不能只看主观感觉，需要离线评测集：

- Recall@K：正确证据是否被召回
- MRR / nDCG：正确证据排名是否靠前
- Faithfulness：答案是否被证据支持
- Answer correctness：答案是否解决问题
- Latency / cost：检索、rerank、LLM 调用耗时和费用

本项目已有 `src/main/resources/eval/cases.yaml` 和 evaluation 包，可以继续扩展 RAG case。

## 4. 常见问题与优化路径

| 问题 | 原因 | 优化 |
| --- | --- | --- |
| 召回不到正确文档 | 分块过大/过小、query 和文档语义鸿沟、只用向量 | 调 chunk size；加 query rewrite；BM25+vector；加 rerank |
| 错误码、订单号、专有名词检索差 | embedding 对精确符号不敏感 | BM25、keyword field、metadata filter |
| 答案幻觉 | prompt 没有限制；证据不足仍强答 | 证据阈值、引用证据、无证据拒答 |
| 上传同文档重复索引 | docId 随机生成 | checksum 作为稳定 docId；chunkId 确定性生成 |
| 文档删除后还能搜到 | 只删业务元数据，没删向量块 | 保存 chunkIds，删除时调用 VectorStore.delete |
| Office/PDF 解析乱码或丢表格 | 解析器能力边界、扫描件无 OCR | Tika + OCR pipeline；表格单独结构化 |
| 大文件摄入慢 | 同步解析、同步 embedding | 队列异步化、批量 embedding、增量索引 |
| 权限泄露 | 检索时只按语义搜，没有权限过滤 | tenant/user/acl metadata filter，生成前二次校验 |
| 更新不一致 | dense/sparse/元数据库部分成功 | ingestion job 状态机、重试、补偿、死信队列 |
| 线上效果变差难定位 | 无观测 | 记录 query、rewrite、召回列表、分数、rerank、最终引用 |

## 5. 面试可以这样讲

我的 RAG 不是 naive RAG。摄入层使用 Apache Tika 做通用文档解析，解析后用 token-aware splitter 分块，并用 checksum 生成稳定 docId，保证重复上传和更新时可以幂等。索引层同时写 Redis VectorStore 和运行时 BM25 sparse index，因为生产问题里错误码、订单号、业务专有名词只靠向量召回不稳定。检索层做 Multi-Query rewrite，再把 BM25 和向量召回用 RRF 融合。生成层要求答案基于 evidence，低分时不强答。后续优化会加 reranker、异步摄入队列、权限过滤、OCR、表格结构化和离线评测。

如果被问“为什么不用 LangChain / LlamaIndex”，可以回答：

Java/Spring 项目里 Spring AI 更贴近现有技术栈，能直接复用 Spring Boot 配置、Actuator、Micrometer、Redis、Resilience4j 和业务服务。Tika 负责文档解析，Spring AI 负责 embedding、VectorStore、splitter 和模型抽象。LangChain/LlamaIndex 更适合 Python 快速实验，但生产系统要优先考虑和现有服务治理、监控、权限、部署体系的集成。

## 6. 资料来源

- Apache Tika: https://tika.apache.org/
- Apache Tika Parser API: https://tika.apache.org/3.3.1/api/org/apache/tika/parser/Parser.html
- Spring AI ETL Pipeline: https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html
- Spring AI Vector Databases: https://docs.spring.io/spring-ai/reference/api/vectordbs.html
- Qdrant Hybrid Queries: https://qdrant.tech/documentation/concepts/hybrid-queries/
