/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ecoos.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.ReadFileTool;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.springframework.ai.chat.model.ChatModel;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;


import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
public class ChatbotAgent {

    private static final String INSTRUCTION = """
            # Role
            你是一位**基于实时工具调用的工业物联网 (IIoT) 故障诊断专家**。
            你的核心原则是：**工具即真理 (Tool is Truth)**。
            你没有任何先验知识，所有判断、推理和建议必须**严格且仅**源自工具返回的执行结果。
            
            # Core Principles (绝对准则)
            1. **无工具，不结论**：
               - 严禁依赖训练数据、通用常识或设备手册记忆来回答具体故障。
               - 在回答任何具体问题前，**必须**先规划并调用相应的诊断工具。
               - 若工具调用返回错误、超时或空值，必须直接陈述“工具执行失败，无法获取数据”，**严禁**猜测故障原因（如网络波动、设备离线等）。
            
            2. **数据即事实**：
               - 所有状态、数值、错误码必须直接引用工具返回的原始字段。
               - 语言风格：客观、克制、精准。禁止使用“可能”、“也许”、“通常”等模糊词汇。
               - 若工具数据与用户描述冲突，以工具实时数据为准。
            
            3. **严格溯源**：
               - 每个结论后必须标注数据来源，格式为：`[数据来源：工具名称]`。
               - 不得虚构任何文档来源或未调用的工具结果。
            
            # Workflow (执行流程)
            收到请求后，严格按以下步骤执行，禁止跳过：
            
            ## Step 1: 工具规划
            - 分析用户意图，确定必须调用的工具列表（如：状态查询、日志拉取、寄存器读取）。
            - **行动**：立即生成工具调用请求。此时**不输出**任何诊断结论。
            
            ## Step 2: 数据验证
            - 接收工具返回结果。
            - **关键判断**：
              - 若结果有效 -> 进入 Step 3。
              - 若结果包含错误信息（包括连接超时、空指针、服务不可用等） -> **立即终止推理**，直接进入 [异常报告模式]。
              - **注意**：即使工具返回了 "timeout" 字样，也仅代表“获取数据失败”，不代表“网络超时是故障原因”。你不得对此进行延伸解释。
            
            ## Step 3: 基于证据的推理
            - 仅根据工具返回的具体字段值进行逻辑推导。
            - 例：仅当工具明确返回 `status: "OVERHEAT"` 时，方可结论“设备过热”。
            
            ## Step 4: 生成报告
            - 按照下方 [输出模板] 生成回复。
            
            # Constraints (行为约束)
            - **禁止猜测**：如果工具没返回数据，就说没数据。不要说“可能是网络不好”。
            - **禁止伪造**：不得修改工具参数或编造返回值。
            - **禁止解释工具错误**：工具报错就是报错，不要分析报错的技术原因（如 DNS 解析失败、端口不通等），除非工具返回的详细信息里明确写了原因。
            
            # Response Templates (输出模板)
            
            ## 场景 A：工具成功返回数据
            ### 实时故障诊断报告
            **诊断对象**：(填入设备ID)
            **数据获取时间**：(填入当前系统时间)
            
            **工具执行记录**：
            - 已调用工具：(工具名称)
            - 关键数据：(引用具体字段值，例如：错误码=503, 温度=75℃) [数据来源：(工具名称)]
            
            **根因分析**：
            根据工具返回数据，(字段名) 显示为 (具体值)，符合 (工具定义的状态)。
            (此处仅陈述工具数据直接支持的结论，不进行发散)
            
            **标准化处置建议**：
            1. (基于工具返回建议的操作)
               - 执行步骤：(严格源自工具返回的指导)
            
            **注意**：以上结论完全基于实时工具数据。
            
            ## 场景 B：工具执行失败 (含超时/空值/报错)
            ### 诊断中断报告
            **状态**：工具执行失败
            **详情**：调用 (工具名称) 时未获取到有效数据（返回错误/超时/空值）。
            **结论**：由于缺乏实时数据支持，**无法判断故障原因**。
            **建议**：请检查设备网络连接或稍后重试诊断工具。
            **[数据来源：无]**
            """;

    @Bean
    public ReactAgent chatbotReactAgent(ChatModel chatModel,
                                        List<ToolCallback> otherTools,
                                        SyncMcpToolCallbackProvider toolCallbackProvider,
                                        MemorySaver memorySaver,
                                        MessageTrimmingHook messagesModelHook) {

        SkillRegistry registry = FileSystemSkillRegistry.builder()
                .projectSkillsDirectory("C:\\Users\\sesa755454\\.copilot\\skills")
                .build();

        SkillsAgentHook hook = SkillsAgentHook.builder()
                .skillRegistry(registry)
                .autoReload(true)
                .build();


        return ReactAgent.builder()
                .name("DAS")
                .model(chatModel)
                .instruction(INSTRUCTION)
                .enableLogging(true)
                .saver(memorySaver)
                .tools(otherTools)
                .toolCallbackProviders(toolCallbackProvider)
                .hooks(List.of(hook,messagesModelHook))
                .build();
    }

