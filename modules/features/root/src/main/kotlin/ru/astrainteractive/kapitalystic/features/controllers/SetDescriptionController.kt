package ru.astrainteractive.kapitalystic.features.controllers

import ru.astrainteractive.kapitalystic.dto.UserDTO
import ru.astrainteractive.kapitalystic.features.controllers.di.ClanManagementControllerModule

class SetDescriptionController(
    module: ClanManagementControllerModule
) : ClanManagementControllerModule by module {
    /**
     * /kpt rules add <index> <rule>
     */
    suspend fun setDescription(
        description: String,
        userDTO: UserDTO,
    ) {
        val economyPrice = configuration.economy.bio.toDouble()
        balanceValidation.assertHaveAtLeast(userDTO, economyPrice)

        return dbApi.setDescription(
            executorDTO = userDTO,
            description = description
        )
    }
}
