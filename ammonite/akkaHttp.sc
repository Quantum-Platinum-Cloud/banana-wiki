import coursier.core.Authentication, coursier.MavenRepository

interp.repositories() ++= Seq(MavenRepository(
  "http://bblfish.net/work/repo/releases/"
  ))

@

import scala.concurrent.ExecutionContext
import com.typesafe.config._

import $ivy.`org.w3::banana-jena:0.8.5-SNAPSHOT`
import org.w3.banana._
import org.w3.banana.syntax._
import org.w3.banana.jena.Jena
import Jena._
import Jena.ops._

import $ivy.`com.typesafe.akka::akka-http:10.0.9`
// import $file.RDFaBananaParser, RDFaBananaParser.{SesameRDFaReader,SesameRDFXMLReader}

//import $ivy.`ch.qos.logback:logback-classic:1.2.3`

import akka.actor.ActorSystem
import akka.actor.SupervisorStrategy
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{Uri=>AkkaUri,_}

import akka.stream.{ActorMaterializer, ActorMaterializerSettings,Supervision,_}
import akka.stream.scaladsl._
import akka.{ NotUsed, Done }
import akka.event.Logging

import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal
import scala.util.{Try,Success,Failure}
//see http://doc.akka.io/docs/akka/snapshot/scala/general/configuration.html#listing-of-the-reference-configuration
/*val shortScriptConf = ConfigFactory.parseString("""
   |akka {
   |   loggers = ["akka.event.Logging$DefaultLogger"]
   |   logging-filter = "akka.event.DefaultLoggingFilter"
   |   loglevel = "ERROR"
   |}
 """.stripMargin)*/
implicit val system = ActorSystem("akka_ammonite_script")
val log = Logging(system.eventStream, "banana-rdf")
implicit val materializer = ActorMaterializer(
                ActorMaterializerSettings(system).withSupervisionStrategy(Supervision.resumingDecider))
implicit val ec: ExecutionContext = system.dispatcher

//just to play with
val bblfish = AkkaUri("http://bblfish.net/people/henry/card#me")
val timbl = AkkaUri("https://www.w3.org/People/Berners-Lee/card#i")

