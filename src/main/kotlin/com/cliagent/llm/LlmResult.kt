package com.cliagent.llm

sealed class LlmResult<out T> {
    data class Success<T>(val data: T) : LlmResult<T>()
    data class Error(val code: Int, val message: String) : LlmResult<Nothing>()
}
