package wotw.server.game

import wotw.server.database.model.User
import wotw.server.database.model.World

data class WorldCreatedEvent(
    val world: World,
)

data class WorldDeletedEvent(
    val worldId: Long,
)

data class PlayerJoinedEvent(
    val player: User,
)

data class PlayerLeftEvent(
    val player: User,
)

data class MultiverseEvent(
    val event: String,
    val sender: User,
    val payload: Any? = null,
)

data class DebugEvent(
    val event: String,
    val payload: Any? = null,
)