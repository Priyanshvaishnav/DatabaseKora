package dev.koradb.server.http;

import dev.koradb.KoraDB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KoraHttpServerTest {

    private KoraDB db;
    private KoraHttpServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        db = new KoraDB();
        // Use port 0 for ephemeral port allocation in tests
        server = new KoraHttpServer(KoraHttpConfig.of("127.0.0.1", 0), db);
        server.start();

        client = HttpClient.newHttpClient();
        baseUrl = "http://127.0.0.1:" + server.getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("Should return UP status on /v1/health")
    void testHealthEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/health"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        Map<String, Object> json = Json.parseObject(resp.body());
        assertThat(json.get("status")).isEqualTo("UP");
        assertThat(json.get("version")).isEqualTo(KoraHttpServer.SERVER_VERSION);
    }

    @Test
    @DisplayName("Should handle complete SQL workflow (DDL, DML, SELECT) via POST /v1/query")
    void testSqlWorkflow() throws Exception {
        // 1. Create Table
        String createSql = """
                {
                  "database": "shop_db",
                  "sql": "CREATE TABLE products (id INT, name VARCHAR, price DOUBLE)"
                }
                """;
        HttpResponse<String> createResp = sendPost("/v1/query", createSql);
        assertThat(createResp.statusCode()).isEqualTo(200);
        Map<String, Object> createJson = Json.parseObject(createResp.body());
        assertThat(createJson.get("status")).isEqualTo("success");

        // 2. Insert rows
        String insertSql1 = """
                {
                  "database": "shop_db",
                  "sql": "INSERT INTO products VALUES (1, 'Laptop', 999.99)"
                }
                """;
        HttpResponse<String> insertResp1 = sendPost("/v1/query", insertSql1);
        assertThat(insertResp1.statusCode()).as(insertResp1.body()).isEqualTo(200);
        assertThat(Json.parseObject(insertResp1.body()).get("affectedRows")).isEqualTo(1L);

        String insertSql2 = """
                {
                  "database": "shop_db",
                  "sql": "INSERT INTO products VALUES (2, 'Mouse', 24.50)"
                }
                """;
        sendPost("/v1/query", insertSql2);

        // 3. Select rows with query param ?database=shop_db
        String selectSql = "{\"sql\": \"SELECT id, name, price FROM products WHERE price > 50\"}";
        HttpResponse<String> selectResp = sendPost("/v1/query?database=shop_db", selectSql);
        if (selectResp.statusCode() != 200) {
            System.err.println("SELECT FAILED WITH BODY: " + selectResp.body());
        }
        assertThat(selectResp.statusCode()).as(selectResp.body()).isEqualTo(200);

        Map<String, Object> selectJson = Json.parseObject(selectResp.body());
        assertThat(selectJson.get("status")).isEqualTo("success");
        assertThat(selectJson.get("rowCount")).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) selectJson.get("columns");
        assertThat(cols).containsExactly("id", "name", "price");

        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) selectJson.get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly(1L, "Laptop", 999.99);

        // 4. Raw SQL body (curl style)
        HttpResponse<String> rawResp = sendPost("/v1/query?database=shop_db", "SELECT * FROM products");
        assertThat(rawResp.statusCode()).isEqualTo(200);
        assertThat(Json.parseObject(rawResp.body()).get("rowCount")).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should list databases and tables via metadata endpoints")
    void testMetadataEndpoints() throws Exception {
        // Create table in custom DB
        sendPost("/v1/query", "{\"database\": \"crm\", \"sql\": \"CREATE TABLE customers (id INT, email VARCHAR)\"}");

        // 1. GET /v1/databases
        HttpRequest dbReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/databases"))
                .GET()
                .build();
        HttpResponse<String> dbResp = client.send(dbReq, HttpResponse.BodyHandlers.ofString());
        assertThat(dbResp.statusCode()).isEqualTo(200);
        Map<String, Object> dbJson = Json.parseObject(dbResp.body());
        @SuppressWarnings("unchecked")
        List<String> dbs = (List<String>) dbJson.get("databases");
        assertThat(dbs).contains("crm");

        // 2. GET /v1/tables?database=crm
        HttpRequest tableReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/tables?database=crm"))
                .GET()
                .build();
        HttpResponse<String> tableResp = client.send(tableReq, HttpResponse.BodyHandlers.ofString());
        assertThat(tableResp.statusCode()).isEqualTo(200);
        Map<String, Object> tableJson = Json.parseObject(tableResp.body());
        assertThat(tableJson.get("database")).isEqualTo("crm");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) tableJson.get("tables");
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).get("name")).isEqualTo("customers");
    }

    @Test
    @DisplayName("Should respond to CORS preflight OPTIONS request")
    void testCorsPreflight() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/query"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(204);
        assertThat(resp.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
        assertThat(resp.headers().firstValue("Access-Control-Allow-Methods")).contains("GET, POST, OPTIONS");
    }

    @Test
    @DisplayName("Should return appropriate error response for syntax or schema errors")
    void testErrorHandling() throws Exception {
        // Syntax error
        HttpResponse<String> syntaxErr = sendPost("/v1/query", "{\"sql\": \"SELEC FROM nowhere\"}");
        assertThat(syntaxErr.statusCode()).isEqualTo(400);
        Map<String, Object> syntaxJson = Json.parseObject(syntaxErr.body());
        assertThat(syntaxJson.get("status")).isEqualTo("error");
        assertThat(syntaxJson.get("code")).isEqualTo("PARSE_ERROR");

        // Missing table
        HttpResponse<String> schemaErr = sendPost("/v1/query", "{\"sql\": \"SELECT * FROM non_existent_table\"}");
        assertThat(schemaErr.statusCode()).isEqualTo(400);
        Map<String, Object> schemaJson = Json.parseObject(schemaErr.body());
        assertThat(schemaJson.get("status")).isEqualTo("error");
        assertThat(schemaJson.get("code")).isEqualTo("SCHEMA_ERROR");

        // Method not allowed
        HttpRequest getOnQuery = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/query"))
                .GET()
                .build();
        HttpResponse<String> methodErr = client.send(getOnQuery, HttpResponse.BodyHandlers.ofString());
        assertThat(methodErr.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("Should reject mutating statements when read-only mode is active")
    void testReadOnlyMode() throws Exception {
        server.stop();
        server = new KoraHttpServer(KoraHttpConfig.of("127.0.0.1", 0, true), db);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getPort();

        HttpResponse<String> resp = sendPost("/v1/query", "{\"sql\": \"CREATE TABLE items (id INT)\"}");
        assertThat(resp.statusCode()).isEqualTo(403);
        Map<String, Object> json = Json.parseObject(resp.body());
        assertThat(json.get("status")).isEqualTo("error");
        assertThat(json.get("code")).isEqualTo("READ_ONLY");
    }

    private HttpResponse<String> sendPost(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
