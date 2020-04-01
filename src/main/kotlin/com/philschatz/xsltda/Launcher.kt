package com.philschatz.xsltda

import java.io.File
import java.net.URI
import java.nio.file.Paths
import javax.xml.transform.ErrorListener
import javax.xml.transform.SourceLocator
import javax.xml.transform.TransformerException
import javax.xml.transform.stream.StreamSource
import net.sf.saxon.lib.Feature
import net.sf.saxon.s9api.MessageListener
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.SaxonApiException
import net.sf.saxon.s9api.XdmAtomicValue
import net.sf.saxon.s9api.XdmNode
import net.sf.saxon.s9api.XdmValue
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.OutputEventArgumentsCategory
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.javacs.kt.LOG
import org.javacs.kt.util.AsyncExecutor
import org.javacs.ktda.adapter.DAPConverter
import org.javacs.ktda.core.DebugContext
import org.javacs.ktda.core.Debuggee
import org.javacs.ktda.core.Source

class Launcher(
    val context: DebugContext,
    val converter: DAPConverter
) {

    fun launch(client: IDebugProtocolClient, xsltPath: String, sourcePath: String, destinationPath: String, params: Map<String, Any>): Debuggee {
        val listener = DebugTraceListener(context, converter)

        AsyncExecutor().compute {
            val processor = Processor(false)
            processor.setConfigurationProperty(Feature.EAGER_EVALUATION, true)
            processor.setConfigurationProperty(Feature.OPTIMIZATION_LEVEL, 0) // disable variable inlining
            processor.setConfigurationProperty(Feature.LINE_NUMBERING, true)
            processor.setConfigurationProperty(Feature.TRACE_LISTENER, listener)

            val source = StreamSource(File(sourcePath))
            val destination = processor.newSerializer(File(destinationPath))
            val c = processor.newXsltCompiler()

                try {
                val ex = c.compile(StreamSource(File(xsltPath)))
                val transformer = ex.load30()

                transformer.errorListener = object : ErrorListener {
                    override fun warning(ex: TransformerException) {
                        doStuff(client, "WARN", OutputEventArgumentsCategory.STDERR, ex.message, ex.locator)
                    }
                    override fun error(ex: TransformerException) {
                        doStuff(client, "ERROR", OutputEventArgumentsCategory.STDERR, ex.message, ex.locator)
                    }
                    override fun fatalError(ex: TransformerException) {
                        doStuff(client, "FATAL", OutputEventArgumentsCategory.STDERR, ex.message, ex.locator)
                    }
                }

                transformer.messageListener = object : MessageListener {
                    override fun message(content: XdmNode, terminate: Boolean, locator: SourceLocator) {
                        doStuff(client, null, OutputEventArgumentsCategory.STDOUT, "${content.stringValue}\n", locator)
                    }
                }

                val qparams: MutableMap<QName, XdmValue> = params.entries.associateTo(mutableMapOf<QName, XdmValue>()) { QName(it.key) to XdmAtomicValue.makeAtomicValue(it.value) }
                transformer.setStylesheetParameters(qparams)

                LOG.trace("Start transform")
                transformer.transform(source, destination)
                LOG.trace("End transform")
            } catch (e: SaxonApiException) {
                LOG.error(e.message ?: "Transform error occurred")
            }
        }
        return listener
    }

    fun doStuff(client: IDebugProtocolClient, prefix: String?, cat: String, msg: String?, loc: SourceLocator?) {
        if (loc != null) {
            val path = Paths.get(URI(loc.systemId).normalize())
            val src = Source(path.fileName.toString(), path)

            client.output(OutputEventArguments().apply {
                category = cat
                output = if (prefix != null) "[$prefix] $msg" else "$msg"
                // variablesReference = 1L
                source = converter.toDAPSource(src)
                line = converter.lineConverter.toExternalLine(loc.lineNumber.toLong())
                column = loc.columnNumber.toLong()
            })
        } else {
            client.output(OutputEventArguments().apply {
                category = cat
                output = "[$prefix] $msg"
            })
        }
    }
}
