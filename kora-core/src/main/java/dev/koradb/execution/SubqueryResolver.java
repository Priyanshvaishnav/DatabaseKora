package dev.koradb.execution;

import dev.koradb.catalog.Row;
import dev.koradb.exception.ExecutionException;
import dev.koradb.sql.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Replaces uncorrelated subqueries by their results before a statement is planned:
 * {@code (SELECT MAX(x) FROM t)} becomes a literal, {@code EXISTS (...)} TRUE/FALSE and
 * {@code x IN (SELECT ...)} an IN list. Each subquery runs once, in the caller's transaction.
 * A subquery that refers to a column of the outer query fails with "column does not exist".
 */
public final class SubqueryResolver {

    private final Function<SelectStmt, QueryResult> runner;

    public SubqueryResolver(Function<SelectStmt, QueryResult> runner) {
        this.runner = runner;
    }

    public SelectStmt resolve(SelectStmt s) {
        if (!hasSubquery(s)) return s;
        List<SelectItem> items = s.selectItems().stream()
                .map(i -> i.isComputed() ? new SelectItem(i.expression(), i.alias(), null, null, expr(i.valueExpr())) : i)
                .toList();
        List<JoinClause> joins = s.joins().stream()
                .map(j -> new JoinClause(j.tableName(), j.onCondition() == null ? null : expr(j.onCondition()), j.alias(), j.left()))
                .toList();
        return new SelectStmt(items, s.tableName(), s.tableAlias(), joins, where(s.where()), s.groupByColumns(),
                s.having().map(this::expr), s.orderBy(), s.limit(), s.offset(), s.distinct());
    }

    public UpdateStmt resolve(UpdateStmt u) {
        List<UpdateStmt.Assignment> as = u.assignments().stream()
                .map(a -> new UpdateStmt.Assignment(a.columnName(), expr(a.value()))).toList();
        return new UpdateStmt(u.tableName(), as, where(u.where()));
    }

    public DeleteStmt resolve(DeleteStmt d) {
        return new DeleteStmt(d.tableName(), where(d.where()));
    }

    private static boolean hasSubquery(SelectStmt s) {
        if (s.where().isPresent() && s.where().get().predicates().stream().anyMatch(ExpressionUtils::hasSubquery)) return true;
        if (s.having().isPresent() && ExpressionUtils.hasSubquery(s.having().get())) return true;
        if (s.joins().stream().anyMatch(j -> j.onCondition() != null && ExpressionUtils.hasSubquery(j.onCondition()))) return true;
        return s.selectItems().stream().anyMatch(i -> i.isComputed() && ExpressionUtils.hasSubquery(i.valueExpr()));
    }

    private Optional<WhereClause> where(Optional<WhereClause> w) {
        return w.map(c -> new WhereClause(c.predicates().stream().map(this::expr).toList()));
    }

    private Expression expr(Expression e) {
        return switch (e) {
            case SubqueryExpr sq -> sq.exists() ? new LiteralExpr(!runner.apply(sq.query()).rows().isEmpty())
                                                : new LiteralExpr(scalar(sq.query()));
            case InExpr in -> {
                Expression operand = expr(in.operand());
                if (in.subquery() != null) {
                    List<Expression> values = new ArrayList<>();
                    QueryResult qr = runner.apply(in.subquery());
                    String col = singleColumn(qr, "IN");
                    for (Row r : qr.rows()) values.add(new LiteralExpr(r.get(col)));
                    yield new InExpr(operand, values, null, in.negated());
                }
                yield new InExpr(operand, in.list().stream().map(this::expr).toList(), null, in.negated());
            }
            case BinaryExpr b -> new BinaryExpr(expr(b.left()), b.operator(), expr(b.right()));
            case UnaryExpr u -> new UnaryExpr(u.kind(), expr(u.operand()));
            case BetweenExpr bt -> new BetweenExpr(expr(bt.operand()), expr(bt.low()), expr(bt.high()), bt.negated());
            default -> e;
        };
    }

    private Object scalar(SelectStmt query) {
        QueryResult qr = runner.apply(query);
        String col = singleColumn(qr, "scalar");
        if (qr.rows().size() > 1) {
            throw new ExecutionException("Subquery used as a value returned " + qr.rows().size() + " rows; it must return at most one");
        }
        return qr.rows().isEmpty() ? null : qr.rows().get(0).get(col);
    }

    private static String singleColumn(QueryResult qr, String use) {
        if (qr.columns().size() != 1) {
            throw new ExecutionException("A " + use + " subquery must return exactly one column, not " + qr.columns().size());
        }
        return qr.columns().get(0);
    }
}
