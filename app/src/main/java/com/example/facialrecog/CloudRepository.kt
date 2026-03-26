package com.example.facialrecog.cloud

import android.net.Uri
import com.example.facialrecog.GestureFeatureVector
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Returned by [CloudRepository.fetchPublicGesturesPaged].
 *
 * @param gestures  The decoded vectors for this page.
 * @param nextCursor  Pass back to [fetchPublicGesturesPaged] as [afterCursor] to
 *                    retrieve the next page.  Null means this is the last page.
 */
data class PagedGestures(
    val gestures: List<GestureFeatureVector>,
    val nextCursor: DocumentSnapshot?
)

class CloudRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {

    companion object {
        /** Number of gestures fetched on the first page and each subsequent page. */
        const val PAGE_SIZE = 50L
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Upload a meme image + its gesture feature vector to Firebase.
     *
     * [localImageUri] must be either:
     *   - an absolute file-system path  (/data/user/0/.../gesture_images/gesture_123.png)
     *   - a content:// URI string       (content://media/…)
     *
     * The Storage object is named after the user-supplied label so the bucket
     * stays human-readable:  memes/<uid>/<memeId>_<safeLabel>.jpg
     *
     * [vector.label] must already be set to the user-supplied name.
     */
    suspend fun uploadMeme(
        localImageUri: String,
        vector: GestureFeatureVector,
        isPublic: Boolean = true
    ): Result<Unit> = runCatching {
        val user = auth.currentUser ?: error("User not signed in")

        val docRef = firestore.collection("memes").document()
        val memeId = docRef.id

        val safeLabel = vector.label
            .trim()
            .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            .take(60)
            .ifEmpty { "unlabelled" }

        val storagePath = "memes/${user.uid}/${memeId}_${safeLabel}.jpg"
        val storageRef = storage.reference.child(storagePath)

        val file = File(localImageUri)
        require(file.exists()) { "Upload file does not exist: $localImageUri" }

        val fileUri = Uri.fromFile(file)
        storageRef.putFile(fileUri).await()

        val downloadUrl = storageRef.downloadUrl.await().toString()

        docRef.set(
            mapOf(
                "id" to memeId,
                "ownerUid" to user.uid,
                "ownerEmail" to user.email.orEmpty(),
                "label" to vector.label,
                "imageUrl" to downloadUrl,
                "storagePath" to storagePath,
                "createdAt" to System.currentTimeMillis(),
                "isPublic" to isPublic,
                "mouthOpen" to vector.mouthOpen.toDouble(),
                "mouthCurve" to vector.mouthCurve.toDouble(),
                "browFurrow" to vector.browFurrow.toDouble(),
                "browRaise" to vector.browRaise.toDouble(),
                "smileProb" to vector.smileProb.toDouble(),
                "yaw" to vector.yaw.toDouble(),
                "pitch" to vector.pitch.toDouble(),
                "roll" to vector.roll.toDouble(),
                "leftWristRelY" to vector.leftWristRelY.toDouble(),
                "rightWristRelY" to vector.rightWristRelY.toDouble(),
                "wristSpread" to vector.wristSpread.toDouble(),
                "handLandmarks" to vector.handLandmarks.map { it.toDouble() }
            )
        ).await()
    }

    // ── Fetch — paginated ─────────────────────────────────────────────────────

    /**
     * Fetch one page of public gestures from ALL users, ordered newest-first.
     *
     * Device cost
     * ───────────
     * Each call reads exactly [PAGE_SIZE] Firestore documents (50 by default).
     * Only vector fields (11 floats + 42-element handLandmarks list) and the
     * image URL are transferred — no large blobs.  The images themselves are
     * loaded lazily and thumbnailed to 256 px by [LocalImageStore.loadBitmapCached].
     *
     * @param afterCursor  Pass the [PagedGestures.nextCursor] from the previous
     *                     call to advance to the next page.  Null = first page.
     */
    suspend fun fetchPublicGesturesPaged(
        afterCursor: DocumentSnapshot? = null
    ): Result<PagedGestures> = runCatching {
        var query = firestore.collection("memes")
            .whereEqualTo("isPublic", true)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(PAGE_SIZE)

        if (afterCursor != null) {
            query = query.startAfter(afterCursor)
        }

        val snapshot = query.get().await()
        val gestures = snapshot.documents.mapNotNull { doc ->
            doc.toObject(CloudMeme::class.java)?.toGestureFeatureVector()
        }

        // If we got a full page there may be more; expose the last doc as cursor.
        val nextCursor = if (snapshot.documents.size >= PAGE_SIZE) {
            snapshot.documents.lastOrNull()
        } else null

        PagedGestures(gestures = gestures, nextCursor = nextCursor)
    }

    /**
     * Convenience wrapper that loads ALL pages sequentially.
     * Use only when the full pool is needed in one shot (e.g. after an upload
     * to immediately refresh the community count).  Capped at 4 pages (200
     * documents) to prevent runaway reads on large datasets.
     */
    suspend fun fetchPublicGestures(maxPages: Int = 4): Result<List<GestureFeatureVector>> =
        runCatching {
            val all = mutableListOf<GestureFeatureVector>()
            var cursor: DocumentSnapshot? = null
            repeat(maxPages) {
                val page = fetchPublicGesturesPaged(cursor).getOrThrow()
                all += page.gestures
                cursor = page.nextCursor ?: return@runCatching all
            }
            all
        }

    // ── Fetch — current user ──────────────────────────────────────────────────

    suspend fun fetchMyCloudMemes(): Result<List<CloudMeme>> = runCatching {
        val user = auth.currentUser ?: return@runCatching emptyList()

        firestore.collection("memes")
            .whereEqualTo("ownerUid", user.uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .await()
            .documents
            .mapNotNull { it.toObject(CloudMeme::class.java) }
    }
}