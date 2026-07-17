# Phase 2 Study Guide — AWS Backend (Simple, Detailed, Learn-Before-You-Build)

> Same format as [PHASE-1-STUDY.md](PHASE-1-STUDY.md):
> **In simple words** — understand it.
> **In our project** — what we will build with it in Phase 2.
> **Say this in the interview** — a confident spoken answer.
>
> Suggested study order: 1 → 2 → 3 (the database side), then 4 → 5 → 6 (the AWS side),
> then 7 → 8 → 9 (the glue). The database part matters most.

---

## 1. PostgreSQL Refresher (the parts we use)

**In simple words:** Postgres is a relational database — tables, rows, SQL. Three features matter for us:
- **`JSONB`** — a column type that stores a whole JSON document in *binary, indexed* form. You get schema flexibility (our rule JSON is deep and evolving) inside a real database. You can query inside it: `rule->>'type' = 'no_parking'`.
- **`TIMESTAMPTZ`** — timestamp WITH timezone. Always this, never plain `TIMESTAMP` (which has the same "wall clock without location" trap as `LocalDateTime` from Phase 1 — same lesson, database edition).
- **Indexes** — a B-tree index makes lookups fast; without one, every query scans the whole table. Special data (like geography) needs special index types (see GiST below).

**In our project:** the `rules` table from Codex's [schema.sql](backend/sql/schema.sql): a UUID id, a `GEOGRAPHY` location, the full rule as `JSONB`, plus `source`, `parser_version`, `created_at TIMESTAMPTZ` — the reproducibility tags from Phase 1, now as columns.

**Say this in the interview:**
"I used a hybrid model: the queryable, index-worthy fields — location, source, parser version — are real columns, and the deep, evolving rule document is JSONB. Full normalization would have been fifteen brittle tables for data we always read as one unit; JSONB gave me flexibility without giving up queryability."

---

## 2. PostGIS (the star of Phase 2)

**In simple words:** PostGIS is a Postgres extension that makes the database understand *locations on Earth*.
- **`GEOMETRY` vs `GEOGRAPHY`**: GEOMETRY treats coordinates as points on flat graph paper (fast, but distances are in meaningless "degrees"). **GEOGRAPHY** treats them as points on the curved Earth — distances come back in **metres**. For lat/lng + "how far apart?" questions, use GEOGRAPHY.
- **`ST_DWithin(a, b, 25)`** — "is `a` within 25 metres of `b`?" The workhorse function. With GEOGRAPHY, 25 means 25 real metres.
- **GiST index** — B-tree indexes can't handle 2-D locations. GiST (Generalized Search Tree) can: it lets `ST_DWithin` check only nearby candidates instead of every row. Without it, every parking check scans the whole table.
- **SRID 4326** — the coordinate system's ID number. 4326 = WGS 84 = the lat/lng system every GPS uses. You'll see it in `ST_SetSRID(ST_MakePoint(lng, lat), 4326)`.
- ⚠️ **The classic bug**: PostGIS points are `(longitude, latitude)` — REVERSED from how people say it ("lat/lng"). Everyone gets bitten once. Now you won't.

**In our project:** the geospatial cache. When someone scans a sign, we store the parsed rules with their GPS point. When anyone later asks `/check?lat=..&lng=..`, we run `ST_DWithin(location, their_point, 25)` — if a stored answer exists within 25 metres, we return it instantly for free instead of paying 3–6 cents for a new LLM extraction. The GiST index keeps that lookup under a second even with millions of rules.

**Say this in the interview:**
"The geospatial cache is the heart of the cost model: each physical sign is paid for once, and every subsequent check within 25 metres is a free indexed lookup. I used the GEOGRAPHY type so `ST_DWithin` works in true metres on the Earth's surface — no application-side haversine math — and a GiST index because B-trees can't index two-dimensional nearness."

---

## 3. Supabase & Connecting from Java (JDBC)

