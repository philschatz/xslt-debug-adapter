package com.philschatz.xsltda

import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.CompletionsArguments
import org.eclipse.lsp4j.debug.CompletionsResponse
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.ContinueResponse
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.EvaluateResponse
import org.eclipse.lsp4j.debug.ExceptionInfoArguments
import org.eclipse.lsp4j.debug.ExceptionInfoResponse
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.GotoArguments
import org.eclipse.lsp4j.debug.GotoTargetsArguments
import org.eclipse.lsp4j.debug.GotoTargetsResponse
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.LoadedSourcesArguments
import org.eclipse.lsp4j.debug.LoadedSourcesResponse
import org.eclipse.lsp4j.debug.ModulesArguments
import org.eclipse.lsp4j.debug.ModulesResponse
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.OutputEventArgumentsCategory
import org.eclipse.lsp4j.debug.PauseArguments
import org.eclipse.lsp4j.debug.RestartArguments
import org.eclipse.lsp4j.debug.RestartFrameArguments
import org.eclipse.lsp4j.debug.ReverseContinueArguments
import org.eclipse.lsp4j.debug.RunInTerminalRequestArguments
import org.eclipse.lsp4j.debug.RunInTerminalResponse
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.ScopesResponse
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments
import org.eclipse.lsp4j.debug.SetFunctionBreakpointsArguments
import org.eclipse.lsp4j.debug.SetFunctionBreakpointsResponse
import org.eclipse.lsp4j.debug.SetVariableArguments
import org.eclipse.lsp4j.debug.SetVariableResponse
import org.eclipse.lsp4j.debug.SourceArguments
import org.eclipse.lsp4j.debug.SourceResponse
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StackTraceResponse
import org.eclipse.lsp4j.debug.StepBackArguments
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepInTargetsArguments
import org.eclipse.lsp4j.debug.StepInTargetsResponse
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.StoppedEventArgumentsReason
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.ThreadsResponse
import org.eclipse.lsp4j.debug.VariablesArguments
import org.eclipse.lsp4j.debug.VariablesResponse
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.javacs.kt.LOG
import org.javacs.kt.LogMessage
import org.javacs.kt.util.AsyncExecutor
import org.javacs.ktda.adapter.DAPConverter
import org.javacs.ktda.adapter.LineNumberConverter
import org.javacs.ktda.core.DebugContext
import org.javacs.ktda.core.Debuggee
import org.javacs.ktda.core.Source
import org.javacs.ktda.core.scope.VariableTreeNode
import org.javacs.ktda.util.KotlinDAException

private typealias DAPThread = org.eclipse.lsp4j.debug.Thread
private typealias DAPVariable = org.eclipse.lsp4j.debug.Variable

class XSLTDebugAdapter : IDebugProtocolServer {

    private val async = AsyncExecutor()
    private val launcherAsync = AsyncExecutor()
    private val stdoutAsync = AsyncExecutor()
    private val stderrAsync = AsyncExecutor()

    private var debuggee: Debuggee? = null
    private var client: IDebugProtocolClient? = null
    private var converter = DAPConverter()
    private val context = DebugContext()

    private val launcher = Launcher(context, converter)

    // Source is not comparable. We keep a cache so that breakpoints can be removed
    private val sourceCache: MutableMap<Path, Source> = mutableMapOf()

    override fun initialize(args: InitializeRequestArguments): CompletableFuture<Capabilities> = async.compute {
        converter.lineConverter = LineNumberConverter(
                externalLineOffset = if (args.linesStartAt1) 0L else -1L
        )

        val capabilities = Capabilities()
        capabilities.supportsConfigurationDoneRequest = true
        // capabilities.exceptionBreakpointFilters = ExceptionBreakpoint.values()
        // 	.map(converter::toDAPExceptionBreakpointsFilter)
        // 	.toTypedArray()

        LOG.trace("Returning capabilities...")
        capabilities
    }

    fun connect(client: IDebugProtocolClient) {
        connectLoggingBackend(client)
        this.client = client
        client.initialized()
        LOG.info("Connected to client")
    }

    override fun configurationDone(args: ConfigurationDoneArguments?): CompletableFuture<Void> {
        LOG.trace("Got configurationDone request")
        val response = CompletableFuture<Void>()
        // configurationDoneResponse = response
        return response
    }

