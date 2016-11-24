package gaea.query

import gaea.graph._

import shapeless._
import shapeless.ops.hlist.RightFolder
import shapeless.ops.hlist.Prepend

import gremlin.scala._
import org.apache.tinkerpop.gremlin.process.traversal.P

// case class WithinQuery(property: String, within: List[String])
// case class HasQuery(has: List[WithinQuery]) extends GaeaStatement

// case class SelectQuery(steps: List[String]) extends GaeaStatement
// case class AsQuery(as: String) extends GaeaStatement

sealed trait Operation

case class VertexOperation(vertex: String) extends Operation {
  def operate(input: GaeaGraph): GremlinScala[Vertex, HNil] = {
    input.typeQuery(vertex)
  }
}

case class HasOperation[M](has: String, within: List[M]) extends Operation {
  def operate[Labels <: HList](input: GremlinScala[Vertex, Labels]): GremlinScala[Vertex, Labels] = {
    val gid = Key[M](has)
    input.has(gid, P.within(within:_*))
  }
}

case class AsOperation(as: String) extends Operation {
  def operate[A, In <: HList](input: GremlinScala[A, In]) (implicit p: Prepend[In, ::[A, HNil]]): GremlinScala[A, p.Out] = {
    input.as(as)
  }
}

case class InOperation(in: String) extends Operation {
  def operate[Labels <: HList](input: GremlinScala[Vertex, Labels]): GremlinScala[Vertex, Labels] = {
    input.in(in)
  }
}

case class OutOperation(out: String) extends Operation {
  def operate[Labels <: HList](input: GremlinScala[Vertex, Labels]): GremlinScala[Vertex, Labels] = {
    input.out(out)
  }
}

case class InEdgeOperation(inEdge: String) extends Operation {
  def operate[Labels <: HList](input: GremlinScala[Vertex, Labels]): GremlinScala[Edge, Labels] = {
    input.inE(inEdge)
  }
}

case class OutEdgeOperation(outEdge: String) extends Operation {
  def operate[Labels <: HList](input: GremlinScala[Vertex, Labels]): GremlinScala[Edge, Labels] = {
    input.outE(outEdge)
  }
}

case class InVertexOperation(inVertex: String) extends Operation {
  def operate[Labels <: HList](input: GremlinScala[Edge, Labels]): GremlinScala[Vertex, Labels] = {
    input.inV()
  }
}

case class OutVertexOperation(outVertex: String) extends Operation {
  def operate[Labels <: HList](input: GremlinScala[Edge, Labels]): GremlinScala[Vertex, Labels] = {
    input.outV()
  }
}

case class GaeaQuery(query: List[Operation]) {
  def operate[R](graph: GaeaGraph): R = {
    var anvil: Any = graph
    // def op[M, G, In <: HList](operation: Operation) (implicit p: Prepend[In, ::[G, HNil]]) {
    def op[M](operation: Operation) {
      operation match {
        case VertexOperation(vertex) => anvil = anvil.asInstanceOf[GaeaGraph].typeQuery(vertex)
        case HasOperation(has, within: List[M]) => anvil = {
          val gid = Key[M](has)
          anvil.asInstanceOf[GremlinScala[Vertex, HList]].has(gid, P.within(within:_*))
        }
        // case AsOperation(as) => anvil = anvil.asInstanceOf[GremlinScala[G, In]].as(as)
        case InOperation(in) => anvil = anvil.asInstanceOf[GremlinScala[Vertex, HList]].in(in)
        case OutOperation(out) => anvil = anvil.asInstanceOf[GremlinScala[Vertex, HList]].out(out)
        case InVertexOperation(inVertex) => anvil = anvil.asInstanceOf[GremlinScala[Edge, HList]].inV()
        case OutVertexOperation(outVertex) => anvil = anvil.asInstanceOf[GremlinScala[Edge, HList]].outV()
        case InEdgeOperation(inEdge) => anvil = anvil.asInstanceOf[GremlinScala[Vertex, HList]].inE()
        case OutEdgeOperation(outEdge) => anvil = anvil.asInstanceOf[GremlinScala[Vertex, HList]].outE()
      }
    }

    query.foreach(x => op(x))
    anvil.asInstanceOf[R]
  }
}

trait ApplyOperationDefault extends Poly2 {
  implicit def default[T, L <: HList] = at[T, L] ((_, acc) => acc)
}

object ApplyOperation extends ApplyOperationDefault {
  implicit def vertex[T, L <: HList, S <: HList] = at[VertexOperation, GaeaGraph] ((t, acc) => t.operate(acc))
  implicit def has[M, T, L <: HList, S <: HList] = at[HasOperation[M], GremlinScala[Vertex, S]] ((t, acc) => t.operate(acc))
  implicit def as[A, T, L <: HList, In <: HList](implicit p: Prepend[In, ::[A, HNil]]) = at[AsOperation, GremlinScala[A, In]] ((t, acc) => t.operate(acc))
  implicit def in[T, L <: HList, S <: HList] = at[InOperation, GremlinScala[Vertex, S]] ((t, acc) => t.operate(acc))
  implicit def out[T, L <: HList, S <: HList] = at[OutOperation, GremlinScala[Vertex, S]] ((t, acc) => t.operate(acc))
  implicit def inEdge[T, L <: HList, S <: HList] = at[InEdgeOperation, GremlinScala[Vertex, S]] ((t, acc) => t.operate(acc))
  implicit def outEdge[T, L <: HList, S <: HList] = at[OutEdgeOperation, GremlinScala[Vertex, S]] ((t, acc) => t.operate(acc))
  implicit def inVertex[T, L <: HList, S <: HList] = at[InVertexOperation, GremlinScala[Edge, S]] ((t, acc) => t.operate(acc))
  implicit def outVertex[T, L <: HList, S <: HList] = at[OutVertexOperation, GremlinScala[Edge, S]] ((t, acc) => t.operate(acc))
}

object Operation {
  def process[Input, Output, A <: HList](operations: A, input: Input) (implicit folder: RightFolder.Aux[A, Input, ApplyOperation.type, Output]): Output = {
    operations.foldRight(input) (ApplyOperation)
  }
}
