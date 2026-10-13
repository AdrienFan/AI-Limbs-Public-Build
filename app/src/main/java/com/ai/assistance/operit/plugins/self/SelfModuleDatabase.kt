package com.ai.assistance.operit.plugins.self

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import java.io.File
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.Timer
import java.util.TimerTask
import org.json.JSONArray
import org.json.JSONObject

/** Generic module-owned SQLite execution. Business tables and migrations exist only in .ails. */
internal class SelfModuleDatabase(private val store: SelfModuleStore) {
    private data class Migration(val version: Int, val checksum: String, val statements: List<String>)
    private data class Contract(val name: String, val version: Int, val migrations: List<Migration>)

    private fun json(bytes: ByteArray): JSONObject {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return JSONObject(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
    }

    private fun contract(resources: Map<String, ByteArray>): Contract? {
        val bytes = resources["resources/storage/database.json"] ?: return null
        val value = json(bytes)
        check(value.getInt("api_version") == 1 && value.getString("engine") == "sqlite") { "SELF_DATABASE_PROTOCOL_INVALID" }
        val name = value.getString("database_name")
        check(Regex("^[a-z][a-z0-9_-]{0,63}\\.sqlite3$").matches(name)) { "SELF_DATABASE_NAME_INVALID" }
        val version = value.getInt("schema_version")
        check(version in 1..64) { "SELF_DATABASE_SCHEMA_INVALID" }
        val entries = value.getJSONArray("migrations")
        check(entries.length() == version) { "SELF_DATABASE_MIGRATION_SEQUENCE_INVALID" }
        val migrations = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            check(entry.getInt("version") == index + 1) { "SELF_DATABASE_MIGRATION_SEQUENCE_INVALID" }
            val resource = entry.getString("resource")
            check(Regex("^resources/storage/migrations/[0-9]{3}\\.json$").matches(resource)) { "SELF_DATABASE_RESOURCE_INVALID" }
            val data = resources[resource] ?: error("SELF_DATABASE_MIGRATION_MISSING")
            check(data.size in 1..512 * 1024) { "SELF_DATABASE_MIGRATION_TOO_LARGE" }
            val migration = json(data)
            check(migration.getInt("version") == index + 1) { "SELF_DATABASE_MIGRATION_VERSION_INVALID" }
            val statements = migration.getJSONArray("statements")
            check(statements.length() in 1..2048) { "SELF_DATABASE_STATEMENTS_INVALID" }
            val sql = (0 until statements.length()).map { statements.getString(it).also(SelfModuleSqlRules::migration) }
            val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            Migration(index + 1, hash, sql)
        }
        return Contract(name, version, migrations)
    }

    private fun path(directory: File, contract: Contract): File {
        val parent = File(directory, "storage").canonicalFile
        check(parent.parentFile == directory.canonicalFile) { "SELF_DATABASE_PATH_INVALID" }
        val file = File(parent, contract.name).canonicalFile
        check(file.parentFile == parent) { "SELF_DATABASE_PATH_INVALID" }
        return file
    }

    private fun open(file: File, readOnly: Boolean): SQLiteDatabase {
        val flags = SQLiteDatabase.NO_LOCALIZED_COLLATORS or
            (if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
        return SQLiteDatabase.openDatabase(file.absolutePath, null, flags).also { database ->
            try {
                database.setForeignKeyConstraintsEnabled(true)
                if (!readOnly) {
                    // Close every connection under the lifecycle lock: exports never race a WAL writer.
                    database.disableWriteAheadLogging()
                    database.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                        check(cursor.moveToFirst() && cursor.getString(0).equals("delete", true)) { "SELF_DATABASE_JOURNAL_INVALID" }
                    }
                }
            } catch (error: Exception) { database.close(); throw error }
        }
    }

