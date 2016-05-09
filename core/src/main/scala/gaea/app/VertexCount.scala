
package gaea.app

import gaea.lib.TitanConnection

object VertexCount {
  def main(args: Array[String]) {
    val conn = new TitanConnection(args(0))
    val graph = conn.connect()
    printf("Vertices: %s", graph.traversal().V().count() )
  }
}