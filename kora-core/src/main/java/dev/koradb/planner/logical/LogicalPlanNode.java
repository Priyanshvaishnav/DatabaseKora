package dev.koradb.planner.logical;

import dev.koradb.catalog.Schema;

import java.util.List;

/**
 * Root interface for all logical query plan nodes.
 *
 * <p>A logical plan describes <em>what</em> relational algebra operations to compute,
 * completely decoupled from physical access paths, algorithms, or execution state.
 */
public interface LogicalPlanNode {

    /**
     * Returns the output schema produced by this logical node.
     */
    Schema getSchema();

    /**
     * Returns all direct child nodes in this tree.
     */
    List<LogicalPlanNode> getChildren();

    /**
     * Creates a new instance of this node with updated children, preserving all other properties.
     */
    LogicalPlanNode withChildren(List<LogicalPlanNode> newChildren);

    /**
     * Appends an ASCII tree visualization of this node and its children.
     *
     * @param sb     the string builder to append into
     * @param prefix current line indentation prefix
     * @param isTail whether this node is the last child of its parent
     */
    void explain(StringBuilder sb, String prefix, boolean isTail);

    /**
     * Returns a pretty-printed ASCII representation of this logical plan sub-tree.
     */
    default String printTree() {
        StringBuilder sb = new StringBuilder();
        explain(sb, "", true);
        return sb.toString();
    }
}