    private fun verify(database: SQLiteDatabase, metadata: JSONObject, contract: Contract) {
        check(database.version in 1..contract.version) { "SELF_DATABASE_SCHEMA_INCOMPATIBLE" }
        database.rawQuery("SELECT identity_id FROM __ails_identity WHERE singleton=1", null).use { cursor ->
            // Controlled migration allocates a new target-local data_id; module identity remains stable.
            check(cursor.moveToFirst() && cursor.getString(0) == metadata.getString("identity_id")) { "SELF_DATABASE_IDENTITY_MISMATCH" }
        }
        database.rawQuery("SELECT version,checksum FROM __ails_migrations ORDER BY version", null).use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                check(index < database.version && cursor.getInt(0) == index + 1 &&
                    cursor.getString(1) == contract.migrations[index].checksum) { "SELF_DATABASE_MIGRATION_CHANGED" }
                index++
            }
            check(index == database.version) { "SELF_DATABASE_MIGRATION_HISTORY_INVALID" }
        }
    }

    private fun names(database: SQLiteDatabase, type: String): JSONArray {
        val result = JSONArray()
        database.rawQuery("SELECT name FROM sqlite_master WHERE type=? AND name NOT GLOB 'sqlite_*' AND name NOT GLOB '__ails_*' ORDER BY name", arrayOf(type)).use { cursor ->
            while (cursor.moveToNext()) result.put(cursor.getString(0))
        }
        return result
    }

    private fun info(metadata: JSONObject, file: File, contract: Contract, database: SQLiteDatabase?): JSONObject {
        val result = JSONObject().put("success", true).put("declared", true).put("engine", "sqlite")
            .put("identity_id", metadata.getString("identity_id")).put("data_id", metadata.getString("data_id"))
            .put("module_version", metadata.getString("module_version")).put("database_path", file.absolutePath)
            .put("relative_path", "storage/${contract.name}").put("declared_schema_version", contract.version)
            .put("initialized", database != null)
        if (database != null) {
            verify(database, metadata, contract)
            result.put("schema_version", database.version).put("tables", names(database, "table"))
                .put("views", names(database, "view")).put("triggers", names(database, "trigger"))
            database.rawQuery("PRAGMA foreign_keys", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 1) { "SELF_DATABASE_FOREIGN_KEYS_DISABLED" }
            }
            result.put("foreign_keys_enabled", true)
        }
        return result
    }

    fun describe(): JSONObject = store.withActiveData { metadata, directory, resources ->
        val contract = contract(resources)
        if (contract == null) JSONObject().put("success", true).put("declared", false).put("initialized", false)
        else {
            val file = path(directory, contract)
            if (!file.exists()) info(metadata, file, contract, null)
            else open(file, true).use { info(metadata, file, contract, it) }
        }
    }

    fun initialize(): JSONObject = store.withActiveData { metadata, directory, resources ->
        val contract = contract(resources) ?: error("SELF_DATABASE_NOT_DECLARED")
        val file = path(directory, contract)
        check(file.parentFile.mkdirs() || file.parentFile.isDirectory) { "SELF_DATABASE_DIRECTORY_UNAVAILABLE" }
        open(file, false).use { database ->
            database.beginTransaction()
            try {
                if (database.version == 0) {
                    check(names(database, "table").length() == 0) { "SELF_DATABASE_UNVERSIONED_CONTENT" }
                    database.execSQL("CREATE TABLE __ails_identity(singleton INTEGER NOT NULL PRIMARY KEY CHECK(singleton=1),identity_id TEXT NOT NULL,created_data_id TEXT NOT NULL)")
                    database.execSQL("CREATE TABLE __ails_migrations(version INTEGER NOT NULL PRIMARY KEY,checksum TEXT NOT NULL,applied_at_ms INTEGER NOT NULL)")
                    database.execSQL("INSERT INTO __ails_identity VALUES(1,?,?)", arrayOf(metadata.getString("identity_id"),metadata.getString("data_id")))
                } else verify(database, metadata, contract)
                for (migration in contract.migrations.filter { it.version > database.version }) {
                    migration.statements.forEach(database::execSQL)
                    database.execSQL("INSERT INTO __ails_migrations VALUES(?,?,?)", arrayOf<Any>(migration.version,migration.checksum,System.currentTimeMillis()))
                    database.version = migration.version
                }
                database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    check(!cursor.moveToFirst()) { "SELF_DATABASE_FOREIGN_KEY_CHECK_FAILED" }
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
            info(metadata, file, contract, database)
        }
    }

    fun query(args: JSONObject): JSONObject = store.withActiveData { metadata, directory, resources ->
        val contract = contract(resources) ?: error("SELF_DATABASE_NOT_DECLARED")
        val file = path(directory, contract)
        check(file.isFile) { "SELF_DATABASE_NOT_INITIALIZED" }
        val sql = args.getString("sql").also(SelfModuleSqlRules::query)
        val input = if (args.has("bindings")) args.getJSONArray("bindings") else JSONArray()
        check(input.length() <= 128) { "SELF_DATABASE_BINDINGS_TOO_LARGE" }
        val bindings = (0 until input.length()).map { if (input.isNull(it)) null else input.getString(it) }.toTypedArray()
        val limit = if (args.has("limit")) args.getInt("limit") else 100
        check(limit in 1..200) { "SELF_DATABASE_LIMIT_INVALID" }
        val signal = CancellationSignal()
        val timer = Timer("self-database-read", true)
        try {
            timer.schedule(object : TimerTask() { override fun run() { signal.cancel() } }, 5000L)
            open(file, true).use { database ->
                verify(database, metadata, contract)
                check(database.version == contract.version) { "SELF_DATABASE_INITIALIZATION_REQUIRED" }
                database.rawQuery(sql, bindings, signal).use { cursor ->
                    val rows = JSONArray()
                    var more = false
                    while (cursor.moveToNext()) {
                        if (rows.length() == limit) { more = true; break }
                        val row = JSONArray()
                        for (column in 0 until cursor.columnCount) row.put(when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(cursor.getBlob(column))
                            else -> cursor.getString(column)
                        })
                        rows.put(row)
                        check(rows.toString().length <= 64 * 1024) { "SELF_DATABASE_QUERY_RESPONSE_TOO_LARGE" }
                    }
                    JSONObject().put("success", true).put("columns", JSONArray(cursor.columnNames.toList()))
                        .put("rows", rows).put("truncated", more).put("schema_version", database.version)
                        .put("identity_id", metadata.getString("identity_id")).put("data_id", metadata.getString("data_id"))
                }
            }
        } finally { timer.cancel() }
    }
}

