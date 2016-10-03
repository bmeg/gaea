package gaea.facet

import org.http4s._
import org.http4s.server._
import org.http4s.dsl._

object BaseFacets {
  val facets = List[GaeaFacet](
    new VertexFacet("/vertex/"),
    new MessageFacet("/message/")
    // ("/console/", ConsoleFacet.service)
  )
}
