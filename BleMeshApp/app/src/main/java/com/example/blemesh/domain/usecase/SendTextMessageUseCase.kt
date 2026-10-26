package com.example.blemesh.domain.usecase

import com.example.blemesh.domain.model.MeshPacket
import com.example.blemesh.domain.model.NodeId
import com.example.blemesh.domain.repository.MeshRepository

class SendTextMessageUseCase(private val repository: MeshRepository) {
    suspend operator fun invoke(dstId: Int, text: String): Result<Unit> {
        val clean = text.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Message vide"))
        if (dstId != MeshPacket.BROADCAST_ID && !NodeId.isValid(dstId)) {
            return Result.failure(IllegalArgumentException("ID destinataire invalide"))
        }
        return repository.sendText(dstId, clean)
    }
}
