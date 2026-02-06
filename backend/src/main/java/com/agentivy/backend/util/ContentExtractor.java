package com.agentivy.backend.util;

import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared utility for extracting text and summaries from Google Genai Content objects.
 * Used by both AgentController and AutonomousAgentController.
 */
public final class ContentExtractor {

    private ContentExtractor() {}

    /**
     * Extract plain text content from an Optional Content.
     * Joins all text parts with newlines, ignoring function calls.
     */
    public static String extractText(Optional<Content> contentOpt) {
        if (contentOpt == null || contentOpt.isEmpty()) {
            return "";
        }
        return extractText(contentOpt.get());
    }

    /**
     * Extract plain text content from a Content object.
     */
    public static String extractText(Content content) {
        if (content == null) {
            return "";
        }
        return content.parts().orElse(List.of()).stream()
                .filter(part -> part.text().isPresent())
                .map(part -> part.text().get())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Extract a summary including both text content and function call descriptions.
     */
    public static String extractSummary(Optional<Content> contentOpt) {
        if (contentOpt == null || contentOpt.isEmpty()) {
            return "";
        }

        Content content = contentOpt.get();
        List<Part> parts = content.parts().orElse(List.of());

        StringBuilder summary = new StringBuilder();

        for (Part part : parts) {
            if (part.text().isPresent()) {
                summary.append(part.text().get()).append("\n");
            } else if (part.functionCall().isPresent()) {
                var functionCall = part.functionCall().get();
                String functionName = functionCall.name().orElse("unknown");
                summary.append("[Calling function: ").append(functionName).append("]\n");
            } else if (part.functionResponse().isPresent()) {
                var functionResponse = part.functionResponse().get();
                String functionName = functionResponse.name().orElse("unknown");
                summary.append("[Function ").append(functionName).append(" completed]\n");
            }
        }

        return summary.toString().trim();
    }

    /**
     * Truncate text to a max length, appending "..." if truncated.
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
