package com.agentivy.backend.tools.github.model;

/**
 * Enum representing the type/category of an Angular component
 * based on its role and location in the application structure.
 */
public enum ComponentType {
    /**
     * Page components - typically routable components that represent full pages/views
     */
    PAGE,

    /**
     * Regular components - reusable components with business logic
     */
    COMPONENT,

    /**
     * Element components - atomic UI elements like buttons, inputs, cards
     */
    ELEMENT
}
