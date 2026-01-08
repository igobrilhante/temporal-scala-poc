# Temporal Scala ZIO POC

Proof of Concept demonstrating the integration of [Temporal](https://temporal.io/) workflow orchestration with [Scala](https://scala-lang.org/) and [ZIO](https://zio.dev/).

## Overview

This POC showcases:

- **ZIO Integration Layer**: Custom ZIO wrappers for Temporal Java SDK
- **Order Processing Workflow**: A complete saga pattern implementation
- **Activities with ZIO Effects**: Running ZIO effects within Temporal activities
- **Signals & Queries**: Interacting with running workflows
- **Compensation Logic**: Rollback support for failed operations

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client Applications                       │
│  (OrderClientApp, OrderAsyncClientApp, CancelOrderApp)          │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                     ZIO Integration Layer                        │
│  (TemporalClient, TemporalWorker, ZTemporalWorkflow,            │
│   ZTemporalActivity)                                             │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Temporal Server                             │
│  (Workflow orchestration, state management, retries)            │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Worker                                   │
│  (OrderWorkerApp - executes workflows and activities)           │
└─────────────────────────────────────────────────────────────────┘
```

## Prerequisites

- JDK 11 or higher
- sbt 1.9+
- Docker & Docker Compose

## Quick Start

### 1. Start Temporal Server

```bash
docker-compose up -d
```

Wait for the services to be healthy:

```bash
docker-compose ps
```

Access the Temporal UI at: http://localhost:8080

### 2. Start the Worker

In a terminal:

```bash
sbt "runMain com.example.temporal.worker.OrderWorkerApp"
```

### 3. Execute a Workflow

In another terminal:

```bash
# Synchronous execution
sbt "runMain com.example.temporal.OrderClientApp"

# Async execution with status polling
sbt "runMain com.example.temporal.OrderAsyncClientApp"
```

### 4. Cancel an Order (Optional)

```bash
sbt "runMain com.example.temporal.CancelOrderApp ORD-XXXXXXXX"
```

## Project Structure

```
src/main/scala/com/example/temporal/
├── core/                          # ZIO-Temporal integration layer
│   ├── TemporalClient.scala       # Managed Temporal client
│   ├── TemporalWorker.scala       # Worker lifecycle management
│   ├── ZTemporalWorkflow.scala    # Workflow operations
│   └── ZTemporalActivity.scala    # Activity utilities
├── activity/                      # Activity definitions
│   ├── OrderActivities.scala      # Activity interface
│   └── OrderActivitiesImpl.scala  # ZIO-based implementation
├── workflow/                      # Workflow definitions
│   ├── OrderWorkflow.scala        # Workflow interface
│   └── OrderWorkflowImpl.scala    # Implementation with saga
├── worker/
│   └── OrderWorkerApp.scala       # Worker application
├── OrderClientApp.scala           # Sync client example
├── OrderAsyncClientApp.scala      # Async client with queries
└── CancelOrderApp.scala           # Signal example
```

## Key Features

### ZIO Layer Integration

```scala
val layers: ZLayer[Any, Throwable, TemporalWorker.Service] =
  TemporalClient.Config.layer >>>
    TemporalClient.layer >>>
    TemporalWorker.layer
```

### Running ZIO Effects in Activities

```scala
class OrderActivitiesImpl extends OrderActivities with ZActivityImplementation {
  override def validateOrder(...): OrderValidationResult = run {
    for {
      _ <- ZIO.logInfo("Validating order...")
      result <- validateLogic(...)
    } yield result
  }
}
```

### Workflow with Signals and Queries

```scala
@WorkflowInterface
trait OrderWorkflow {
  @WorkflowMethod
  def processOrder(request: OrderRequest): OrderResult

  @SignalMethod
  def cancelOrder(reason: String): Unit

  @QueryMethod
  def getOrderStatus: OrderStatus
}
```

## Order Processing Flow

```
1. Validate Order    ──────┐
                           │
2. Reserve Inventory ◄─────┘
   │
   ├── (Failure) ────► Rollback
   │
3. Process Payment
   │
   ├── (Failure) ────► Refund + Release Inventory
   │
4. Ship Order
   │
   ├── (Failure) ────► Refund + Release Inventory
   │
5. Send Notification
   │
   ▼
   COMPLETED
```

## Configuration

### Temporal Client

```scala
TemporalClient.Config(
  host = "localhost",
  port = 7233,
  namespace = "default"
)
```

### Activity Retry Options

```scala
ZTemporalActivity.ActivityConfig(
  startToCloseTimeout = 30.seconds,
  retryOptions = Some(RetryConfig(
    initialInterval = 1.second,
    backoffCoefficient = 2.0,
    maximumInterval = 30.seconds,
    maximumAttempts = 3
  ))
)
```

## Development

### Compile

```bash
sbt compile
```

### Format Code

```bash
sbt scalafmtAll
```

### Run Tests

```bash
sbt test
```

## Troubleshooting

### Connection Refused

Ensure Temporal server is running:

```bash
docker-compose ps
docker-compose logs temporal
```

### Workflow Not Starting

Check that the worker is running and registered with the correct task queue.

### Activity Timeout

Increase `startToCloseTimeout` in activity configuration.

## Resources

- [Temporal Documentation](https://docs.temporal.io/)
- [Temporal Java SDK](https://github.com/temporalio/sdk-java)
- [ZIO Documentation](https://zio.dev/)
- [Temporal UI](http://localhost:8080) (when running locally)

## License

MIT
