package com.angeasla.ai_labor_rights_guide.config;

import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Batching for the Chroma vector store. Spring AI's default {@link TokenCountBatchingStrategy} caps every
 * document at ~8k tokens (an OpenAI embedding limit) and rejects the whole ingest when one article is
 * longer — which several long Greek articles are. That limit protects nothing here: the local
 * all-MiniLM-L6-v2 embedder truncates its input to 256 tokens anyway (same as the Meili vectors, so the
 * trial stays a fair comparison). The higher per-batch budget only bounds request size to Chroma.
 * Picked up by {@code ChromaVectorStoreAutoConfiguration}.
 */
@Configuration
public class ChromaBatchingConfig {

    private static final int MAX_TOKENS_PER_BATCH = 200_000;

    @Bean
    BatchingStrategy chromaBatchingStrategy() {
        return new TokenCountBatchingStrategy(EncodingType.CL100K_BASE, MAX_TOKENS_PER_BATCH, 0.1);
    }
}
