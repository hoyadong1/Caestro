package com.drivesoundtrack.app.library

import com.drivesoundtrack.app.audio.BassLine
import com.drivesoundtrack.app.audio.HighLine
import com.drivesoundtrack.app.audio.Instruments
import com.drivesoundtrack.app.audio.Piece
import com.drivesoundtrack.app.audio.SampleBank
import com.drivesoundtrack.app.audio.Score
import com.drivesoundtrack.app.audio.Soundscapes
import com.drivesoundtrack.app.audio.classical
import com.drivesoundtrack.app.ui.InstrumentLooks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scores and instruments made on the creator platform (outside the car) and shared through the
 * cloud. The car only lists, downloads and plays them: nothing is created or uploaded here.
 * The pack format is described in tools/cloud/README.md.
 *
 * A downloaded pack is a folder under [root] named by its id, holding its manifest (<id>.json) and,
 * for an instrument, its notes (<id>.pcm). [load] adds every installed pack to the app's tracks
 * and instruments; indices of the app's own content never move.
 */
object CloudLibrary {
    /** The example cloud: GitHub Pages of the project repository. */
    const val BASE = "https://hoyadong1.github.io/Caestro/cloud/"

    /**
     * Where the list and the files come from. The example cloud keeps both under one folder; the studio
     * platform (Supabase) serves the list from a database function and the files from a public bucket,
     * and wants its public key with every request to the project.
     */
    class Source(val catalog: String, val files: String, val apiKey: String? = null) {
        companion object {
            /** A folder holding catalog.json and the packs (GitHub Pages, a local copy in tests). */
            fun folder(base: String) = Source(URL(URL(base), "catalog.json").toString(), base)
            /** The studio platform on Supabase: approved works, most liked first (tools/platform/schema.sql). */
            fun platform(project: String, key: String): Source {
                val root = project.trimEnd('/')
                return Source("$root/rest/v1/rpc/car_catalog", "$root/storage/v1/object/public/packs/", key)
            }
        }
    }