/** SQL supplied by the module cannot attach host databases or change connection policy. */
internal object SelfModuleSqlRules {
    private val forbidden = setOf("ATTACH","DETACH","PRAGMA","VACUUM","LOAD_EXTENSION")
    private fun tokens(sql: String): List<String> {
        check(sql.length in 1..128 * 1024) { "SELF_DATABASE_SQL_SIZE_INVALID" }
        val tokens = mutableListOf<String>()
        var position = 0
        while (position < sql.length) {
            val character = sql[position]
            when {
                sql.startsWith("--", position) -> { val end = sql.indexOf('\n', position + 2); position = if (end < 0) sql.length else end + 1 }
                sql.startsWith("/*", position) -> { val end = sql.indexOf("*/", position + 2); check(end >= 0) { "SELF_DATABASE_SQL_COMMENT_INVALID" }; position = end + 2 }
                character == '\'' || character == '"' || character == '`' || character == '[' -> {
                    val endQuote = if (character == '[') ']' else character
                    val content = StringBuilder()
                    position++
                    var closed = false
                    while (position < sql.length) {
                        val next = sql[position++]
                        if (next == endQuote) {
                            if (position < sql.length && sql[position] == endQuote) { content.append(next); position++ }
                            else { closed = true; break }
                        } else content.append(next)
                    }
                    check(closed) { "SELF_DATABASE_SQL_QUOTE_INVALID" }
                    if (character != '\'') tokens.add(content.toString().uppercase(java.util.Locale.ROOT))
                }
                character.isLetter() || character == '_' -> {
                    val start = position++
                    while (position < sql.length && (sql[position].isLetterOrDigit() || sql[position] == '_')) position++
                    tokens.add(sql.substring(start,position).uppercase(java.util.Locale.ROOT))
                }
                else -> position++
            }
        }
        check(tokens.isNotEmpty() && tokens.none { it in forbidden }) { "SELF_DATABASE_SQL_FORBIDDEN" }
        return tokens
    }
    fun migration(sql: String) {
        val words = tokens(sql)
        check(words.first() in setOf("CREATE","INSERT","UPDATE","DELETE","ALTER","DROP") && words.none { it.startsWith("__AILS_") }) { "SELF_DATABASE_MIGRATION_SQL_FORBIDDEN" }
    }
    fun query(sql: String) {
        check(tokens(sql).first() in setOf("SELECT","WITH")) { "SELF_DATABASE_QUERY_READ_ONLY" }
        // The native database is additionally opened OPEN_READONLY; WITH cannot smuggle a write.
    }
}
