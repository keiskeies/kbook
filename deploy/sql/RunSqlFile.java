import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读执行一个 .sql 文件（用于没有 mysql 客户端的环境，跑 profile-threshold-audit.sql）。
 *
 * 连接信息只从环境变量读：DB_URL / DB_USER / DB_PASS，不落文件、不进命令行历史。
 * 只允许 SELECT / WITH / SHOW / EXPLAIN / SET，其余语句直接拒绝——审计脚本本来也不需要写权限，
 * 这里把"只读"从口头约定变成执行时的硬拦。
 */
public class RunSqlFile {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java RunSqlFile <file.sql>");
            System.exit(2);
        }
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USER");
        String pass = System.getenv("DB_PASS");
        if (url == null || user == null || pass == null) {
            System.err.println("缺少 DB_URL / DB_USER / DB_PASS 环境变量");
            System.exit(2);
        }

        String text = Files.readString(Path.of(args[0]));
        StringBuilder stripped = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            stripped.append(line).append('\n');
        }

        List<String> statements = new ArrayList<>();
        for (String raw : stripped.toString().split(";")) {
            String sql = raw.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        System.out.println("语句数：" + statements.size());

        try (Connection connection = DriverManager.getConnection(url, user, pass)) {
            int index = 0;
            for (String sql : statements) {
                index++;
                String head = sql.replaceAll("\\s+", " ").trim();
                String kind = head.split(" ")[0].toUpperCase();
                if (!List.of("SELECT", "WITH", "SHOW", "EXPLAIN", "SET").contains(kind)) {
                    System.err.println("[" + index + "] 拒绝执行非只读语句（" + kind + "）：这条审计脚本不需要写权限");
                    System.exit(3);
                }
                try (Statement statement = connection.createStatement()) {
                    if (!statement.execute(sql)) {
                        continue; // SET 之类没有结果集
                    }
                    try (ResultSet rs = statement.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        StringBuilder header = new StringBuilder();
                        for (int col = 1; col <= md.getColumnCount(); col++) {
                            header.append(col > 1 ? " | " : "").append(md.getColumnLabel(col));
                        }
                        System.out.println("\n[" + index + "] " + header);
                        int rows = 0;
                        while (rs.next() && rows < 50) {
                            StringBuilder row = new StringBuilder();
                            for (int col = 1; col <= md.getColumnCount(); col++) {
                                row.append(col > 1 ? " | " : "").append(rs.getString(col));
                            }
                            System.out.println("    " + row);
                            rows++;
                        }
                        if (rows == 0) {
                            System.out.println("    （无行）");
                        }
                    }
                } catch (SQLException e) {
                    System.err.println("[" + index + "] 失败：" + e.getMessage()
                            + "\n    SQL=" + head.substring(0, Math.min(90, head.length())));
                    System.exit(1);
                }
            }
        }
    }
}
