package com.example.facialrecog.cloud

import android.net.Uri
import com.example.facialrecog.GestureFeatureVector
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

class CloudRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {

    suspend fun uploadMeme(
        localImageUri: String,
        vector: GestureFeatureVector,
        isPublic: Boolean = true
    ): Result<Unit> = runCatching {
        val user = auth.currentUser ?: error("User not signed in")

        val memesRef = firestore.collection("memes")
        val docRef = memesRef.document()
        val memeId = docRef.id

        val storagePath = "memes/${user.uid}/$memeId.jpg"
        val storageRef = storage.reference.child(storagePath)

        storageRef.putFile(Uri.parse(localImageUri)).await()
        val downloadUrl = storageRef.downloadUrl.await().toString()

        val cloudMeme = vector.toCloudMeme(
            id = memeId,
            ownerUid = user.uid,
            ownerEmail = user.email.orEmpty(),
            imageUrl = downloadUrl,
            storagePath = storagePath,
            isPublic = isPublic
        )

        docRef.set(cloudMeme).await()
        Unit
    }

    suspend fun fetchPublicGestures(): Result<List<GestureFeatureVector>> = runCatching {
        val snapshot = firestore.collection("memes")
            .whereEqualTo("isPublic", true)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .await()

        snapshot.documents.mapNotNull { doc ->
            doc.toObject(CloudMeme::class.java)?.toGestureFeatureVector()
        }
    }

    suspend fun fetchMyCloudMemes(): Result<List<CloudMeme>> = runCatching {
        val user = auth.currentUser ?: return@runCatching emptyList()

        val snapshot = firestore.collection("memes")
            .whereEqualTo("ownerUid", user.uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .await()

        snapshot.documents.mapNotNull { it.toObject(CloudMeme::class.java) }
    }
}