**In simple words:**
- **Supabase** = a hosted Postgres with a dashboard, generous free tier, and PostGIS available as a one-click extension. We use it as "Postgres someone else operates" — our code speaks plain SQL to it; nothing Supabase-specific.
- **JDBC** = Java's standard database API. A connection URL (`jdbc:postgresql://host:5432/db`), a `DataSource`, `PreparedStatement` with `?` placeholders — **always** placeholders, never string-concatenated SQL (that's SQL injection).
- **Connection pooling** = opening a DB connection is slow (TCP + TLS + auth, ~100s of ms), so apps keep a pool of open connections and borrow/return them. Standard library: **HikariCP**.
- ⚠️ **The serverless twist**: Lambda can run 100 copies of itself at once → 100 pools → database refuses connections. Fixes: a tiny pool per Lambda (even size 1), or connection-pooling middleware (Supabase ships **PgBouncer** — pooling that lives server-side). Know this trade-off; it's a favorite interview topic.

**In our project:** the Lambda's repository implementation (`PostgresRuleRepository`, implementing the Phase 1 `RuleRepository` interface) will talk JDBC to Supabase, through Supabase's pooled port.

**Say this in the interview:**
"Serverless plus relational databases have a known tension: every concurrent Lambda instance wants its own connections, which can exhaust Postgres. I addressed it by connecting through PgBouncer — server-side pooling — and keeping the per-instance footprint minimal. And because storage was behind a Repository interface from day one, the Postgres implementation was an add, not a refactor."

---

## 4. AWS Lambda

**In simple words:** Lambda is "run my function without owning a server." You upload code; AWS runs it when an event arrives (for us: an HTTP request), bills per millisecond, and scales by simply running more copies. Key mental model:
- A **handler** is your entry point: a class implementing `RequestHandler<In, Out>` with one `handleRequest` method.
- **Cold start**: the first request to a new instance must load the JVM + your code — for Java that's seconds. Warm instances answer in milliseconds. Mitigations: keep the jar lean, initialize heavy things (DB pool, clients) in the constructor/static block so they're reused across calls, allocate more memory (more memory = more CPU on Lambda), or pay for "provisioned concurrency."
- **Statelessness**: an instance may be reused (so cache things in fields!) or killed at any time (so never *rely* on state surviving).
- Configuration comes from **environment variables**; permissions come from an IAM role (below).
- ⚠️ **Our specific catch**: AWS's managed Java runtime tops out at Java 21; our project targets Java 25. We'll either compile the Lambda module with `--release 21` or ship it as a container image. Real decision, worth mentioning in interviews.

**In our project:** thin handlers for `/scan`, `/check`, `/nearby` that parse the request, call the *same Phase 1 engine code unchanged*, and format the response. Architecture rule #1: **zero business logic in handlers** — the handler is to Lambda what `ScanCLI.main()` is to the CLI: a doorway.

**Say this in the interview:**
"My Lambda handlers are deliberately dumb — parse, delegate to the same tested core that the CLI uses, respond. The engine doesn't know Lambda exists. For cold starts I front-load initialization into the constructor so warm invocations reuse the DB pool and SDK clients, and I kept the deployment artifact lean. And I hit a real version-skew issue — the managed runtime lagged my toolchain JDK — which I resolved at the build level rather than downgrading the whole project."

---

## 5. API Gateway

**In simple words:** API Gateway is the front door that turns Lambdas into a real HTTPS API. It owns the URL, routes `POST /scan` to one function and `GET /check` to another, and handles TLS, throttling (rate limits), CORS (letting a browser/app on another domain call you), and API keys. Your Lambda receives a structured event (method, path, query params, body) and returns a status code + body.

**In our project:** three routes — `POST /scan` (upload a photo → extract → store → verdict), `GET /check?lat&lng` (the fast path: gov data or cache within 25 m), `GET /nearby?lat&lng` (all rules within ~1 km, for the map view). Copilot's mobile `api.ts` client already targets exactly these shapes.

**Say this in the interview:**
"API Gateway gave me the production concerns for free — TLS, throttling, CORS — so the Lambdas only contain request handling. I designed the contract first: the mobile client was written against the documented `/scan`, `/check`, `/nearby` shapes before the backend existed, which the interface-first architecture made safe."

---

## 6. S3 (photo storage)

**In simple words:** S3 stores files ("objects") in "buckets" under string keys, effectively infinitely, very cheap. Two concepts to actually learn:
- **Presigned URLs** — the elegant trick. Instead of streaming a photo *through* your API (slow, expensive), your Lambda generates a special URL that grants permission to upload/download one specific object for a few minutes. The phone uploads **directly to S3** with that URL; your API only handles the small JSON around it. No AWS credentials ever live in the app.
- **Lifecycle rules** — automatic policies like "move photos to cheap storage after 90 days."
- Buckets are **private by default** — keep them that way; presigned URLs are the only access path.

