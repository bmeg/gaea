package gaea.facet

import gaea.graph._
import gaea.ingest.Ingest
import gaea.collection.Collection._

import org.http4s._
import org.http4s.server._
import org.http4s.dsl._

import com.thinkaurelius.titan.core.TitanGraph
import gremlin.scala._
import org.apache.tinkerpop.gremlin.process.traversal.Order
import org.apache.tinkerpop.gremlin.process.traversal.P._

import com.typesafe.scalalogging._
import _root_.argonaut._, Argonaut._
import org.http4s.argonaut._
import scalaz.stream.text
import scalaz.stream.Process
import scalaz.stream.Process._
import scalaz.stream.Process1
import scalaz.concurrent.Task
import scala.collection.JavaConversions._

case class MessageFacet(root: String) extends GaeaFacet with LazyLogging {
  def ingestMessage(graph: GaeaGraph) (line: String): Task[Vertex] = Task {
    val json = Ingest.parseJson(line)
    Ingest.ingestVertex(graph) (json)
  }

  def service(graph: GaeaGraph): HttpService = {
    HttpService {
      case request @ POST -> Root / "ingest" =>
        val messages = request.bodyAsText.pipe(text.lines(1024 * 1024 * 64)).flatMap { line =>
          Process eval ingestMessage(graph) (line)
        }

        messages.runLog.run
        Ok(jString("done!"))
    }
  }
}
