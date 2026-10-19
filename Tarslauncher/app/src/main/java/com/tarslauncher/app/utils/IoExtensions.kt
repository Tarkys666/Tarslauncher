package com.tarslauncher.app.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHexString()

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}

fun InputStream.readTextWithLimit(maxBytes: Long, charset: Charset = Charsets.UTF_8): String {
    val buf = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw SecurityException("File size exceeds limit of $maxBytes bytes.")
        buf.write(chunk, 0, n)
    }
    return buf.toString(charset.name())
}

fun InputStream.readTextWithLimit(maxBytes: Int, charset: Charset = Charsets.UTF_8): String =
    readTextWithLimit(maxBytes.toLong(), charset)
