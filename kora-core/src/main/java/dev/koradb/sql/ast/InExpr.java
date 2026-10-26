package dev.koradb.sql.ast;

import java.util.List;

/** {@code x [NOT] IN (v1, v2, ...)} or {@code x [NOT] IN (SELECT ...)} (exactly one of list/subquery is set). */
public record InExpr(Expression operand, List<Expression> list, SelectStmt subquery, boolean negated) implements Expression {
    public InExpr {
        list = list == null ? null : List.copyOf(list);
    }

    @Override
    public String toString() {
        return operand + (negated ? " NOT IN " : " IN ") + (subquery != null ? "(subquery)" : list.toString());
    }
}
