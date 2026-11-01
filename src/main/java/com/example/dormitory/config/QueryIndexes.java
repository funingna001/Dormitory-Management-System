package com.example.dormitory.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.regex.Pattern;

/** Metadata checks support existing MySQL and SQLite schemas without rebuilding tables. */
@Component
public class QueryIndexes {
    private final JdbcTemplate jdbc;

    public QueryIndexes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initialize() throws Exception {
        String script = new ClassPathResource("db/performance-indexes.sql").getContentAsString(StandardCharsets.UTF_8);
        var declaration = Pattern.compile("CREATE INDEX (\\w+) ON (\\w+)\\([\\w, ]+\\)");
        for (String statement : script.split(";")) {
            if (statement.isBlank()) continue;
            var match = declaration.matcher(statement.strip());
            if (!match.matches()) throw new IllegalStateException("Invalid index declaration");
            String index = match.group(1), table = match.group(2);
            if (exists(table, index)) continue;
            try {
                jdbc.execute(statement);
            } catch (org.springframework.dao.DataAccessException failure) {
                // Another application instance may have created the same index concurrently.
                if (!exists(table, index)) throw failure;
            }
        }
    }

    private boolean exists(String table, String index) throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection();
             var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (indexes.next())
                if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
            return false;
        }
    }
}