trait WebException extends java.lang.RuntimeException with NoStackTrace with Product with Serializable
case class HTTPException(resourceUri: String, msg: String) extends WebException
case class ConnectionException(resourceUri: String, e: Throwable) extends WebException
case class NodeTranslationException(graphLoc: String, problemNode: Rdf#Node, e: Throwable) extends WebException
case class MissingParserException(
  resourceUri: String,
  statusCode: StatusCode,
  contentType: ContentType,
  initialContent: String
) extends WebException
case class ParseException(
  resourceUri: String,
  status: StatusCode,
  responseHeaders: Seq[HttpHeader],
  contentType: ContentType,
  initialContent: String,
  e: Throwable
) extends WebException


object RdfMediaTypes {
   import akka.http.scaladsl.model
   import model.ContentType
   import model.MediaType.{text,applicationWithOpenCharset,applicationWithFixedCharset}
   import model.HttpCharsets._
   import org.w3.banana.io.RDFReader
   import akka.http.scaladsl.unmarshalling.{Unmarshaller,PredefinedFromEntityUnmarshallers,FromEntityUnmarshaller}
   import akka.http.scaladsl.model.MediaTypes.`text/html`
   import scala.util.{Try,Success,Failure}
   import akka.http.scaladsl.model.{HttpHeader,StatusCode}

    case class NoUnmarshallerException(mime: ContentType, msg: String) extends java.lang.RuntimeException with NoStackTrace with Product with Serializable

   //todo: check if there are other older mime types, or if there are widely used extensions
   val `text/turtle` = text("turtle","ttl")
   val `application/rdf+xml` = applicationWithOpenCharset("rdf+xml","rdf")
   val `application/ntriples` = applicationWithFixedCharset("ntriples",`UTF-8`,"nt")
   val `application/ld+json` = applicationWithOpenCharset("ld+json","jsonld")


   def rdfUnmarshaller(
     requestUri: AkkaUri, status: StatusCode,
     headers: Iterable[HttpHeader]
   )(implicit ec: ExecutionContext): FromEntityUnmarshaller[Rdf#Graph] =
      PredefinedFromEntityUnmarshallers.stringUnmarshaller flatMapWithInput { (entity, string) ⇒
        //todo: use non blocking parsers
         val readerOpt = entity.contentType.mediaType match { //<- this needs to be tuned!
            case `text/turtle` => Some(turtleReader)
            case `application/rdf+xml` => Some(rdfXMLReader)
            case `application/ntriples` => Some(ntriplesReader)
            case `application/ld+json` => Some(jsonldReader)
            // case `text/html` => new SesameRDFaReader()
            case _ => None
         }
         readerOpt.map{ reader=>
           Future.fromTry {
             reader.read(new java.io.StringReader(string),requestUri.toString) recoverWith {
             case e => Failure(ParseException(
               requestUri.toString, status,
               headers.toSeq,entity.contentType,string.take(400),e)
             )}
          }
        } getOrElse {
           scala.concurrent.Future.failed(
             MissingParserException(
               requestUri.toString, status,
               entity.contentType,string.take(400)
             )
           )
       }
    }

}


object Web {
    type PGWeb = IRepresentation[PointedGraph[Rdf]]

    import akka.http.scaladsl.model.MediaTypes.`text/html`



   val foaf = FOAFPrefix[Rdf]
   val rdfs = RDFSPrefix[Rdf]

   implicit class UriW(val uri: AkkaUri)  extends AnyVal {
         def fragmentLess: AkkaUri =
            if (uri.fragment.isEmpty) uri else uri.copy(fragment=None)

         def toRdf: Rdf#URI = URI(uri.toString)
    }

   def rdfRequest(uri: AkkaUri): HttpRequest = {
      import RdfMediaTypes._
      import akka.http.scaladsl.model.headers.Accept
      HttpRequest(uri=uri.fragmentLess)
           .addHeader(Accept(`text/turtle`,`application/rdf+xml`,
                             `application/ntriples`,
                             `application/ld+json`.withQValue(0.8), //our parser uses more memory
                             `text/html`.withQValue(0.2))) //we can't specify that we want RDFa in our markup
   }

   //interpreted HttpResponse
   case class IRepresentation[C](origin: AkkaUri, status: StatusCode,
     headers: Seq[HttpHeader], fromContentType: ContentType,
     content: C) {
      def map[D](f: C => D) = this.copy(content=f(content))
   }


   implicit class HttResPG(val h: PGWeb) extends AnyVal {
    def jump(rel: Rdf#URI)(implicit web: Web): List[Future[PGWeb]] =
          (h.content/rel).toList.map{ pg =>
              if (pg.pointer.isURI) try {
                   web.pointedGET(AkkaUri(pg.pointer.toString))
                 } catch {
                   case NonFatal(e) => Future.failed(NodeTranslationException(h.origin.toString, pg.pointer,e))
                 }
              else Future.successful(h.copy(content=pg))
       }

     // does it make sense to use Http().superPool(...) ?
     //def jumps(rel: Rdf#URI)(implicit web: Web): Stream[PGWeb] {
     //}
    }

    import scala.collection.immutable
    def uriSource(uris: AkkaUri*): Source[AkkaUri,NotUsed] =
         Source(immutable.Seq(uris:_*).to[collection.immutable.Iterable])

   //simple transformation of a Future into an always successful one - useful to avoid failed futures leading to closure of streams
   def neverFail[X](fut: Future[X]): Future[Try[X]] = fut.transform(Success(_))

   //flatten a flow of Flow[Future[X]] to a Flow[X]
   def flattenFutureFlow[X](n: Int=1): Flow[Future[X],X,_] = Flow[Future[X]].mapAsyncUnordered(n)(identity)

   /**
    * return an Akka Source of Try[IRepresentation[Rdf#PointedGraphs]] starting
    * from the me WebID, and including any relevant rdfs.seeAlso linked files.
    * The source is useful for finding all the linked to friends, including broken
    * links, with very simple explanations as to what went wrong accessing those
    * (hence the Try).
    */
   def foafKnowsSource(webid: AkkaUri)(implicit web: Web): Source[Try[PGWeb],_] =
      uriSource(webid)
           .mapAsync(1){uri => web.pointedGET(uri)}
                      .via(addSeeAlso)
                      .mapConcat{ //jump to remote foaf.knows
                      case Success(pgweb) => pgweb.jump(foaf.knows).to[immutable.Iterable].map(neverFail(_))
                      case failure => immutable.Iterable(Future.successful(failure))
                   }.via(flattenFutureFlow(50))


   //add any rdfs:seeAlso going out from a node to the stream, placing the pointer on the same point in the other graph
   def addSeeAlso(implicit web: Web): Flow[PGWeb,Try[PGWeb],_] = Flow[PGWeb].mapConcat{ pgweb =>
       val seqFut:  immutable.Seq[Future[PGWeb]] = pgweb.jump(rdfs.seeAlso).to[immutable.Seq]
        //we want the see Also docs to be pointing to the same URI as the original pgweb
       val seeAlso = seqFut.map(fut => fut.map( _.map(pg=>PointedGraph[Rdf](pgweb.content.pointer,pg.graph) )))
         (seeAlso :+ Future.successful(pgweb)).map(neverFail(_))
   }.via(flattenFutureFlow(50))  // 50 is a bit arbitrary

   def filterForSuccess[X] = Flow[Try[X]].collect{ case Success(x) => x }

   def filterLinkedTo(rel: Rdf#URI, obj: Rdf#URI): Flow[PGWeb,PGWeb,_] =
     Flow[PGWeb].filter(htres => (htres.content/rel).exists(_.pointer == obj))

   def consciensciousFriends(me: AkkaUri)(implicit web: Web): Source[PGWeb,_] =
      foafKnowsSource(me).via(filterForSuccess)
             .via(addSeeAlso)
             .via(filterForSuccess)
             .via(filterLinkedTo(foaf.knows,me.toRdf))


   /*
   def xxx() = {

      val sinkFold2 = Sink.fold[List[Try[Web.PGWeb]],
                                Try[Web.PGWeb]](List()){ case (l,t)=> t::l }
      sourceJumpTryKn.toMat(sinkFold2)(Keep.right)
   }
   */

}

class Web(implicit ec: ExecutionContext) {
   import Web._


   def GETRdfDoc(uri: AkkaUri, maxRedirect: Int=4): Future[HttpResponse] = GET(rdfRequest(uri),maxRedirect)

   //todo: add something to the response re number of redirects
   //see: https://github.com/akka/akka-http/issues/195
   def GET(req: HttpRequest, maxRedirect: Int = 4)(implicit
      system: ActorSystem, mat: Materializer): Future[HttpResponse] = {
      try {
         import StatusCodes._
         Http().singleRequest(req)
               .recoverWith{case e=>Future.failed(ConnectionException(req.uri.toString,e))}
               .flatMap { resp =>
            resp.status match {
              case Found | SeeOther | TemporaryRedirect | PermanentRedirect => {
                  log.info(s"received a ${resp.status} for ${req.uri}")
                  resp.header[headers.Location].map { loc =>
                  val newReq = req.copy(uri = loc.uri)
                  if (maxRedirect > 0) GET(newReq, maxRedirect - 1) else Http().singleRequest(newReq)
                 }.getOrElse(Future.failed(HTTPException(req.uri.toString,s"Location header not found on ${resp.status} for ${req.uri}")))
              }
              case _ => Future.successful(resp)
            }
         }
      } catch {
         case NonFatal(e) => Future.failed(ConnectionException(req.uri.toString,e))
      }
   }



   def GETrdf(uri: AkkaUri): Future[IRepresentation[Rdf#Graph]] = {
     import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
     import akka.util.ByteString

     def decode(bytes: ByteString, entity: ResponseEntity) = {
        val charBuffer = Unmarshaller.bestUnmarshallingCharsetFor(entity).nioCharset.decode(bytes.asByteBuffer)
        val array = new Array[Char](charBuffer.length())
        charBuffer.get(array)
        new java.lang.String(array)
     }

     GETRdfDoc(uri).flatMap {
        case HttpResponse(status,headers,entity,protocol) => {
            implicit  val reqUnmarhaller = RdfMediaTypes.rdfUnmarshaller(uri,status,headers)
            Unmarshal(entity).to[Rdf#Graph].map {g =>
              IRepresentation[Rdf#Graph](uri,status,headers,entity.contentType,g)
            }
         }
       }
   }

    def pointedGET(uri: AkkaUri): Future[PGWeb] =
         GETrdf(uri).map(_.map(PointedGraph[Rdf](uri.toRdf,_)))


}
