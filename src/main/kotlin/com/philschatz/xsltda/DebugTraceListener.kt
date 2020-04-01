package com.philschatz.xsltda

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Stack
import net.sf.saxon.Controller
import net.sf.saxon.expr.XPathContext
import net.sf.saxon.expr.parser.Location
import net.sf.saxon.lib.Logger
import net.sf.saxon.lib.TraceListener
import net.sf.saxon.om.AxisInfo
import net.sf.saxon.om.GroundedValue
import net.sf.saxon.om.Item
import net.sf.saxon.om.NodeInfo
import net.sf.saxon.om.StandardNames
import net.sf.saxon.trace.InstructionInfo
import net.sf.saxon.trace.LocationKind
import net.sf.saxon.type.Type
import net.sf.saxon.value.BooleanValue
import net.sf.saxon.value.Int64Value
import net.sf.saxon.value.SequenceExtent
import net.sf.saxon.value.StringValue
import org.javacs.kt.LOG
import org.javacs.ktda.adapter.DAPConverter
import org.javacs.ktda.core.DebugContext
import org.javacs.ktda.core.Debuggee
import org.javacs.ktda.core.DebuggeeThread
import org.javacs.ktda.core.Position
import org.javacs.ktda.core.Source
import org.javacs.ktda.core.breakpoint.Breakpoint
import org.javacs.ktda.core.event.BreakpointStopEvent
import org.javacs.ktda.core.event.ExitEvent
import org.javacs.ktda.core.event.StepStopEvent
import org.javacs.ktda.core.scope.VariableTreeNode
import org.javacs.ktda.core.stack.StackFrame
import org.javacs.ktda.core.stack.StackTrace
import org.javacs.ktda.util.ObservableList

// Listener {
//
//     stack
//     breakpoints
//     spinLock
//
//     resume()      { nextStopStack = -1; unlock }
//     stepInto()    { nextStopStack = curStackSize + 1 ; unlock }
//     step()        { nextStopStack = curStackSize     ; unlock }
//     stepReturn()  { nextStopStack = curStackSize - 1 ; unlock }
//
//     enter() {
//         push variables on stack
//         if breakpoint is here || curStackSize >= nextStopStack then
//             send BreakpointStopped
//         pause
//     }
//
//     leave() {
//         pop stack
//     }
// }

enum class Step {
    NONE,
    INTO,
    OUT,
    OVER
}

