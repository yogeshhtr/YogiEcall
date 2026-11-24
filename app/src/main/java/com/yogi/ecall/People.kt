package com.yogi.ecall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A favourite person: big photo, name, number. */
class Contact(val id: String, var name: String, var number: String, var color: Int) {
    fun photo(ctx: Context) = File(File(ctx.filesDir, "photos").also { it.mkdirs() }, "$id.jpg")
    fun json(): JSONObject = JSONObject().put("id", id).put("n", name).put("p", number).put("c", color)
    companion object {
        fun from(o: JSONObject) = Contact(o.optString("id"), o.optString("n"), o.optString("p"), o.optInt("c", COLORS[0]))
        val COLORS = listOf(0xFF0A84FF.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(),
            0xFF5AC8FA.toInt(), 0xFFFFCC00.toInt(), 0xFF8E8E93.toInt())
    }
}

object People {
    fun all(): List<Contact> = try {
        JSONArray(Store.str("people", "[]")).let { a -> (0 until a.length()).map { Contact.from(a.getJSONObject(it)) } }
    } catch (_: Exception) { emptyList() }

    fun save(list: List<Contact>) {
        val a = JSONArray()
        for (c in list) a.put(c.json())
        Store.setStr("people", a.toString())
    }

    fun put(c: Contact) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == c.id }
        if (i >= 0) list[i] = c else list.add(c)
        save(list)
    }

    fun remove(ctx: Context, c: Contact) {
        c.photo(ctx).delete()
        save(all().filter { it.id != c.id })
    }

    fun move(c: Contact, by: Int) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == c.id }
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        save(list)
    }

    fun newId() = System.currentTimeMillis().toString(36)

    /** Saves a picture as a square photo (centre crop, upright, 512 px). */
    fun savePhoto(ctx: Context, src: Uri, c: Contact): Boolean = try {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (minOf(o.outWidth, o.outHeight) / (s * 2) >= 512) s *= 2
        var b = cr.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
            ?: throw IllegalStateException()
        val rot = try {
            cr.openInputStream(src)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
                }
            } ?: 0
        } catch (_: Exception) { 0 }
        if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val side = minOf(b.width, b.height)
        val sq = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        val out = if (side > 512) Bitmap.createScaledBitmap(sq, 512, 512, true) else sq
        c.photo(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        true
    } catch (_: Throwable) { false }

    /** digits and + only, for the phone app */
    fun clean(n: String) = n.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
}