    @Bean
    public MemorySaver memorySaver() {
        return new MemorySaver();
    }


    public ToolCallback searchDocuments(VectorStore vectorStore) {
        return FunctionToolCallback.builder("search_documents",
                        // 2. 简化 Lambda 表达式逻辑，直接调用 similaritySearch(String query)
                        (Function<DocumentSearchRequest, DocumentSearchResponse>) request -> {
                            // 直接传入查询字符串进行检索
                            List<Document> docs = vectorStore.similaritySearch(request.query());

                            // 3. 合并文档内容
                            // 参考代码逻辑：将检索到的多个文档内容合并为一个字符串返回
                            // 这样 Agent 能更直接地获取上下文，而不是处理复杂的 JSON 结构
                            String combinedContent = docs.stream()
                                    .map(Document::getText) // 或者 doc.getContent() 取决于具体版本，通常是 getText()
                                    .collect(Collectors.joining("\n\n"));

                            return new DocumentSearchResponse(combinedContent);
                        })
                .description("搜索知识库文档以查找与故障相关的信息。输入查询语句，返回相关的文档片段内容。")
                .inputType(DocumentSearchRequest.class)
                .build();
    }

    //    @Bean
    public VectorStore generateVectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            List<Document> documents = Arrays.stream(
                            resolver.getResources("classpath:knowledge/*"))
                    .flatMap(resource -> {
                        TextReader textReader = new TextReader(resource);
                        return textReader.get().stream();
                    })
                    .collect(Collectors.toList());

            store.add(documents);
        } catch (IOException e) {
            throw new RuntimeException("加载文档失败", e);
        }

        return store;
    }
    // ==================== 内部数据类 ====================

    /**
     * 文档搜索请求
     * 简化：只保留查询语句。
     * 原因：topK 和 threshold 应由后端逻辑控制，避免 Agent 产生幻觉或输入无效参数。
     */
    public record DocumentSearchRequest(String query) {
    }

    /**
     * 文档搜索响应
     * 简化：直接返回合并后的文本内容。
     * 原因：Agent 需要的是“上下文信息”，而不是原始的数据结构。
     * 合并后的文本能让 LLM 更流畅地阅读和引用。
     */
    public record DocumentSearchResponse(String content) {
    }

    // 假设您有一个空的请求类，或者可以直接使用 Object/Void 的包装器
// 如果框架支持无参函数，可以直接用 Supplier，但为了匹配您的 Function<Input, Output> 模式：
    record TimeRequest(String timezone) {
    } // 允许用户指定时区，默认则在逻辑处理

    record TimeResponse(String currentTimeIso, String currentUnixEpoch) {
    }

    @Bean
    public ToolCallback getCurrentTime() {
        return FunctionToolCallback.builder("get_current_time",
                        // 逻辑：接收可选的时区请求（或忽略），返回当前时间的标准化字符串
                        (Function<TimeRequest, String>) request -> {
                            // 1. 确定时区：如果请求中包含时区则使用，否则默认使用系统默认或 UTC+8 (根据您之前的上下文)
                            ZoneId zoneId = (request.timezone() != null && !request.timezone().isBlank())
                                    ? ZoneId.of(request.timezone())
                                    : ZoneId.of("Asia/Shanghai"); // 默认根据您的环境设为上海时间

                            // 2. 获取当前时间
                            ZonedDateTime now = ZonedDateTime.now(zoneId);

                            // 3. 格式化输出 (直接合并为易读的字符串，方便 Agent 直接引用)
                            DateTimeFormatter formatter = DateTimeFormatter.ISO_ZONED_DATE_TIME;
                            String isoTime = now.format(formatter);
                            long unixEpoch = now.toInstant().getEpochSecond();

                            // 返回合并后的内容，类似 search_documents 返回 combinedContent
                            return String.format("当前时间 (ISO): %s | 时区: %s | Unix 时间戳: %d",
                                    isoTime, zoneId.getId(), unixEpoch);
                        })
                .description("获取当前的系统时间。可选输入时区（例如 'UTC', 'Asia/Shanghai'），默认返回亚洲/上海时间。返回格式包含 ISO 时间戳和 Unix 纪元秒数。")
                .inputType(TimeRequest.class) // 需要定义对应的 Record 类
                .build();
    }

}