    /** The platform when the build names one (gradle.properties caestro.supabase*), else the example cloud. */
    val DEFAULT: Source get() =
        if (com.drivesoundtrack.app.BuildConfig.SUPABASE_URL.isNotBlank() && com.drivesoundtrack.app.BuildConfig.SUPABASE_KEY.isNotBlank())
            Source.platform(com.drivesoundtrack.app.BuildConfig.SUPABASE_URL, com.drivesoundtrack.app.BuildConfig.SUPABASE_KEY)
        else Source.folder(BASE)
    /** Folder under the app's files that holds downloaded packs. */
    const val FOLDER = "library"
    private const val MAX_FILE_BYTES = 16L shl 20
    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,39}")

    class Item(
        val id: String,
        /** "score" or "instrument". */
        val type: String,
        val title: String,
        val subtitle: String,
        val creator: String,
        val license: String,
        /** Instruments: how the stage draws it ([InstrumentLooks] shape name) and its color. */
        val look: String,
        val color: Int,
        val stages: List<Int>,
        val files: List<String>,
        val bytes: Long,
    ) {
        val isScore get() = type == "score"
    }

    // ------------------------------------------------------------------ catalog and download

    fun parseCatalog(text: String): List<Item> {
        val items = JSONObject(text).getJSONArray("items")
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val id = o.getString("id")
            val type = o.getString("type")
            if (!ID.matches(id) || type !in setOf("score", "instrument")) return@mapNotNull null
            Item(id, type, o.getString("title"), o.optString("subtitle"), o.optString("creator"), o.optString("license"),
                o.optString("look"), color(o.optString("color")), ints(o.optJSONArray("stages")),
                strings(o.getJSONArray("files")), o.optLong("bytes"))
        }.also { list ->
            // The band screen can already draw an instrument it lists.
            for (item in list) if (!item.isScore) InstrumentLooks.register(item.id, item.look, item.color)
        }
    }

    fun fetchCatalog(base: String): List<Item> = fetchCatalog(Source.folder(base))
    fun fetchCatalog(source: Source = DEFAULT): List<Item> = parseCatalog(fetch(URL(source.catalog), source).decodeToString())

    fun download(item: Item, root: File, base: String) = download(item, root, Source.folder(base))

    /** Downloads [item] into [root]/<id>, replacing an older copy only once the new one is complete and valid. */
    fun download(item: Item, root: File, source: Source = DEFAULT) {
        require(ID.matches(item.id))
        root.mkdirs()
        val part = File(root, "${item.id}.part").apply { deleteRecursively(); mkdirs() }
        try {
            for (path in item.files) {
                require(!path.startsWith("/") && ".." !in path.split('/')) { "bad path $path" }
                File(part, path.substringAfterLast('/')).writeBytes(fetch(URL(URL(source.files), path), source))
            }
            // Refuse a pack the app could not play.
            if (item.isScore) parseScore(read(part, item.id)) else parseInstrument(read(part, item.id), part)
            val target = File(root, item.id)
            target.deleteRecursively()
            if (!part.renameTo(target)) throw IOException("could not install ${item.id}")
        } finally {
            part.deleteRecursively()
        }
    }

    /** Ids of the packs downloaded so far. */
    fun installed(root: File): Set<String> =
        root.listFiles()?.filter { it.isDirectory && ID.matches(it.name) && File(it, "${it.name}.json").isFile }
            ?.map { it.name }?.toSet() ?: emptySet()

    private fun fetch(url: URL, source: Source): ByteArray {
        val connection = url.openConnection()
        // The platform's public key goes only to the platform's own host.
        val key = source.apiKey
        if (key != null && url.host == URL(source.catalog).host) {
            connection.setRequestProperty("apikey", key)
            connection.setRequestProperty("Authorization", "Bearer $key")
        }
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        if (connection is HttpURLConnection && connection.responseCode != 200)
            throw IOException("${connection.responseCode} for $url")
        return connection.getInputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_FILE_BYTES) throw IOException("$url is too large")
            }
            out.toByteArray()
        }
    }

    // ------------------------------------------------------------------ loading into the app

    private val loaded = mutableSetOf<String>()

    /**
     * Adds every installed pack not added yet: instruments first, so a score may suggest them.
     * Safe to call from the screen and the service; returns true when something new was added.
     */
    @Synchronized fun load(root: File): Boolean {
        val ids = installed(root) - loaded
        if (ids.isEmpty()) return false
        val manifests = ids.mapNotNull { id ->
            try { id to read(File(root, id), id) } catch (_: Exception) { null }
        }
        var added = false
        for ((id, json) in manifests.filter { it.second.optString("type") == "instrument" }) {
            try { registerInstrument(json, File(root, id)); loaded += id; added = true } catch (_: Exception) { }
        }
        for ((id, json) in manifests.filter { it.second.optString("type") == "score" }) {
            try { registerScore(json); loaded += id; added = true } catch (_: Exception) { }
        }
        return added
    }

    private fun read(dir: File, id: String) = JSONObject(File(dir, "$id.json").readText())

    private class InstrumentPack(val kind: Instruments.Kind, val stages: List<Int>, val set: SampleBank.Set,
                                 val look: String, val color: Int)

    private fun parseInstrument(o: JSONObject, dir: File): InstrumentPack {
        require(o.getInt("format") == 1 && o.getString("type") == "instrument")
        val id = o.getString("id")
        require(ID.matches(id))
        require(o.optInt("rate", SampleBank.RATE.toInt()) == SampleBank.RATE.toInt()) { "notes must be 24 kHz" }
        val bytes = File(dir, "$id.pcm").readBytes()
        val data = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
        val rows = o.getJSONArray("notes")
        val notes = (0 until rows.length()).map { i ->
            val n = rows.getJSONObject(i)
            val note = SampleBank.Note(n.getDouble("root"), "n$i", n.getInt("offset"), n.getInt("length"),
                n.getInt("loopStart"), n.getInt("loopEnd"))
            require(note.root > 20 && note.offset >= 0 && note.offset + note.length <= data.size) { "note $i outside the recording" }
            require(note.loopEnd <= 0 || (note.loopStart in note.offset until note.loopEnd && note.loopEnd <= note.offset + note.length))
            note
        }
        require(notes.isNotEmpty())
        val kind = Instruments.sampled(id, o.getString("label"), recorded = o.optString("sound") == "recorded",
            sustained = o.getBoolean("sustained"), voices = o.optInt("voices", 4).coerceIn(1, 8), mono = o.optBoolean("mono"),
            min = o.optInt("minMidi", 24), max = o.optInt("maxMidi", 100), gain = o.optDouble("gain", 2.0).coerceIn(0.1, 6.0),
            attack = o.optDouble("attack", 0.01).coerceIn(0.001, 2.0), release = o.optDouble("release", 0.3).coerceIn(0.01, 4.0))
        return InstrumentPack(kind, ints(o.getJSONArray("stages")), SampleBank.Set(id, data, notes),
            o.optString("look"), color(o.optString("color")))
    }

    private fun registerInstrument(o: JSONObject, dir: File) {
        val pack = parseInstrument(o, dir)
        if (Instruments.all.any { it.id == pack.kind.id && !it.cloud }) return // never shadow the app's own
        SampleBank.register(pack.set)
        InstrumentLooks.register(pack.kind.id, pack.look, pack.color)
        Instruments.addCloud(pack.kind, pack.stages)
    }

    private class ScorePack(val piece: Piece, val character: String, val color: Int, val bpm: Double, val tempoRange: Double)

    private fun parseScore(o: JSONObject): ScorePack {
        require(o.getInt("format") == 1 && o.getString("type") == "score")
        require(ID.matches(o.getString("id")))
        val beats = o.getInt("beatsPerBar")
        require(beats == 3 || beats == 4) { "3/4 or 4/4 only" }
        val spb = beats * 4
        val chords = Score.chords(o.getString("chords"), spb, voicings = o.optString("voicings").ifBlank { null })
        val length = chords.last().start + chords.last().length
        val melody = Score.notes(o.getString("melody"), spb)
        val bassText = o.getString("bass")
        val bassStyle = BassLine.entries.firstOrNull { it.name == bassText } ?: BassLine.EXPLICIT
        val bass = if (bassStyle == BassLine.EXPLICIT) Score.notes(bassText, spb, tileTo = length) else emptyList()
        val defaults = strings(o.getJSONArray("defaults"))
        require(defaults.size == 6)
        // A suggested instrument the car does not have (or not on that stage) falls back to the default band.
        val usable = List(6) { stage ->
            val index = Instruments.all.indexOfFirst { it.id == defaults[stage] }
            if (index in Instruments.options[stage]) defaults[stage] else Instruments.BAND_DEFAULT[stage]
        }
        val piece = Piece(o.getString("title"), o.optString("composer"), beats, Score.pitchClass(o.getString("tonic")),
            chords, melody, bassStyle, bass, Score.pattern(o.getString("arp")), Score.hits(o.getString("comp")),
            HighLine.valueOf(o.optString("high", "BELLS")), o.optInt("canonBars"), usable,
            // Optional since 0.14: older apps ignore them and keep the one-bar arp and comp.
            drumLine = o.optString("drums").ifBlank { null }?.let { Score.drums(it, spb) },
            arpLine = o.optString("arpLine").ifBlank { null }?.let { Score.patternLine(it, spb) },
            compLine = o.optString("compLine").ifBlank { null }?.let { Score.hitsLine(it, spb) })
        return ScorePack(piece, o.getString("character"), color(o.optString("color")),
            o.getDouble("bpm").coerceIn(40.0, 160.0), o.optDouble("tempoRange", 6.0).coerceIn(0.0, 40.0))
    }

    private fun registerScore(o: JSONObject) {
        val pack = parseScore(o)
        Soundscapes.addCloud(classical(pack.piece, pack.character, pack.color, pack.bpm, pack.tempoRange, cloudId = o.getString("id")))
    }

    private fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).map { a.getInt(it) }
    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
    private fun color(hex: String): Int = hex.removePrefix("#").toLongOrNull(16)?.toInt()?.and(0xFFFFFF) ?: 0x9FB4C2
}
