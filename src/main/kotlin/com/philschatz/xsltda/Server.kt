package com.philschatz.xsltda

import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.javacs.kt.LOG
import org.javacs.kt.LogLevel

val logger = Logger.getLogger("xslt-debug")

class Server(
    val port: Int
) {
    var serverSocket = newSocket()
    var isStarted = false
    var executor = ThreadPoolExecutor(0, 100, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>())

    fun newSocket(): ServerSocket {
        val s = ServerSocket(port, 1)
        logger.log(Level.INFO, String.format("Started up on port %d", s.localPort))
        return s
    }

    fun start() {
        LOG.level = LogLevel.TRACE
        while (true) {
            val connection = serverSocket.accept()
            val input = connection.inputStream
            val output = connection.outputStream
            // Create debug adapter and launcher
            val debugAdapter = XSLTDebugAdapter()
            val threads = Executors.newSingleThreadExecutor { Thread(it, "server") }
            val serverLauncher = DSPLauncher.createServerLauncher(debugAdapter, input, output, threads) { it }

            debugAdapter.connect(serverLauncher.remoteProxy)
            serverLauncher.startListening()
        }
    }
}
