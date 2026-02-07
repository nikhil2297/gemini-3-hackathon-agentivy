package com.agentivy.backend.agents;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Single source of truth for the ComponentAnalyzerAgent instruction prompt.
 * Loaded from classpath resource: agent-instruction.txt
 */
@Slf4j
public final class AgentInstructions {

    private static final String RESOURCE_PATH = "agent-instruction.txt";
    private static String cachedInstruction;

    private AgentInstructions() {}

    /**
     * Returns the agent instruction text, loaded once from the classpath resource.
     */
    public static synchronized String getComponentAnalyzerInstruction() {
        if (cachedInstruction == null) {
            try {
                ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
                cachedInstruction = resource.getContentAsString(StandardCharsets.UTF_8);
                log.info("Loaded agent instruction from classpath ({} chars)", cachedInstruction.length());
            } catch (IOException e) {
                log.error("Failed to load agent instruction from {}", RESOURCE_PATH, e);
                throw new IllegalStateException("Could not load agent instruction resource: " + RESOURCE_PATH, e);
            }
        }
        return cachedInstruction;
    }
}
