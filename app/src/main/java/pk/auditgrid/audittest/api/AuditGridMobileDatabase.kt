package pk.auditgrid.audittest.api

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import pk.auditgrid.audittest.model.*
import java.security.MessageDigest

/**
 * Local SQLite database on the mobile device for storing server-synced users, roles,
 * user-linked master data (cities, territories, locations, sub-locations, shops,
 * distributors, product groups, products), audit forms (both server-synced and
 * manually imported via Form Package), plans, completed reports, and offline outbox.
 */
class AuditGridMobileDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "auditgrid_mobile.db"
        private const val DATABASE_VERSION = 1

        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                role_id TEXT,
                assigned_city_ids TEXT,
                assigned_territory_ids TEXT,
                assigned_group_ids TEXT,
                status TEXT,
                json_data TEXT NOT NULL,
                synced_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS roles (
                id TEXT PRIMARY KEY,
                code TEXT,
                name TEXT,
                surface TEXT,
                json_data TEXT NOT NULL,
                synced_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_sessions (
                user_id TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                password_hash TEXT,
                session_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cities (
                id TEXT PRIMARY KEY,
                code TEXT,
                name TEXT,
                status TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                json_data TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS territories (
                id TEXT PRIMARY KEY,
                city_id TEXT,
                code TEXT,
                name TEXT,
                status TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                json_data TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS locations (
                id TEXT PRIMARY KEY,
                territory_id TEXT,
                city_id TEXT,
                status TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                json_data TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sub_locations (
                id TEXT PRIMARY KEY,
                location_id TEXT,
                territory_id TEXT,
                city_id TEXT,
                status TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                json_data TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS shops (
                id TEXT PRIMARY KEY,
                sub_location_id TEXT,
                location_id TEXT,
                territory_id TEXT,
                city_id TEXT,
                status TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                json_data TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS distributors (
                id TEXT PRIMARY KEY,
                territory_id TEXT,
                city_id TEXT,
                status TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                json_data TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS product_groups (
                id TEXT PRIMARY KEY,
                status TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                json_data TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS products (
                id TEXT PRIMARY KEY,
                group_id TEXT,
                status TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                json_data TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_data_links (
                user_id TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                PRIMARY KEY (user_id, entity_type, entity_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_sync_meta (
                user_id TEXT PRIMARY KEY,
                maps_api_key TEXT,
                forms_bundle_hash TEXT,
                forms_synced_once INTEGER NOT NULL DEFAULT 0,
                last_synced_at TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audit_forms (
                user_id TEXT NOT NULL,
                form_id TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'server',
                sort_order INTEGER NOT NULL DEFAULT 0,
                form_json TEXT NOT NULL,
                config_json TEXT,
                PRIMARY KEY (user_id, form_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_plans (
                user_id TEXT NOT NULL,
                plan_id TEXT NOT NULL,
                status TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                json_data TEXT NOT NULL,
                PRIMARY KEY (user_id, plan_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_reports (
                user_id TEXT NOT NULL,
                report_kind TEXT NOT NULL,
                report_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                json_data TEXT NOT NULL,
                PRIMARY KEY (user_id, report_kind, report_id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    @Synchronized
    fun saveUserSession(session: Session, passwordPlain: String? = null) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            upsertUserInternal(db, session.user, now)
            session.role?.let { upsertRoleInternal(db, it, now) }

            val existingHash = if (passwordPlain == null) {
                db.rawQuery(
                    "SELECT password_hash FROM user_sessions WHERE user_id = ?",
                    arrayOf(session.user.id)
                ).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                }
            } else {
                sha256(passwordPlain)
            }

            val cv = ContentValues().apply {
                put("user_id", session.user.id)
                put("username", session.user.username.lowercase())
                if (existingHash != null) {
                    put("password_hash", existingHash)
                }
                put("session_json", AuditGridJsonParsers.serializeSession(session).toString())
                put("updated_at", now)
            }
            db.insertWithOnConflict("user_sessions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun upsertUserInternal(db: SQLiteDatabase, u: AppUser, now: Long) {
        val cv = ContentValues().apply {
            put("id", u.id)
            put("username", u.username.lowercase())
            put("role_id", u.roleId)
            put("assigned_city_ids", JSONArray(u.assignedCityIds).toString())
            put("assigned_territory_ids", JSONArray(u.assignedTerritoryIds).toString())
            put("assigned_group_ids", JSONArray(u.assignedGroupIds).toString())
            put("status", u.status)
            put("json_data", AuditGridJsonParsers.serializeUser(u).toString())
            put("synced_at", now)
        }
        db.insertWithOnConflict("users", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun upsertRoleInternal(db: SQLiteDatabase, r: UserRole, now: Long) {
        val cv = ContentValues().apply {
            put("id", r.id)
            put("code", r.code)
            put("name", r.name)
            put("surface", r.surface)
            put("json_data", AuditGridJsonParsers.serializeRole(r).toString())
            put("synced_at", now)
        }
        db.insertWithOnConflict("roles", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun loadSessionByUsername(username: String): Session? {
        val clean = username.trim().lowercase()
        if (clean.isBlank()) return null
        val db = readableDatabase
        val baseSession = db.rawQuery(
            "SELECT session_json FROM user_sessions WHERE LOWER(username) = ? LIMIT 1",
            arrayOf(clean)
        ).use { c ->
            if (c.moveToFirst()) {
                runCatching { AuditGridJsonParsers.parseSession(JSONObject(c.getString(0))) }.getOrNull()
            } else null
        }

        val latestUser = db.rawQuery(
            "SELECT json_data FROM users WHERE LOWER(username) = ? LIMIT 1",
            arrayOf(clean)
        ).use { c ->
            if (c.moveToFirst()) {
                runCatching { AuditGridJsonParsers.parseUser(JSONObject(c.getString(0))) }.getOrNull()
            } else null
        } ?: baseSession?.user ?: return null

        val latestRole = if (latestUser.roleId.isNotBlank()) {
            db.rawQuery(
                "SELECT json_data FROM roles WHERE id = ? LIMIT 1",
                arrayOf(latestUser.roleId)
            ).use { c ->
                if (c.moveToFirst()) {
                    runCatching { AuditGridJsonParsers.parseRole(JSONObject(c.getString(0))) }.getOrNull()
                } else null
            }
        } else null

        return Session(
            user = latestUser,
            role = latestRole ?: baseSession?.role,
            surface = baseSession?.surface ?: "mobile",
            isGlobal = baseSession?.isGlobal ?: false
        )
    }

    @Synchronized
    fun authenticateOfflineUser(username: String, passwordPlain: String): Session? {
        val clean = username.trim().lowercase()
        if (clean.isBlank() || passwordPlain.isEmpty()) return null
        val db = readableDatabase

        // Check if this user has a saved session in user_sessions
        val storedHashAndSession = db.rawQuery(
            "SELECT password_hash, session_json FROM user_sessions WHERE LOWER(username) = ? LIMIT 1",
            arrayOf(clean)
        ).use { c ->
            if (c.moveToFirst()) {
                val hash = if (!c.isNull(0)) c.getString(0) else null
                val json = c.getString(1)
                hash to json
            } else null
        }

        if (storedHashAndSession != null) {
            val (savedHash, _) = storedHashAndSession
            if (!savedHash.isNullOrBlank() && savedHash != sha256(passwordPlain)) {
                throw ApiError("Invalid username or password.", 401)
            }
            return loadSessionByUsername(clean)
        }

        // Also check if the user was downloaded from the server into the `users` table during a previous sync
        return loadSessionByUsername(clean)?.takeIf {
            it.user.status.equals("Active", ignoreCase = true) && it.user.canLoginFromMobile
        }
    }

    /**
     * Saves all server-downloaded users, roles, cities, territories, locations, sub-locations,
     * shops, distributors, product groups, and products to the mobile database, links them to
     * each user, and updates the active user's Session with any server-side changes.
     */
    @Synchronized
    fun saveBootstrapForUser(session: Session, boot: BootstrapPayload): Session {
        val db = writableDatabase
        val now = System.currentTimeMillis()

        val allCities = boot.cities
        val allTerritories = boot.territories

        // Resolve updated active user & role from server bootstrap
        val rawActiveUser = boot.users.find {
            it.id == session.user.id ||
                (it.username.isNotBlank() && it.username.equals(session.user.username, ignoreCase = true))
        } ?: session.user
        val normalizedActiveUser = rawActiveUser.withNormalizedLinks(
            allCities = allCities,
            allTerritories = allTerritories,
            isGlobal = session.isGlobal
        )
        val updatedActiveRole = boot.roles.find { it.id == normalizedActiveUser.roleId } ?: session.role
        val updatedSession = session.copy(
            user = normalizedActiveUser,
            role = updatedActiveRole
        )

        db.beginTransaction()
        try {
            // 1. Replace master tables with authoritative server data so edits/deletions on server sync cleanly
            db.delete("users", null, null)
            val normalizedUsers = boot.users.map { u ->
                val isGlobal = (u.id == normalizedActiveUser.id && session.isGlobal)
                u.withNormalizedLinks(allCities, allTerritories, isGlobal)
            }
            val finalUsers = if (normalizedUsers.any { it.id == normalizedActiveUser.id }) {
                normalizedUsers
            } else {
                normalizedUsers + normalizedActiveUser
            }
            for (u in finalUsers) {
                upsertUserInternal(db, u, now)
            }

            db.delete("roles", null, null)
            val finalRoles = if (updatedActiveRole != null && boot.roles.none { it.id == updatedActiveRole.id }) {
                boot.roles + updatedActiveRole
            } else {
                boot.roles
            }
            for (r in finalRoles) {
                upsertRoleInternal(db, r, now)
            }

            db.delete("cities", null, null)
            allCities.forEachIndexed { idx, c ->
                val cv = ContentValues().apply {
                    put("id", c.id)
                    put("code", c.code)
                    put("name", c.name)
                    put("status", c.status)
                    put("sort_order", idx)
                    put("json_data", AuditGridJsonParsers.serializeCity(c).toString())
                }
                db.insertWithOnConflict("cities", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.delete("territories", null, null)
            allTerritories.forEachIndexed { idx, t ->
                val cv = ContentValues().apply {
                    put("id", t.id)
                    put("city_id", t.cityId)
                    put("code", t.code)
                    put("name", t.name)
                    put("status", t.status)
                    put("sort_order", idx)
                    put("json_data", AuditGridJsonParsers.serializeTerritory(t).toString())
                }
                db.insertWithOnConflict("territories", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.delete("locations", null, null)
            boot.locations.forEachIndexed { idx, l ->
                val cv = ContentValues().apply {
                    put("id", l.id)
                    put("territory_id", l.territoryId)
                    put("city_id", l.cityId)
                    put("status", l.status)
                    put("sort_order", idx)
                    put("json_data", AuditGridJsonParsers.serializeLocation(l).toString())
                }
                db.insertWithOnConflict("locations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.delete("sub_locations", null, null)
            boot.subLocations.forEachIndexed { idx, s ->
                val cv = ContentValues().apply {
                    put("id", s.id)
                    put("location_id", s.locationId)
                    put("territory_id", s.territoryId)
                    put("city_id", s.cityId)
                    put("status", s.status)
                    put("sort_order", idx)
                    put("json_data", AuditGridJsonParsers.serializeSubLocation(s).toString())
                }
                db.insertWithOnConflict("sub_locations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.delete("shops", null, null)
            boot.shops.forEachIndexed { idx, sh ->
                val cv = ContentValues().apply {
                    put("id", sh.id)
                    put("sub_location_id", sh.subLocationId)
                    put("location_id", sh.locationId)
                    put("territory_id", sh.territoryId)
                    put("city_id", sh.cityId)
                    put("status", sh.status)
                    put("sort_order", idx)
                    put("json_data", AuditGridJsonParsers.serializeShop(sh).toString())
                }
                db.insertWithOnConflict("shops", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.delete("distributors", null, null)
            boot.distributors.forEachIndexed { idx, d ->
                val cv = ContentValues().apply {
                    put("id", d.id)
                    put("territory_id", d.territoryId)
                    put("city_id", d.cityId)
                    put("status", d.status)
                    put("sort_order", idx)
                    put("json_data", AuditGridJsonParsers.serializeDistributor(d).toString())
                }
                db.insertWithOnConflict("distributors", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.delete("product_groups", null, null)
            boot.groups.forEachIndexed { idx, g ->
                val cv = ContentValues().apply {
                    put("id", g.id)
                    put("status", g.status)
                    put("sort_order", idx)
                    put("json_data", AuditGridJsonParsers.serializeProductGroup(g).toString())
                }
                db.insertWithOnConflict("product_groups", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.delete("products", null, null)
            boot.products.forEachIndexed { idx, p ->
                val cv = ContentValues().apply {
                    put("id", p.id)
                    put("group_id", p.groupId)
                    put("status", p.status)
                    put("sort_order", idx)
                    put("json_data", AuditGridJsonParsers.serializeProduct(p).toString())
                }
                db.insertWithOnConflict("products", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            // 2. Link all data to each user in `user_data_links`
            db.delete("user_data_links", null, null)
            for (u in finalUsers) {
                val terIds = u.assignedTerritoryIds.toSet()
                val cityIds = u.assignedCityIds.toSet()
                fun link(type: String, entityId: String) {
                    if (entityId.isBlank()) return
                    val linkCv = ContentValues().apply {
                        put("user_id", u.id)
                        put("entity_type", type)
                        put("entity_id", entityId)
                    }
                    db.insertWithOnConflict("user_data_links", null, linkCv, SQLiteDatabase.CONFLICT_IGNORE)
                }
                u.roleId.takeIf { it.isNotBlank() }?.let { link("role", it) }
                cityIds.forEach { link("city", it) }
                terIds.forEach { link("territory", it) }
                boot.locations.filter { terIds.contains(it.territoryId) }.forEach { link("location", it.id) }
                boot.subLocations.filter { terIds.contains(it.territoryId) }.forEach { link("subLocation", it.id) }
                boot.shops.filter { terIds.contains(it.territoryId) }.forEach { link("shop", it.id) }
                boot.distributors.filter { terIds.contains(it.territoryId) }.forEach { link("distributor", it.id) }
                boot.groups.forEach { link("group", it.id) }
                boot.products.forEach { link("product", it.id) }
            }

            // 3. Save sync metadata for the user
            val existingMeta = getUserSyncMetaInternal(db, normalizedActiveUser.id)
            val metaCv = ContentValues().apply {
                put("user_id", normalizedActiveUser.id)
                put("maps_api_key", boot.mapsApiKey.ifBlank { existingMeta?.first ?: "" })
                put("forms_bundle_hash", boot.formsBundleHash.ifBlank { existingMeta?.second ?: "" })
                put("forms_synced_once", if (boot.forms.isNotEmpty() || (existingMeta?.third == true)) 1 else 0)
                put("last_synced_at", pk.auditgrid.audittest.util.currentIsoTimestamp())
            }
            db.insertWithOnConflict("user_sync_meta", null, metaCv, SQLiteDatabase.CONFLICT_REPLACE)

            // 4. Update active user's saved session with latest server user & role
            val existingHash = db.rawQuery(
                "SELECT password_hash FROM user_sessions WHERE user_id = ?",
                arrayOf(normalizedActiveUser.id)
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
            val sessionCv = ContentValues().apply {
                put("user_id", normalizedActiveUser.id)
                put("username", normalizedActiveUser.username.lowercase())
                if (existingHash != null) {
                    put("password_hash", existingHash)
                }
                put("session_json", AuditGridJsonParsers.serializeSession(updatedSession).toString())
                put("updated_at", now)
            }
            db.insertWithOnConflict("user_sessions", null, sessionCv, SQLiteDatabase.CONFLICT_REPLACE)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updatedSession
    }

    private fun getUserSyncMetaInternal(db: SQLiteDatabase, userId: String): Triple<String, String, Boolean>? {
        return db.rawQuery(
            "SELECT maps_api_key, forms_bundle_hash, forms_synced_once FROM user_sync_meta WHERE user_id = ?",
            arrayOf(userId)
        ).use { c ->
            if (c.moveToFirst()) {
                Triple(
                    c.getString(0) ?: "",
                    c.getString(1) ?: "",
                    c.getInt(2) != 0
                )
            } else null
        }
    }

    @Synchronized
    fun loadBootstrapForUser(session: Session): BootstrapPayload? {
        val db = readableDatabase
        val cities = queryJsonList(db, "SELECT json_data FROM cities ORDER BY sort_order ASC", AuditGridJsonParsers::parseCity)
        val territories = queryJsonList(db, "SELECT json_data FROM territories ORDER BY sort_order ASC", AuditGridJsonParsers::parseTerritory)
        val locations = queryJsonList(db, "SELECT json_data FROM locations ORDER BY sort_order ASC", AuditGridJsonParsers::parseLocation)
        val subLocations = queryJsonList(db, "SELECT json_data FROM sub_locations ORDER BY sort_order ASC", AuditGridJsonParsers::parseSubLocation)
        val shops = queryJsonList(db, "SELECT json_data FROM shops ORDER BY sort_order ASC", AuditGridJsonParsers::parseShop)
        val distributors = queryJsonList(db, "SELECT json_data FROM distributors ORDER BY sort_order ASC", AuditGridJsonParsers::parseDistributor)
        val groups = queryJsonList(db, "SELECT json_data FROM product_groups ORDER BY sort_order ASC", AuditGridJsonParsers::parseProductGroup)
        val products = queryJsonList(db, "SELECT json_data FROM products ORDER BY sort_order ASC", AuditGridJsonParsers::parseProduct)
        val users = queryJsonList(db, "SELECT json_data FROM users", AuditGridJsonParsers::parseUser)
        val roles = queryJsonList(db, "SELECT json_data FROM roles", AuditGridJsonParsers::parseRole)

        if (cities.isEmpty() && territories.isEmpty() && users.isEmpty()) {
            return null
        }

        val forms = loadFormsForUser(session.user.id)
        val formConfigs = loadFormConfigsForUser(session.user.id)
        val meta = getUserSyncMetaInternal(db, session.user.id)

        val normalizedUsers = (if (users.isNotEmpty()) users else listOf(session.user)).map { u ->
            val isGlobal = (u.id == session.user.id && session.isGlobal)
            u.withNormalizedLinks(cities, territories, isGlobal)
        }

        return BootstrapPayload(
            cities = cities,
            territories = territories,
            locations = locations,
            subLocations = subLocations,
            shops = shops,
            distributors = distributors,
            groups = groups,
            products = products,
            users = normalizedUsers,
            roles = if (roles.isNotEmpty()) roles else listOfNotNull(session.role),
            forms = forms,
            formConfigs = formConfigs,
            mapsApiKey = meta?.first ?: "",
            formsBundleHash = meta?.second ?: ""
        )
    }

    private fun <T> queryJsonList(
        db: SQLiteDatabase,
        sql: String,
        parser: (JSONObject) -> T,
        args: Array<String>? = null
    ): List<T> {
        val out = mutableListOf<T>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(0) ?: continue
                runCatching { parser(JSONObject(raw)) }.getOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    /**
     * Saves server-synced forms and configs while preserving any forms that were manually added
     * via a Form Package (`source = 'manual_package'`) and are not yet on the server.
     */
    @Synchronized
    fun saveServerFormsForUser(
        userId: String,
        serverForms: List<AuditFormDefinition>,
        serverConfigs: FormConfigMap,
        bundleHash: String = ""
    ): Pair<List<AuditFormDefinition>, FormConfigMap> {
        val db = writableDatabase
        val serverIds = serverForms.map { it.id }.toSet()

        // Load any manually imported forms that are not in the server's list so manual package additions aren't lost
        val manualExtras = mutableListOf<Pair<AuditFormDefinition, FormConfigPayload?>>()
        db.rawQuery(
            "SELECT form_json, config_json FROM audit_forms WHERE user_id = ? AND source = 'manual_package' ORDER BY sort_order ASC",
            arrayOf(userId)
        ).use { c ->
            while (c.moveToNext()) {
                val fJson = c.getString(0) ?: continue
                val cfgJson = if (!c.isNull(1)) c.getString(1) else null
                val def = runCatching { AuditGridJsonParsers.parseFormDefinition(JSONObject(fJson)) }.getOrNull() ?: continue
                if (def.id !in serverIds) {
                    val cfg = cfgJson?.takeIf { it.isNotBlank() }?.let {
                        runCatching { AuditGridJsonParsers.parseFormConfig(JSONObject(it)) }.getOrNull()
                    }
                    manualExtras.add(def to cfg)
                }
            }
        }

        db.beginTransaction()
        try {
            db.delete("audit_forms", "user_id = ?", arrayOf(userId))
            var order = 0
            for (f in serverForms) {
                val cfg = serverConfigs[f.id]
                val cv = ContentValues().apply {
                    put("user_id", userId)
                    put("form_id", f.id)
                    put("source", "server")
                    put("sort_order", order++)
                    put("form_json", AuditGridJsonParsers.serializeFormDefinition(f).toString())
                    put("config_json", cfg?.let { AuditGridJsonParsers.serializeFormConfig(it).toString() })
                }
                db.insertWithOnConflict("audit_forms", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for ((f, cfg) in manualExtras) {
                val cv = ContentValues().apply {
                    put("user_id", userId)
                    put("form_id", f.id)
                    put("source", "manual_package")
                    put("sort_order", order++)
                    put("form_json", AuditGridJsonParsers.serializeFormDefinition(f).toString())
                    put("config_json", cfg?.let { AuditGridJsonParsers.serializeFormConfig(it).toString() })
                }
                db.insertWithOnConflict("audit_forms", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            val existingMeta = getUserSyncMetaInternal(db, userId)
            val metaCv = ContentValues().apply {
                put("user_id", userId)
                put("maps_api_key", existingMeta?.first ?: "")
                put("forms_bundle_hash", bundleHash.ifBlank { existingMeta?.second ?: "" })
                put("forms_synced_once", 1)
                put("last_synced_at", pk.auditgrid.audittest.util.currentIsoTimestamp())
            }
            db.insertWithOnConflict("user_sync_meta", null, metaCv, SQLiteDatabase.CONFLICT_REPLACE)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return loadFormsForUser(userId) to loadFormConfigsForUser(userId)
    }

    /**
     * Merges manually imported Form Package forms and configs into the mobile database for the user.
     */
    @Synchronized
    fun importManualFormBundleForUser(
        userId: String,
        bundle: FormBundlePayload
    ): Pair<List<AuditFormDefinition>, FormConfigMap> {
        val db = writableDatabase
        val existingForms = loadFormsForUser(userId)
        val existingConfigs = loadFormConfigsForUser(userId).toMutableMap()
        val existingSources = mutableMapOf<String, String>()
        db.rawQuery(
            "SELECT form_id, source FROM audit_forms WHERE user_id = ?",
            arrayOf(userId)
        ).use { c ->
            while (c.moveToNext()) {
                existingSources[c.getString(0)] = c.getString(1) ?: "server"
            }
        }

        val importedById = bundle.forms.associateBy { it.id }
        val mergedForms = mutableListOf<AuditFormDefinition>()

        // Update existing forms if present in package, otherwise keep them
        for (ef in existingForms) {
            val updated = importedById[ef.id]
            if (updated != null) {
                mergedForms.add(updated)
                existingSources[ef.id] = "manual_package"
            } else {
                mergedForms.add(ef)
            }
        }
        // Add new forms from package
        val existingIds = existingForms.map { it.id }.toSet()
        for (nf in bundle.forms) {
            if (nf.id !in existingIds) {
                mergedForms.add(nf)
                existingSources[nf.id] = "manual_package"
            }
        }
        existingConfigs.putAll(bundle.formConfigs)

        db.beginTransaction()
        try {
            db.delete("audit_forms", "user_id = ?", arrayOf(userId))
            mergedForms.forEachIndexed { idx, f ->
                val cfg = existingConfigs[f.id]
                val cv = ContentValues().apply {
                    put("user_id", userId)
                    put("form_id", f.id)
                    put("source", existingSources[f.id] ?: "manual_package")
                    put("sort_order", idx)
                    put("form_json", AuditGridJsonParsers.serializeFormDefinition(f).toString())
                    put("config_json", cfg?.let { AuditGridJsonParsers.serializeFormConfig(it).toString() })
                }
                db.insertWithOnConflict("audit_forms", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            val existingMeta = getUserSyncMetaInternal(db, userId)
            val metaCv = ContentValues().apply {
                put("user_id", userId)
                put("maps_api_key", existingMeta?.first ?: "")
                put("forms_bundle_hash", bundle.bundleHash.ifBlank { existingMeta?.second ?: "" })
                put("forms_synced_once", 1)
                put("last_synced_at", pk.auditgrid.audittest.util.currentIsoTimestamp())
            }
            db.insertWithOnConflict("user_sync_meta", null, metaCv, SQLiteDatabase.CONFLICT_REPLACE)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return mergedForms to existingConfigs
    }

    @Synchronized
    fun loadFormsForUser(userId: String): List<AuditFormDefinition> {
        val db = readableDatabase
        return queryJsonList(
            db,
            "SELECT form_json FROM audit_forms WHERE user_id = ? ORDER BY sort_order ASC",
            AuditGridJsonParsers::parseFormDefinition,
            arrayOf(userId)
        )
    }

    @Synchronized
    fun loadFormConfigsForUser(userId: String): FormConfigMap {
        val db = readableDatabase
        val out = mutableMapOf<String, FormConfigPayload>()
        db.rawQuery(
            "SELECT form_id, config_json FROM audit_forms WHERE user_id = ? ORDER BY sort_order ASC",
            arrayOf(userId)
        ).use { c ->
            while (c.moveToNext()) {
                val formId = c.getString(0) ?: continue
                val cfgRaw = if (!c.isNull(1)) c.getString(1) else null
                if (!cfgRaw.isNullOrBlank()) {
                    runCatching { AuditGridJsonParsers.parseFormConfig(JSONObject(cfgRaw)) }.getOrNull()?.let {
                        out[formId] = it
                    }
                }
            }
        }
        return out
    }

    @Synchronized
    fun loadBundleHashForUser(userId: String): String {
        val db = readableDatabase
        return getUserSyncMetaInternal(db, userId)?.second ?: ""
    }

    @Synchronized
    fun saveBundleHashForUser(userId: String, hash: String) {
        val db = writableDatabase
        val existingMeta = getUserSyncMetaInternal(db, userId)
        val cv = ContentValues().apply {
            put("user_id", userId)
            put("maps_api_key", existingMeta?.first ?: "")
            put("forms_bundle_hash", hash)
            put("forms_synced_once", if (existingMeta?.third == true) 1 else 0)
            put("last_synced_at", pk.auditgrid.audittest.util.currentIsoTimestamp())
        }
        db.insertWithOnConflict("user_sync_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun hasSyncedFormsOnce(userId: String): Boolean {
        val db = readableDatabase
        return getUserSyncMetaInternal(db, userId)?.third == true
    }

    @Synchronized
    fun setFormsSyncedOnce(userId: String, synced: Boolean) {
        val db = writableDatabase
        val existingMeta = getUserSyncMetaInternal(db, userId)
        val cv = ContentValues().apply {
            put("user_id", userId)
            put("maps_api_key", existingMeta?.first ?: "")
            put("forms_bundle_hash", existingMeta?.second ?: "")
            put("forms_synced_once", if (synced) 1 else 0)
            put("last_synced_at", pk.auditgrid.audittest.util.currentIsoTimestamp())
        }
        db.insertWithOnConflict("user_sync_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun upsertLocation(l: LocationItem) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", l.id)
            put("territory_id", l.territoryId)
            put("city_id", l.cityId)
            put("status", l.status)
            put("sort_order", 0)
            put("json_data", AuditGridJsonParsers.serializeLocation(l).toString())
        }
        db.insertWithOnConflict("locations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun upsertSubLocation(s: SubLocation) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", s.id)
            put("location_id", s.locationId)
            put("territory_id", s.territoryId)
            put("city_id", s.cityId)
            put("status", s.status)
            put("sort_order", 0)
            put("json_data", AuditGridJsonParsers.serializeSubLocation(s).toString())
        }
        db.insertWithOnConflict("sub_locations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun upsertShop(sh: Shop) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", sh.id)
            put("sub_location_id", sh.subLocationId)
            put("location_id", sh.locationId)
            put("territory_id", sh.territoryId)
            put("city_id", sh.cityId)
            put("status", sh.status)
            put("sort_order", 0)
            put("json_data", AuditGridJsonParsers.serializeShop(sh).toString())
        }
        db.insertWithOnConflict("shops", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun upsertDistributor(d: Distributor) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", d.id)
            put("territory_id", d.territoryId)
            put("city_id", d.cityId)
            put("status", d.status)
            put("sort_order", 0)
            put("json_data", AuditGridJsonParsers.serializeDistributor(d).toString())
        }
        db.insertWithOnConflict("distributors", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun savePlansForUser(userId: String, plans: List<AuditPlan>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("user_plans", "user_id = ?", arrayOf(userId))
            plans.forEachIndexed { idx, p ->
                val cv = ContentValues().apply {
                    put("user_id", userId)
                    put("plan_id", p.id)
                    put("status", p.status)
                    put("sort_order", idx)
                    put("json_data", AuditGridJsonParsers.serializePlan(p).toString())
                }
                db.insertWithOnConflict("user_plans", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun loadPlansForUser(userId: String): List<AuditPlan> {
        val db = readableDatabase
        return queryJsonList(
            db,
            "SELECT json_data FROM user_plans WHERE user_id = ? ORDER BY sort_order ASC",
            AuditGridJsonParsers::parsePlan,
            arrayOf(userId)
        )
    }

    @Synchronized
    fun saveReportItemsForUser(userId: String, kind: String, serializedObjects: List<Pair<String, JSONObject>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("user_reports", "user_id = ? AND report_kind = ?", arrayOf(userId, kind))
            serializedObjects.forEachIndexed { idx, (reportId, obj) ->
                val cv = ContentValues().apply {
                    put("user_id", userId)
                    put("report_kind", kind)
                    put("report_id", reportId.ifBlank { "$kind-$idx" })
                    put("sort_order", idx)
                    put("json_data", obj.toString())
                }
                db.insertWithOnConflict("user_reports", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun <T> loadReportItemsForUser(userId: String, kind: String, parser: (JSONObject) -> T): List<T> {
        val db = readableDatabase
        return queryJsonList(
            db,
            "SELECT json_data FROM user_reports WHERE user_id = ? AND report_kind = ? ORDER BY sort_order ASC",
            parser,
            arrayOf(userId, kind)
        )
    }
}
