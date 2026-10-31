package org.windy.playershelter.adapter.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移 SQL 的<b>方言静态自检</b>——不连数据库，只审生成的 SQL 文本。
 *
 * <p>存在理由：SQLite 集成测试全绿也挡不住 MySQL 专属语法错误。真实事故就是
 * {@code CREATE INDEX IF NOT EXISTS ...} 在 MySQL 上直接 1064，把插件在启动阶段打死。
 * 这里把「MySQL 不认的东西不许出现在 MySQL 分支里」固化成断言。
 */
class MigrationDialectTest {

    private static List<String> allStatements(boolean sqlite) {
        List<String> out = new ArrayList<>();
        for (Migrations.Step step : Migrations.stepsFor(sqlite)) {
            out.addAll(step.statements());
            // 索引/列走 Java 侧存在性判断，不该再生成 DDL 字符串——这里只收集建表语句。
        }
        return out;
    }

    @Test
    @DisplayName("MySQL 分支不出现 CREATE INDEX IF NOT EXISTS（MySQL 8 会 1064）")
    void mysqlHasNoCreateIndexIfNotExists() {
        // 索引已从 SQL 列表里拿出，改由 ensureIndex 先查后建；这条断言防止有人手滑改回去。
        for (String sql : allStatements(false)) {
            assertFalse(sql.toUpperCase().contains("CREATE INDEX"),
                    "MySQL 迁移语句里不应再有 CREATE INDEX: " + sql);
        }
        for (String sql : allStatements(true)) {
            assertFalse(sql.toUpperCase().contains("CREATE INDEX"),
                    "SQLite 侧索引也应走 ensureIndex 统一路径: " + sql);
        }
    }

    @Test
    @DisplayName("MySQL 分支的索引定义为可移植的名字/表/列三元组")
    void mysqlIndicesAreDeclarative() {
        List<Migrations.Step> steps = Migrations.stepsFor(false);
        List<Migrations.Index> indices = new ArrayList<>();
        steps.forEach(step -> indices.addAll(step.indices()));

        assertFalse(indices.isEmpty(), "MySQL 分支必须仍声明索引（只是建法换成先查后建）");
        for (Migrations.Index index : indices) {
            assertTrue(index.name().startsWith("idx_"), "索引名应带 idx_ 前缀: " + index.name());
            assertTrue(index.table().startsWith("ps_"), "索引应落在 ps_ 表上: " + index.table());
        }
        // 每个索引名只能出现一次，否则 ensureIndex 的存在性检查会掩盖重复定义。
        for (Migrations.Index index : indices) {
            long same = indices.stream().filter(i -> i.name().equals(index.name())).count();
            assertTrue(same == 1, "索引名重复: " + index.name());
        }
    }

    @Test
    @DisplayName("MySQL 建表带 utf8mb4，SQLite 不带字符集子句")
    void charsetOnlyOnMysql() {
        for (String sql : allStatements(false)) {
            if (sql.toUpperCase().startsWith("CREATE TABLE")) {
                assertTrue(sql.contains("utf8mb4"), "MySQL 建表应钉 utf8mb4: " + sql);
            }
        }
        for (String sql : allStatements(true)) {
            assertFalse(sql.contains("utf8mb4"), "SQLite 建表不该出现字符集子句: " + sql);
        }
    }

    @Test
    @DisplayName("两端迁移版本号集合一致，且 ALTER TABLE 只出现在 Column 声明里")
    void versionsMatchAcrossDialects() {
        List<Migrations.Step> sqlite = Migrations.stepsFor(true);
        List<Migrations.Step> mysql = Migrations.stepsFor(false);
        assertTrue(sqlite.size() == mysql.size(), "两端迁移步数应一致");
        for (int i = 0; i < sqlite.size(); i++) {
            assertTrue(sqlite.get(i).version() == mysql.get(i).version(), "第 " + i + " 步版本号不一致");
        }
        // ALTER 已改成 ensureColumn，SQL 列表里不该再裸着写（否则 MySQL 重试必炸）。
        for (String sql : allStatements(false)) {
            assertFalse(sql.toUpperCase().startsWith("ALTER TABLE"),
                    "MySQL 侧不该有裸 ALTER：应走 Column 声明: " + sql);
        }
    }

    @Test
    @DisplayName("MySQL 用 BIGINT / AUTO_INCREMENT，SQLite 用 INTEGER / AUTOINCREMENT")
    void typeDialects() {
        String mysqlShelter = allStatements(false).stream()
                .filter(s -> s.contains("ps_shelter")).findFirst().orElseThrow();
        assertTrue(mysqlShelter.contains("BIGINT"), "MySQL 大整数应为 BIGINT");

        String sqliteShelter = allStatements(true).stream()
                .filter(s -> s.contains("ps_shelter")).findFirst().orElseThrow();
        assertFalse(sqliteShelter.contains("BIGINT"), "SQLite 不该用 BIGINT");

        String mysqlMessage = allStatements(false).stream()
                .filter(s -> s.contains("ps_message")).findFirst().orElseThrow();
        assertTrue(mysqlMessage.contains("AUTO_INCREMENT"), "MySQL 自增应为 AUTO_INCREMENT");

        String sqliteMessage = allStatements(true).stream()
                .filter(s -> s.contains("ps_message")).findFirst().orElseThrow();
        assertTrue(sqliteMessage.contains("AUTOINCREMENT"), "SQLite 自增应为 AUTOINCREMENT");
    }
}