**In our project:** every scanned photo goes to S3, and its key is stored with the extraction record — that's the `photo_reference` from Phase 1's reproducibility contract, now durable. When the extractor improves, we re-run old photos from S3.

**Say this in the interview:**
"Photos flow phone → S3 directly via presigned URLs, so image bytes never pass through my API — the Lambda just issues a short-lived, single-object permission. The stored S3 key is the photo reference in my reproducibility chain: photo + parser version + timestamp means any historical scan can be re-extracted when the model improves."

---

## 7. IAM (permissions) & SSM (secrets)

**In simple words:**
- **IAM** is AWS's permission system. Every Lambda runs under a **role** — a list of allowed actions. **Least privilege** is the rule: our scan function gets "put objects into this one bucket, read these two parameters" and *nothing else*. If it's compromised, the blast radius is tiny.
- **SSM Parameter Store** holds configuration and secrets (as `SecureString`, encrypted). The Lambda fetches `ANTHROPIC_API_KEY` and the DB password at startup — secrets live in *no* file, *no* code, *no* git, ever.

**In our project:** one role per function, minimal actions; the Anthropic key and Supabase credentials in SSM SecureStrings, fetched once per Lambda instance and cached in memory.

**Say this in the interview:**
"Secrets never touch the repo — they live in SSM Parameter Store as encrypted SecureStrings, and each Lambda's IAM role can read exactly the parameters it needs and nothing more. Least privilege per function: the check endpoint can't even see the S3 bucket, because it has no reason to."

---

## 8. SAM (test AWS locally before paying AWS)

**In simple words:** AWS SAM (Serverless Application Model) is two things: a YAML **template** describing your serverless app (functions, routes, buckets — infrastructure as code, versioned in git), and a **CLI**: `sam local start-api` runs your Lambdas + API Gateway *on your laptop in Docker*, so you develop against `localhost:3000`; `sam deploy` pushes the same template to real AWS. Infrastructure-as-code means the whole backend is reviewable, repeatable, and deletable in one command.

**In our project:** `template.yaml` in the backend defining the three routes; local development loop is `mvn package` → `sam local start-api` → `curl localhost:3000/check?lat=37.77&lng=-122.41`.

**Say this in the interview:**
"The backend is defined as code in a SAM template — reviewable in git like everything else — and I developed the whole API locally with `sam local start-api` before deploying, so AWS charges and deployment cycles never slowed the inner loop."

---

## 9. How Phase 2 Fits Phase 1 (the answer that ties it together)

**In simple words:** Phase 2 adds zero business logic. It wraps the already-tested Phase 1 core in cloud plumbing:

| Phase 1 seam (already exists) | Phase 2 plugs in |
|---|---|
| `RuleRepository` interface | `PostgresRuleRepository` (Supabase + PostGIS) |
| `photo_reference` string | Real S3 keys |
| `ScanCLI` as thin entry point | Lambda handlers, equally thin |
| `VisionExtractor` (Claude) | Same class, key now from SSM |
| Engine takes `Instant` parameter | Same jar, byte-for-byte unchanged |

**Say this in the interview (the closer):**
"The proof of the architecture is that Phase 2 didn't touch the engine at all. Every cloud component landed on an interface that existed from day one — storage behind Repository, extraction behind Strategy, entry points kept logic-free. Going from local CLI to a scalable AWS API was configuration and adapters, not a rewrite. That was the design bet, and it paid off."

---

## Quick self-test (can you answer these without looking?)

1. GEOGRAPHY vs GEOMETRY — which gives metres, and why do we care?
2. Why does `ST_DWithin` need a GiST index to be fast?
3. What's a cold start, why is Java's bad, and name two mitigations.
4. Why can Lambda + Postgres exhaust connections, and what's the fix?
5. What's a presigned URL and why is it better than uploading through the API?
6. Where does our Anthropic API key live in production, and who's allowed to read it?
7. Longitude or latitude — which comes first in `ST_MakePoint`?
8. Why did our handlers stay free of business logic, and what Phase 1 rule does that mirror?

*(Answers are all above. Companions: [PHASE-1-STUDY.md](PHASE-1-STUDY.md) for concepts already built, [QUESTIONS.md](QUESTIONS.md) for project-specific interview rehearsal.)*
