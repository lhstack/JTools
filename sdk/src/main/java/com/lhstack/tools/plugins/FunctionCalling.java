package com.lhstack.tools.plugins;

public interface FunctionCalling {

    String name();

    default String description() {
        return name();
    }

    /**
     * JSON schema string for the function arguments.
     */
    String parameters();

    /**
     * Execute the function using JSON arguments, returns JSON string result.
     */
    String call(String argumentsJson);

    /**
     * Execute the function in a coding/agent session.
     * Existing implementations keep using {@link #call(String)}.
     */
    default String call(String argumentsJson, String sessionName, String sessionId) {
        return call(argumentsJson);
    }

    /**
     * Execute the function with the current session and the model currently in use.
     * Existing implementations keep using {@link #call(String, String, String)}.
     */
    default String call(String argumentsJson, String sessionName, String sessionId, String provider, String model) {
        return call(argumentsJson, sessionName, sessionId);
    }
}
