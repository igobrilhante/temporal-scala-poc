# Temporal Scala ZIO POC

Proof of Concept demonstrating the integration of [Temporal](https://temporal.io/) workflow orchestration with [Scala](https://scala-lang.org/) and [ZIO](https://zio.dev/).

## Overview

This POC showcases:

- **ZIO Integration Layer**: Custom ZIO wrappers for Temporal Java SDK
- **Order Processing Workflow**: A complete saga pattern implementation
- **News Verification Workflow**: AI-powered fake news detection system
- **Activities with ZIO Effects**: Running ZIO effects within Temporal activities
- **Signals & Queries**: Interacting with running workflows
- **Compensation Logic**: Rollback support for failed operations
- **AI Integration**: OpenAI-powered news analysis and classification

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

---

## News Verification Workflow

A sophisticated AI-powered workflow for detecting fake news by cross-referencing multiple sources.

### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    NewsVerificationWorkflow                          │
└─────────────────────────────────────────────────────────────────────┘
                               │
           ┌───────────────────┼───────────────────┐
           ▼                   ▼                   ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ NewsScraperAct. │  │  AIAgentAct.    │  │ NewsClassifier  │
│                 │  │                 │  │    Act.         │
│ - Fetch RSS     │  │ - Find sources  │  │ - Analyze       │
│ - Scrape HTML   │  │ - Extract claims│  │ - Classify      │
│ - Search news   │  │ - Compare texts │  │ - Score         │
└─────────────────┘  └─────────────────┘  └─────────────────┘
           │                   │                   │
           └───────────────────┼───────────────────┘
                               ▼
                    ┌─────────────────┐
                    │   OpenAI API    │
                    │   (optional)    │
                    └─────────────────┘
```

### Quick Start - News Verification

#### 1. Set OpenAI API Key (Optional but Recommended)

```bash
export OPENAI_API_KEY="your-api-key-here"
```

Without the API key, the system uses heuristic analysis only.

#### 2. Start the News Verification Worker

```bash
sbt "runMain com.example.temporal.news.worker.NewsVerificationWorkerApp"
```

#### 3. Run News Verification

```bash
# Verify headlines from BBC News RSS feed
sbt "runMain com.example.temporal.news.NewsVerificationClientApp"

# Verify from a specific source with custom headline count
sbt "runMain com.example.temporal.news.NewsVerificationClientApp https://feeds.bbci.co.uk/news/rss.xml 5"

# Async mode with progress monitoring
sbt "runMain com.example.temporal.news.NewsVerificationAsyncClientApp"
```

### News Verification Flow

```
1. FETCH HEADLINES
   │
   └──► Scrape RSS/HTML from news source
        Extract: title, URL, summary, date
   │
2. FOR EACH HEADLINE:
   │
   ├──► Extract Article Content
   │    Parse full article text
   │
   ├──► AI Agent: Find Alternative Sources
   │    • Generate search queries
   │    • Search for related articles
   │    • Compare and analyze sources
   │
   ├──► AI Agent: Extract Key Claims
   │    Identify factual statements
   │
   └──► Classify News
        • Source credibility check
        • Linguistic pattern analysis
        • Cross-reference scoring
        • AI classification
   │
3. AGGREGATE RESULTS
   │
   └──► Generate statistics and report
```

### Classification Factors

The system analyzes multiple factors:

| Factor | Weight | Description |
|--------|--------|-------------|
| Source Credibility | 25% | Known reliable vs unreliable sources |
| Linguistic Analysis | 20% | Sensationalism, clickbait, emotional language |
| Cross-Reference | 30% | Corroboration from other sources |
| AI Analysis | 25% | GPT-4 based content analysis |

### Verdicts

- **LIKELY_REAL**: Multiple credible sources confirm, high confidence
- **LIKELY_FAKE**: Major contradictions or known false patterns
- **UNCERTAIN**: Mixed signals, needs more investigation

### Supported News Sources

- RSS feeds (e.g., `https://feeds.bbci.co.uk/news/rss.xml`)
- News website URLs (HTML scraping)
- Custom search queries

### Example Output

```
======================================================================
  VERIFICATION RESULTS
======================================================================

Request ID: VER-A1B2C3D4
Source: https://feeds.bbci.co.uk/news/rss.xml
Headlines Analyzed: 5
Processing Time: 45230ms

----------------------------------------
OVERALL STATISTICS
----------------------------------------
  Likely Real:    4
  Likely Fake:    0
  Uncertain:      1
  Avg Confidence: 78%

----------------------------------------
DETAILED CLASSIFICATIONS
----------------------------------------

[1] Breaking: Major Policy Announcement by Government...
    Source: bbc.co.uk
    Verdict: ✓ LIKELY_REAL
    Confidence: 85%
    Factors Analyzed:
      - Source credibility: mainstream (90%)
      - Linguistic analysis: 92% credible
      - Cross-reference: 3 supporting, 0 contradicting
      - AI analysis: LIKELY_REAL (88% confident)
```

### Project Structure (News Verification)

```
src/main/scala/com/example/temporal/news/
├── model/
│   └── Models.scala              # Domain models
├── activity/
│   ├── NewsScraperActivities.scala
│   ├── NewsScraperActivitiesImpl.scala
│   ├── AIAgentActivities.scala
│   ├── AIAgentActivitiesImpl.scala
│   ├── NewsClassifierActivities.scala
│   └── NewsClassifierActivitiesImpl.scala
├── workflow/
│   ├── NewsVerificationWorkflow.scala
│   └── NewsVerificationWorkflowImpl.scala
├── worker/
│   └── NewsVerificationWorkerApp.scala
├── NewsVerificationClientApp.scala
└── NewsVerificationAsyncClientApp.scala
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
