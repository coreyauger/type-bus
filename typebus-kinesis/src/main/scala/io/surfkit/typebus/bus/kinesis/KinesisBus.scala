package io.surfkit.typebus.bus.kinesis

import java.nio.ByteBuffer
import java.util.UUID

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.alpakka.kinesis.{KinesisFlowSettings, ShardSettings}
import akka.stream.OverflowStrategy.backpressure
import akka.stream.alpakka.kinesis.scaladsl.{KinesisFlow, KinesisSink, KinesisSource}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.util.{ByteString, Timeout}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.{AmazonKinesisAsync, AmazonKinesisAsyncClientBuilder}
import com.amazonaws.services.kinesis.model.{PutRecordsRequestEntry, PutRecordsResultEntry, Record, ShardIteratorType}
import com.typesafe.config.ConfigFactory
import io.surfkit.typebus.AvroByteStreams
import io.surfkit.typebus.actors.{ProducerActor, TypeBusActor}
import io.surfkit.typebus.bus.Bus
import io.surfkit.typebus.event._
import io.surfkit.typebus.module.Service

import scala.concurrent.duration._
import scala.concurrent.Future

trait KinesisBus[UserBaseType] extends Bus[UserBaseType] with AvroByteStreams with Actor {
  service: Service[UserBaseType] =>

  import collection.JavaConverters._
  val cfg = ConfigFactory.load
  // TODO: get kinesis config
  //val kafka = cfg.getString("bus.kafka")
  val kinesisStream = cfg.getString("bus.kinesis.stream")
  val shards = cfg.getStringList("bus.kinesis.shards").asScala
  val kinesisPartitionKey = cfg( Math.floor(Math.random() * shards.size).toInt )    // pick a random shard to publish?

  // Create a Kinesis endpoint pointed at our local kinesalite
  val endpoint = new EndpointConfiguration("http://localhost:4567", "us-west-2")
  implicit val amazonKinesisAsync: AmazonKinesisAsync = AmazonKinesisAsyncClientBuilder.standard().withEndpointConfiguration(endpoint).build()

  val tyebusMap = scala.collection.mutable.Map.empty[String, ActorRef]

  val publishedEventReader = new AvroByteStreamReader[PublishedEvent]
  val publishedEventWriter = new AvroByteStreamWriter[PublishedEvent]

  //#flow-settings
  val flowSettings = KinesisFlowSettings(
    parallelism = 1,
    maxBatchSize = 500,
    maxRecordsPerSecond = 1000,
    maxBytesPerSecond = 1000000,
    maxRetries = 5,
    backoffStrategy = KinesisFlowSettings.Exponential,
    retryInitialTimeout = 100.millis
  )

  val publishActor = Source.actorRef[PublishedEvent](Int.MaxValue, backpressure)
    .map(event => publishedEventWriter.write(event))
    .map(data => new PutRecordsRequestEntry().withData( ByteBuffer.wrap(data) ).withPartitionKey(kinesisPartitionKey))
    .to(KinesisSink(kinesisStream, flowSettings))
    .run()

  def publish(event: PublishedEvent): Unit =
    publishActor ! event

  def busActor(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new ProducerActor(this)))

  def startTypeBus(serviceName: String)(implicit system: ActorSystem): Unit = {
    import system.dispatcher
    val decider: Supervision.Decider = {
      case _ => Supervision.Resume  // Never give up !
    }
    implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))

    implicit val amazonKinesisAsync: com.amazonaws.services.kinesis.AmazonKinesisAsync =
      AmazonKinesisAsyncClientBuilder.defaultClient()

    system.registerOnTermination(amazonKinesisAsync.shutdown())


    val serviceDescription = makeServiceDescriptor(serviceName)
    implicit val serviceDescriptorWriter = new AvroByteStreamWriter[ServiceDescriptor]
    implicit val getServiceDescriptorReader = new AvroByteStreamReader[GetServiceDescriptor]

    /***
      * getServiceDescriptor - default hander to broadcast ServiceDescriptions
      * @param x - GetServiceDescriptor is another service requesting a ServiceDescriptions
      * @param meta - EventMeta routing info
      * @return - Future[ServiceDescriptor]
      */
    def getServiceDescriptor(x: GetServiceDescriptor, meta: EventMeta): Future[ServiceDescriptor] = {
      println("**************** getServiceDescriptor")
      println(serviceDescription)
      Future.successful(serviceDescription)
    }
    registerServiceStream(getServiceDescriptor _)

    tyebusMap ++= (listOfFunctions.map(_._1) ::: listOfServiceFunctions.map(_._1)).map(x => x -> context.actorOf(TypeBusActor.props(x, context.self)) ).toMap

    // source-list
    val mergeSettings = shards.map { shardId =>
      ShardSettings(kinesisStream,
        shardId,
        ShardIteratorType.AT_TIMESTAMP,
        atTimestamp = Some(new java.util.Date()),
        refreshInterval = 1.second,
        limit = 500)
    }.toList

    val mergedSource: Source[Record, NotUsed] = KinesisSource.basicMerge(mergeSettings, amazonKinesisAsync)

    mergedSource.map { msg =>
      val event = publishedEventReader.read(msg.getData.array())
      if(!tyebusMap.contains(event.meta.eventType) )
        tyebusMap += event.meta.eventType -> context.actorOf(TypeBusActor.props(event.meta.eventType, context.self))
      tyebusMap(event.meta.eventType) ! event
      event
    }.runWith(Sink.ignore)


    def receive: Receive = {
      case event: PublishedEvent =>
        system.log.debug(s"TypeBus: got msg for topic: ${event.meta.eventType}")
        try {
          val reader = listOfServiceImplicitsReaders.get(event.meta.eventType).getOrElse(listOfImplicitsReaders(event.meta.eventType))
          val publish = publishedEventReader.read(event.payload)
          system.log.debug(s"TypeBus: got publish: ${publish}")
          system.log.debug(s"TypeBus: reader: ${reader}")
          system.log.debug(s"publish.payload.size: ${publish.payload.size}")
          val payload = reader.read(publish.payload)
          system.log.debug(s"TypeBus: got payload: ${payload}")
          (if(handleEventWithMetaUnit.isDefinedAt( (payload, publish.meta) ) )
            handleEventWithMetaUnit( (payload, publish.meta) )
          else if(handleEventWithMeta.isDefinedAt( (payload, publish.meta) ) )
            handleEventWithMeta( (payload, publish.meta)  )
          else if(handleServiceEventWithMeta.isDefinedAt( (payload, publish.meta) ) )
            handleServiceEventWithMeta( (payload, publish.meta) )
          else
            handleEvent(payload)) map{ ret =>
            implicit val timeout = Timeout(4 seconds)
            val retType = ret.getClass.getCanonicalName
            val publishedEvent = PublishedEvent(
              meta = event.meta.copy(
                eventId = UUID.randomUUID.toString,
                eventType = ret.getClass.getCanonicalName,
                responseTo = Some(event.meta.eventId)
              ),
              payload = listOfServiceImplicitsWriters.get(retType).map{ writer =>
                writer.write(ret.asInstanceOf[TypeBus])
              }.getOrElse(listOfImplicitsWriters(retType).write(ret.asInstanceOf[UserBaseType]))
            )
            event.meta.directReply.foreach( system.actorSelection(_).resolveOne().map( actor => actor ! publishedEvent ) )
            publish(publishedEvent)
          }
        }catch{
          case t:Throwable =>
            t.printStackTrace()
            throw t
        }
    }
  }
}