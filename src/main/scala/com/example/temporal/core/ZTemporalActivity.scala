package com.example.temporal.core

import io.temporal.activity.{Activity, ActivityExecutionContext}
import io.temporal.workflow.Workflow
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import zio._
import zio.Unsafe

import java.time.Duration as JDuration
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

/**
 * ZIO-friendly activity operations
 * Provides utilities for running ZIO effects within Temporal activities
 */
object ZTemporalActivity {

  case class ActivityConfig(
    startToCloseTimeout: Duration = Duration("10 seconds"),
    scheduleToCloseTimeout: Option[Duration] = None,
    scheduleToStartTimeout: Option[Duration] = None,
    heartbeatTimeout: Option[Duration] = None,
    retryOptions: Option[RetryConfig] = None
  )

  case class RetryConfig(
    initialInterval: Duration = Duration("1 second"),
    backoffCoefficient: Double = 2.0,
    maximumInterval: Duration = Duration("1 minute"),
    maximumAttempts: Int = 0,
    doNotRetry: Seq[Class[_ <: Throwable]] = Seq.empty
  )

  def newActivityStub[T: ClassTag](
    config: ActivityConfig = ActivityConfig()
  ): T = {
    val builder = ActivityOptions.newBuilder()
      .setStartToCloseTimeout(JDuration.ofMillis(config.startToCloseTimeout.toMillis))

    config.scheduleToCloseTimeout.foreach { d =>
      builder.setScheduleToCloseTimeout(JDuration.ofMillis(d.toMillis))
    }

    config.scheduleToStartTimeout.foreach { d =>
      builder.setScheduleToStartTimeout(JDuration.ofMillis(d.toMillis))
    }

    config.heartbeatTimeout.foreach { d =>
      builder.setHeartbeatTimeout(JDuration.ofMillis(d.toMillis))
    }

    config.retryOptions.foreach { retry =>
      val retryBuilder = RetryOptions.newBuilder()
        .setInitialInterval(JDuration.ofMillis(retry.initialInterval.toMillis))
        .setBackoffCoefficient(retry.backoffCoefficient)
        .setMaximumInterval(JDuration.ofMillis(retry.maximumInterval.toMillis))
        .setMaximumAttempts(retry.maximumAttempts)

      if (retry.doNotRetry.nonEmpty) {
        retryBuilder.setDoNotRetry(retry.doNotRetry: _*)
      }

      builder.setRetryOptions(retryBuilder.build())
    }

    val clazz = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]
    Workflow.newActivityStub(clazz, builder.build())
  }

  def runZIO[R, E <: Throwable, A](
    runtime: Runtime[R],
    effect: ZIO[R, E, A]
  ): A = {
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(effect).getOrThrowFiberFailure()
    }
  }

  def heartbeat[T](details: T): Task[Unit] = ZIO.attempt {
    Activity.getExecutionContext.heartbeat(details)
  }

  def getInfo: Task[ActivityExecutionContext] = ZIO.attempt {
    Activity.getExecutionContext
  }

  trait ZActivityImplementation {
    protected val runtime: Runtime[Any] = Runtime.default

    protected def run[A](effect: Task[A]): A = {
      runZIO(runtime, effect)
    }

    protected def run[R, A](runtime: Runtime[R], effect: ZIO[R, Throwable, A]): A = {
      runZIO(runtime, effect)
    }
  }
}