    override fun launch(args: Map<String, Any>) = launcherAsync.execute {
        LOG.info("launching {} to convert {}", args["xslPath"], args["sourcePath"])
        val xslPath = args["xslPath"] as? String ?: throw missingRequestArgument("launch", "xslPath")
        val sourcePath = args["sourcePath"] as? String ?: throw missingRequestArgument("launch", "sourcePath")
        val destinationPath = args["destinationPath"] as? String
                ?: throw missingRequestArgument("launch", "destinationPath")
        val params = args["parameters"] as? Map<String, Any>
            ?: mutableMapOf()

        debuggee = launcher.launch(client!!, xslPath, sourcePath, destinationPath, params)
                .also(::setupDebuggeeListeners)

        LOG.trace("Instantiated debuggee")
    }

    override fun threads(): CompletableFuture<ThreadsResponse> {
        return completedFuture(ThreadsResponse().apply {
            threads = listOf(DAPThread().apply {
                name = "The XSLT Thread"
                id = 1
            }).toTypedArray()
        })
    }

    private fun setupDebuggeeListeners(debuggee: Debuggee) {
        val eventBus = debuggee.eventBus
        eventBus.exitListeners.add {
            // TODO: Use actual exitCode instead
            sendExitEvent(0L)
        }
        eventBus.breakpointListeners.add {
            sendStopEvent(it.threadID, StoppedEventArgumentsReason.BREAKPOINT)
        }
        eventBus.stepListeners.add {
            sendStopEvent(it.threadID, StoppedEventArgumentsReason.STEP)
        }
        eventBus.exceptionListeners.add {
            sendStopEvent(it.threadID, StoppedEventArgumentsReason.EXCEPTION)
        }
        stdoutAsync.execute {
            debuggee.stdout?.let { pipeStreamToOutput(it, OutputEventArgumentsCategory.STDOUT) }
        }
        stderrAsync.execute {
            debuggee.stderr?.let { pipeStreamToOutput(it, OutputEventArgumentsCategory.STDERR) }
        }
        LOG.trace("Configured debuggee listeners")
    }

    override fun setBreakpoints(args: SetBreakpointsArguments): CompletableFuture<SetBreakpointsResponse> = async.compute {
        LOG.debug("{} breakpoints found", args.breakpoints.size)

        val placedBreakpoints = context
                .breakpointManager
                .setAllIn(
                        getCached(converter.toInternalSource(args.source)),
                        args.breakpoints.map { converter.toInternalSourceBreakpoint(args.source, it) }
                )
                .map(converter::toDAPBreakpoint)
                .toTypedArray()

        SetBreakpointsResponse().apply {
            breakpoints = placedBreakpoints
        }
    }

    override fun continue_(args: ContinueArguments) = async.compute {
        val success = debuggee!!.threadByID(args.threadId)?.resume()
        if (success == true) {
            converter.variablesPool.clear()
            converter.stackFramePool.removeAllOwnedBy(args.threadId)
        }
        ContinueResponse().apply {
            allThreadsContinued = true
        }
    }

    override fun next(args: NextArguments) = async.execute {
        debuggee!!.threadByID(args.threadId)?.stepOver()
    }

    override fun stepIn(args: StepInArguments) = async.execute {
        debuggee!!.threadByID(args.threadId)?.stepInto()
    }

    override fun stepOut(args: StepOutArguments) = async.execute {
        debuggee!!.threadByID(args.threadId)?.stepOut()
    }

    override fun pause(args: PauseArguments) = async.execute {
        debuggee!!.threadByID(args.threadId)?.pause()
    }

    override fun stackTrace(args: StackTraceArguments): CompletableFuture<StackTraceResponse> {
        val threadId = args.threadId
        return completedFuture(StackTraceResponse().apply {
            stackFrames = debuggee!!
                    .threadByID(threadId)
                    ?.stackTrace()
                    ?.frames
                    ?.map { converter.toDAPStackFrame(it, threadId) }
                    ?.toTypedArray()
                    .orEmpty()
        })
    }

    override fun scopes(args: ScopesArguments) = completedFuture(
            ScopesResponse().apply {
                scopes = (converter.toInternalStackFrame(args.frameId)
                        ?: throw KotlinDAException("Could not find stackTrace with ID ${args.frameId}"))
                        .scopes
                        .map(converter::toDAPScope)
                        .toTypedArray()
            }
    )

    override fun variables(args: VariablesArguments) = completedFuture(
            VariablesResponse().apply {
                variables = (args.variablesReference
                        .let(converter::toVariableTree)
                        ?: throw KotlinDAException("Could not find variablesReference with ID ${args.variablesReference}"))
                        .childs
                        .map { toDAPVariable(it) }
                        .toTypedArray()
            }
    )

