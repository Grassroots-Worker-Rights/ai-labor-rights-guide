package com.angeasla.ai_labor_rights_guide.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Points the local ONNX embedder at LOCAL files when present, falling back to Spring AI's remote
 * default (download) only if they are missing — so there is no hard external dependency at startup
 * (corporate SSL proxy / offline). Runs before the transformers autoconfiguration and just sets the
 * two URI properties; the autoconfig still builds the {@code EmbeddingModel} exactly as usual.
 *
 * <ul>
 *   <li><b>tokenizer.json</b> (~700 KB) ships inside the {@code spring-ai-transformers} jar at
 *       {@code classpath:/onnx/all-MiniLM-L6-v2/tokenizer.json} — the default below, so it NEVER
 *       downloads.</li>
 *   <li><b>model.onnx</b> (~90 MB) is NOT bundled (the jar holds only a Git-LFS pointer). Provide it at
 *       {@code app.embedding.model-path} (default {@code ./models/all-MiniLM-L6-v2/model.onnx} — e.g. a
 *       mounted volume, or run {@code scripts/fetch-embedding-model.*}). If absent, we fall back to the
 *       remote LFS URL (download).</li>
 * </ul>
 *
 * <p>Explicit {@code spring.ai.embedding.transformer.{onnx.model-uri,tokenizer.uri}} settings are
 * respected (never overridden). Registered via
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}.
 */
public class EmbeddingModelLocationPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelLocationPostProcessor.class);

    static final String MODEL_URI_PROP = "spring.ai.embedding.transformer.onnx.model-uri";
    static final String TOKENIZER_URI_PROP = "spring.ai.embedding.transformer.tokenizer.uri";
    static final String DEFAULT_MODEL_PATH = "models/all-MiniLM-L6-v2/model.onnx";
    static final String DEFAULT_TOKENIZER_PATH = "classpath:/onnx/all-MiniLM-L6-v2/tokenizer.json";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> resolved = new HashMap<>();
        resolve(environment, resolved, MODEL_URI_PROP,
                environment.getProperty("app.embedding.model-path", DEFAULT_MODEL_PATH),
                TransformersEmbeddingModel.DEFAULT_ONNX_MODEL_URI, "ONNX model");
        resolve(environment, resolved, TOKENIZER_URI_PROP,
                environment.getProperty("app.embedding.tokenizer-path", DEFAULT_TOKENIZER_PATH),
                TransformersEmbeddingModel.DEFAULT_ONNX_TOKENIZER_URI, "tokenizer");
        if (!resolved.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("embeddingModelLocation", resolved));
        }
    }

    private static void resolve(ConfigurableEnvironment env, Map<String, Object> out, String targetProp,
                                String localCandidate, String remoteDefault, String what) {
        if (env.containsProperty(targetProp)) {
            log.info("Embedding {} URI explicitly configured — left untouched.", what);
            return;
        }
        String localUri = toLocalUriIfPresent(localCandidate);
        if (localUri != null) {
            log.info("Embedding {}: using local resource {}", what, localUri);
            out.put(targetProp, localUri);
        } else {
            log.warn("Embedding {} not found at '{}' — falling back to download from {}",
                    what, localCandidate, remoteDefault);
            out.put(targetProp, remoteDefault);
        }
    }

    /** A usable {@code file:}/{@code classpath:} URI if the candidate exists locally, else null. */
    static String toLocalUriIfPresent(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        if (candidate.startsWith("http:") || candidate.startsWith("https:")) {
            return null; // an explicit remote candidate is not "local"
        }
        if (candidate.startsWith("classpath:")) {
            String path = candidate.substring("classpath:".length());
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            return new ClassPathResource(path).exists() ? candidate : null;
        }
        String filePart = candidate.startsWith("file:") ? candidate.substring("file:".length()) : candidate;
        Path p = Path.of(filePart);
        if (Files.isReadable(p)) {
            return "file:" + p.toAbsolutePath();
        }
        // tolerate a bare path that is actually a classpath resource
        return new ClassPathResource(filePart).exists() ? "classpath:/" + filePart : null;
    }
}
