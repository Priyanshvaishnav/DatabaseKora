package dev.koradb.execution;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;

/**
 * Parses TIMESTAMP literals the same way everywhere (INSERT/UPDATE values and WHERE comparisons).
 *
 * <p>Accepts {@code yyyy-MM-dd HH:mm:ss}, an ISO {@code T} separator, seconds omitted
 * ({@code HH:mm}, as {@link LocalDateTime#toString()} prints a whole minute), fractional seconds
 * (as sent by JDBC {@code Timestamp.toString()}) and a bare date (midnight). TIMESTAMP is stored
 * with second precision, so fractional seconds are truncated.
 */
final class TimestampParser {

    private TimestampParser() {}

    private static final DateTimeFormatter LENIENT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendPattern("HH:mm")
            .optionalStart().appendLiteral(':').appendPattern("ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .optionalEnd()
            .optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter();

    /** @throws DateTimeParseException if {@code s} is not a timestamp in any accepted form */
    static LocalDateTime parse(String s) {
        return LocalDateTime.parse(s.trim(), LENIENT).withNano(0);
    }
}
