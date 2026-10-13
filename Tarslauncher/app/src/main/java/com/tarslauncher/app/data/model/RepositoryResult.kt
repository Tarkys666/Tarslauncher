package com.tarslauncher.app.data.model

sealed interface RepositoryResult<out T> {
    data class Success<out T>(val data: T) : RepositoryResult<T>
    data class Error(val exception: Throwable, val message: String = exception.localizedMessage ?: "Unknown error") : RepositoryResult<Nothing>
    data object Loading : RepositoryResult<Nothing>

    val isSuccess: Boolean
        get() = this is Success

    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }
}

fun <T> RepositoryResult<T>.getOrDefault(default: T): T = when (this) {
    is RepositoryResult.Success -> data
    else -> default
}
