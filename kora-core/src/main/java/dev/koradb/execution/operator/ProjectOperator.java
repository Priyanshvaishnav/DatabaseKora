package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import dev.koradb.sql.ast.SelectItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projection operator.
 *
 * <p>Projects tuples from the child operator onto the requested columns and aliases.
 * If the projection list is empty (representing {@code SELECT *}), tuples pass through unchanged.
 */
public class ProjectOperator implements Operator {

    private final Operator child;
    private final List<SelectItem> selectItems;
    private final Schema schema;

    public ProjectOperator(Operator child, List<SelectItem> selectItems) {
        this.child = child;
        this.selectItems = selectItems != null ? List.copyOf(selectItems) : List.of();
        this.schema = computeSchema(child.getSchema(), this.selectItems);
    }

    private static Schema computeSchema(Schema childSchema, List<SelectItem> items) {
        if (items.isEmpty()) {
            return childSchema;
        }
        List<ColumnDef> cols = new ArrayList<>();
        for (SelectItem item : items) {
            String colName = item.expression();
            ColumnDef existing = childSchema.findColumn(colName);
            if (existing == null && colName.contains(".")) {
                existing = childSchema.findColumn(colName.substring(colName.indexOf('.') + 1));
            }
            DataType type = existing != null ? existing.dataType() : DataType.VARCHAR;
            cols.add(new ColumnDef(item.alias(), type));
        }
        return new Schema(cols);
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Row next() {
        Row row = child.next();
        if (row == null) return null;
        if (selectItems.isEmpty()) {
            return row;
        }

        Map<String, Object> projected = new LinkedHashMap<>();
        for (SelectItem item : selectItems) {
            String colName = item.expression();
            Object val = row.get(colName);
            if (val == null && colName.contains(".")) {
                val = row.get(colName.substring(colName.indexOf('.') + 1));
            }
            if (val == null) {
                for (Map.Entry<String, Object> entry : row.values().entrySet()) {
                    if (entry.getKey().endsWith("." + colName)) {
                        val = entry.getValue();
                        break;
                    }
                }
            }
            projected.put(item.alias(), val);
            if (item.alias().contains(".")) {
                String shortName = item.alias().substring(item.alias().indexOf('.') + 1);
                projected.putIfAbsent(shortName, val);
            }
        }
        return new Row(projected);
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public Schema getSchema() {
        return schema;
    }
}