class DebugTraceListener(
    private val debugContext: DebugContext,
    private val converter: DAPConverter
) : Debuggee, DebuggeeThread, TraceListener {
    override val threads = ObservableList<DebuggeeThread>()
    override val eventBus = SimpleEventBus()
    override val stdin: OutputStream? = null
    override val stdout: InputStream? = null
    override val stderr: InputStream? = null

    override val id = 1L
    override val name = "Saxon Thread"

    var nextStop = Step.NONE
    var nextStopStack = -1
    var isPaused = false
    var stackFrames: Stack<Stackframe> = Stack()
    var currentItem: Item<*>? = null

    var breakpoints: MutableList<String> = mutableListOf()

    override fun threadByID(id: Long): DebuggeeThread? {
        return this
    }

    override fun exit() {
        // Exit the transformation and clean up
    }

    override fun enter(instr: InstructionInfo, context: XPathContext) {
        var variables = mutableMapOf<String, GroundedValue<*>?>()
        val i = currentItem
        if (i != null) {
            variables["(this)"] = i
        }
        var p = 0
        val sf = context.stackFrame
        sf.stackFrameValues.forEach {
            val name = sf.stackFrameMap.variableMap.get(p).clarkName
            val v = it?.iterate()?.materialize()
            variables[name] = v
            p++
        }

        stackFrames.push(Stackframe(instr, variables))

        // Check if a breakpoint for this location has been set
        val item = currentItem
        if (hasBreakpoint(Paths.get(URI(instr.systemId).normalize()), instr.lineNumber)) {
            eventBus.breakpointListeners.fire(BreakpointStopEvent(1))
            pause()
        } else if ((nextStop == Step.INTO && stackFrames.size >= nextStopStack) ||
                        (nextStop == Step.OVER && stackFrames.size == nextStopStack) ||
                        (nextStop == Step.OUT && stackFrames.size <= nextStopStack)) {
            eventBus.stepListeners.fire(StepStopEvent(1))
            pause()
        } else if (item != null) {
            // Pause on XML breakpoints every time the node is processed
            val gv = item.iterate().materialize()
            if (gv is Location) {
                if (gv is NodeInfo && UNBREAKABLE_NODES.contains(gv.nodeKind.toShort())) {
                    // Do not break on text nodes or comment nodes or attribute nodes
                } else if (hasBreakpoint(Paths.get(URI(gv.systemId).normalize()), gv.lineNumber)) {
                    eventBus.breakpointListeners.fire(BreakpointStopEvent(1))
                    pause()
                }
            }
        }
        sleepIfPaused()
    }

    override fun leave(instr: InstructionInfo) {
        stackFrames.pop()
    }

    override fun startCurrentItem(item: Item<*>) {
        currentItem = item
    }

    override fun endCurrentItem(item: Item<*>) {}

    override fun setOutputDestination(log: Logger) {}

    override fun open(controller: Controller) {
        hookBreakpoints()
    }

    override fun close() {
        eventBus.exitListeners.fire(ExitEvent())
    }

    private fun sleepIfPaused() {
        while (isPaused) {
            Thread.sleep(20)
        }
    }

    override fun pause(): Boolean {
        isPaused = true
        return true
    }

    override fun resume(): Boolean {
        nextStop = Step.NONE
        nextStopStack = -1
        resume_()
        return true
    }

    fun resume_() {
        isPaused = false
    }

    override fun stackTrace() = object : StackTrace {
        public override val frames = stackFrames.reversed().map {
            object : StackFrame {
                override val name = toConstructName(it.instruction)
                override val position = toPosition(it.instruction)
                override val scopes = listOf(toScopeVariableTreeNode(it.variables))
            }
        }
    }

    override fun stepInto() { nextStop = Step.INTO; nextStopStack = stackFrames.size + 1; resume_() }
    override fun stepOut() { nextStop = Step.OUT; nextStopStack = stackFrames.size - 1; resume_() }
    override fun stepOver() { nextStop = Step.OVER; nextStopStack = stackFrames.size; resume_() }

    private fun hookBreakpoints() {
        debugContext.breakpointManager.also { manager ->
            manager.breakpoints.listenAndFire { setAllBreakpoints(it.values.flatten()) }
            // manager.exceptionBreakpoints.listenAndFire(::setExceptionBreakpoints)
        }
    }

    private fun setAllBreakpoints(bps: List<Breakpoint>) {
        LOG.info("ClearingAllBreakpoints")
        breakpoints.clear()
        bps.forEach { bp ->
            bp.position.let { setBreakpoint(
                it.source.filePath.toAbsolutePath(),
                it.lineNumber
            ) }
        }
    }

    private fun setBreakpoint(filePath: Path, lineNumber: Long) {
        val key = "${filePath.toAbsolutePath()}:$lineNumber"
        LOG.info("AddingBreakpoint_$key")
        breakpoints.add(key)
    }

    private fun hasBreakpoint(filePath: Path, lineNumber: Int): Boolean {
        val key = "${filePath.toAbsolutePath()}:$lineNumber"
        return breakpoints.contains(key)
    }
}

// Breakpoints on these nodes do not work
val UNBREAKABLE_NODES: List<Short> = listOf(Type.COMMENT, Type.TEXT, Type.WHITESPACE_TEXT, Type.ATTRIBUTE)

fun toConstructName(instr: InstructionInfo): String {
    val type = instr.constructType
    return if (type < 1024) {
        return StandardNames.getStructuredQName(type).displayName
    } else {
        when (type) {
            LocationKind.LITERAL_RESULT_ELEMENT -> "LITERAL_RESULT_ELEMENT"
            LocationKind.LITERAL_RESULT_ATTRIBUTE -> "LITERAL_RESULT_ATTRIBUTE"
            LocationKind.EXTENSION_INSTRUCTION -> "EXTENSION_INSTRUCTION"
            LocationKind.TEMPLATE -> "TEMPLATE"
            LocationKind.FUNCTION_CALL -> "FUNCTION_CALL"
            LocationKind.XPATH_IN_XSLT -> "XPATH_IN_XSLT"
            LocationKind.LET_EXPRESSION -> "LET_EXPRESSION"
            LocationKind.TRACE_CALL -> "TRACE_CALL"
            LocationKind.SAXON_EVALUATE -> "SAXON_EVALUATE"
            LocationKind.FUNCTION -> "FUNCTION"
            LocationKind.XPATH_EXPRESSION -> "XPATH_EXPRESSION"
            else -> "Unknown"
        }
    }
}

fun toPosition(instr: InstructionInfo): Position {
    val path = Paths.get(URI(instr.systemId).normalize())
    val name = path.fileName.toString()
    val source = Source(name, path)
    return Position(source, instr.lineNumber.toLong(), instr.columnNumber.toLong())
}

