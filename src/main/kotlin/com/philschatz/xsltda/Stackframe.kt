package com.philschatz.xsltda

import net.sf.saxon.om.GroundedValue
import net.sf.saxon.trace.InstructionInfo

data class Stackframe(
    val stackInstruction: InstructionInfo,
    var currentInstruction: InstructionInfo,
    var variables: Map<String, GroundedValue<*>?>,
    var tunnelParams: Map<String, GroundedValue<*>?>
)
