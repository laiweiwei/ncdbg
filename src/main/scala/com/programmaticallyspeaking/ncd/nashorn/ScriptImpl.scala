package com.programmaticallyspeaking.ncd.nashorn

import java.io.{File, FileNotFoundException}
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files

import com.programmaticallyspeaking.ncd.host.Script
import com.programmaticallyspeaking.ncd.infra.Hasher

class ScriptImpl(val uri: String, scriptData: Array[Byte], val id: String) extends Script {
  import ScriptImpl._

  val contents = new String(scriptData, UTF8)

  val lines: Seq[String] = contents.split("\r?\n")

  val lineCount = lines.length
  val lastLineLength = lines.lastOption.map(_.length).getOrElse(0)

  private var cachedHash: String = _
  private object hashLock
  override def contentsHash(): String = {
    if (cachedHash == null) {
      hashLock.synchronized {
        if (cachedHash == null) {
          cachedHash = Hasher.md5(scriptData)
        }
      }
    }
    cachedHash
  }
}

object ScriptImpl {

  val UTF8 = Charset.forName("utf8")

  def filePathToUrl(path: String): String = {
    if (path.startsWith("file:/")) return filePathToUrl(path.substring(6))
    val parts: Seq[String] = path.split("[/\\\\]").filter(_ != "").toList match {
      case head :: tail if head.length >= 2 && head(1) == ':' =>
        Seq(head(0).toString) ++ tail
      case head :: tail => Seq(head) ++ tail
      case Nil => Seq.empty
    }
    "file://" + parts.mkString("/")
  }

  def fromFile(path: String, id: String): Script = {
    val file = new File(path)
    // Files.readAllBytes doesn't do this, it seems. Weird!
    if (!file.exists) throw new FileNotFoundException(path)
    new ScriptImpl(filePathToUrl(path), Files.readAllBytes(file.toPath), id)
  }

  def fromSource(path: String, source: String, id: String): Script = {
    val bytes = source.getBytes(UTF8)
    new ScriptImpl(new URI(path).toString, bytes, id)
  }
}