package com.philschatz.xsltda

import net.sf.saxon.om.GroundedValue
import net.sf.saxon.trace.InstructionInfo

data class Stackframe(
    val instruction: InstructionInfo,
    val variables: MutableMap<String, GroundedValue<*>?>
)
