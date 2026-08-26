package fuck.andes.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 语义检索向量表。关联 refType(refId) 指向具体记忆条目（atom / scenario / conversation）。
 * vector 为 512 维 float 的 BLOB（小端编码，见 VectorCodec）。写记忆时自动索引，检索时按 query 向量 top-k 召回。
 */
@Entity(
    tableName = "memory_embeddings",
    indices = [
        Index(value = ["refType"]),
        Index(value = ["refId"]),
        Index(value = ["updatedAt"]),
    ],
)
internal data class MemoryEmbeddingEntity(
    @PrimaryKey val id: String,
    /** 关联记忆类型：atom / scenario / conversation。 */
    val refType: String,
    /** 关联记忆条目 id。 */
    val refId: String,
    /** 512 维归一化向量（小端 float 数组）。 */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val vector: ByteArray,
    val updatedAt: Long,
)