fun toScopeVariableTreeNode(variables: Map<String, GroundedValue<*>?>): VariableTreeNode {
    return object : VariableTreeNode {
        override val name = "Local"
        override val childs: List<VariableTreeNode>
            get() = variables.entries.map { toVariableTreeNode(it) }
    }
}

fun toVariableTreeNode(v: Map.Entry<String, GroundedValue<*>?>): VariableTreeNode {
    return object : VariableTreeNode {
        override val name = v.key
        override val value = getValue(v.value)
        override val type = getType(v.value)
        override val childs: List<VariableTreeNode>
            get() = getChildVariableTreeNodes(v.value)
    }
}

fun shortString(msg: String): String {
    if (msg.length < 20) {
        return msg
    } else {
        val len = msg.length
        return String.format("%s...%s", msg.substring(0, 8), msg.substring(len - 9, len - 1))
    }
}

fun getValue(v: GroundedValue<*>?): String {
    if (v is NodeInfo) {
        // Element Attributes do not have source information so use the parent
        when (v.nodeKind.toShort()) {
            Type.DOCUMENT -> if (v.columnNumber < 0) {
                return String.format("(created root)")
            } else {
                return String.format("%s @%d:%d", v.toShortString(), v.lineNumber, v.columnNumber)
            }
            Type.ELEMENT,
            Type.PROCESSING_INSTRUCTION,
            Type.COMMENT,
            Type.NAMESPACE ->
                return String.format("%s @%d:%d", v.toShortString(), v.lineNumber, v.columnNumber)
            Type.TEXT,
            Type.WHITESPACE_TEXT ->
                return String.format("\"%s\"", shortString(v.getStringValue().trim().replace("\n", "")))
            // Type.ATTRIBUTE,
            else -> {
                val p = v.parent
                return String.format("%s @%d:%d", shortString(v.getStringValue()), p.lineNumber, p.columnNumber)
            }
        }
    } else if (v == null) {
        return "null"
    } else if (v is StringValue) {
        return v.toShortString()
    }

    val i = v.length
    if (i == 0) {
        return "[]"
    } else if (i == 1) {
        return v.head().stringValue
    } else {
        return String.format("['%s' ... %d]", getValue(v.head()), i)
    }
}

fun getType(v: GroundedValue<*>?): String {
    return if (v == null) {
        "null"
    } else if (v is NodeInfo) {
        when (v.nodeKind.toShort()) {
            Type.ELEMENT -> "ELEMENT NODE"
            Type.ATTRIBUTE -> "ATTRIBUTE NODE"
            Type.TEXT -> "TEXT NODE"
            Type.WHITESPACE_TEXT -> "WHITESPACE_TEXT NODE"
            Type.PROCESSING_INSTRUCTION -> "PROCESSING_INSTRUCTION NODE"
            Type.COMMENT -> "COMMENT NODE"
            Type.DOCUMENT -> "DOCUMENT NODE"
            Type.NAMESPACE -> "NAMESPACE NODE"
            else -> { throw Error("BUG: Unsupported node type. Add it!") }
        }
    } else if (v is StringValue) { "String"
    } else if (v is Int64Value) { "Integer"
    } else if (v is BooleanValue) { "Boolean"
    } else if (v is SequenceExtent) { "Sequence"
    } else {
        v::class.simpleName ?: "UNKNOWN_CLASS"
    }
}

fun getChildren(v: GroundedValue<*>?): Map<String, GroundedValue<*>> {
    val ret = LinkedHashMap<String, GroundedValue<*>>()
    if (v == null) {
    } else if (v is StringValue) {
    } else if (v is NodeInfo) {
        if (v.hasChildNodes()) {
            val it = v.iterateAxis(AxisInfo.CHILD)
            var i = 0
            it.forEach {
                when (it.nodeKind.toShort()) {
                    Type.WHITESPACE_TEXT,
                    Type.TEXT -> {
                        // Skip empty whitespace nodes
                        if (it.stringValue.trim().length > 0) {
                            ret[i.toString()] = it
                            i++
                        }
                    }
                    Type.ATTRIBUTE,
                    Type.ELEMENT,
                    Type.PROCESSING_INSTRUCTION,
                    Type.COMMENT,
                    Type.DOCUMENT,
                    Type.NAMESPACE -> {
                        ret[i.toString()] = it
                        i++
                    }
                }
            }
        }
    } else {
        var i = 0
        v.forEach {
            ret[i.toString()] = it
            i++
        }
    }
    return ret
}

fun getChildVariableTreeNodes(v: GroundedValue<*>?): List<VariableTreeNode> {
    return getChildren(v).entries.map { toVariableTreeNode(it) }
}
