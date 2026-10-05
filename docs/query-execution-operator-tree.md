# Query Execution Engine (Volcano Iterator Model & Operator Tree) — Design Document

## 1. What Problem Does It Solve?

In early database architectures, queries are executed procedurally: full tables are loaded into memory, filtered in an ad-hoc loop, and written to intermediate lists. This approach has critical drawbacks:
1. **Excessive Memory Usage**: Intermediate data for joins and filters must be materialized entirely in RAM.
2. **Poor Pipeline Composability**: Adding features like sorting, joins, aggregations, and pagination requires bespoke, fragile branching logic.
3. **No Early Termination**: Queries like `LIMIT 5` still process the entire dataset before discarding surplus rows.

**Milestone 5** introduces the **Volcano Iterator Model** (also known as the Pipelined Execution Model). Queries compile into an **Operator Tree** where each relational operator implements a uniform iterator contract, streaming tuples upward on-demand with minimal memory footprint and early termination capabilities.

---

## 2. Architecture & Pipeline

```
                                [ QueryResult ]
                                       ▲
                             [ LimitOperator ]        (LIMIT / OFFSET)
                                       ▲
                            [ ProjectOperator ]       (SELECT col1, col2)
                                       ▲
                              [ SortOperator ]        (ORDER BY col ASC/DESC)
                                       ▲
                           [ AggregateOperator ]      (GROUP BY, COUNT, SUM, AVG)
                                       ▲
                             [ FilterOperator ]       (WHERE condition)
                                       ▲
                        [ NestedLoop / HashJoin ]     (INNER JOIN ... ON ...)
                                   /       \
              [ IndexScan / SeqScan ]     [ SeqScan ]
                     (Table A)             (Table B)
```

---

## 3. The `Operator` Interface

All relational operators implement [`dev.koradb.execution.operator.Operator`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/Operator.java):

```java
public interface Operator extends AutoCloseable {
    void open();
    Row next(); // returns next tuple, or null when stream ends
    void close();
    Schema getSchema();
}
```

- **`open()`**: Allocates data structures, resets cursors, and opens child operator streams.
- **`next()`**: Pulls the next valid output tuple from downstream operators. Returns `null` when exhausted.
- **`close()`**: Frees resources and ensures all child operators are closed.
- **`getSchema()`**: Returns the output `Schema` of tuples produced by this operator.

---

## 4. Relational Operators

### 4.1 Scan Operators
- **`SeqScanOperator`** ([`SeqScanOperator.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/SeqScanOperator.java)):
  - Iterates over live rows in a table (both disk-backed `DiskTable` and in-memory tables).
  - Populates tuples with both unqualified and table-prefixed keys (e.g. `users.id` and `id`) to support multi-table disambiguation.
- **`IndexScanOperator`** ([`IndexScanOperator.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/IndexScanOperator.java)):
  - Uses a B+ Tree index for point lookups ($O(\log N)$) or range scans.
  - Streams matching `RecordId`s, fetching tuples from `TableFile` on demand.

### 4.2 Selection & Transformation
- **`FilterOperator`** ([`FilterOperator.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/FilterOperator.java)):
  - Evaluates `WHERE` predicates against tuples from its child operator using `ExpressionEvaluator`.
  - Discards non-matching tuples and yields matching tuples one-by-one.
- **`ProjectOperator`** ([`ProjectOperator.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/ProjectOperator.java)):
  - Evaluates projection expressions, renaming output fields according to aliases (`AS alias`).

### 4.3 Pipelined Pagination & Sorting
- **`LimitOperator`** ([`LimitOperator.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/LimitOperator.java)):
  - Skips `offset` tuples, yields up to `limit` tuples, then stops pulling from the child operator immediately (early termination).
- **`SortOperator`** ([`SortOperator.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/SortOperator.java)):
  - Materializes child tuples during `open()`, sorts them according to one or more sort criteria (`ASC`/`DESC`), with typed comparison and NULLs-last handling. Emits sorted tuples in `next()`.

### 4.4 Join Operators
- **`NestedLoopJoinOperator`** ([`NestedLoopJoinOperator.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/NestedLoopJoinOperator.java)):
  - General-purpose Cartesian / predicate join. For each outer tuple, resets and scans the inner operator, evaluating arbitrary join predicates.
- **`HashJoinOperator`** ([`HashJoinOperator.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/HashJoinOperator.java)):
  - High-performance equijoin ($O(N + M)$). Builds an in-memory hash index on the inner relation during `open()`, then probes it with outer relation tuples in `next()`.

### 4.5 Aggregations & Grouping
- **`AggregateOperator`** ([`AggregateOperator.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/AggregateOperator.java)):
  - Supports standard SQL aggregate functions: `COUNT(*)`, `COUNT(col)`, `SUM(col)`, `AVG(col)`, `MIN(col)`, `MAX(col)`.
  - Supports bucketed aggregations via `GROUP BY <col>`.

---

## 5. SQL Parser & Plan Compilation

### 5.1 Grammar Extensions
The Lexer and Parser now support:
- `ORDER BY <col> [ASC | DESC] [, ...]`
- `LIMIT <int> [OFFSET <int>]`
- `[INNER] JOIN <table2> ON <cond>`
- `GROUP BY <col>`
- Aggregate functions: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX` with optional `[AS] <alias>`.

### 5.2 Physical Plan Builder
[`PhysicalPlanBuilder.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/execution/operator/PhysicalPlanBuilder.java) analyzes the parsed `SelectStmt` AST and constructs the optimized pipeline:
1. Picks `IndexScanOperator` if an equality or range predicate matches an available index, else `SeqScanOperator`.
2. Stacks `JoinOperator` if a join clause is present.
3. Stacks `FilterOperator` for any remaining unindexed predicates.
4. Stacks `AggregateOperator` if grouping or aggregate functions are present.
5. Stacks `SortOperator` if `ORDER BY` is present.
6. Stacks `ProjectOperator` for column projection.
7. Stacks `LimitOperator` for pagination.

---

## 6. Test Verification

Milestone 5 is verified with **247 passing tests** across unit and integration suites:
- **`OperatorTest.java`**: Direct unit tests for all Volcano operators (SeqScan, Filter, Project, Limit, Sort, NestedLoopJoin, HashJoin, Aggregate).
- **`ParserTest.java`**: AST validation for `ORDER BY`, `LIMIT/OFFSET`, `JOIN`, and `GROUP BY` with aggregates.
- **`OperatorTreeIntegrationTest.java`**: End-to-end SQL queries testing joins across disk tables, sorted results, pagination, aggregations, and disk persistence across JVM restarts.
