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

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.springframework.ai.chat.model.ChatModel;

import org.springframework.ai.document.Document;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
public class ChatbotAgent {

    private static final String INSTRUCTION = """
            你是一个高效的工具调用助手。你的核心职责是：
            1. 理解用户需求
            2. 选择合适的工具执行
            3. 将工具返回结果直接呈现给用户
            
            执行原则：
            - 优先使用可用工具完成任务
            - 工具返回什么就输出什么，不做额外解释
            - 若工具执行失败，直接报告错误信息
            - 不要编造或猜测工具未返回的内容
            """;

    @Bean
    public ReactAgent chatbotReactAgent(ChatModel chatModel,
                                        List<ToolCallback> otherTools,
                                        @Autowired(required = false) SyncMcpToolCallbackProvider toolCallbackProvider,
                                        MemorySaver memorySaver,
                                        MessageTrimmingHook messagesModelHook) {

        SkillRegistry registry = FileSystemSkillRegistry.builder()
                .projectSkillsDirectory("C:\\Users\\Admin\\.qwen\\skills")
                .build();

        SkillsAgentHook hook = SkillsAgentHook.builder()
                .skillRegistry(registry)
                .autoReload(true)
                .build();


        Builder builder = ReactAgent.builder()
                .name("DAS")
                .model(chatModel)
                .instruction(INSTRUCTION)
                .enableLogging(true)
                .saver(memorySaver)
                .tools(otherTools)
                .hooks(List.of(hook, messagesModelHook));
        if (toolCallbackProvider != null) {
            builder.toolCallbackProviders(toolCallbackProvider);
        }
        return builder.build();

    }

    @Bean
    public MemorySaver memorySaver() {
        return new MemorySaver();
    }


    @Bean
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
                                    .map(this::formatQaDocument)
                                    .collect(Collectors.joining("\n\n"));

                            return new DocumentSearchResponse(combinedContent);
                        })
                .description("搜索知识库文档以查找与故障相关的信息。输入查询语句，返回相关的文档片段内容。")
                .inputType(DocumentSearchRequest.class)
                .build();
    }

    private String formatQaDocument(Document doc) {
        String question = doc.getText() == null ? "" : doc.getText().trim();
        Object answerObj = doc.getMetadata() == null ? null : doc.getMetadata().get("answer");
        String answer = answerObj == null ? "" : String.valueOf(answerObj).trim();
        return "问题：" + question + "\n答案：" + answer;
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

