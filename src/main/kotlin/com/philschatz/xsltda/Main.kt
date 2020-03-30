package com.philschatz.xsltda

fun main(args: Array<String>) {
    if (args.size == 1) {
        Server(args[0].toInt()).start()
    } else {
        System.err.println("Starting adapter using stdin and stdout. To listen on a port, specify the port number as an argument.")
        runCli()
    }
}
