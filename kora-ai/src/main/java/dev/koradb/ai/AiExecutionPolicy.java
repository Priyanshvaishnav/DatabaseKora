package dev.koradb.ai;

/**
 * Which statements {@link KoraAi#ask(String, AiExecutionPolicy)} may execute. Generated SQL that the
 * policy does not allow is returned unexecuted, so the caller can review it and run it explicitly.
 */
public enum AiExecutionPolicy {
    /** Only SELECT, SHOW, DESCRIBE and EXPLAIN (the default of {@link KoraAi#ask(String)}). */
    READ_ONLY,
    /** Also INSERT, UPDATE and DELETE, when the request itself asks for that change. */
    ALLOW_WRITES,
    /** Also CREATE / DROP / USE, when the request itself asks for that change. */
    ALLOW_ALL
}
