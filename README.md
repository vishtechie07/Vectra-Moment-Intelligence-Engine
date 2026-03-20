# VectraMoment — Semantic Video Intelligence Engine

Production-grade system: upload videos, AI analysis (GPT-4o Vision + embeddings), and natural-language search to find timestamps.

## Stack

- **Backend**: Java 21, Spring Boot 3.4, Kafka, JavaCV, OpenAI, OpenSearch
- **Frontend**: Vue 3, Vite, Tailwind, Pinia, Video.js
- **Infra**: AWS CDK (Java), region `ap-southeast-2`

## Quick start

### One command (PowerShell)

From the project root:

```powershell
.\start-all.ps1
```

This starts Docker (Kafka, Zookeeper, OpenSearch), then the backend (port 8081), then the frontend (port 5173). Two PowerShell windows will stay open for backend and frontend. Open **http://localhost:5173** when ready.

### Manual

```powershell
# 1. Docker
docker-compose up -d

# 2. Backend (local profile → port 8081)
cd backend; $env:SPRING_PROFILES_ACTIVE="local"; mvn spring-boot:run

# 3. Frontend (proxy points to :8081)
cd frontend; npm run dev
```

Set the OpenAI API key in the UI (session-only, sent as `X-OpenAI-Key`). Upload a video, wait for processing, then use Time Machine search.

**Local Kafka:** With the `local` profile, the ingest topic is `video.ingested.local` (single partition). To verify messages:  
`docker exec -it <kafka-container> kafka-console-consumer --bootstrap-server localhost:9092 --topic video.ingested.local --from-beginning`  
Run `docker-compose up -d` from the **project root** so all three containers (Zookeeper, Kafka, OpenSearch) start.

### Full stack in Docker (recommended on Windows)

Run backend and frontend in Docker so Kafka and the API are on the same network. One command brings up everything:

```powershell
# From project root: build and start all services (Kafka, OpenSearch, backend, frontend)
docker compose up -d --build
```

Open **http://localhost:5173**. The frontend is served by Nginx; `/api` is proxied to the backend. Backend uses `kafka:29092` and `opensearch:9200`, so the ingest → frame-extract → vision/embed pipeline runs correctly.

### Backend

- `POST /api/videos/upload` — multipart file + optional `X-OpenAI-Key`
- `GET /api/videos/{videoId}/playback-url` — presigned S3 URL
- `GET /api/search?q=...&videoId=...` — **AI comparison** when `videoId` is set (see below); vector search when omitted (requires `X-OpenAI-Key`)

### Time Machine Search: AI comparison (why we switched from vector search)

Time Machine search uses **AI for the comparison step** when a video is selected (`videoId` is sent). Previously we used **vector (embedding) similarity** plus a score threshold:

- **Why we switched:**  
  - **Vector + threshold** caused **false negatives**: e.g. searching for "publish" often returned no results even when a frame clearly showed a Publish button, because the embedding of the short query "publish" was just below the similarity threshold when compared to the frame’s description embedding.  
  - **Vector + threshold** also caused **false positives**: e.g. searching for "animal" could return all frames when none described an animal, because with only a few documents every frame was among the “nearest” and some passed the threshold.  
  - Tuning a single threshold (e.g. 0.45 or 0.52) could not reliably give both good recall for relevant terms (publish, finger, warning) and good precision for irrelevant ones (animal).

- **Current behaviour:**  
  When you search with a video selected, the backend loads that video’s **frame descriptions** (from OpenSearch), sends them with your **search query** to the **LLM (GPT-4o)**, and asks which frame(s) match the query by meaning. The model returns the matching timestamps; we map those back to frames and return them. No embedding or score threshold is used for this path.

- **Examples:**  
  - Query **"publish"** → LLM sees a frame described as “A finger is about to press the Publish button…” and correctly includes that frame.  
  - Query **"animal"** → LLM sees only descriptions about buttons, screens, and hands → returns no matching timestamps.  
  - Query **"warning"** → LLM includes the frame whose description mentions a warning message.

- **Fallback:** When no `videoId` is provided (e.g. search across all videos), the backend still uses **vector search** (embed query, k-NN in OpenSearch) so that path remains unchanged.

- **Implementation:** The AI comparison prompt and logic are in `OpenAIVisionService.selectMatchingFrames()`. Frame descriptions are loaded via `OpenSearchVectorService.listFramesByVideoId()`. The LLM is instructed to return a JSON object `{"timestamps": [0, 2, ...]}` of matching frame timestamps (seconds); the backend maps these back to the stored frames and returns them as search hits.

### Zero-Trust

- API key only via `X-OpenAI-Key` header; never in env or config.
- Frontend stores key in Pinia (memory only); Axios interceptor attaches it.

### Infra (AWS)

```bash
cd infra && mvn compile && cdk bootstrap && cdk deploy
```

After deploy, stack outputs: `RawVideosBucket`, `FramesBucket`, `VideoMetadataTable`, `OpenSearchCollectionName`.

### Running backend against AWS

1. **Get resource names** from stack outputs (CloudFormation console or `aws cloudformation describe-stacks --stack-name VectraMomentStack --query 'Stacks[0].Outputs'`).

2. **Get OpenSearch Serverless endpoint** (collection must be active):
   ```bash
   aws opensearchserverless get-collection --id vectramoment --region ap-southeast-2 --query 'collectionDetail.collectionEndpoint' --output text
   ```
   Use this as `OPENSEARCH_ENDPOINT` (HTTPS URL). The backend will need IAM signing for OpenSearch Serverless; currently it uses a plain HTTP client suited for local OpenSearch.

3. **Set env and run backend** (no `local` profile; ensure AWS credentials are configured):
   ```powershell
   $env:S3_RAW_BUCKET="<RawVideosBucket output>"
   $env:S3_FRAMES_BUCKET="<FramesBucket output>"
   $env:OPENSEARCH_ENDPOINT="https://<endpoint from step 2>"
   cd backend; mvn spring-boot:run
   ```
   Kafka still defaults to `localhost:9092`; for full AWS you would use Amazon MSK and set `KAFKA_BOOTSTRAP_SERVERS`.

4. **Optional**: Use an `application-aws.yml` profile that sets `vectramoment.storage.mode: s3` and the above properties from env.

## Tests

```bash
cd backend && mvn test
```

- `VideoUploadIT`: controller test (empty file rejected).
- `KafkaOpenSearchPipelineIT`: Kafka produce with EmbeddedKafka.
