# AI Layer — Natural Language to SQL, Query Explainer & Optimization Advisor

## 1. What Problem Does It Solve?

Relational databases require users and developers to master Structured Query Language (SQL) and understand internal query execution plans and index structures. For ad-hoc analytics, exploratory querying, and performance tuning:
1. **Natural Language Translation**: Users, data scientists, and engineers often know the questions they want to ask (e.g., *"show me customers who spent more than 500 in electronics"*) but must spend time handcrafting SQL queries conforming strictly to the database's dialect.
2. **Dialect Mismatches & Hallucination**: Generic LLMs frequently produce SQL with features unsupported by specific database engines (such as dialects from PostgreSQL or Oracle). Without strict grounding in the database schema and engine dialect constraints, generated queries fail to execute.
3. **Execution Plan Complexity**: Database execution plans (`EXPLAIN`) are typically cryptic trees of physical operators (`SeqScan`, `IndexScan`, `NestedLoopJoin`, `Project`) that are difficult for non-specialists to interpret.
4. **Performance Tuning & Index Recommendations**: Identifying slow query patterns, full table scans, and unindexed filter predicates requires manual analysis.

**Milestone 16** introduces the **`kora-ai`** module, providing an integrated AI engine directly inside KoraDB with:
- Schema-grounded Natural Language to SQL translation with **1-shot self-healing parse verification**.
- Plain English query plan explanation (`QueryExplainer`).
- Proactive B+ Tree index optimization suggestions (`OptimizationAdvisor`).
- Multi-provider LLM support (**Rule-Based Offline**, **OpenAI**, **Google Gemini**, **Anthropic Claude**) with zero external database/JSON library dependencies.
- Native interactive REPL commands in `kora-cli` (`\ai`, `\explain-ai`, `\advisor`, `\ai-provider`).

---

## 2. Architecture & Pipeline

```
  ┌─────────────────────────┐             ┌─────────────────────────┐
  │   Interactive CLI       │             │   Java Application      │
  │ (\ai, \explain-ai, ...) │             │       (KoraAi)          │
  └────────────┬────────────┘             └────────────┬────────────┘
               │                                       │
               ▼                                       ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │                        KoraAiEngine                             │
  │                                                                 │
  │  ┌────────────────────────┐         ┌────────────────────────┐  │
  │  │    SchemaExtractor     │         │     DialectRules       │  │
  │  │ Extracts tables, cols, │         │ Injects KoraDB dialect │  │
  │  │ data types, indexes    │         │ syntax restrictions    │  │
  │  └───────────┬────────────┘         └───────────┬────────────┘  │
  │              │                                  │               │
  │              └────────────────┬─────────────────┘               │
  │                               ▼                                 │
  │                  ┌─────────────────────────┐                    │
  │                  │     PromptTemplates     │                    │
  │                  └────────────┬────────────┘                    │
  │                               ▼                                 │
  │                  ┌─────────────────────────┐                    │
  │                  │      SqlGenerator       │                    │
  │                  │  ┌───────────────────┐  │                    │
  │                  │  │ Self-Healing Loop │  │                    │
  │                  │  │ (Parser Feedback) │  │                    │
  │                  │  └───────────────────┘  │                    │
  │                  └────────────┬────────────┘                    │
  │                               ▼                                 │
  │                  ┌─────────────────────────┐                    │
  │                  │    LlmClientFactory     │                    │
  │                  └────────────┬────────────┘                    │
  └───────────────────────────────┼─────────────────────────────────┘
                                  │
        ┌─────────────────────────┼────────────────────────┐
        ▼                         ▼                        ▼
┌──────────────┐          ┌──────────────┐         ┌──────────────┐
│  Rule-Based  │          │ OpenAI /     │         │ Gemini /     │
│   (Offline   │          │ Ollama /     │         │ Anthropic    │
│ Deterministic│          │ vLLM         │         │ Cloud APIs   │
└──────────────┘          └──────────────┘         └──────────────┘
```

---

## 3. Multi-Provider LLM Abstraction

