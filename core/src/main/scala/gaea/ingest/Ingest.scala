package gaea.ingest

import gaea.titan.Titan

import org.json4s._
import org.json4s.jackson.JsonMethods._
import com.thinkaurelius.titan.core.TitanGraph
import gremlin.scala._
import java.lang.{Long => Llong}

object Ingest {
  val edgesPattern = "(.*)Edges$".r
  val keymap = collection.mutable.Map[String, Key[Any]]()

  def findKey[T](key: String): Key[T] = {
    val newkey = keymap.get(key).getOrElse {
      val newkey = Key[Any](key)
      keymap(key) = newkey
      newkey
    }

    newkey.asInstanceOf[Key[T]]
  }

  def parseJson(raw: String): JValue = {
    parse(raw)
  }

  def stringFor(obj: JObject) (key: String): String = {
    (obj \\ key).asInstanceOf[JString].s
  }

  def camelize(s: String): String = {
    val break = s.split("_")
    val upper = break.head +: break.tail.map(_.capitalize)
    upper.mkString("")
  }

  def retryCommit(graph: TitanGraph) (times: Integer): Unit = {
    if (times == 0) {
      println("TRANSACTION FAILED!")
    } else {
      try {
        graph.tx.commit()
      } catch {
        case ex: Exception => {
          retryCommit(graph) (times - 1)
        }
      }
    }
  }

  def findVertex(graph: TitanGraph) (label: String) (name: String): Vertex = {
    val vertex = Titan.namedVertex(graph) (label) (name)
    Titan.associateType(graph) (vertex) (label)
    vertex
  }

  def setProperty(vertex: Vertex) (field: Tuple2[String, JValue]): Unit = {
    val key = camelize(field._1)
    field._2 match {
      case JString(value) =>
        vertex.setProperty(findKey[String](key), value)
      case JDouble(value) =>
        vertex.setProperty(findKey[Double](key), value)
      case JBool(value) =>
        vertex.setProperty(findKey[Boolean](key), value)
      case JInt(value) =>
        vertex.setProperty(findKey[Long](key), value.toLong)
      case _ =>
        println("Unsupported Key: " + key)
    }
  }

  def setProperties(vertex: Vertex) (fields: List[Tuple2[String, JValue]]): Unit = {
    for (field <- fields) {
      setProperty(vertex) (field)
    }
  }

  def ingestVertex(graph: TitanGraph) (json: JValue): Vertex = {
    val data = json.asInstanceOf[JObject]
    val name = stringFor(data) ("name")
    val label = stringFor(data) ("type")
    val vertex = findVertex(graph) (label) (name)

    for (field <- data.obj) {
      val key = field._1
      field._2 match {
        case JObject(obj) =>
          setProperties(vertex) (obj)
        case JArray(arr) =>
          edgesPattern.findAllIn(key).matchData.foreach { edgeMatch =>
            for (value <- arr) {
              val edge = value.asInstanceOf[JString].s
              val label = Titan.labelPrefix(edge)
              Titan.associateOut(graph) (vertex) (edgeMatch.group(1)) (label) (edge)
            }
          }
        case _ =>
          setProperty(vertex) (field)
      }
    }

    retryCommit(graph) (5)
    vertex
  }
}