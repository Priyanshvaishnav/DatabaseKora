package dev.koradb.execution.operator;

import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.*;

import java.time.LocalDateTime;

/** Static result type of a computed select item (used for result metadata). */
final class ExpressionTypes {

    private ExpressionTypes() {}

    static DataType typeOf(Expression e, Schema schema) {
        return switch (e) {
            case LiteralExpr l -> {
                Object v = l.value();
                if (v instanceof Integer) yield DataType.INT;
                if (v instanceof Long) yield DataType.BIGINT;
                if (v instanceof Double) yield DataType.DOUBLE;
                if (v instanceof Boolean) yield DataType.BOOLEAN;
                if (v instanceof LocalDateTime) yield DataType.TIMESTAMP;
                yield DataType.VARCHAR;
            }
            case ColumnRefExpr c -> {
                ColumnDef cd = schema.findColumn(c.columnName());
                if (cd == null && c.columnName().contains(".")) {
                    cd = schema.findColumn(c.columnName().substring(c.columnName().indexOf('.') + 1));
                }
                yield cd != null ? cd.dataType() : DataType.VARCHAR;
            }
            case BinaryExpr b -> {
                if (!b.operator().isArithmetic()) yield DataType.BOOLEAN;
                DataType l = typeOf(b.left(), schema), r = typeOf(b.right(), schema);
                if (l == DataType.DOUBLE || r == DataType.DOUBLE) yield DataType.DOUBLE;
                if (l == DataType.BIGINT || r == DataType.BIGINT) yield DataType.BIGINT;
                yield DataType.INT;
            }
            case UnaryExpr u -> u.kind() == UnaryExpr.Kind.NEGATE ? typeOf(u.operand(), schema) : DataType.BOOLEAN;
            case InExpr i -> DataType.BOOLEAN;
            case BetweenExpr b -> DataType.BOOLEAN;
            case AggregateExpr a -> a.type() == AggregateType.COUNT ? DataType.BIGINT : DataType.DOUBLE;
            case SubqueryExpr s -> DataType.VARCHAR;
        };
    }
}
