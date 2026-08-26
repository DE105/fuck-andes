package fuck.andes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 语义检索向量表的数据访问层。 */
@Dao
internal interface MemoryEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryEmbeddingEntity)

    @Query("SELECT * FROM memory_embeddings")
    suspend fun all(): List<MemoryEmbeddingEntity>

    @Query("DELETE FROM memory_embeddings WHERE refType = :refType AND refId = :refId")
    suspend fun deleteByRef(refType: String, refId: String)

    @Query("DELETE FROM memory_embeddings WHERE refId = :id")
    suspend fun deleteByRefId(id: String)

    @Query("DELETE FROM memory_embeddings")
    suspend fun clear()
}
