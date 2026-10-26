package com.example.blemesh.domain.usecase

import com.example.blemesh.domain.repository.MeshRepository

class SetMyPseudoUseCase(private val repository: MeshRepository) {
    operator fun invoke(pseudo: String): Result<Unit> = repository.setMyPseudo(pseudo)
}
