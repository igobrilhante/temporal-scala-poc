package com.example.temporal.core

import io.temporal.client.WorkflowClient
import io.temporal.worker.{Worker, WorkerFactory, WorkerFactoryOptions, WorkerOptions}
import zio._

/**
 * ZIO layer for Temporal Worker management
 * Handles worker lifecycle and registration
 */
object TemporalWorker {

  case class Config(
    taskQueue: String,
    maxConcurrentWorkflowTaskExecutionSize: Int = 200,
    maxConcurrentActivityExecutionSize: Int = 200
  )

  object Config {
    def live(taskQueue: String): ULayer[Config] =
      ZLayer.succeed(Config(taskQueue))
  }

  trait Service {
    def factory: WorkerFactory
    def worker: Worker
    def start: UIO[Unit]
    def shutdown: UIO[Unit]
  }

  case class Live(
    factory: WorkerFactory,
    worker: Worker,
    started: Ref[Boolean]
  ) extends Service {

    override def start: UIO[Unit] =
      started.get.flatMap { isStarted =>
        if (!isStarted) {
          ZIO.succeed(factory.start()) *> started.set(true)
        } else {
          ZIO.unit
        }
      }

    override def shutdown: UIO[Unit] =
      started.get.flatMap { isStarted =>
        if (isStarted) {
          ZIO.succeed(factory.shutdown()) *> started.set(false)
        } else {
          ZIO.unit
        }
      }
  }

  def layer: ZLayer[TemporalClient.Service & Config, Throwable, Service] =
    ZLayer.scoped {
      for {
        temporalClient <- ZIO.service[TemporalClient.Service]
        config <- ZIO.service[Config]
        started <- Ref.make(false)

        factory <- ZIO.acquireRelease(
          ZIO.attempt {
            val factoryOptions = WorkerFactoryOptions.newBuilder().build()
            WorkerFactory.newInstance(temporalClient.client, factoryOptions)
          }
        )(f => ZIO.succeed(f.shutdown()))

        worker <- ZIO.attempt {
          val workerOptions = WorkerOptions.newBuilder()
            .setMaxConcurrentWorkflowTaskExecutionSize(config.maxConcurrentWorkflowTaskExecutionSize)
            .setMaxConcurrentActivityExecutionSize(config.maxConcurrentActivityExecutionSize)
            .build()
          factory.newWorker(config.taskQueue, workerOptions)
        }
      } yield Live(factory, worker, started)
    }

  def worker: ZIO[Service, Nothing, Worker] =
    ZIO.serviceWith[Service](_.worker)

  def start: ZIO[Service, Nothing, Unit] =
    ZIO.serviceWithZIO[Service](_.start)

  def shutdown: ZIO[Service, Nothing, Unit] =
    ZIO.serviceWithZIO[Service](_.shutdown)

  def registerWorkflowImplementationTypes(classes: Class[_]*): ZIO[Service, Throwable, Unit] =
    ZIO.serviceWithZIO[Service] { service =>
      ZIO.attempt(service.worker.registerWorkflowImplementationTypes(classes: _*))
    }

  def registerActivitiesImplementations(activities: AnyRef*): ZIO[Service, Throwable, Unit] =
    ZIO.serviceWithZIO[Service] { service =>
      ZIO.attempt(service.worker.registerActivitiesImplementations(activities: _*))
    }
}
