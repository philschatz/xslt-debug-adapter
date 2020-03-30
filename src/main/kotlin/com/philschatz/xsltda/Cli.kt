package com.philschatz.xsltda

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.javacs.kt.LOG
import org.javacs.kt.LogLevel
import org.javacs.kt.util.ExitingInputStream
import org.javacs.ktda.util.LoggingInputStream
import org.javacs.ktda.util.LoggingOutputStream

/** Enable logging of raw input JSON messages (if it is enabled in the user's debug configuration). */
private const val JSON_IN_LOGGING = true
private const val JSON_IN_LOGGING_BUFFER_LINES = true

/** Enable logging of raw output JSON messages (if it is enabled in the user's debug configuration). */
private const val JSON_OUT_LOGGING = true
private const val JSON_OUT_LOGGING_BUFFER_LINES = true

fun runCli() {
    LOG.level = LogLevel.TRACE
    LOG.connectJULFrontend()

    // Setup IO streams for JSON communication
    val input = LoggingInputStream(ExitingInputStream(System.`in`), JSON_IN_LOGGING, JSON_IN_LOGGING_BUFFER_LINES)
    val output = LoggingOutputStream(System.out, JSON_OUT_LOGGING, JSON_OUT_LOGGING_BUFFER_LINES)

    start(input, output)
}

fun start(input: InputStream, output: OutputStream): ExecutorService {
    // Create debug adapter and launcher
    val debugAdapter = XSLTDebugAdapter()
    val threads = Executors.newSingleThreadExecutor { Thread(it, "server") }
    val serverLauncher = DSPLauncher.createServerLauncher(debugAdapter, input, output, threads) { it }

    debugAdapter.connect(serverLauncher.remoteProxy)
    serverLauncher.startListening()
    return threads
}
