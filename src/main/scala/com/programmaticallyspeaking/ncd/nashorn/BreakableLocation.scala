package com.programmaticallyspeaking.ncd.nashorn

import com.programmaticallyspeaking.ncd.host.{Breakpoint, Script, ScriptLocation}
import com.sun.jdi.Location
import com.sun.jdi.request.{BreakpointRequest, EventRequest, EventRequestManager}

object BreakableLocation {
  // TODO: Move elsewhere
  def scriptLocationFromScriptAndLocation(script: Script, location: Location): ScriptLocation = {
    val lineNo = location.lineNumber()
    // Don't set column here - columns are guessed in NashornDebuggerHost.
    ScriptLocation(lineNo, None)
  }
}

/**
  * Represents a location in a script that the debugger can break at.
  *
  * @param script the script that contains the location
  * @param eventRequestManager [[EventRequestManager]] instance for creating/removing a breakpoint
  * @param location the location
  */
class BreakableLocation(val script: Script, eventRequestManager: EventRequestManager, val location: Location) {

  var _scriptLocation: ScriptLocation = BreakableLocation.scriptLocationFromScriptAndLocation(script, location)

  def scriptLocation: ScriptLocation = _scriptLocation

  private var breakpointRequest: BreakpointRequest = _

  def setColumn(column1Based: Int): Unit = {
    _scriptLocation = _scriptLocation.copy(columnNumber1Based = Some(column1Based))
  }

  def isEnabled = breakpointRequest != null

  /**
    * Instructs the VM to break at this location.
    */
  def enable(): Unit = {
    breakpointRequest = eventRequestManager.createBreakpointRequest(location)

    // Assume script code runs in a single thread, so pausing that thread should be enough.
    breakpointRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)

    breakpointRequest.enable()
  }

  def disable(): Unit = {
    eventRequestManager.deleteEventRequest(breakpointRequest)
    breakpointRequest = null
  }

//  def toBreakpoint(id: String) = Breakpoint(id, script.id, Some(script.url), scriptLocation)

  override def toString: String = script.id + "/" + scriptLocation.toString
}
