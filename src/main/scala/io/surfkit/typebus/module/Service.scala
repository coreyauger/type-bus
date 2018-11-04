package io.surfkit.typebus.module

import java.io.ByteArrayInputStream
import java.util.UUID

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerMessage, ConsumerSettings, Subscriptions}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import com.sksamuel.avro4s.{AvroInputStream, AvroSchema}
import io.surfkit.typebus.event._
import io.surfkit.typebus.{ByteStreamReader, ByteStreamWriter}

import scala.concurrent.Future
import scala.concurrent.duration._
import org.joda.time.DateTime

import scala.reflect.ClassTag

/**
  * Created by suroot on 21/12/16.
  */
trait Service[UserBaseType] extends Module[UserBaseType]{

  def registerStream[T <: UserBaseType : ClassTag, U <: UserBaseType : ClassTag](f: (T) => Future[U]) (implicit reader: ByteStreamReader[T], writer: ByteStreamWriter[U]) =
    op(funToPF(f))

  def registerStream[T <: UserBaseType : ClassTag, U <: UserBaseType : ClassTag](f:  (T, EventMeta) => Future[U])  (implicit reader: ByteStreamReader[T], writer: ByteStreamWriter[U]) =
    op2(funToPF2(f))

  def startService(consumerSettings: ConsumerSettings[Array[Byte], Array[Byte]])(implicit system: ActorSystem) = {
    import system.dispatcher
    val decider: Supervision.Decider = {
      case _ => Supervision.Resume  // Never give up !
    }

    implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))

    val replyAndCommit = new PartialFunction[(ConsumerMessage.CommittableMessage[Array[Byte], Array[Byte]],PublishedEvent, Any), Future[Done]]{
      def apply(x: (ConsumerMessage.CommittableMessage[Array[Byte], Array[Byte]],PublishedEvent, Any) ) = {
        //println("replyAndCommit")
        implicit val timeout = Timeout(4 seconds)
        //println(s"Doing actor selection and send: ${x._3} => ${x._2.source}")
        //println(s"Doing actor selection and send: ${x._2.source}")

        system.actorSelection(x._2.meta.source).resolveOne().flatMap { actor =>
          /*
          actor ! PublishedEvent(
            meta = EventMeta(
              eventId = UUID.randomUUID.toString,
              eventType = x._3.getClass.getCanonicalName,
              userId = x._2.userId,
              source = x._2.source,
              socketId = x._2.socketId,
              responseTo = Some(x._2.eventId),
              publishedAt = new DateTime(),
              occurredAt = new DateTime(),
              correlationId = x._2.correlationId
            ),
            payload = x._3)
            */
          x._1.committableOffset.commitScaladsl()
        }
      }
      def isDefinedAt(x: (ConsumerMessage.CommittableMessage[Array[Byte], Array[Byte]],PublishedEvent, Any) ) = true
    }

    println(s"STARTING TO LISTEN ON TOPICS:\n ${listOfTopics}")

    Consumer.committableSource(consumerSettings, Subscriptions.topics(listOfTopics:_*))
      .mapAsyncUnordered(4) { msg =>
        println(s"Consumer got message: ${msg}")
        println(s"got msg for topic: ${msg.record.topic()}")
        val schema = AvroSchema[PublishedEvent]
        val reader = listOfImplicitsReaders(msg.record.topic())
        println(s"listOfImplicits: ${listOfImplicitsReaders}")
        try {
          val input = AvroInputStream.binary[PublishedEvent].from(msg.record.value()).build(schema)

          val result = input.iterator.toSeq
          println(s"got result: ${result}")
          val publish = result.head
          println(s"got publish: ${publish}")
          println(s"reader: ${reader}")
          println(s"publish.payload.size: ${publish.payload.size}")
          val payload = reader.read(publish.payload)
          println(s"got payload: ${payload}")

          //val event = publish.copy(payload = mapper.readValue[m.Model](mapper.writeValue(publish.payload)) )

          if(handleEventWithMeta.isDefinedAt( (payload, publish.meta) ) )
            handleEventWithMeta( (payload, publish.meta)  ).map(x => (msg, publish, x))
          else
            handleEvent(payload).map(x => (msg, publish, x))
        }catch{
          case t:Throwable =>
            t.printStackTrace()
            throw t
        }
      }
      .mapAsyncUnordered(4)(replyAndCommit)
      .runWith(Sink.ignore)
  }
}
