package kr.bsm.evenglass

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.sqrt

internal data class PersonaPair(val id: Long, val q: String, val a: String, val lang: String)
internal data class Hit(val pair: PersonaPair, val score: Double)

/**
 * On-device store of (other's utterance, SJK reply) pairs.
 *
 * - assets/persona/pairs.jsonl is imported into SQLite (persona.db) whenever its hash changes.
 * - Search: character 2/3-gram TF-IDF cosine similarity over the `q` column (fallback for an
 *   on-device multilingual embedder — see PROJECT_PROGRESS.md). The `emb` column is reserved
 *   for embedding vectors so a vector search can be dropped in later without a schema change.
 */
internal class PersonaStore(private val context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "persona.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE pairs(id INTEGER PRIMARY KEY AUTOINCREMENT, q TEXT NOT NULL, a TEXT NOT NULL, lang TEXT NOT NULL, emb BLOB)")
            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private var pairs: List<PersonaPair> = emptyList()
    private var vectors: List<Map<String, Double>> = emptyList()
    private var idf: Map<String, Double> = emptyMap()
    var style: String = ""
        private set
    val size get() = pairs.size

    /** Blocking; call off the main thread. */
    fun load() {
        style = context.assets.open("persona/style.md").bufferedReader().use { it.readText() }.trim()
        val raw = context.assets.open("persona/pairs.jsonl").bufferedReader().use { it.readText() }
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val db = helper.writableDatabase
        val stored = db.rawQuery("SELECT v FROM meta WHERE k='pairs_hash'", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (stored != hash) {
            db.beginTransaction()
            try {
                db.delete("pairs", null, null)
                raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    try {
                        val o = JSONObject(line)
                        val q = o.optString("q").trim()
                        val a = o.optString("a").trim()
                        if (q.isNotEmpty() && a.isNotEmpty()) db.insert("pairs", null, ContentValues().apply {
                            put("q", q); put("a", a); put("lang", o.optString("lang", "ko"))
                        })
                    } catch (_: Exception) { /* skip malformed line */ }
                }
                db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "pairs_hash"); put("v", hash) },
                    SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val list = ArrayList<PersonaPair>()
        db.rawQuery("SELECT id, q, a, lang FROM pairs ORDER BY id", null).use { c ->
            while (c.moveToNext()) list += PersonaPair(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))
        }
        val tfs = list.map { termFreq(it.q) }
        val df = HashMap<String, Int>()
        tfs.forEach { tf -> tf.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = list.size.toDouble()
        idf = df.mapValues { ln((n + 1) / (it.value + 1)) + 1.0 }
        vectors = tfs.map { weigh(it) }
        pairs = list
    }

    /** Top-k pairs by n-gram cosine similarity; each query string is scored and the best is kept per pair. */
    fun search(queries: List<String>, k: Int = 5): List<Hit> {
        if (pairs.isEmpty()) return emptyList()
        val qv = queries.filter { it.isNotBlank() }.distinct().map { weigh(termFreq(it)) }
        if (qv.isEmpty()) return emptyList()
        return pairs.indices.map { i ->
            Hit(pairs[i], qv.maxOf { cosine(it, vectors[i]) })
        }.sortedByDescending { it.score }.take(k)
    }

    private fun weigh(tf: Map<String, Int>): Map<String, Double> {
        val v = tf.mapValues { (g, c) -> (1.0 + ln(c.toDouble())) * (idf[g] ?: 1.0) }
        val norm = sqrt(v.values.sumOf { it * it })
        return if (norm == 0.0) v else v.mapValues { it.value / norm }
    }

    companion object {
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
                .replace(Regex("[\\p{P}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim()

        /** Character 2- and 3-grams (word boundaries kept as spaces). */
        fun termFreq(s: String): Map<String, Int> {
            val t = " " + normalize(s) + " "
            val out = HashMap<String, Int>()
            for (n in 2..3) for (i in 0..t.length - n) {
                val g = t.substring(i, i + n)
                if (g.isNotBlank()) out[g] = (out[g] ?: 0) + 1
            }
            return out
        }

        fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
            val (small, big) = if (a.size <= b.size) a to b else b to a
            return small.entries.sumOf { (g, w) -> w * (big[g] ?: 0.0) }
        }

        /** Plain (unweighted) n-gram cosine for comparing two sentences. */
        fun similarity(x: String, y: String): Double {
            fun unit(tf: Map<String, Int>): Map<String, Double> {
                val norm = sqrt(tf.values.sumOf { it.toDouble() * it })
                return tf.mapValues { if (norm == 0.0) 0.0 else it.value / norm }
            }
            return cosine(unit(termFreq(x)), unit(termFreq(y)))
        }
    }
}
