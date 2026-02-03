package com.agentivy.backend.tools.registry;

/**
 * Categories for organizing tools into logical groups.
 *
 * Each category represents a domain or functional area,
 * making it easier to discover and manage related tools.
 */
public enum ToolCategory {
    SOURCE_CONTROL("Source Control", "Tools for repository management and version control"),
    ANGULAR_DEV("Angular Development", "Angular-specific development and build tools"),
    COMPONENT_TESTING("Component Testing", "Tools for isolating and testing components"),
    ACCESSIBILITY("Accessibility", "WCAG and accessibility auditing tools"),
    BROWSER_AUTOMATION("Browser Automation", "Playwright and browser testing tools"),
    CODE_ANALYSIS("Code Analysis", "Static analysis and code scanning tools"),
    CODE_FIXING("Code Fixing", "Automated code fixing and optimization tools"),
    RESPONSIVENESS("Responsiveness", "Viewport and responsive design testing tools");

    private final String displayName;
    private final String description;

    ToolCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
