package com.ai.assistance.operit.plugins.self

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SelfModuleDatabaseSqlRulesTest {
    private fun rejects(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (error: IllegalStateException) { assertEquals(code, error.message) }
    }

    @Test fun commentsAndLiteralTextDoNotBecomeOperations() {
        SelfModuleSqlRules.query("-- harmless ATTACH comment\nSELECT 'PRAGMA; load_extension()' AS text")
        SelfModuleSqlRules.migration("/* ordinary trigger */ CREATE TRIGGER example AFTER INSERT ON item BEGIN SELECT RAISE(ABORT,'fixture error'); END;")
    }

    @Test fun moduleSqlCannotAttachOrAlterConnectionPolicy() {
        for (sql in listOf("ATTACH DATABASE '/outside.sqlite' AS outside", "/* leading */ PRAGMA foreign_keys=OFF", "VACUUM INTO '/outside.sqlite'")) {
            rejects("SELF_DATABASE_SQL_FORBIDDEN") { SelfModuleSqlRules.migration(sql) }
        }
        rejects("SELF_DATABASE_SQL_FORBIDDEN") { SelfModuleSqlRules.query("SELECT \"load_extension\"('/outside.so')") }
        rejects("SELF_DATABASE_SQL_FORBIDDEN") { SelfModuleSqlRules.migration("CREATE TABLE item(id); ATTACH '/outside' AS other;") }
    }

    @Test fun callersCannotIssueDirectWritesOrChangeMigrationJournal() {
        rejects("SELF_DATABASE_QUERY_READ_ONLY") { SelfModuleSqlRules.query("DELETE FROM item") }
        rejects("SELF_DATABASE_MIGRATION_SQL_FORBIDDEN") { SelfModuleSqlRules.migration("DELETE FROM \"__ails_migrations\"") }
        rejects("SELF_DATABASE_MIGRATION_SQL_FORBIDDEN") { SelfModuleSqlRules.migration("BEGIN TRANSACTION") }
    }

    @Test fun malformedQuotesAndCommentsAreRejected() {
        rejects("SELF_DATABASE_SQL_QUOTE_INVALID") { SelfModuleSqlRules.query("SELECT 'unfinished") }
        rejects("SELF_DATABASE_SQL_COMMENT_INVALID") { SelfModuleSqlRules.query("/* unfinished") }
    }
}
