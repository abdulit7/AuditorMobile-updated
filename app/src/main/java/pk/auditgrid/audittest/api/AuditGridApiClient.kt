package pk.auditgrid.audittest.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pk.auditgrid.audittest.BuildConfig
import pk.auditgrid.audittest.model.*
import pk.auditgrid.audittest.util.dataUrlToBytes
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ApiError(
    override val message: String,
    val status: Int,
    val code: String? = null
) : Exception(message) {
    val isNetwork: Boolean get() = status == 0
}

class AuditGridApiClient(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("auditgrid_prefs", Context.MODE_PRIVATE)

    val mobileDb: AuditGridMobileDatabase = AuditGridMobileDatabase(context.applicationContext)

    @Volatile
    private var accessToken: String? = null
    private val refreshMutex = Mutex()
    var onSessionEnded: (() -> Unit)? = null

    companion object {
        private const val BASE_KEY = "ag.apiBase"
        private const val RT_KEY = "ag.refreshToken"
        private const val SESSION_USER_KEY = "ag.session.username"
        private const val SESSION_USER_ID_KEY = "ag.session.userId"
        private const val LOCAL_SESSION_KEY = "ag.session.local"
        private const val LOGGED_OUT_KEY = "ag.session.loggedOut"

        fun normalizeBase(v: String): String = v.trim().trimEnd('/')
    }

    init {
        // Purge any legacy dummy or fake local fallback data from earlier builds
        val wasFakeLocalSession = prefs.getBoolean(LOCAL_SESSION_KEY, false)
        val savedUserId = prefs.getString(SESSION_USER_ID_KEY, "") ?: ""
        if (wasFakeLocalSession ||
            savedUserId.startsWith("usr-local-") ||
            prefs.contains("ag.demoMode") ||
            prefs.contains("ag.demoSeeded") ||
            prefs.contains("ag.completed.dist.usr-demo-auditor-01") ||
            prefs.contains("ag.completed.retail.usr-demo-auditor-01") ||
            prefs.contains("ag.completed.custom.usr-demo-auditor-01")
        ) {
            val ed = prefs.edit()
                .remove(LOCAL_SESSION_KEY)
                .remove("ag.demoMode")
                .remove("ag.demoSeeded")
                .remove("ag.demo.webCreatedCount")
                .remove("ag.completed.dist.usr-demo-auditor-01")
                .remove("ag.completed.retail.usr-demo-auditor-01")
                .remove("ag.completed.custom.usr-demo-auditor-01")
            if (wasFakeLocalSession || savedUserId.startsWith("usr-local-")) {
                ed.remove(SESSION_USER_KEY)
                ed.remove(SESSION_USER_ID_KEY)
                ed.remove(RT_KEY)
            }
            ed.apply()
        }
    }

    private fun currentAuditorId(): String =
        prefs.getString(SESSION_USER_ID_KEY, null)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(SESSION_USER_KEY, null)?.let { mobileDb.loadSessionByUsername(it)?.user?.id }
            ?: ""

    fun getApiBase(): String {
        val stored = prefs.getString(BASE_KEY, null)
        if (!stored.isNullOrBlank() && !stored.startsWith("demo://")) return normalizeBase(stored)
        val envUrl = try {
            val field = BuildConfig::class.java.getDeclaredField("VITE_API_URL")
            field.get(null) as? String ?: ""
        } catch (_: Exception) {
            ""
        }
        return normalizeBase(envUrl).ifBlank { "http://192.168.1.20:3001" }
    }

    fun getConfiguredServerUrl(): String = getApiBase()

    fun setApiBase(v: String) {
        val normalized = normalizeBase(v)
        if (normalized.isNotBlank()) {
            prefs.edit().putString(BASE_KEY, normalized).apply()
        }
    }

    fun hasStoredSession(): Boolean {
        if (prefs.getBoolean(LOGGED_OUT_KEY, false)) return false
        val savedUsername = prefs.getString(SESSION_USER_KEY, null)
        if (!savedUsername.isNullOrBlank() && mobileDb.loadSessionByUsername(savedUsername) != null) {
            return true
        }
        return !prefs.getString(RT_KEY, null).isNullOrBlank() && getConfiguredServerUrl().isNotBlank()
    }

    private fun applySession(p: JSONObject, passwordPlain: String? = null): Session {
        accessToken = p.optString("accessToken").takeIf { it.isNotEmpty() }
        val rt = p.optString("refreshToken")
        val userObj = p.optJSONObject("user") ?: JSONObject()
        val parsedUser = AuditGridJsonParsers.parseUser(userObj)
        val roleObj = p.optJSONObject("role")
        val session = Session(
            user = parsedUser,
            role = roleObj?.let { AuditGridJsonParsers.parseRole(it) },
            surface = p.optString("surface", "mobile"),
            isGlobal = p.optBoolean("isGlobal", false)
        )
        val ed = prefs.edit()
            .putBoolean(LOCAL_SESSION_KEY, false)
            .putBoolean(LOGGED_OUT_KEY, false)
            .putString(SESSION_USER_KEY, parsedUser.username)
            .putString(SESSION_USER_ID_KEY, parsedUser.id)
        if (rt.isNotEmpty()) {
            ed.putString(RT_KEY, rt)
        }
        ed.apply()
        mobileDb.saveUserSession(session, passwordPlain)
        return session
    }

    suspend fun checkServer(base: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("${normalizeBase(base)}/api/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                json.optBoolean("ok", false)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun sendRaw(
        path: String,
        method: String = "GET",
        jsonBody: Any? = null
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val base = getConfiguredServerUrl()
        if (base.isBlank()) {
            throw ApiError("Server address is not configured.", 0)
        }
        try {
            val url = URL(base + path)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                if (method == "PATCH") {
                    try {
                        requestMethod = "PATCH"
                    } catch (_: Exception) {
                        requestMethod = "POST"
                        setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    }
                } else {
                    requestMethod = method
                }
                val token = accessToken
                if (!token.isNullOrEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = jsonBody.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            status to text
        } catch (e: ApiError) {
            throw e
        } catch (_: Exception) {
            throw ApiError(
                "Cannot reach the server. Check the server address, your Wi-Fi/mobile data, and that the server is running.",
                0
            )
        }
    }

    private fun readError(status: Int, body: String): ApiError {
        return try {
            val j = JSONObject(body)
            val err = j.optString("error").ifEmpty { "Request failed ($status)." }
            val code = j.optString("code").takeIf { it.isNotEmpty() }
            ApiError(err, status, code)
        } catch (_: Exception) {
            ApiError("Request failed ($status).", status)
        }
    }

    suspend fun refreshSession(): Session? = refreshMutex.withLock {
        val savedUser = prefs.getString(SESSION_USER_KEY, null)
        val token = prefs.getString(RT_KEY, null)
        if (token.isNullOrBlank()) {
            return@withLock savedUser?.let { mobileDb.loadSessionByUsername(it) }
        }
        try {
            val payload = JSONObject().apply {
                put("client", "native")
                put("refreshToken", token)
            }
            val (status, body) = sendRaw("/api/auth/refresh", "POST", payload)
            if (status !in 200..299) {
                accessToken = null
                if (status == 401) {
                    prefs.edit().remove(RT_KEY).apply()
                    return@withLock null
                }
                return@withLock savedUser?.let { mobileDb.loadSessionByUsername(it) }
            }
            applySession(JSONObject(body))
        } catch (e: ApiError) {
            if (e.isNetwork) {
                if (!savedUser.isNullOrBlank()) {
                    val cached = mobileDb.loadSessionByUsername(savedUser)
                    if (cached != null) return@withLock cached
                }
                throw e
            }
            null
        } catch (_: Exception) {
            savedUser?.let { mobileDb.loadSessionByUsername(it) }
        }
    }

    suspend fun requestJson(
        path: String,
        method: String = "GET",
        jsonBody: Any? = null
    ): String {
        var (status, body) = sendRaw(path, method, jsonBody)
        if (status == 401 && !path.startsWith("/api/auth/")) {
            val refreshed = runCatching { refreshSession() }.getOrNull()
            if (refreshed != null && !accessToken.isNullOrEmpty()) {
                val retry = sendRaw(path, method, jsonBody)
                status = retry.first
                body = retry.second
            } else {
                accessToken = null
                onSessionEnded?.invoke()
                throw ApiError("Your session has ended. Please sign in again.", 401, "unauthenticated")
            }
        }
        if (status !in 200..299) {
            throw readError(status, body)
        }
        return body
    }

    suspend fun login(username: String, password: String): Session {
        val cleanUser = username.trim()
        val payload = JSONObject().apply {
            put("username", cleanUser)
            put("password", password)
            put("surface", "mobile")
            put("client", "native")
        }
        return try {
            val (status, body) = sendRaw("/api/auth/login", "POST", payload)
            if (status !in 200..299) throw readError(status, body)
            applySession(JSONObject(body), passwordPlain = password)
        } catch (e: ApiError) {
            if (e.isNetwork) {
                // Only allow offline login if this user was previously downloaded from the server into mobileDb
                val offlineSession = mobileDb.authenticateOfflineUser(cleanUser, password)
                if (offlineSession != null) {
                    prefs.edit()
                        .putBoolean(LOCAL_SESSION_KEY, false)
                        .putBoolean(LOGGED_OUT_KEY, false)
                        .putString(SESSION_USER_KEY, offlineSession.user.username)
                        .putString(SESSION_USER_ID_KEY, offlineSession.user.id)
                        .apply()
                    return offlineSession
                }
                throw ApiError(
                    "Cannot reach the server (${getConfiguredServerUrl()}). Please connect to the server to sign in and download your user account, role, cities, territories, and forms.",
                    0
                )
            }
            throw e
        }
    }

    suspend fun restoreSession(): Session? {
        if (prefs.getBoolean(LOGGED_OUT_KEY, false)) {
            return null
        }
        val savedUser = prefs.getString(SESSION_USER_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        if (!hasStoredSession()) {
            return null
        }
        return try {
            refreshSession() ?: mobileDb.loadSessionByUsername(savedUser)
        } catch (_: Exception) {
            mobileDb.loadSessionByUsername(savedUser)
        }
    }

    suspend fun logout() {
        val token = prefs.getString(RT_KEY, null)
        if (!token.isNullOrEmpty()) {
            try {
                val payload = JSONObject().apply {
                    put("client", "native")
                    put("refreshToken", token)
                }
                sendRaw("/api/auth/logout", "POST", payload)
            } catch (_: Exception) {
            }
        }
        accessToken = null
        prefs.edit()
            .remove(RT_KEY)
            .remove(SESSION_USER_KEY)
            .remove(SESSION_USER_ID_KEY)
            .putBoolean(LOCAL_SESSION_KEY, false)
            .putBoolean(LOGGED_OUT_KEY, true)
            .apply()
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val payload = JSONObject().apply {
            put("currentPassword", currentPassword)
            put("newPassword", newPassword)
        }
        requestJson("/api/auth/change-password", "POST", payload)
    }

    /**
     * Downloads users, roles, cities, territories, locations, sub-locations, shops,
     * distributors, product groups, products, and forms from the server, links them
     * to each user, and saves everything into `AuditGridMobileDatabase`.
     */
    suspend fun fetchBootstrap(session: Session): BootstrapPayload {
        return try {
            val body = requestJson("/api/bootstrap")
            val parsed = AuditGridJsonParsers.parseBootstrap(JSONObject(body), session)
            val updatedSession = withContext(Dispatchers.IO) {
                mobileDb.saveBootstrapForUser(session, parsed)
            }
            prefs.edit()
                .putString(SESSION_USER_KEY, updatedSession.user.username)
                .putString(SESSION_USER_ID_KEY, updatedSession.user.id)
                .apply()
            parsed
        } catch (e: ApiError) {
            if (e.isNetwork) {
                withContext(Dispatchers.IO) {
                    mobileDb.loadBootstrapForUser(session)
                } ?: throw e
            } else {
                throw e
            }
        }
    }

    fun getUpdatedSessionFromMobileDb(currentSession: Session): Session {
        return mobileDb.loadSessionByUsername(currentSession.user.username) ?: currentSession
    }

    suspend fun fetchForms(): List<AuditFormDefinition> {
        return try {
            val body = requestJson("/api/forms")
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { AuditGridJsonParsers.parseFormDefinition(it) }
            }
        } catch (e: ApiError) {
            if (e.isNetwork) {
                val uid = currentAuditorId()
                if (uid.isNotBlank()) mobileDb.loadFormsForUser(uid) else emptyList()
            } else {
                throw e
            }
        }
    }

    suspend fun fetchFormBundle(): FormBundlePayload {
        val body = requestJson("/api/forms/bundle")
        return AuditGridJsonParsers.parseFormBundle(JSONObject(body))
    }

    /**
     * Imports a Form Package (.json) manually and merges its forms & field configs
     * into the user's mobile SQLite database (`AuditGridMobileDatabase`).
     */
    fun importFormBundleJson(auditorId: String, rawJson: String): FormBundlePayload {
        val parsedBundle = AuditGridJsonParsers.parseFormBundleFromRawJson(rawJson)
        if (parsedBundle.forms.isEmpty()) {
            throw IllegalArgumentException("Form package JSON does not contain any audit forms.")
        }
        // Ensure every imported form has a default config entry if not explicitly provided
        val enrichedConfigs = parsedBundle.formConfigs.toMutableMap()
        for (f in parsedBundle.forms) {
            if (!enrichedConfigs.containsKey(f.id)) {
                enrichedConfigs[f.id] = FormConfigPayload(formId = f.id, version = f.version.coerceAtLeast(1))
            }
        }
        val normalizedBundle = parsedBundle.copy(formConfigs = enrichedConfigs)
        val (mergedForms, mergedConfigs) = mobileDb.importManualFormBundleForUser(auditorId, normalizedBundle)
        return normalizedBundle.copy(
            forms = mergedForms,
            formConfigs = mergedConfigs
        )
    }

    fun saveServerSyncedForms(
        auditorId: String,
        serverForms: List<AuditFormDefinition>,
        serverConfigs: FormConfigMap,
        bundleHash: String = ""
    ): Pair<List<AuditFormDefinition>, FormConfigMap> {
        return mobileDb.saveServerFormsForUser(auditorId, serverForms, serverConfigs, bundleHash)
    }

    fun loadCachedBundleHash(auditorId: String): String =
        mobileDb.loadBundleHashForUser(auditorId)

    fun saveCachedBundleHash(auditorId: String, hash: String) {
        mobileDb.saveBundleHashForUser(auditorId, hash)
    }

    suspend fun createLocation(location: LocationItem): LocationItem {
        val payload = AuditGridJsonParsers.serializeLocation(location)
        val res = requestJson("/api/locations", "POST", payload)
        val saved = AuditGridJsonParsers.parseLocation(JSONObject(res))
        withContext(Dispatchers.IO) {
            mobileDb.upsertLocation(saved)
        }
        return saved
    }

    suspend fun createSubLocation(subLocation: SubLocation): SubLocation {
        val payload = AuditGridJsonParsers.serializeSubLocation(subLocation)
        val res = requestJson("/api/sub-locations", "POST", payload)
        val saved = AuditGridJsonParsers.parseSubLocation(JSONObject(res))
        withContext(Dispatchers.IO) {
            mobileDb.upsertSubLocation(saved)
        }
        return saved
    }

    suspend fun createShop(shop: Shop): Shop {
        val payload = AuditGridJsonParsers.serializeShop(shop)
        val res = requestJson("/api/shops", "POST", payload)
        val saved = AuditGridJsonParsers.parseShop(JSONObject(res))
        withContext(Dispatchers.IO) {
            mobileDb.upsertShop(saved)
        }
        return saved
    }

    suspend fun updatePlaceGps(
        targetType: String,
        targetId: String,
        lat: Double,
        lng: Double,
        accuracy: Double
    ): JSONObject {
        val payload = JSONObject().apply {
            put("targetType", targetType)
            put("targetId", targetId)
            put("lat", lat)
            put("lng", lng)
            put("accuracy", accuracy)
        }
        val res = requestJson("/api/mobile/gps", "PATCH", payload)
        return JSONObject(res)
    }

    suspend fun fetchFormConfig(formId: String): FormConfigPayload {
        return try {
            val body = requestJson("/api/forms/$formId/config")
            AuditGridJsonParsers.parseFormConfig(JSONObject(body))
        } catch (e: ApiError) {
            if (e.isNetwork) {
                val uid = currentAuditorId()
                val cached = if (uid.isNotBlank()) mobileDb.loadFormConfigsForUser(uid)[formId] else null
                cached ?: FormConfigPayload(formId = formId)
            } else {
                throw e
            }
        }
    }

    suspend fun fetchDistributorReports(): List<DistributorProductAuditReport> {
        val body = requestJson("/api/reports/distributor")
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { AuditGridJsonParsers.parseDistributorReport(it) }
        }
    }

    suspend fun fetchRetailReports(): List<RetailMarketShopAuditReport> {
        val body = requestJson("/api/reports/retail")
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { AuditGridJsonParsers.parseRetailReport(it) }
        }
    }

    suspend fun fetchCustomSubmissions(): List<CustomAuditSubmission> {
        val body = requestJson("/api/submissions")
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { AuditGridJsonParsers.parseCustomSubmission(it) }
        }
    }

    suspend fun uploadPhotoDataUrl(dataUrl: String): String = withContext(Dispatchers.IO) {
        if (dataUrl.startsWith("/api/photos/")) {
            return@withContext dataUrl.substringAfterLast('/')
        }
        val (mime, bytes) = dataUrlToBytes(dataUrl)
        val boundary = "----AuditGridBoundary${UUID.randomUUID()}"
        val base = getApiBase()

        fun doUpload(): Pair<Int, String> {
            val url = URL("$base/api/photos")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                doOutput = true
                requestMethod = "POST"
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"photo\"; filename=\"photo.jpg\"\r\n")
                out.writeBytes("Content-Type: $mime\r\n\r\n")
                out.write(bytes)
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return status to text
        }

        var (status, body) = try {
            doUpload()
        } catch (_: Exception) {
            throw ApiError("Could not upload photo. Check your network connection.", 0)
        }
        if (status == 401) {
            val s = runCatching { refreshSession() }.getOrNull()
            if (s != null) {
                val retry = doUpload()
                status = retry.first
                body = retry.second
            }
        }
        if (status !in 200..299) throw readError(status, body)
        JSONObject(body).optString("id")
    }

    suspend fun submitDistributorReport(report: DistributorProductAuditReport): DistributorProductAuditReport {
        val photosArr = JSONArray()
        for (img in report.capturedImages) {
            val photoId = uploadPhotoDataUrl(img.url)
            photosArr.put(JSONObject().apply {
                put("photoId", photoId)
                put("caption", img.caption)
            })
        }
        val itemsArr = JSONArray()
        for (item in report.items) {
            itemsArr.put(JSONObject().apply {
                put("productId", item.productId)
                put("qtyInCtn", item.qtyInCtn)
                put("qtyInBox", item.qtyInBox)
                put("dispatchDate", item.dispatchDate)
                put("stacking", item.stacking)
                put("remarks", item.remarks)
            })
        }
        val payload = JSONObject().apply {
            put("clientRequestId", report.id)
            put("distributorId", report.distributorId)
            put("items", itemsArr)
            put("mobileLat", report.mobileLat)
            put("mobileLng", report.mobileLng)
            put("mobileAccuracyMeters", report.mobileAccuracyMeters)
            put("photos", photosArr)
            report.overallComments?.let { put("overallComments", it) }
            report.planId?.let { put("planId", it) }
            report.planItemId?.let { put("planItemId", it) }
        }
        val res = requestJson("/api/reports/distributor", "POST", payload)
        return AuditGridJsonParsers.parseDistributorReport(JSONObject(res))
    }

    suspend fun submitRetailShopReport(report: RetailMarketShopAuditReport): RetailMarketShopAuditReport {
        val shopAuditsArr = JSONArray()
        for (sa in report.shopAudits) {
            val photosArr = JSONArray()
            for (img in sa.capturedImages) {
                val photoId = uploadPhotoDataUrl(img.url)
                photosArr.put(JSONObject().apply {
                    put("photoId", photoId)
                    put("caption", img.caption)
                })
            }
            shopAuditsArr.put(JSONObject().apply {
                put("shopId", sa.shopId)
                put("productACount", sa.productACount)
                put("productBCount", sa.productBCount)
                put("displayRating", sa.displayRating)
                put("packedProductQty", sa.packedProductQty)
                put("regularity", sa.regularity)
                put("remarks", sa.remarks)
                put("auditedAt", sa.auditedAt)
                put("mobileLat", sa.mobileLat)
                put("mobileLng", sa.mobileLng)
                put("mobileAccuracyMeters", sa.mobileAccuracyMeters)
                put("photos", photosArr)
            })
        }
        val payload = JSONObject().apply {
            put("clientRequestId", report.id)
            put("subLocationId", report.subLocationId)
            put("shopAudits", shopAuditsArr)
            report.planId?.let { put("planId", it) }
            report.planItemId?.let { put("planItemId", it) }
        }
        val res = requestJson("/api/reports/retail", "POST", payload)
        return AuditGridJsonParsers.parseRetailReport(JSONObject(res))
    }

    suspend fun submitCustomAudit(
        clientRequestId: String,
        formId: String,
        targetType: String,
        targetId: String,
        answers: Map<String, Any?>,
        photos: List<AuditCapturedImage>,
        mobileLat: Double,
        mobileLng: Double,
        mobileAccuracyMeters: Double,
        planLink: PlanLink?,
        fallbackMeta: CustomAuditSubmission? = null
    ): CustomAuditSubmission {
        val stringAnswers = mutableMapOf<String, String>()
        for ((k, v) in answers) {
            if (v != null && v != "") {
                stringAnswers[k] = v.toString()
            }
        }
        val photosArr = JSONArray()
        for (img in photos) {
            val photoId = uploadPhotoDataUrl(img.url)
            photosArr.put(JSONObject().apply {
                put("photoId", photoId)
                put("caption", img.caption)
            })
        }
        val ansObj = JSONObject()
        for ((k, v) in answers) {
            if (v != null && v != "") {
                ansObj.put(k, v)
            }
        }
        val payload = JSONObject().apply {
            put("clientRequestId", clientRequestId)
            put("formId", formId)
            put("targetType", targetType)
            put("targetId", targetId)
            put("answers", ansObj)
            put("photos", photosArr)
            put("mobileLat", mobileLat)
            put("mobileLng", mobileLng)
            put("mobileAccuracyMeters", mobileAccuracyMeters)
            planLink?.let {
                put("planId", it.planId)
                put("planItemId", it.itemId)
            }
        }
        val res = requestJson("/api/submissions", "POST", payload)
        val parsed = AuditGridJsonParsers.parseCustomSubmission(JSONObject(res))
        return parsed.copy(
            formId = parsed.formId.ifEmpty { formId },
            formCode = parsed.formCode.ifEmpty { fallbackMeta?.formCode ?: "" },
            formTitle = parsed.formTitle.ifEmpty { fallbackMeta?.formTitle ?: "" },
            targetType = parsed.targetType.ifEmpty { targetType },
            targetId = parsed.targetId.ifEmpty { targetId },
            targetCode = parsed.targetCode.ifEmpty { fallbackMeta?.targetCode ?: "" },
            targetName = parsed.targetName.ifEmpty { fallbackMeta?.targetName ?: "" },
            cityName = parsed.cityName.ifEmpty { fallbackMeta?.cityName ?: "" },
            territoryCode = parsed.territoryCode.ifEmpty { fallbackMeta?.territoryCode ?: "" },
            territoryName = parsed.territoryName.ifEmpty { fallbackMeta?.territoryName ?: "" },
            submittedDate = parsed.submittedDate.ifEmpty { fallbackMeta?.submittedDate ?: "" },
            submittedTime = parsed.submittedTime.ifEmpty { fallbackMeta?.submittedTime ?: "" },
            mobileLat = if (parsed.mobileLat != 0.0) parsed.mobileLat else mobileLat,
            mobileLng = if (parsed.mobileLng != 0.0) parsed.mobileLng else mobileLng,
            mobileAccuracyMeters = if (parsed.mobileAccuracyMeters != 0) parsed.mobileAccuracyMeters else mobileAccuracyMeters.toInt(),
            distanceMeters = if (parsed.distanceMeters != 0) parsed.distanceMeters else (fallbackMeta?.distanceMeters ?: 0),
            allowedRadiusMeters = if (parsed.allowedRadiusMeters != 0) parsed.allowedRadiusMeters else (fallbackMeta?.allowedRadiusMeters ?: 150),
            answers = if (parsed.answers.isNotEmpty()) parsed.answers else stringAnswers,
            capturedImages = if (parsed.capturedImages.isNotEmpty()) parsed.capturedImages else photos
        )
    }

    suspend fun fetchPlans(inProgressOnly: Boolean = false): List<AuditPlan> {
        val uid = currentAuditorId()
        return try {
            val path = if (inProgressOnly) "/api/plans?status=In%20Progress" else "/api/plans"
            val body = requestJson(path)
            val arr = JSONArray(body)
            val parsed = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { AuditGridJsonParsers.parsePlan(it) }
            }
            if (!inProgressOnly && uid.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    mobileDb.savePlansForUser(uid, parsed)
                }
            }
            parsed
        } catch (e: ApiError) {
            if (e.isNetwork && uid.isNotBlank()) {
                val all = withContext(Dispatchers.IO) { mobileDb.loadPlansForUser(uid) }
                if (inProgressOnly) all.filter { it.status == "In Progress" } else all
            } else {
                throw e
            }
        }
    }

    suspend fun createPlan(
        clientRequestId: String,
        planDate: String,
        title: String?,
        items: List<PlanItem>
    ): AuditPlan {
        val itemsArr = JSONArray()
        for (i in items) {
            itemsArr.put(JSONObject().apply {
                put("formId", i.formId)
                put("targetType", i.targetType)
                put("targetId", i.targetId)
                put("shopIds", JSONArray(i.shopIds))
            })
        }
        val payload = JSONObject().apply {
            put("clientRequestId", clientRequestId)
            put("planDate", planDate)
            if (!title.isNullOrBlank()) put("title", title.trim())
            put("items", itemsArr)
        }
        val res = requestJson("/api/plans", "POST", payload)
        val created = AuditGridJsonParsers.parsePlan(JSONObject(res))
        val uid = currentAuditorId()
        if (uid.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val existing = mobileDb.loadPlansForUser(uid)
                mobileDb.savePlansForUser(uid, listOf(created) + existing.filter { it.id != created.id })
            }
        }
        return created
    }

    suspend fun startPlan(planId: String): AuditPlan {
        val res = requestJson("/api/plans/$planId/start", "POST", JSONObject())
        val updated = AuditGridJsonParsers.parsePlan(JSONObject(res))
        val uid = currentAuditorId()
        if (uid.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val existing = mobileDb.loadPlansForUser(uid)
                mobileDb.savePlansForUser(uid, existing.map { if (it.id == planId) updated else it })
            }
        }
        return updated
    }

    suspend fun cancelPlan(planId: String): AuditPlan {
        val res = requestJson("/api/plans/$planId/cancel", "POST", JSONObject())
        val updated = AuditGridJsonParsers.parsePlan(JSONObject(res))
        val uid = currentAuditorId()
        if (uid.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val existing = mobileDb.loadPlansForUser(uid)
                mobileDb.savePlansForUser(uid, existing.map { if (it.id == planId) updated else it })
            }
        }
        return updated
    }

    suspend fun deletePlan(planId: String) {
        requestJson("/api/plans/$planId", "DELETE")
        val uid = currentAuditorId()
        if (uid.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val existing = mobileDb.loadPlansForUser(uid)
                mobileDb.savePlansForUser(uid, existing.filterNot { it.id == planId })
            }
        }
    }

    suspend fun skipPlanItem(planId: String, itemId: String, reason: String): AuditPlan {
        val payload = JSONObject().apply {
            put("status", "Skipped")
            put("reason", reason.trim())
        }
        val res = requestJson("/api/plans/$planId/items/$itemId", "PATCH", payload)
        val updated = AuditGridJsonParsers.parsePlan(JSONObject(res))
        val uid = currentAuditorId()
        if (uid.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val existing = mobileDb.loadPlansForUser(uid)
                mobileDb.savePlansForUser(uid, existing.map { if (it.id == planId) updated else it })
            }
        }
        return updated
    }

    suspend fun sendTrackingPing(lat: Double, lng: Double, accuracy: Double) {
        val payload = JSONObject().apply {
            put("lat", lat)
            put("lng", lng)
            put("accuracy", accuracy)
        }
        requestJson("/api/tracking/ping", "POST", payload)
    }

    // Local draft storage (mirrors localStorage in browser app)
    fun saveDraftString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getDraftString(key: String): String? = prefs.getString(key, null)

    fun removeDraft(key: String) {
        prefs.edit().remove(key).apply()
    }

    // Offline Outbox Queue & Read-Only Completed Audits in Mobile SQLite Database
    fun loadOutboxItems(auditorId: String): List<PendingOutboxItem> =
        mobileDb.loadReportItemsForUser(auditorId, "outbox", AuditGridJsonParsers::parsePendingOutboxItem)

    fun saveOutboxItems(auditorId: String, items: List<PendingOutboxItem>) {
        mobileDb.saveReportItemsForUser(
            auditorId,
            "outbox",
            items.map { it.id to AuditGridJsonParsers.serializePendingOutboxItem(it) }
        )
    }

    fun loadCachedDistributorReports(auditorId: String): List<DistributorProductAuditReport> =
        mobileDb.loadReportItemsForUser(auditorId, "distributor", AuditGridJsonParsers::parseDistributorReport)

    fun saveCachedDistributorReports(auditorId: String, items: List<DistributorProductAuditReport>) {
        mobileDb.saveReportItemsForUser(
            auditorId,
            "distributor",
            items.take(100).map { it.id to AuditGridJsonParsers.serializeDistributorReport(it) }
        )
    }

    fun loadCachedRetailReports(auditorId: String): List<RetailMarketShopAuditReport> =
        mobileDb.loadReportItemsForUser(auditorId, "retail", AuditGridJsonParsers::parseRetailReport)

    fun saveCachedRetailReports(auditorId: String, items: List<RetailMarketShopAuditReport>) {
        mobileDb.saveReportItemsForUser(
            auditorId,
            "retail",
            items.take(100).map { it.id to AuditGridJsonParsers.serializeRetailReport(it) }
        )
    }

    fun loadCachedCustomSubmissions(auditorId: String): List<CustomAuditSubmission> =
        mobileDb.loadReportItemsForUser(auditorId, "custom", AuditGridJsonParsers::parseCustomSubmission)

    fun saveCachedCustomSubmissions(auditorId: String, items: List<CustomAuditSubmission>) {
        mobileDb.saveReportItemsForUser(
            auditorId,
            "custom",
            items.take(100).map { it.id to AuditGridJsonParsers.serializeCustomSubmission(it) }
        )
    }

    // On-device SQLite persistence for Synced Audit Forms, Field Configs & Master Data
    fun hasSyncedFormsOnce(auditorId: String): Boolean =
        mobileDb.hasSyncedFormsOnce(auditorId)

    fun setFormsSyncedOnce(auditorId: String, synced: Boolean) {
        mobileDb.setFormsSyncedOnce(auditorId, synced)
    }

    fun loadCachedForms(auditorId: String): List<AuditFormDefinition> =
        mobileDb.loadFormsForUser(auditorId)

    fun saveCachedForms(auditorId: String, forms: List<AuditFormDefinition>) {
        val configs = mobileDb.loadFormConfigsForUser(auditorId)
        mobileDb.saveServerFormsForUser(auditorId, forms, configs)
    }

    fun loadCachedFormConfigs(auditorId: String): FormConfigMap =
        mobileDb.loadFormConfigsForUser(auditorId)

    fun saveCachedFormConfigs(auditorId: String, configs: FormConfigMap) {
        val forms = mobileDb.loadFormsForUser(auditorId)
        mobileDb.saveServerFormsForUser(auditorId, forms, configs)
    }

    fun loadCachedBootstrap(auditorId: String, session: Session): BootstrapPayload? =
        mobileDb.loadBootstrapForUser(session)
}
