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
- `server.port: 8081`
- `agent.knowledge.raw-dir: ${AGENT_RAW_KNOWLEDGE_DIR:src/main/resources/raw-knowledge}`
- `agent.knowledge.store-file: ${AGENT_KNOWLEDGE_STORE_FILE:src/main/resources/knowledge/vector-store.json}`
- `agent.knowledge.rebuild-on-startup: ${AGENT_KNOWLEDGE_REBUILD:false}`

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
$env:AGENT_KNOWLEDGE_REBUILD="false"
$env:AGENT_RAW_KNOWLEDGE_DIR="src/main/resources/raw-knowledge"
$env:AGENT_KNOWLEDGE_STORE_FILE="src/main/resources/knowledge/vector-store.json"
$env:MCP_ENABLE="false"
$env:MCP_URL="http://localhost:8000/"
```

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
问题：什么是活动报警？什么是历史报警？
答案：报警触发时，在alarm_active表中存储，称为活动报警；报警解除后，在alarm_history表中存储，称为历史报警。

问题：报警为什么不触发？
答案：报警不触发有多种原因，不同报警类型排查手段不同，需要先明确报警类型。
```

解析规则：

- 支持 `问题:` 或 `问题：`
- 支持 `答案:` 或 `答案：`
- 缺少问题或答案的块会被忽略

### 6.2 新增知识步骤

1. 在 `src/main/resources/raw-knowledge` 新增或编辑 `.txt` 文件。
2. 启动前开启重建向量库。
3. 启动应用，系统会重建并写入向量库文件。
4. 重建完成后建议恢复为 `false`，以加快后续启动。

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
