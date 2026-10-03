# SQL Engine — Design Document

## 1. What Problem Does It Solve?

A database must accept SQL text from a user and transform it into structured
operations on data. The SQL engine bridges the gap between the raw string a
user types and the typed, executable representation the rest of the system
needs.

---

## 2. How It Works

The SQL engine is a three-stage pipeline:

```
SQL string
    ↓
Lexer          → Token list
    ↓
Parser         → Statement AST
    ↓
Executor       → QueryResult
```

### 2.1 Lexer

The Lexer (`Lexer.java`) performs a single left-to-right pass over the input
character by character. It produces a flat list of `Token` objects.

Each `Token` carries:
- `TokenType` — what kind of token it is (keyword, literal, operator, punctuation)
- `lexeme`    — the raw text (keywords normalized to uppercase, string literals without quotes)
- `line`      — the 1-based source line number for error messages

**Key behaviors:**
- Keywords are case-insensitive (`SELECT` = `select` = `SeLeCt`)
- String literals are single-quoted; the Lexer strips the quotes
- Numeric literals self-classify: integers become `LITERAL_INT` or `LITERAL_LONG`
  depending on magnitude; numbers with a decimal point become `LITERAL_DOUBLE`
- Single-line `--` comments are stripped
- The Lexer appends a synthetic `EOF` token so the Parser never reads past the end

**What it doesn't do:** grammar validation — that is the Parser's job.

### 2.2 Parser

The Parser (`Parser.java`) is a hand-written **recursive-descent LL(1) parser**.

Design:
- One public method `parse()` — produces exactly one `Statement`
- One private method per grammar rule
- Uses `peek()` to decide which rule to apply without consuming a token
- No backtracking — every branch is determined by the current token
- Throws `ParseException` with line number on any syntax error

The Parser produces an AST consisting of Java `record` types implementing
the `sealed interface Statement`.

### 2.3 AST Nodes

All AST nodes are **Java records** — immutable, with auto-generated `equals`,
`hashCode`, and `toString`. Using Java 21 `sealed` interfaces enables
**exhaustive pattern matching** in the executor:

```java
return switch (statement) {
    case CreateDatabaseStmt s -> executeCreateDatabase(s, catalog);
    case InsertStmt         s -> executeInsert(s, catalog);
    // ... compiler ensures every case is covered
};
```

If a new statement type is added to the `sealed` hierarchy, the compiler
will refuse to compile until every `switch` is updated.

---

## 3. Why This Design?

| Decision | Reason |
|---|---|
| Hand-written parser | Maximum learning; auditable; no code generation |
| Sealed interfaces + records | Exhaustive switches; compiler-enforced correctness; immutable |
| AND-structural WHERE clause | Keeps the evaluator simple; OR/NOT deferred |
| Single-pass Lexer | Simple, fast, no memory overhead |
| Separate Lexer/Parser phases | Easier to test each independently |

---

## 4. Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| ANTLR | Hides the parsing logic; defeats the learning purpose |
| JavaCC | Same problem |
| Visitor pattern for AST | Java 21 sealed + switch is cleaner and more concise |
| Class hierarchy instead of records | Records give equals/hashCode for free |

---

## 5. SQL Grammar (Milestone 1)

```ebnf
statement       ::= create_db | drop_db | use_db
                  | create_table | drop_table
                  | insert | select | update | delete

create_db       ::= CREATE DATABASE identifier
drop_db         ::= DROP DATABASE identifier
use_db          ::= USE identifier

create_table    ::= CREATE TABLE identifier '(' column_def (',' column_def)* ')'
column_def      ::= identifier type_name
type_name       ::= INT | BIGINT | BOOLEAN | VARCHAR | DOUBLE | TIMESTAMP
drop_table      ::= DROP TABLE identifier

insert          ::= INSERT INTO identifier VALUES '(' literal (',' literal)* ')'
literal         ::= integer | long | float | string | TRUE | FALSE

select          ::= SELECT select_list FROM identifier (WHERE where_clause)?
select_list     ::= '*' | identifier (',' identifier)*

update          ::= UPDATE identifier SET assignment (',' assignment)* (WHERE where_clause)?
assignment      ::= identifier '=' literal

delete          ::= DELETE FROM identifier (WHERE where_clause)?

where_clause    ::= predicate (AND predicate)*
predicate       ::= identifier comparison_op literal
comparison_op   ::= '=' | '!=' | '<' | '>' | '<=' | '>='
```

---

## 6. Limitations in Milestone 1

- No JOINs (single-table queries only)
- No OR or NOT in WHERE
- No subqueries
- No aggregates (COUNT, SUM, AVG, MAX, MIN)
- No ORDER BY, GROUP BY, LIMIT
- No column-list INSERT (positional values only)
- No expressions in SELECT list (no `age * 2`)
- No aliases
- No EXPLAIN
- Case-sensitive column and table names in results

All of these are explicitly planned for later milestones.

---

## 7. Failure Modes

| Failure | Behavior |
|---|---|
| Unterminated string literal | `ParseException` at the opening quote's line |
| Unknown character | `ParseException` with the character and its hex code |
| Missing closing paren | `ParseException` pointing to the token after the column list |
| Unknown statement keyword | `ParseException` naming the unexpected token |
| Extra tokens after statement | `ParseException`: "Unexpected token after statement" |

---

## 8. Test Coverage

- `LexerTest` — 30+ tests covering every token type, case-insensitivity, line tracking, error cases
- `ParserTest` — one test per statement type, all data types, all operators, error cases
- `ExpressionEvaluatorTest` — all 6 operators × all 5 types, numeric promotion, null handling
- `InMemoryCatalogTest` — full CRUD lifecycle for databases and tables
- `ExecutorIntegrationTest` — end-to-end SQL execution for all DML/DDL operations
