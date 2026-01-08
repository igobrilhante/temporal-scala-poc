package com.example.temporal.core

import io.temporal.client.{WorkflowClient, WorkflowClientOptions}
import io.temporal.serviceclient.{WorkflowServiceStubs, WorkflowServiceStubsOptions}
import zio._

/**
 * ZIO layer for Temporal WorkflowClient
 * Provides a managed connection to Temporal server
 */
object TemporalClient {

  case class Config(
    host: String = "localhost",
    port: Int = 7233,
    namespace: String = "default"
  ) {
    def target: String = s"$host:$port"
  }

  object Config {
    val default: Config = Config()

    val layer: ULayer[Config] = ZLayer.succeed(default)

    def live(host: String, port: Int, namespace: String): ULayer[Config] =
      ZLayer.succeed(Config(host, port, namespace))
  }

  trait Service {
    def client: WorkflowClient
    def serviceStubs: WorkflowServiceStubs
  }

  case class Live(
    client: WorkflowClient,
    serviceStubs: WorkflowServiceStubs
  ) extends Service

  val layer: ZLayer[Config, Throwable, Service] = ZLayer.scoped {
    for {
      config <- ZIO.service[Config]
      serviceStubs <- ZIO.acquireRelease(
        ZIO.attempt {
          val options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(config.target)
            .build()
          WorkflowServiceStubs.newServiceStubs(options)
        }
      )(stubs => ZIO.succeed(stubs.shutdown()))

      workflowClient <- ZIO.attempt {
        val options = WorkflowClientOptions.newBuilder()
          .setNamespace(config.namespace)
          .build()
        WorkflowClient.newInstance(serviceStubs, options)
      }
    } yield Live(workflowClient, serviceStubs)
  }

  def client: ZIO[Service, Nothing, WorkflowClient] =
    ZIO.serviceWith[Service](_.client)

  def serviceStubs: ZIO[Service, Nothing, WorkflowServiceStubs] =
    ZIO.serviceWith[Service](_.serviceStubs)
}
