package com.ovoenergy.fs2.kafka

import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import cats.effect.IO
import fs2._
import org.apache.kafka.clients.consumer.{
  ConsumerConfig,
  ConsumerRecord,
  KafkaConsumer
}
import org.apache.kafka.clients.producer.{
  ProducerConfig,
  ProducerRecord,
  RecordMetadata
}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._

class KafkaSpec extends BaseUnitSpec with EmbeddedKafka {

  implicit val stringSerializer: Serializer[String] = new StringSerializer
  implicit val stringDeserializer: Deserializer[String] = new StringDeserializer

  "consume" should {
    "return a Stream of kafka messages" in withRunningKafkaOnFoundPort(
      EmbeddedKafkaConfig()) { config =>
      val topic = "test-1"
      val groupId = "test-1"

      val settings = ConsumerSettings(
        250.milliseconds,
        4,
        Map(
          ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
          ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}",
          ConsumerConfig.GROUP_ID_CONFIG -> groupId,
          ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest"
        )
      )

      createCustomTopic(topic, partitions = 3)

      val produced = (0 until 1000).map(i => s"key-$i" -> s"value->$i")

      publishToKafka(topic, produced)

      val consumed = consume[IO](TopicSubscription(Set(topic)),
                                 new StringDeserializer,
                                 new StringDeserializer,
                                 settings)
        .take(1000)
        .map(r => r.key() -> r.value())
        .compile
        .toVector
        .unsafeRunSync()

      consumed should contain theSameElementsAs produced

    }
  }

  "consumeProcessAndCommit" should {
    "commit all the messages" in withRunningKafkaOnFoundPort(
      EmbeddedKafkaConfig(customConsumerProperties =
        Map(ConsumerConfig.EXCLUDE_INTERNAL_TOPICS_CONFIG -> "false"))) {
      config =>
        val topic = "test-1"
        val groupId = "test-1"

        val settings = ConsumerSettings(
          250.milliseconds,
          4,
          Map(
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}",
            ConsumerConfig.GROUP_ID_CONFIG -> groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest"
          )
        )

        createCustomTopic(topic, partitions = 3)

        val produced = (0 until 100).map(i => s"key-$i" -> s"value->$i")
        publishToKafka(topic, produced)

        val consumeStream =
          consumeProcessAndCommit[IO](
            TopicSubscription(Set(topic)),
            new StringDeserializer,
            new StringDeserializer,
            settings
          ) { record =>
            IO(record.partition() -> record.offset())
          }.take(100)

        val committedOffsets =
          consumeStream.compile.toVector.unsafeRunSync().groupBy(_._1).map {
            case (p, offsets) =>
              p -> offsets.map(_._2).max
          }

        withKafkaConsumer[String, String, Unit](settings) { consumer =>
          committedOffsets.foreach {
            case (partition, offset) =>
              consumer
                .committed(new TopicPartition(topic, partition))
                .offset() shouldBe offset + 1
          }
        }
    }
  }

  "consumeProcessBatchAndCommit" should {
    "commit offsets returned by processing function" in withRunningKafkaOnFoundPort(
      EmbeddedKafkaConfig(customConsumerProperties =
        Map(ConsumerConfig.EXCLUDE_INTERNAL_TOPICS_CONFIG -> "false"))) {
      config =>
        val topic = "test-2"
        val groupId = "test-2"

        val settings = ConsumerSettings(
          250.milliseconds,
          4,
          Map(
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}",
            ConsumerConfig.GROUP_ID_CONFIG -> groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest"
          )
        )

        createCustomTopic(topic, partitions = 3)

        val produced = (0 until 100).map(i => s"key-$i" -> s"value->$i")
        publishToKafka(topic, produced)

        val consumeStream =
          consumeProcessBatchAndCommit[IO](
            TopicSubscription(Set(topic)),
            new StringDeserializer,
            new StringDeserializer,
            settings
          ) { records =>
            IO.pure(records.map(record =>
              (record.partition, record.offset) ->
                Offset(record.offset)))
          }.take(100)

        val committedOffsets =
          consumeStream.compile.toVector.unsafeRunSync().groupBy(_._1).map {
            case (p, offsets) =>
              p -> offsets.map(_._2).max
          }

        withKafkaConsumer[String, String, Unit](settings) { consumer =>
          committedOffsets.foreach {
            case (partition, offset) =>
              consumer
                .committed(new TopicPartition(topic, partition))
                .offset() shouldBe offset + 1
          }
        }
    }
  }

  "produceRecord" should {
    "be composed with consumeProcessAndCommit" in withRunningKafkaOnFoundPort(
      EmbeddedKafkaConfig()) { config =>
      {

        val sourceTopic = "source"
        val destinationTopic = "destination"

        val groupId = "test-1"

        val consumerSettings = ConsumerSettings(
          250.milliseconds,
          4,
          Map(
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}",
            ConsumerConfig.GROUP_ID_CONFIG -> groupId,
            ConsumerConfig.GROUP_ID_CONFIG -> groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest"
          )
        )

        val producerSettings = ProducerSettings(
          Map(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}",
            ProducerConfig.ACKS_CONFIG -> "all",
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION -> "1"
          )
        )

        createCustomTopic(sourceTopic, partitions = 6)
        createCustomTopic(destinationTopic, partitions = 12)

        val produced = (0 until 1000).map(i => s"key-$i" -> s"value->$i")
        publishToKafka(sourceTopic, produced)

        val stream = producerStream[IO](producerSettings,
                                        stringSerializer,
                                        stringSerializer).flatMap { producer =>
          def process(
              record: ConsumerRecord[String, String]): IO[RecordMetadata] = {
            produceRecord[IO](
              producer,
              new ProducerRecord[String, String](destinationTopic,
                                                 record.key(),
                                                 record.value()))
          }

          consumeProcessAndCommit[IO](
            TopicSubscription(Set(sourceTopic)),
            new StringDeserializer,
            new StringDeserializer,
            consumerSettings
          )(process)
        }

        stream.take(1000).compile.toVector.unsafeRunSync()

        val messages =
          consumeNumberKeyedMessagesFrom(destinationTopic, 1000, false)

        messages should contain theSameElementsAs produced
      }

    }
  }

  "produceRecordBatch" should {
    "be composed with consumeProcessBatchAndCommit" in withRunningKafkaOnFoundPort(
      EmbeddedKafkaConfig()) { config =>
      {

        val sourceTopic = "source"
        val destinationTopic = "destination"

        val groupId = "test-1"

        val consumerSettings = ConsumerSettings(
          250.milliseconds,
          4,
          Map(
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}",
            ConsumerConfig.GROUP_ID_CONFIG -> groupId,
            ConsumerConfig.GROUP_ID_CONFIG -> groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest"
          )
        )

        val producerSettings = ProducerSettings(
          Map(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}",
            ProducerConfig.ACKS_CONFIG -> "all",
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION -> "1"
          )
        )

        createCustomTopic(sourceTopic, partitions = 6)
        createCustomTopic(destinationTopic, partitions = 12)

        val produced = (0 until 1000).map(i => s"key-$i" -> s"value->$i")
        publishToKafka(sourceTopic, produced)

        val stream = producerStream[IO](producerSettings,
                                        stringSerializer,
                                        stringSerializer).flatMap { producer =>
          def processBatch(recordBatch: Chunk[ConsumerRecord[String, String]])
            : IO[Chunk[(RecordMetadata, Offset)]] = {
            produceRecordBatch[IO](producer, recordBatch.map { record =>
              new ProducerRecord[String, String](
                destinationTopic,
                record.key(),
                record.value()
              ) -> Offset(record.offset())
            })
          }

          consumeProcessBatchAndCommit[IO](
            TopicSubscription(Set(sourceTopic)),
            new StringDeserializer,
            new StringDeserializer,
            consumerSettings
          )(processBatch)
        }

        stream.take(1000).compile.toVector.unsafeRunSync()

        val messages =
          consumeNumberKeyedMessagesFrom(destinationTopic, 1000, false)

        messages should contain theSameElementsAs produced
      }

    }
  }

  "produceRecordWithBatching" should {
    "batch" in withRunningKafkaOnFoundPort(EmbeddedKafkaConfig()) { config =>
      {

        val destinationTopic = "destination"

        val producerSettings = ProducerSettings(
          Map(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}",
            ProducerConfig.ACKS_CONFIG -> "all",
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION -> "1"
          )
        )

        val start = System.currentTimeMillis()
        producerStream[IO](producerSettings, stringSerializer, stringSerializer)
          .flatMap { producer =>
            Stream
              .iterate(0)(_ + 1)
              .segmentN(100)
              .flatMap(Stream.segment)
              .evalMap[IO, IO[RecordMetadata]] { i =>
                val record =
                  new ProducerRecord[String, String](destinationTopic,
                                                     s"key-$i",
                                                     s"value-$i")
                produceRecordWithBatching[IO].apply(producer, record)
              }
              // The buffer allow to put 1000 elements in the batch before waiting for the callback
              .buffer(1000)
              .evalMap(identity)
              .take(100000)
          }
          .compile
          .toVector
          .unsafeRunSync()

        println(System.currentTimeMillis() - start)

        val messages =
          consumeNumberKeyedMessagesFrom(destinationTopic, 1000, false)

        messages should not be empty
      }
    }
  }

  def withKafkaConsumer[K, V, A](settings: ConsumerSettings)(
      f: KafkaConsumer[K, V] => A)(implicit keyDeserializer: Deserializer[K],
                                   valueDeserializer: Deserializer[V]): A = {
    val consumer = new KafkaConsumer[K, V](settings.nativeSettings.asJava,
                                           keyDeserializer,
                                           valueDeserializer)
    try {
      f(consumer)
    } finally {
      consumer.close()
    }

  }

}
