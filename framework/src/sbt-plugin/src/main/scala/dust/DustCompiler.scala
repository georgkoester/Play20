package play.core.dust

import java.io._
import play.api._

object DustCompiler {

  import org.mozilla.javascript._
  import org.mozilla.javascript.tools.shell._

  import scala.collection.JavaConverters._

  import scalax.file._

  private lazy val compiler = {
    val ctx = Context.enter; ctx.setOptimizationLevel(-1)
    val global = new Global; global.init(ctx)
    val scope = ctx.initStandardObjects(global)
                          
    // create the window variable that dust compilation requires
    // sadly not working jhval fakeWindow = ctx.newObject(global)
    //scope.put("window",  scope, fakeWindow)

    val wrappedDustCompiler = Context.javaToJS(this, scope)
    ScriptableObject.putProperty(scope, "DustCompiler", wrappedDustCompiler)

    ctx.evaluateReader(scope, new InputStreamReader(
      this.getClass.getClassLoader.getResource("dust-full-0.3.0.js").openConnection().getInputStream()),
      "dust-full-0.3.0.js",
      1, null)

    val dust = scope.get("dust", scope).asInstanceOf[NativeObject]
    val compilerFunction = dust.get("compile", scope).asInstanceOf[Function]

    Context.exit

    (source: File) => {
      val path = Path(source)
      val dustTemplate = path.slurpString.replace("\r", "")
      val name = path.name match {
          case s : String if s.contains(".")  => s.substring(0, s.lastIndexOf("."))
          case s : String => s
      }
      Context.call(null, compilerFunction, scope, scope, Array(dustTemplate, name)).asInstanceOf[String]
    }

  }

  def compile(source: File): String = {
    try {
      compiler(source)
    } catch {
      case e: JavaScriptException => {

        val line = """.*on line ([0-9]+).*""".r
        val error = e.getValue.asInstanceOf[Scriptable]

        throw ScriptableObject.getProperty(error, "message").asInstanceOf[String] match {
          case msg @ line(l) => CompilationException(
            msg,
            source,
            Some(Integer.parseInt(l)))
          case msg => CompilationException(
            msg,
            source,
            None)
        }

      }
    }
  }

}

case class CompilationException(message: String, dustFile: File, atLine: Option[Int]) extends PlayException(
  "Compilation error", message) with PlayException.ExceptionSource {
  def line = atLine
  def position = None
  def input = Some(scalax.file.Path(dustFile))
  def sourceName = Some(dustFile.getAbsolutePath)
}

