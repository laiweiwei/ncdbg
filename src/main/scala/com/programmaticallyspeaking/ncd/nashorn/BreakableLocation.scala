package com.programmaticallyspeaking.ncd.nashorn

import com.programmaticallyspeaking.ncd.host.{Breakpoint, Script, ScriptLocation}
import com.sun.jdi.Location
import com.sun.jdi.request.{BreakpointRequest, EventRequest, EventRequestManager}

object BreakableLocation {
  // TODO: Move elsewhere
  def scriptLocationFromScriptAndLocation(script: Script, location: Location): ScriptLocation = {
    val lineNo = location.lineNumber()
    var colNo = script.sourceLine(lineNo) match {
      case Some(line) =>
        // Use index of first non-whitespace
        1 + line.takeWhile(_.isWhitespace).length
      case None => 1 // assume first column
    }
    ScriptLocation(lineNo, colNo)
  }
}

/**
  * Represents a location in a script that the debugger can break at.
  *
  * @param id unique ID of this breakable location
  * @param script the script that contains the location
  * @param eventRequestManager [[EventRequestManager]] instance for creating/removing a breakpoint
  * @param location the location
  */
class BreakableLocation(val id: String, val script: Script, eventRequestManager: EventRequestManager, val location: Location) {

  val scriptLocation: ScriptLocation = BreakableLocation.scriptLocationFromScriptAndLocation(script, location)

  private var breakpointRequest: BreakpointRequest = _
  private var enabledOnce: Boolean = false

  def isEnabled = breakpointRequest != null
  def isEnabledOnce = isEnabled && enabledOnce

  /**
    * Instructs the VM to break at this location.
    */
  def enable() = doEnable(once = false)

  /**
    * Instructs the VM to break at this location, and sets a flag that indicates that it's a one-time breakpoint.
    */
  def enableOnce() = doEnable(once = true)

  private def doEnable(once: Boolean): Unit = {
    breakpointRequest = eventRequestManager.createBreakpointRequest(location)

    // Assume script code runs in a single thread, so pausing that thread should be enough.
    breakpointRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)

    breakpointRequest.enable()

    // Note that we don't add a count filter if once==true. The reason is that we create a lot of one-off breakpoints in
    // the step-into case, and we need to remove all of those when one of them is hit. A count filter doesn't work that
    // way.
    enabledOnce = once
  }

  def disable(): Unit = {
    eventRequestManager.deleteEventRequest(breakpointRequest)
    breakpointRequest = null
  }

  def toBreakpoint = Breakpoint(id, script.id, scriptLocation)
}
