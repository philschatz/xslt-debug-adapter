package com.philschatz.xsltda

import org.javacs.ktda.core.event.BreakpointStopEvent
import org.javacs.ktda.core.event.DebuggeeEventBus
import org.javacs.ktda.core.event.ExceptionStopEvent
import org.javacs.ktda.core.event.ExitEvent
import org.javacs.ktda.core.event.StepStopEvent
import org.javacs.ktda.util.ListenerList

class SimpleEventBus : DebuggeeEventBus {
    override val exitListeners = ListenerList<ExitEvent>()
        override val breakpointListeners = ListenerList<BreakpointStopEvent>()
        override val stepListeners = ListenerList<StepStopEvent>()
        override var exceptionListeners = ListenerList<ExceptionStopEvent>()
}
