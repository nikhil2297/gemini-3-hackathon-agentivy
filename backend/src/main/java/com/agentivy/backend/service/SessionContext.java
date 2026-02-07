package com.agentivy.backend.service;

import lombok.extern.slf4j.Slf4j;

/**
 * Thread-local session context holder.
 * Allows tools and services to access the current session ID without passing it explicitly.
 */
@Slf4j
public class SessionContext {

    private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentComponentName = new ThreadLocal<>();

    /**
     * Set the current session ID for this thread.
     */
    public static void setSessionId(String sessionId) {
        currentSessionId.set(sessionId);
        log.debug("Set session ID: {} for thread: {}", sessionId, Thread.currentThread().getName());
    }

    /**
     * Get the current session ID for this thread.
     */
    public static String getSessionId() {
        return currentSessionId.get();
    }

    /**
     * Set the current component name being tested/analyzed.
     * This allows tools to use the actual component name in events.
     */
    public static void setCurrentComponent(String componentName) {
        currentComponentName.set(componentName);
        log.debug("Set current component: {} for thread: {}", componentName, Thread.currentThread().getName());
    }

    /**
     * Get the current component name being tested/analyzed.
     * Returns null if no component is set.
     */
    public static String getCurrentComponent() {
        return currentComponentName.get();
    }

    /**
     * Clear the session ID and component name for this thread.
     */
    public static void clear() {
        String sessionId = currentSessionId.get();
        String componentName = currentComponentName.get();
        currentSessionId.remove();
        currentComponentName.remove();
        log.debug("Cleared session ID: {} and component: {} for thread: {}", sessionId, componentName, Thread.currentThread().getName());
    }

    /**
     * Check if a session ID is set.
     */
    public static boolean hasSessionId() {
        return currentSessionId.get() != null;
    }

    /**
     * Check if a current component is set.
     */
    public static boolean hasCurrentComponent() {
        return currentComponentName.get() != null;
    }
}