To avoid bloating the engine classpath with massive cloud SDKs or Jackson/Gson transitive trees, `kora-ai` features a handcrafted, high-performance recursive-descent JSON parser and serializer ([`SimpleJson`](file:///d:/Projects/DB/kora-db/kora-ai/src/main/java/dev/koradb/ai/util/SimpleJson.java)) and utilizes native Java 21 `java.net.http.HttpClient` with Project Loom Virtual Threads.

### Supported Providers

| Provider | Identifier | Default Model | Configuration | Offline / Online |
|---|---|---|---|---|
| **Rule-Based** | `rule-based` | `offline-rules-v1` | Built-in (no keys needed) | Offline (100% deterministic) |
| **OpenAI** | `openai` | `gpt-4o` | `OPENAI_API_KEY` (or custom base URL for Ollama/vLLM) | Online / Self-hosted |
| **Google Gemini** | `gemini` | `gemini-1.5-flash` | `GEMINI_API_KEY` | Online |
| **Anthropic Claude** | `anthropic` | `claude-3-5-sonnet-20241022` | `ANTHROPIC_API_KEY` | Online |

### Provider Factory & Switching
Providers can be instantiated programmatically or configured via environment variables:
```java
// Automatic discovery from environment variables or fallback to rule-based
LlmClient client = LlmClientFactory.createFromEnvironment();

// Explicit instantiation
LlmClient ruleBased = LlmClientFactory.create("rule-based");
LlmClient openAi = LlmClientFactory.create("openai", Map.of("apiKey", "sk-...", "model", "gpt-4o"));
LlmClient ollama = LlmClientFactory.create("openai", Map.of(
    "apiKey", "ollama",
    "model", "llama3.1",
    "endpoint", "http://localhost:11434/v1/chat/completions"
));
```

---

## 4. Grounded Prompt Engineering & Self-Healing Verification

### Dialect Rules Injection
Large Language Models trained on open-source code often produce SQL syntax specific to PostgreSQL, MySQL, or SQLite. KoraDB's SQL compiler enforces a lean, performant ANSI SQL subset. To prevent invalid queries before they reach the parser, [`DialectRules`](file:///d:/Projects/DB/kora-db/kora-ai/src/main/java/dev/koradb/ai/prompt/DialectRules.java) grounds every LLM prompt with KoraDB-specific constraints:
- Supported statements: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `CREATE TABLE`, `ALTER TABLE ADD COLUMN`, `CREATE INDEX`, `DROP TABLE`.
- Expressions: arithmetic, comparisons (`=`, `!=`, `<>`, `<`, `<=`, `>`, `>=`), `AND`, `OR`, `NOT`, `LIKE`, `IN`, `BETWEEN`, `IS [NOT] NULL`, uncorrelated subqueries.
- Aggregates: `COUNT(*)`, `SUM(col)`, `AVG(col)`, `MIN(col)`, `MAX(col)`, with `GROUP BY` on several columns and `HAVING`.
- Joins: `[INNER] JOIN` and `LEFT JOIN`, with table aliases. Not supported: other SQL functions, `RIGHT`/`FULL` joins, and `INSERT ... SELECT`.

### 1-Shot Self-Healing Feedback Loop
When translating natural language into SQL, the [`SqlGenerator`](file:///d:/Projects/DB/kora-db/kora-ai/src/main/java/dev/koradb/ai/engine/SqlGenerator.java) executes a real-time verification step using KoraDB's internal [`Parser`](file:///d:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/core/parser/Parser.java):
1. **Initial Translation**: The prompt is sent to the LLM alongside table schemas and dialect rules.
2. **Local AST Verification**: The generated SQL is compiled via `new Parser(new Lexer(rawSql)).parse()`.
3. **Self-Healing Correction**: If the parser throws a `ParseException`, `SqlGenerator` captures the exact error message and token offset, crafts a self-healing diagnostic prompt (`"The generated query failed KoraDB validation: <error>. Fix the SQL to be compliant with KoraDB dialect"`), and requests a corrected query from the LLM.
4. **Validation Result**: If valid, the result is marked `valid = true`; if still invalid after the retry, the user is returned the query alongside the specific diagnostic warning.

---

## 5. Query Explainer & Optimization Advisor

### Query Explainer ([`QueryExplainer`](file:///d:/Projects/DB/kora-db/kora-ai/src/main/java/dev/koradb/ai/engine/QueryExplainer.java))
Translates complex SQL queries and operator execution trees into clear, human-readable explanations detailing:
- The high-level intent of the query.
- Step-by-step physical execution breakdown (table scan, filter predicates, projection, sorting, limits).
- Tables and columns accessed.

### Optimization Advisor ([`OptimizationAdvisor`](file:///d:/Projects/DB/kora-db/kora-ai/src/main/java/dev/koradb/ai/engine/OptimizationAdvisor.java))
Analyzes queries against the current database schema to identify performance bottlenecks:
- **Missing Index Detection**: Detects filter columns (`WHERE age > 30`, `WHERE status = 'ACTIVE'`) that would cause sequential full table scans ($O(N)$).
- **Index Awareness**: Cross-references existing B+ Tree indexes from the schema catalog to prevent redundant `CREATE INDEX` recommendations.
- **Actionable DDL**: Produces ready-to-run DDL statements, such as:
  ```sql
  CREATE INDEX idx_users_age ON users (age);
  ```
- **Complexity Analysis**: Explains the algorithmic improvement (e.g., transforming an $O(N)$ disk page scan into an $O(\log N)$ B+ Tree range scan).

---

## 6. Interactive CLI Integration

The KoraDB REPL CLI includes built-in AI meta-commands:

### `\ai <prompt>`
Translates a natural language question into SQL, validates it against KoraDB parser, prints the translation summary, and prompts for immediate execution:
```
kora [shop]> \ai show all products priced over 50
Translating with KoraDB AI (rule-based)...

Generated SQL: SELECT * FROM products WHERE price > 50;
Provider:      rule-based (offline-rules-v1) [4ms]
Execute query? [Y/n]: y
+----+----------+-------+
| id | name     | price |
+----+----------+-------+
|  1 | Keyboard |  75.0 |
+----+----------+-------+
1 row in set (12.00 ms)
```

### `\explain-ai <sql>`
Explains what the query does in plain English:
```
kora [shop]> \explain-ai SELECT * FROM products WHERE price > 50;

### Query Explanation

**Intent:** Retrieves matching records from the database.

**Execution Steps:**
1. **Scan**: Evaluates rows from target relation(s).
2. **Filter Predicate**: Applies WHERE filter condition(s) to discard non-matching rows.
```

### `\advisor <sql>`
Provides performance diagnostics and recommends indexes:
```
kora [shop]> \advisor SELECT * FROM products WHERE price > 50;

### Performance & Index Recommendations

**Bottleneck:** Filter condition on column `price` may trigger a full table scan without an index.

**Recommendation:** Create a B+ Tree index on `price`:
```sql
CREATE INDEX idx_products_price ON products (price);
```

**Expected Gain:** Replaces O(N) sequential table scan with O(log N) B+ Tree point lookup or range scan.

Recommended DDL Statements:
  CREATE INDEX idx_products_price ON products (price);
```

### `\ai-provider [name]`
Displays the active provider or hot-swaps to a new provider:
```
kora [shop]> \ai-provider
Active AI Provider: rule-based (model: offline-rules-v1)
Supported providers: rule-based, openai, gemini, anthropic
Usage: \ai-provider <provider_name>

kora [shop]> \ai-provider openai
Switched AI provider to: openai (model: gpt-4o)
```

---

## 7. Programmatic Java API

Applications can consume the AI engine directly via the fluid [`KoraAi`](file:///d:/Projects/DB/kora-db/kora-ai/src/main/java/dev/koradb/ai/KoraAi.java) facade:

```java
import dev.koradb.ai.KoraAi;
import dev.koradb.ai.model.*;
import dev.koradb.core.Database;

// 1. Initialize KoraAi bound to an active KoraDB instance
Database db = Database.openInMemory();
KoraAi ai = KoraAi.builder()
        .database(db)
        .provider("rule-based") // or "openai", "gemini", "anthropic"
        .build();

// 2. Natural Language -> SQL
TranslationResult result = ai.translate("find users older than 25");
System.out.println("SQL: " + result.generatedSql());
System.out.println("Valid: " + result.isValid());

// 3. Explain Query Plan
ExplanationResult explanation = ai.explain("SELECT * FROM users WHERE age > 25;");
System.out.println("Explanation: " + explanation.explanation());

// 4. Index Optimization Advice
OptimizationReport advice = ai.advise("SELECT * FROM users WHERE age > 25;");
for (String ddl : advice.recommendedIndexDdls()) {
    System.out.println("Recommended DDL: " + ddl);
}

// 5. End-to-end NL Execution (read-only by default)
AiQueryResult queryResult = ai.ask("find users older than 25");
if (queryResult.executed()) {
    System.out.println("Rows returned: " + queryResult.queryResult().rows().size());
} else {
    System.out.println("Not executed: " + queryResult.errorMessage());
}

// Data changes must be allowed explicitly, and must be what the request asks for
ai.ask("delete users where id = 7", AiExecutionPolicy.ALLOW_WRITES);   // DELETE runs
ai.ask("how many users are there", AiExecutionPolicy.ALLOW_WRITES);   // a DELETE answer would be refused
```

### Safety and grounding

- **Execution policy.** `ask(prompt)` executes only SELECT, SHOW, DESCRIBE and EXPLAIN. INSERT/UPDATE/DELETE need
  `AiExecutionPolicy.ALLOW_WRITES`, and CREATE/DROP/USE need `ALLOW_ALL`. Even when allowed, a statement runs only
  if the request asks for that kind of change ("delete", "remove", "update", "add", "drop", ...). A model answering
  a question with `DROP TABLE` (a wrong answer or a prompt injection) is never executed. Refused SQL is returned in
  `AiQueryResult.generatedSql()` for review.
- **Schema validation.** Generated SQL must be exactly one statement whose tables and columns exist. Otherwise the
  translation is invalid, and remote providers get one correction attempt with the error.
- **Sample values.** For the offline rule-based provider, the schema context includes the distinct values of
  low-cardinality text columns (for example `orders.status: 'pending', 'shipped'`), so "pending orders" maps to
  `status = 'pending'`. They are **not** sent to remote providers unless you call `ai.shareSampleValues(true)`,
  because they are table data.
- **Provider errors.** Rate limits (429) and temporary server errors (500/502/503/504) are retried up to 3 times with
  backoff (honouring `Retry-After`). Remaining failures come back as an invalid `TranslationResult` or a failed
  `AiQueryResult`; they are not thrown.

---

## 8. Summary of Completed Capabilities

| Capability | Module | Status |
|---|---|---|
| Schema Extraction & Grounding | `kora-ai` (`dev.koradb.ai.schema`) | ✅ Complete |
| Dialect Rules & Prompt Grounding | `kora-ai` (`dev.koradb.ai.prompt`) | ✅ Complete |
| Zero-Dependency JSON Parser | `kora-ai` (`dev.koradb.ai.util.SimpleJson`) | ✅ Complete |
| Rule-Based Deterministic Provider | `kora-ai` (`dev.koradb.ai.provider.RuleBasedLlmClient`) | ✅ Complete |
| OpenAI / Ollama Provider | `kora-ai` (`dev.koradb.ai.provider.OpenAiLlmClient`) | ✅ Complete |
| Google Gemini Provider | `kora-ai` (`dev.koradb.ai.provider.GeminiLlmClient`) | ✅ Complete |
| Anthropic Claude Provider | `kora-ai` (`dev.koradb.ai.provider.AnthropicLlmClient`) | ✅ Complete |
| 1-Shot Self-Healing SQL Generator | `kora-ai` (`dev.koradb.ai.engine.SqlGenerator`) | ✅ Complete |
| Query Plan Explainer | `kora-ai` (`dev.koradb.ai.engine.QueryExplainer`) | ✅ Complete |
| B+ Tree Index Optimization Advisor | `kora-ai` (`dev.koradb.ai.engine.OptimizationAdvisor`) | ✅ Complete |
| Unified Engine & Facade | `kora-ai` (`dev.koradb.ai.KoraAi`) | ✅ Complete |
| Interactive CLI Integration | `kora-cli` (`\ai`, `\explain-ai`, `\advisor`, `\ai-provider`) | ✅ Complete |
