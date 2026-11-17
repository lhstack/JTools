package com.lhstack.tools.llm

/** 模型响应未能解析为合法工具调用；由当前模型请求重试，不产生工具结果。 */
class ToolCallParseException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
