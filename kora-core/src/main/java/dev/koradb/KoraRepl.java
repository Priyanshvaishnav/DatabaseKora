package dev.koradb;

import dev.koradb.execution.QueryResult;

import java.util.Scanner;

/**
 * A minimal interactive REPL for KoraDB Milestone 1.
 *
 * Run with:
 *   mvn exec:java -pl kora-core -Dexec.mainClass=dev.koradb.KoraRepl
 *
 * Type SQL at the prompt and press Enter.
 * Statements can span multiple lines — end them with a semicolon.
 * Type 'exit' or 'quit' to stop.
 */
public final class KoraRepl {

    private static final String PROMPT     = "kora> ";
    private static final String CONT_PROMPT = "   -> ";
    private static final String BANNER = """
            ╔══════════════════════════════════════════╗
            ║     KoraDB — Milestone 1 SQL Engine      ║
            ║     In-Memory · Java 21 · From Scratch   ║
            ╚══════════════════════════════════════════╝
            Type SQL and press Enter. End statements with ;
            Type  exit  or  quit  to stop.
            """;

    public static void main(String[] args) {
        System.out.println(BANNER);

        KoraDB db      = new KoraDB();
        Scanner scanner = new Scanner(System.in);
        StringBuilder buffer = new StringBuilder();

        while (true) {
            String prompt = buffer.isEmpty() ? PROMPT : CONT_PROMPT;
            System.out.print(prompt);

            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();

            // Exit commands
            if (buffer.isEmpty() && (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit"))) {
                System.out.println("Bye!");
                break;
            }

            // Skip blank lines
            if (line.isEmpty()) continue;

            buffer.append(line).append(" ");

            // Execute when we see a semicolon
            String accumulated = buffer.toString().trim();
            if (accumulated.endsWith(";")) {
                String sql = accumulated.substring(0, accumulated.length() - 1).trim();
                buffer.setLength(0);

                if (sql.isEmpty()) continue;

                try {
                    long start  = System.nanoTime();
                    QueryResult result = db.execute(sql);
                    long ms     = (System.nanoTime() - start) / 1_000_000;

                    System.out.print(result.toDisplayString());
                    System.out.printf("(%d ms)%n", ms);
                } catch (Exception e) {
                    System.out.println("ERROR: " + e.getMessage());
                }
                System.out.println();
            }
        }

        scanner.close();
    }
}
