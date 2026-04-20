# spring-ai-alibaba-agent

基于 Spring AI Alibaba 的 Agent 示例项目，支持：

- DashScope 大模型对话
- 本地 QA 文本知识库向量检索
- Chat UI 页面访问
- 可选 MCP 工具接入

## 1. 环境要求

- JDK 17
- Maven 3.9+
- DashScope API Key
- DashScope 模型名（通过环境变量 `AI_DASHSCOPE_MODEL` 配置）

> 项目当前为 Maven 工程，未包含 `mvnw`，请使用本机 Maven。

## 2. 安装依赖 / 构建

在项目根目录执行：

```powershell
mvn clean compile
```

若需要打包：

```powershell
mvn clean package -DskipTests
```

## 3. 启动前配置

配置文件：`src/main/resources/application.yaml`

关键配置项：

- `spring.ai.dashscope.api-key: ${AI_DASHSCOPE_API_KEY}`
- `spring.ai.dashscope.chat.options.model: ${AI_DASHSCOPE_MODEL}`
- `spring.ai.dashscope.embedding.options.model-name: text-embedding-v3`
- `server.port: 8081`
- `agent.knowledge.raw-dir: ${AGENT_RAW_KNOWLEDGE_DIR:src/main/resources/raw-knowledge}`
- `agent.knowledge.store-file: ${AGENT_KNOWLEDGE_STORE_FILE:src/main/resources/knowledge/vector-store.json}`
- `agent.knowledge.rebuild-on-startup: ${AGENT_KNOWLEDGE_REBUILD:true}`

### 3.1 必填环境变量（PowerShell）

```powershell
$env:AI_DASHSCOPE_API_KEY="你的DashScopeKey"
$env:AI_DASHSCOPE_MODEL="qwen3-max-2026-01-23"
```

`AI_DASHSCOPE_MODEL` 说明：

- 作用：指定对话模型，对应 `spring.ai.dashscope.chat.options.model`
- 是否必填：当前 `application.yaml` 未设置默认值，建议按必填处理
- 示例值：`qwen3-max-2026-01-23`、`qwen-max`、`qwen-plus`（以账号实际可用模型为准）

### 3.2 可选环境变量

```powershell
$env:AGENT_KNOWLEDGE_REBUILD="true"
$env:AGENT_RAW_KNOWLEDGE_DIR="src/main/resources/raw-knowledge"
$env:AGENT_KNOWLEDGE_STORE_FILE="src/main/resources/knowledge/vector-store.json"
$env:MCP_ENABLE="false"
$env:MCP_URL="http://localhost:8000/"
```

`AGENT_KNOWLEDGE_REBUILD` 说明：

- 当前默认值是 `true`，每次启动都会根据原始知识库重建向量库
- 如需加快启动，可手动设置为 `false`（前提是已有可用的 `vector-store.json`）

## 4. 启动项目

```powershell
mvn spring-boot:run
```

或打包后运行：

```powershell
mvn clean package -DskipTests
java -jar target/agent-0.0.1-SNAPSHOT.jar
```

## 5. 访问方式

启动后访问：

- `http://localhost:8081/chatui/index.html`

该地址也会在启动日志中打印（见 `src/main/java/com/ecoos/agent/AgentApplication.java`）。

## 6. 如何增加知识库

知识库加载逻辑位于：`src/main/java/com/ecoos/agent/KnowledgeVectorStoreBootstrap.java`。
默认读取目录：`src/main/resources/raw-knowledge` 下所有 `.txt` 文件。

### 6.1 文本格式要求（必须）

每条知识必须是“问题 + 答案”结构，条目之间空行分隔，例如：

```text
问题：微服务之间调用超时怎么处理？
答案：服务间超时可能是下游服务处理慢、网络延迟、超时配置不合理或熔断器未正确配置，解法各有不同。请提供调用链路、超时配置值和下游服务的响应时间监控数据。

问题：消息队列积压怎么处理？
答案：消息积压的处理方式取决于积压原因（消费者处理慢/消费者数量不足/消息量突增）和消息队列类型。请提供消息队列类型（Kafka/RabbitMQ 等）、积压量级和消费者当前状态。
```

解析规则：

- 支持 `问题:` 或 `问题：`
- 支持 `答案:` 或 `答案：`
- 缺少问题或答案的块会被忽略

### 6.2 新增知识步骤

1. 在 `src/main/resources/raw-knowledge` 新增或编辑 `.txt` 文件。
2. 启动前开启重建向量库（当前默认即为 `true`）。
3. 启动应用，系统会重建并写入向量库文件。
4. 如需后续快速启动，可改成 `false`，直接加载已有向量库。

```powershell
$env:AGENT_KNOWLEDGE_REBUILD="true"
mvn spring-boot:run
$env:AGENT_KNOWLEDGE_REBUILD="false"
```

向量库存储文件默认路径：

- `src/main/resources/knowledge/vector-store.json`

## 7. 可选：MCP 接入

在 `application.yaml` 中已预留 MCP 配置：

- `spring.ai.mcp.client.enabled: ${MCP_ENABLE:false}`
- `spring.ai.mcp.client.sse.connections.das-mcp.url: ${MCP_URL:http://localhost:8000/}`
- `spring.ai.mcp.client.sse.connections.das-mcp.sse-endpoint: /sse`

如需启用：

```powershell
$env:MCP_ENABLE="true"
$env:MCP_URL="http://localhost:8000/"
```

## 8. 项目结构（简要）

- `src/main/java/com/ecoos/agent/AgentApplication.java`：应用启动与访问地址输出
- `src/main/java/com/ecoos/agent/ChatbotAgent.java`：Agent、工具、Hook 组装
- `src/main/java/com/ecoos/agent/KnowledgeVectorStoreBootstrap.java`：知识库解析、向量化与持久化
- `src/main/resources/application.yaml`：配置中心
- `src/main/resources/raw-knowledge/`：原始知识文件
- `src/main/resources/knowledge/vector-store.json`：向量库存储文件

## 9. 注意事项（新增）

- `AI_DASHSCOPE_MODEL` 当前没有默认值，未设置会导致对话模型无法按预期初始化
- `ChatbotAgent` 中技能目录当前写死为 `C:\Users\Admin\.qwen\skills`，如果本机不存在该目录，请按实际环境调整代码

