package com.agentivy.backend.tools.github.support;

import com.agentivy.backend.tools.github.model.ClassifiedComponent;
import com.agentivy.backend.tools.github.model.ComponentType;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for classifying Angular components based on their file location
 * and naming conventions.
 */
@Service
public class ComponentClassifier {

    /**
     * Classifies a component based on its file path.
     * Uses directory structure and naming patterns to determine the component type.
     *
     * @param componentPath Path to the component TypeScript file
     * @return ComponentType (PAGE, COMPONENT, or ELEMENT)
     */
    public ComponentType classifyComponent(Path componentPath) {
        String pathStr = componentPath.toString().toLowerCase().replace("\\", "/");
        String fileName = componentPath.getFileName().toString().toLowerCase();

        // Check for PAGE components
        if (pathStr.contains("/pages/") ||
            pathStr.contains("/page/") ||
            pathStr.contains("/views/") ||
            pathStr.contains("/routes/") ||
            fileName.endsWith("-page.component.ts") ||
            fileName.contains("page.component")) {
            return ComponentType.PAGE;
        }

        // Check for ELEMENT components (atomic, reusable UI elements)
        if (pathStr.contains("/elements/") ||
            pathStr.contains("/atoms/") ||
            pathStr.contains("/shared/") ||
            pathStr.contains("/ui/") ||
            pathStr.contains("/common/") ||
            pathStr.contains("/primitives/") ||
            pathStr.contains("/basic/")) {
            return ComponentType.ELEMENT;
        }

        // Default to regular COMPONENT
        return ComponentType.COMPONENT;
    }

    /**
     * Groups a list of ClassifiedComponents by their type.
     *
     * @param components List of all classified components
     * @return Map with keys "pages", "components", "elements" containing their respective component lists
     */
    public Map<String, List<ClassifiedComponent>> groupByType(List<ClassifiedComponent> components) {
        Map<String, List<ClassifiedComponent>> grouped = new HashMap<>();
        grouped.put("pages", new ArrayList<>());
        grouped.put("components", new ArrayList<>());
        grouped.put("elements", new ArrayList<>());

        for (ClassifiedComponent component : components) {
            switch (component.type()) {
                case PAGE -> grouped.get("pages").add(component);
                case COMPONENT -> grouped.get("components").add(component);
                case ELEMENT -> grouped.get("elements").add(component);
            }
        }

        return grouped;
    }

    /**
     * Calculates a summary of component counts by type.
     *
     * @param components List of all classified components
     * @return Map with "total", "pages", "components", "elements" counts
     */
    public Map<String, Integer> calculateSummary(List<ClassifiedComponent> components) {
        Map<String, Integer> summary = new HashMap<>();

        int pages = 0, regularComponents = 0, elements = 0;

        for (ClassifiedComponent component : components) {
            switch (component.type()) {
                case PAGE -> pages++;
                case COMPONENT -> regularComponents++;
                case ELEMENT -> elements++;
            }
        }

        summary.put("total", components.size());
        summary.put("pages", pages);
        summary.put("components", regularComponents);
        summary.put("elements", elements);

        return summary;
    }
}
