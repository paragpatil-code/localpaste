package com.localpaste

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** HTTP front end for [PasteStore]. All endpoints speak JSON except "/" (the web page). */
class PasteServer(
    port: Int,
    private val html: String,
    private val onRequest: () -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        onRequest()
        val uri = session.uri
        val method = session.method
        return when {
            uri == "/" && method == Method.GET -> text(Response.Status.OK, "text/html", html)

            uri == "/api/pastes" -> when (method) {
                Method.GET -> json(JSONArray().apply { PasteStore.all().forEach { put(it.toJson()) } })
                Method.POST -> create(session)
                Method.DELETE -> {
                    PasteStore.clear()
                    json(JSONObject().put("ok", true))
                }
                else -> error(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
            }

            uri.startsWith("/api/pastes/") && method == Method.DELETE -> {
                val id = uri.removePrefix("/api/pastes/").toLongOrNull()
                    ?: return error(Response.Status.BAD_REQUEST, "Bad id")
                if (PasteStore.delete(id)) json(JSONObject().put("ok", true))
                else error(Response.Status.NOT_FOUND, "Not found")
            }

            else -> error(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun create(session: IHTTPSession): Response {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len !in 1..MAX_BODY_BYTES) {
            // Body left unread, so the connection can't be reused.
            return error(Response.Status.BAD_REQUEST, "Empty or too large (max ${MAX_BODY_BYTES / 1024} KB)")
                .apply { closeConnection(true) }
        }
        val body = ByteArray(len).also { DataInputStream(session.inputStream).readFully(it) }
        val text = runCatching { JSONObject(String(body, Charsets.UTF_8)).getString("text") }
            .getOrElse { return error(Response.Status.BAD_REQUEST, "Expected {\"text\": \"...\"}") }
        if (text.isBlank()) return error(Response.Status.BAD_REQUEST, "Empty paste")
        return json(PasteStore.add(text).toJson(), Response.Status.CREATED)
    }

    private fun PasteStore.Paste.toJson() = JSONObject().put("id", id).put("text", text).put("time", time)

    private fun json(body: Any, status: Response.Status = Response.Status.OK) =
        text(status, "application/json", body.toString()).apply { addHeader("Cache-Control", "no-store") }

    // Encode ourselves: NanoHTTPD's String overload doesn't reliably produce UTF-8 on Android.
    private fun text(status: Response.Status, mime: String, body: String): Response {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(status, "$mime; charset=utf-8", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun error(status: Response.Status, message: String) =
        json(JSONObject().put("error", message), status)

    companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}
