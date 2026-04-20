package com.ecoos.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
public class KnowledgeVectorStoreBootstrap {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorStoreBootstrap.class);
    private static final Pattern QUESTION_PATTERN = Pattern.compile("(?m)^问题[：:]\\s*(.+)$");
    private static final Pattern ANSWER_PATTERN = Pattern.compile("(?s)答案[：:]\\s*(.+)$");

    @Bean
    public VectorStore knowledgeVectorStore(EmbeddingModel embeddingModel,
                                            @Value("${agent.knowledge.raw-dir:src/main/resources/raw-knowledge}") String rawKnowledgeDir,
                                            @Value("${agent.knowledge.store-file:src/main/resources/knowledge/vector-store.json}") String storeFile,
                                            @Value("${agent.knowledge.rebuild-on-startup:false}") boolean rebuildOnStartup) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        Path storePath = Paths.get(storeFile).toAbsolutePath().normalize();

        try {
            Files.createDirectories(storePath.getParent());

            if (!rebuildOnStartup && Files.exists(storePath)) {
                store.load(storePath.toFile());
                log.info("Loaded vector store from {}", storePath);
                return store;
            }

            List<Document> rawDocuments = readRawKnowledge(rawKnowledgeDir);
            if (rawDocuments.isEmpty()) {
                log.warn("No raw knowledge text found under {}", rawKnowledgeDir);
                return store;
            }

            store.add(rawDocuments);
            store.save(storePath.toFile());

            log.info("Indexed {} QA items from {} and persisted store to {}",
                    rawDocuments.size(), rawKnowledgeDir, storePath);
            return store;
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to initialize vector store", ex);
        }
    }

    private List<Document> readRawKnowledge(String rawKnowledgeDir) throws IOException {
        Path rawPath = Paths.get(rawKnowledgeDir).toAbsolutePath().normalize();
        if (!Files.exists(rawPath)) {
            return List.of();
        }

        List<Document> documents = new ArrayList<>();
        try (Stream<Path> files = Files.walk(rawPath)) {
            List<Path> txtFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .sorted(Comparator.naturalOrder())
                    .toList();

            for (Path txtFile : txtFiles) {
                documents.addAll(parseQaDocuments(rawPath, txtFile));
            }
        }

        return documents;
    }

    private List<Document> parseQaDocuments(Path rawBasePath, Path txtFile) throws IOException {
        String content = Files.readString(txtFile, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String[] blocks = content.split("\\n\\s*\\n+");

        List<Document> result = new ArrayList<>();
        int index = 1;
        for (String block : blocks) {
            QaPair pair = extractQaPair(block);
            if (pair == null) {
                continue;
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("answer", pair.answer());
            metadata.put("question", pair.question());
            metadata.put("source", rawBasePath.relativize(txtFile).toString());
            metadata.put("qa_id", txtFile.getFileName() + "#" + index++);
            metadata.put("annotation_type", "qa");

            // 向量仅使用问题文本，答案放在 metadata 供检索后拼接。
            result.add(new Document(pair.question(), metadata));
        }

        return result;
    }

    private QaPair extractQaPair(String block) {
        String trimmed = block == null ? "" : block.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        Matcher questionMatcher = QUESTION_PATTERN.matcher(trimmed);
        Matcher answerMatcher = ANSWER_PATTERN.matcher(trimmed);
        if (!questionMatcher.find() || !answerMatcher.find()) {
            return null;
        }

        String question = questionMatcher.group(1).trim();
        String answer = answerMatcher.group(1).trim();
        if (question.isEmpty() || answer.isEmpty()) {
            return null;
        }
        return new QaPair(question, answer);
    }

    private record QaPair(String question, String answer) {
    }
}

