//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  StoragePaths.kt
//
//  Conversions between file system paths and Storage Access Framework URIs for shared
//  storage volumes.
//

package org.play_retro.frontend

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

object StoragePaths {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val DEFAULT_APP_DATA_NAME = "PlayRetro"
    const val DEFAULT_ROM_NAME = "ROMs"

    private val primaryRoot: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    fun defaultAppDataDirectory() = "$primaryRoot/$DEFAULT_APP_DATA_NAME"

    fun defaultRomDirectory() = "$primaryRoot/$DEFAULT_ROM_NAME"

    // Converts a document tree URI picked with ACTION_OPEN_DOCUMENT_TREE to a path, e.g.
    // primary:Games/ROMs -> /storage/emulated/0/Games/ROMs and 1A2B-3C4D:ROMs ->
    // /storage/1A2B-3C4D/ROMs. Returns null for providers other than shared storage.
    fun treeUriToPath(treeUri: Uri): String? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) primaryRoot else "/storage/$volume"
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    // Inverse of treeUriToPath() for document IDs: /storage/emulated/0/ROMs/nes/a.nes ->
    // primary:ROMs/nes/a.nes.
    fun pathToDocumentId(path: String): String? {
        val canonical = File(path).absolutePath
        if (canonical == primaryRoot || canonical.startsWith("$primaryRoot/")) {
            return "primary:" + canonical.removePrefix(primaryRoot).removePrefix("/")
        }
        val match = Regex("^/storage/([^/]+)(?:/(.*))?$").find(canonical) ?: return null
        val volume = match.groupValues[1]
        if (volume == "emulated" || volume == "self") return null
        return "$volume:" + match.groupValues[2]
    }

    // Builds a document URI for a game file, inside the persisted ROM directory tree when
    // the file is located there so that the grant covers it.
    fun documentUriForPath(path: String, romTreeUri: String): Uri? {
        val documentId = pathToDocumentId(path) ?: return null
        if (romTreeUri.isNotEmpty()) {
            val tree = Uri.parse(romTreeUri)
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            if (documentId == treeId || documentId.startsWith("$treeId/") || treeId.endsWith(':')) {
                return DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            }
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
    }

    fun isWritableDirectory(path: String): Boolean {
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return dir.canWrite()
    }
}
