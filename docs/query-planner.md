# Query Planner & Cost-Based Optimizer — Design Document

## 1. What Problem Does It Solve?

In **Milestone 5**, KoraDB gained an execution engine based on Volcano iterator operators. However, queries were translated directly from the SQL AST into physical operators using hardcoded heuristics. In a production-grade relational database, this direct coupling creates major performance bottlenecks:

1. **Inefficient Join Processing**: Without predicate pushdown, a query joining two million-row tables filters rows *after* performing a full cross-product or hash join instead of *before*.
2. **Excessive Column Deserialization**: Without projection pruning, every column across wide tables is copied through the entire pipeline even if only one column is needed.
3. **Suboptimal Access Paths**: Heuristic planners cannot determine whether an index scan is actually faster than a sequential scan (for low selectivity, index lookups incur high random I/O overhead).
4. **Poor Memory Utilization in Joins**: Without table statistics, a hash join might choose a 10 GB table as its in-memory build relation instead of a 10 MB dimension table.
5. **No Plan Transparency**: Developers and DBAs have no way to inspect the chosen access paths or execution profiles.

**Milestone 6** introduces a decoupled **two-stage Query Planning and Cost-Based Optimization Pipeline**:
- **Stage 1 (Logical Planning & RBO)**: Translates the AST into an initial canonical logical plan and applies algebraic rewrite rules (Predicate Pushdown, Projection Pruning, Constant Folding, Limit Pushdown) that are *always beneficial*.
- **Stage 2 (Physical Planning & CBO)**: Uses catalog statistics (cardinalities, page counts, distinct values) and a calibrated hardware cost model to select optimal physical algorithms (IndexScan vs. SeqScan, HashJoin vs. NestedLoopJoin, Build vs. Probe sizing).
- **Plan Observability (`EXPLAIN [ANALYZE]`)**: Formats query plans into ASCII execution trees and profiles real-world execution times.

---

## 2. Architecture & Pipeline

```
                     SQL Query String
                            │
                          Lexer
                            │
                     Parser (AST: SelectStmt, ExplainStmt)
                            │
                  ┌─────────▼─────────┐
                  │ LogicalPlanBuilder│
                  └─────────┬─────────┘
                            │ Canonical Logical Plan
                  ┌─────────▼─────────┐
                  │ RuleBasedOptimizer│ ◄─── Predicate Pushdown, Projection Pruning,
                  └─────────┬─────────┘      Constant Folding, Limit Pushdown
                            │ Optimized Logical Plan
                  ┌─────────▼─────────┐
                  │  PhysicalPlanner  │ ◄─── TableStatistics, CostModel, Indexes
                  └─────────┬─────────┘
                            │ Physical Operator Tree with Costs
                            ├────────────────────────────┐
                            ▼                            ▼
                      Regular Query                   EXPLAIN [ANALYZE]
                            │                            │
                  Volcano Iterator Engine          ASCII Plan Visualizer
                   (open / next / close)           (with optional ProfilingOperator)
                            │                            │
                       QueryResult                  QueryResult (QUERY PLAN)
```

---

## 3. Logical Plan Representation

All logical nodes implement [`dev.koradb.planner.logical.LogicalPlanNode`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/logical/LogicalPlanNode.java):
- `getSchema()`: Output relational schema.
- `getChildren()`: Sub-tree children.
- `withChildren(...)`: Immutably copies the node with transformed children.
- `printTree()`: ASCII visualizer.

### Logical Nodes
| Node | Relational Operation | Key Attributes |
|---|---|---|
| [`LogicalScan`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/logical/LogicalScan.java) | Relation scan ($\sigma$) | `tableName`, `schema`, `projectedColumns`, `pushedPredicates` |
| [`LogicalFilter`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/logical/LogicalFilter.java) | Selection ($\sigma_p$) | `child`, `predicates` |
| [`LogicalProject`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/logical/LogicalProject.java) | Projection ($\pi$) | `child`, `projectItems` |
| [`LogicalJoin`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/logical/LogicalJoin.java) | Theta / Equi-join ($\bowtie$) | `left`, `right`, `onCondition`, `joinType` (`INNER`, `CROSS`) |
| [`LogicalAggregate`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/logical/LogicalAggregate.java) | Grouping & Aggregate ($\gamma$) | `child`, `selectItems`, `groupBy` |
| [`LogicalSort`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/logical/LogicalSort.java) | Ordering ($\tau$) | `child`, `orderItems` |
| [`LogicalLimit`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/logical/LogicalLimit.java) | Pagination | `child`, `limit`, `offset` |

---

## 4. Rule-Based Optimizer (RBO)

The [`RuleBasedOptimizer`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/optimizer/RuleBasedOptimizer.java) coordinates sequential algebraic rewrite rules:

### 4.1 Constant Folding (`ConstantFoldingRule`)
- **Tautology Elimination**: Conditions like `WHERE 1 = 1` or `'a' = 'a'` are identified at compile-time and stripped from the filter. If all predicates evaluate to TRUE, the `LogicalFilter` node is removed entirely.
- **Operand Normalization**: Rewrites `literal = column` into canonical `column = literal` (e.g. `42 = id` → `id = 42`) to enable index matching.