    private fun pipeStreamToOutput(stream: InputStream, outputCategory: String) {
        stream.bufferedReader().use {
            var line = it.readLine()
            while (line != null) {
                client?.output(OutputEventArguments().apply {
                    category = outputCategory
                    output = line + System.lineSeparator()
                })
                line = it.readLine()
            }
        }
    }

    private fun sendStopEvent(threadId: Long, reason: String) {
        client!!.stopped(StoppedEventArguments().also {
            it.reason = reason
            it.threadId = threadId
        })
    }

    private fun sendExitEvent(exitCode: Long) {
        client!!.exited(ExitedEventArguments().also {
            it.exitCode = exitCode
        })
        client!!.terminated(TerminatedEventArguments())
        LOG.info("Sent exit event")
    }

    private fun missingRequestArgument(requestName: String, argumentName: String) =
            KotlinDAException("Sent $requestName to debug adapter without the required argument'$argumentName'")

    private fun connectLoggingBackend(client: IDebugProtocolClient) {
        val backend: (LogMessage) -> Unit = {
            client.output(OutputEventArguments().apply {
                category = OutputEventArgumentsCategory.CONSOLE
                output = "[${it.level}] ${it.message}\n"
            })
        }
        LOG.connectOutputBackend(backend)
        LOG.connectErrorBackend(backend)
    }

    override fun disconnect(args: DisconnectArguments): CompletableFuture<Void> = CompletableFuture()
    override fun setExceptionBreakpoints(args: SetExceptionBreakpointsArguments): CompletableFuture<Void> = CompletableFuture()

    override fun runInTerminal(args: RunInTerminalRequestArguments): CompletableFuture<RunInTerminalResponse> = notImplementedDAPMethod("runInTerminal")
    override fun restart(args: RestartArguments): CompletableFuture<Void> = notImplementedDAPMethod("restart")
    override fun setFunctionBreakpoints(args: SetFunctionBreakpointsArguments): CompletableFuture<SetFunctionBreakpointsResponse> = notImplementedDAPMethod("setFunctionBreakpoints")
    override fun stepBack(args: StepBackArguments): CompletableFuture<Void> = notImplementedDAPMethod("stepBack")
    override fun reverseContinue(args: ReverseContinueArguments): CompletableFuture<Void> = notImplementedDAPMethod("reverseContinue")
    override fun restartFrame(args: RestartFrameArguments): CompletableFuture<Void> = notImplementedDAPMethod("restartFrame")
    override fun goto_(args: GotoArguments): CompletableFuture<Void> = notImplementedDAPMethod("goto_")
    override fun setVariable(args: SetVariableArguments): CompletableFuture<SetVariableResponse> = notImplementedDAPMethod("setVariable")
    override fun source(args: SourceArguments): CompletableFuture<SourceResponse> = notImplementedDAPMethod("source")
    override fun modules(args: ModulesArguments): CompletableFuture<ModulesResponse> = notImplementedDAPMethod("modules")
    override fun loadedSources(args: LoadedSourcesArguments): CompletableFuture<LoadedSourcesResponse> = notImplementedDAPMethod("loadedSources")
    override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> = notImplementedDAPMethod("evaluate")
    override fun stepInTargets(args: StepInTargetsArguments): CompletableFuture<StepInTargetsResponse> = notImplementedDAPMethod("stepInTargets")
    override fun gotoTargets(args: GotoTargetsArguments): CompletableFuture<GotoTargetsResponse> = notImplementedDAPMethod("gotoTargets")
    override fun completions(args: CompletionsArguments): CompletableFuture<CompletionsResponse> = notImplementedDAPMethod("completions")
    override fun exceptionInfo(args: ExceptionInfoArguments): CompletableFuture<ExceptionInfoResponse> = notImplementedDAPMethod("exceptionInfo")

    private fun <T> notImplementedDAPMethod(fn: String): CompletableFuture<T> {
        TODO(fn)
    }

    private fun toDAPVariable(variableTree: VariableTreeNode) = DAPVariable().apply {
        name = variableTree.name
        value = variableTree.value
        type = variableTree.type
        variablesReference = if (variableTree.childs.size == 0) 0 else converter.variablesPool.store(Unit, variableTree)
    }

    fun getCached(s: Source): Source {
        val existing = sourceCache[s.filePath]
        if (existing != null) {
            return existing
        } else {
            sourceCache[s.filePath] = s
            return s
        }
    }
}
