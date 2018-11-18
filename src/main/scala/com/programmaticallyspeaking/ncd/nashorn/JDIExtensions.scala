package com.programmaticallyspeaking.ncd.nashorn

import com.programmaticallyspeaking.ncd.host.ScopeType
import com.programmaticallyspeaking.ncd.infra.{CompiledScript, ScriptURL}
import com.programmaticallyspeaking.ncd.nashorn.NashornDebuggerHost.{EventHandler, EventHandlerKey}
import com.sun.jdi._
import com.sun.jdi.event.Event
import com.sun.jdi.request.EventRequest

import scala.language.implicitConversions

object JDIExtensions {
  import scala.collection.JavaConverters._
  import TypeConstants._

  implicit class RichEvent(val event: Event) extends AnyVal {
    def handle(): Option[Boolean] = {
      // Null-testing the request for https://github.com/provegard/ncdbg/issues/88
      Option(event.request()).flatMap(r => Option(r.getProperty(EventHandlerKey))).collect {
        case h: EventHandler => h(event)
      }
    }
  }

  implicit class RichRequest(val eventRequest: EventRequest) extends AnyVal {
    def onEventDo(h: EventHandler): Unit = {
      Option(eventRequest.getProperty(EventHandlerKey)).foreach(_ => throw new IllegalStateException("Event handler already associated."))
      eventRequest.putProperty(EventHandlerKey, h)
    }
  }

  implicit class RichLocation(val location: Location) extends AnyVal {

    /**
      * Determines if the location is in ScriptRuntime.DEBUGGER.
      */
    def isDebuggerStatement: Boolean =
      location.declaringType().name() == NIR_ScriptRuntime && location.method().name() == ScriptRuntime_DEBUGGER


    def byteCode: Int = {
      val methodByteCodes = location.method().bytecodes()
      val bc = methodByteCodes(location.codeIndex().toInt).toInt
      if (bc < 0) bc + 256 else bc
    }

    private def lineOfLastLocation = location.method().allLineLocations().asScala.last.lineNumber()
    def isLastLineInFunction: Boolean = location.lineNumber() == lineOfLastLocation

    def sameMethodAndLineAs(other: Option[Location]): Boolean =
      other.exists(l => l.method() == location.method() && l.lineNumber() == location.lineNumber())

    def scriptURL: ScriptURL = ScriptURL.create(scriptPath)
    private def scriptPath: String = {
      // It appears *name* is a path on the form 'file:/c:/...', whereas path has a namespace prefix
      // (jdk\nashorn\internal\scripts\). This seems to be consistent with the documentation (although it's a bit
      // surprising), where it is stated that the Java stratum doesn't use source paths and a path therefore is a
      // package-qualified file name in path form, whereas name is the unqualified file name (e.g.:
      // java\lang\Thread.java vs Thread.java).
      val sourceName = location.sourceName()
      sourceNameToUrl(location.declaringType(), sourceName)
    }
  }

  private def sourceNameToUrl(refType: ReferenceType, sourceName: String): String =
    NameConvert.sourceNameToUrl(refType.name(), sourceName)

  implicit class RichValue(val v: Value) extends AnyVal {
    def scopeType: ScopeType = {
      val typeName = v.`type`().name()
      // jdk.nashorn.internal.objects.Global
      if (typeName.endsWith(".Global"))
        ScopeType.Global
      // jdk.nashorn.internal.runtime.WithObject
      else if (isWithObject)
        ScopeType.With
      else ScopeType.Closure
    }

    def isGlobal: Boolean = scopeType == ScopeType.Global

    def typeName: String = v.`type`().name()

    def isUndefined: Boolean = {
      typeName == "jdk.nashorn.internal.runtime.Undefined"
    }

    def isWithObject: Boolean = {
      typeName.endsWith(".WithObject")
    }
  }

  implicit class RichReferenceType(val referenceType: ReferenceType) extends AnyVal {
    private def scriptSourceField(refType: ReferenceType): Field = {
      // Generated script classes has a field named 'source'
      Option(refType.fieldByName("source"))
        .getOrElse(throw new Exception("Found no 'source' field in " + refType.name()))
    }

    def scriptURL: ScriptURL = ScriptURL.create(scriptPath)
    private def scriptPath: String = {
      val sourceName = referenceType.sourceName()
      sourceNameToUrl(referenceType, sourceName)
    }

    /**
      * This method extracts the source code of an evaluated script, i.e. a script that hasn't been loaded from a file
      * and therefore doesn't have a path/URL. The official way to do this would be to call `DebuggerSupport.getSourceInfo`,
      * but we cannot do that because when we connect to the VM and discover all scripts, we don't have a thread that is
      * in the appropriate state to allow execution of methods. However, extracting field values is fine, so we dive deep
      * down in the Nashorn internals to grab the raw source code data.
      *
      * @return a source code string
      */
    def shamelesslyExtractEvalSourceFromPrivatePlaces(): Option[String] = {
      val sourceField = scriptSourceField(referenceType)
      // Get the Source instance in that field
      Option(referenceType.getValue(sourceField).asInstanceOf[ObjectReference]).map { source =>
        // From the instance, get the 'data' field, which is of type Source.Data
        val dataField = Option(source.referenceType().fieldByName("data"))
          .getOrElse(throw new Exception("Found no 'data' field in " + source.referenceType().name()))
        // Get the Source.Data instance, which should be a RawData instance
        val data = source.getValue(dataField).asInstanceOf[ObjectReference]
        // Source.RawData has a field 'array' of type char[]
        val charArrayField = Option(data.referenceType().fieldByName("array"))
          .getOrElse(throw new Exception("Found no 'array' field in " + data.referenceType().name()))
        // Get the char[] data
        val charData = data.getValue(charArrayField).asInstanceOf[ArrayReference]
        // Get individual char values from the array
        val chars = charData.getValues.asScala.map(v => v.asInstanceOf[CharValue].charValue())
        // Finally combine into a string
        chars.mkString
      }
    }

  }
}