### 4.2 Predicate Pushdown (`PredicatePushdownRule`)
- **Pushdown past Joins**: Splits conjunctions (`AND`). Inspects column references for each predicate:
  - If all columns belong to the left branch, pushes the filter below the join's left child.
  - If all columns belong to the right branch, pushes the filter below the join's right child.
  - Cross-relation predicates remain at the join level.
- **Pushdown into Scans**: Filter predicates directly above a `LogicalScan` are pushed into `LogicalScan.pushedPredicates`, enabling the physical planner to evaluate indexes or filter rows at the storage layer.

### 4.3 Projection Pruning (`ProjectionPruningRule`)
- Computes required column sets top-down from projections, sorts, aggregates, filters, and joins.
- Prunes `LogicalScan.projectedColumns` to only columns actually referenced in the query, reducing tuple width and data transfer.

---

## 5. Catalog Statistics & Hardware Cost Model

### 5.1 Statistics
- [`TableStatistics`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/catalog/TableStatistics.java): Tracks `rowCount`, `pageCount`, and column statistics.
- [`ColumnStatistics`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/catalog/ColumnStatistics.java): Tracks distinct value count (`NDV`), `nullCount`, and min/max values.

### 5.2 Calibrated Cost Model
[`CostModel.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/cost/CostModel.java) uses hardware-calibrated metrics:
- **`PAGE_IO_COST = 1.0`**: Sequential read of a 4KB disk page.
- **`RANDOM_PAGE_IO_COST = 4.0`**: Random seek read for unclustered B+ Tree RID lookups.
- **`CPU_TUPLE_COST = 0.01`**: Per-tuple iteration and processing.
- **`CPU_OPERATOR_COST = 0.002`**: Per-expression comparison and filter evaluation.
- **`CPU_HASH_COST = 0.02`**: Hash table insertion and probe.
- **`CPU_SORT_COST = 0.05`**: $N \log_2(N)$ sort comparison overhead.

### 5.3 Selectivity Estimation
- Equality (`col = val`): $s = \frac{1}{\max(\text{NDV}, 1)}$
- Inequality (`<`, `>`, `<=`, `>=`): $s = 0.33$
- Conjunction ($P_1 \land P_2$): $s = s_1 \times s_2$

---

## 6. Physical Plan Generation & CBO Decisions

[`PhysicalPlanner.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/physical/PhysicalPlanner.java) converts optimized logical nodes into physical operators:

1. **Access Path Selection (IndexScan vs. SeqScan)**:
   - For an equality predicate on an indexed column:
     $$\text{Cost}_{\text{seq}} = \text{pageCount} \times 1.0 + \text{rowCount} \times 0.01$$
     $$\text{Cost}_{\text{idx}} = 3 \times 1.0 + \text{matchingRows} \times 4.0 + \text{matchingRows} \times 0.01$$
   - When selectivity is high (few matching rows), `IndexScanOperator` is selected. If the table is small or selectivity is low, `SeqScanOperator` is selected.
2. **Join Selection & Direction**:
   - Equijoins select `HashJoinOperator`.
   - The relation with **smaller cardinality** is selected as the in-memory **build relation**, while the larger relation streams through as the **probe relation**.
   - Non-equi joins fall back to `NestedLoopJoinOperator`.

---

## 7. `EXPLAIN` and `EXPLAIN ANALYZE`

KoraDB supports SQL `EXPLAIN` and `EXPLAIN ANALYZE`:

```sql
EXPLAIN ANALYZE SELECT id, name FROM users WHERE age > 25;
```

**Output**:
```text
=== LOGICAL PLAN ===
└── LogicalProject [items=[id, name]]
    └── LogicalFilter [predicates=[BinaryExpr(ColumnRef(age) GT Literal(25))]]
        └── LogicalScan [table=users]

=== OPTIMIZED LOGICAL PLAN ===
└── LogicalProject [items=[id, name]]
    └── LogicalScan [table=users, pushedPredicates=[BinaryExpr(ColumnRef(age) GT Literal(25))]]

=== PHYSICAL PLAN ===
└── Project [items=[id, name]] (cost=20.02 rows=330)
    └── SeqScan [table=users] (cost=20.00 rows=1000)

=== EXECUTION PROFILE ===
Actual rows: 2
Execution time: 0.145 ms
```

[`ProfilingOperator.java`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/planner/explain/ProfilingOperator.java) wraps physical operators to measure exact execution times and tuple counts without modifying underlying operator semantics.

---

## 8. Test Verification

Milestone 6 is verified with **267 passing tests** (0 failures, 0 skipped):
- **`LogicalPlanBuilderTest`**: AST to logical plan tree structural verification.
- **`RuleBasedOptimizerTest`**: Constant folding, tautology elimination, predicate pushdown through joins, and projection pruning.
- **`CostModelTest`**: Hardware cost models, selectivity estimation, and cost formulas.
- **`ExplainTest`**: End-to-end execution of `EXPLAIN` and `EXPLAIN ANALYZE`.
- **`PlannerIntegrationTest`**: Complex SQL queries combining joins, pushdowns, sorting, limits, disk persistence, and B+ tree index lookups across JVM restarts.
