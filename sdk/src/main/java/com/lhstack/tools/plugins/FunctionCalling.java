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
}
