package wotw.server.game.handlers

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.SeedGenConfig
import wotw.io.messages.json
import wotw.io.messages.protobuf.*
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.api.UberStateSyncStrategy
import wotw.server.api.sync
import wotw.server.api.with
import wotw.server.database.model.Multiverse
import wotw.server.database.model.User
import wotw.server.database.model.World
import wotw.server.game.*
import wotw.server.main.WotwBackendServer
import wotw.server.sync.ShareScope
import wotw.server.sync.StateCache
import wotw.server.sync.normalWorldSyncAggregationStrategy
import wotw.server.sync.pickupIds
import wotw.server.util.Every
import wotw.server.util.Scheduler
import wotw.server.util.doAfterTransaction
import wotw.server.util.logger
import java.lang.Integer.min
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.pow


@Serializable
data class HideAndSeekGameHandlerState(
    var started: Boolean = false,
    var catchPhase: Boolean = false,
    var secondsUntilCatchPhase: Int = 600,
    var seekerHintBaseInterval: Int = 600,
    var seekerHintIntervalMultiplier: Float = 0.75f,
    var seekerHintMinInterval: Int = 45,
    var gameSecondsElapsed: Int = 0,
    var secondsUntilSeekerHint: Int = secondsUntilCatchPhase,
    var seekerHintsGiven: Int = 0,
    var seekerWorlds: MutableMap<Long, SeekerWorldInfo> = mutableMapOf(),
)

@Serializable
data class SeekerWorldInfo(
    @ProtoNumber(1) val worldId: Long,
    @ProtoNumber(2) val radius: Float,
    @ProtoNumber(3) val cooldown: Float,
)

@Serializable
data class HideAndSeekGameHandlerClientInfo(
    @ProtoNumber(1) val seekerWorldInfos: List<SeekerWorldInfo>,
)

enum class PlayerType {
    Hider,
    Seeker,
}

data class PlayerInfo(
    var type: PlayerType,
    var position: Vector2 = Vector2(0f, 0f),
    var revealedMapPosition: Vector2? = null,
) {
    override fun toString(): String {
        return "$type at $position"
    }
}

class HideAndSeekGameHandler(
    multiverseId: Long,
    server: WotwBackendServer,
) : GameHandler<HideAndSeekGameHandlerClientInfo>(multiverseId, server) {
    private var state = HideAndSeekGameHandlerState()

    private val playerInfos = mutableMapOf<PlayerId, PlayerInfo>()

    private val BLAZE_UBER_ID = UberId(6, 1115)

    private val seekerSeedgenConfig = SeedGenConfig(
        flags = listOf("--multiplayer"),
        presets = listOf("qol", "rspawn"),
        difficulty = "gorlek",
        headers = listOf(
            "vanilla_opher_upgrades",
            "black_market",
            "key_hints",
            "zone_hints",
            "trial_hints",
        ),
        customHeaders = listOf(
            """
                !!remove 2|115  // Remove Blaze
                3|0|2|100
                3|0|2|5
                3|0|2|97
                3|0|2|101
                3|0|9|0
            """.trimIndent()
        ),
    )

    private val hiderSeedgenConfig = SeedGenConfig(
        flags = listOf("--multiplayer"),
        presets = listOf("qol", "rspawn"),
        difficulty = "gorlek",
        headers = listOf(
            "tp_zone_hints",
            "vanilla_opher_upgrades",
            "black_market",
            "key_hints",
            "zone_hints",
            "trial_hints",
        ),
        goals = listOf("trees"),
    )

    private val scheduler = Scheduler {
        state.apply {
            if (started) {
                if (!catchPhase) {
                    secondsUntilCatchPhase--

                    val message: PrintTextMessage

                    if (secondsUntilCatchPhase == 0) {
                        catchPhase = true

                        // Give blaze to seeker worlds
                        newSuspendedTransaction {
                            seekerWorlds.keys.forEach { seekerWorldId ->
                                World.findById(seekerWorldId)?.let { seekerWorld ->
                                    val result = server.sync.aggregateStates(seekerWorld, mapOf(BLAZE_UBER_ID to 1.0))
                                    seekerWorld.members.forEach { member ->
                                        server.sync.syncStates(member.id.value, result)
                                    }
                                }
                            }
                        }

                        message = PrintTextMessage(
                            "<s_2>GO!</>",
                            Vector2(0f, 1f),
                            0,
                            3f,
                            PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
                            queue = "hide_and_seek",
                        )

                        broadcastPlayerVisibility()
                    } else {
                        val minutesPart = (secondsUntilCatchPhase / 60f).toInt()
                        val secondsPart = secondsUntilCatchPhase % 60

                        message = PrintTextMessage(
                            "Catching starts in $minutesPart:${secondsPart.toString().padStart(2, '0')}",
                            Vector2(1.5f, 0f),
                            0,
                            3f,
                            screenPosition = PrintTextMessage.SCREEN_POSITION_BOTTOM_RIGHT,
                            horizontalAnchor = PrintTextMessage.HORIZONTAL_ANCHOR_RIGHT,
                            alignment = PrintTextMessage.ALIGNMENT_RIGHT,
                            withBox = false,
                            withSound = false,
                            queue = "hide_and_seek",
                        )
                    }

                    server.connections.toPlayers(playerInfos.keys, message)
                } else {
                    gameSecondsElapsed++
                    secondsUntilSeekerHint--

                    val seekerHintCountdownSeconds = min(secondsUntilSeekerHint / 2, 30)

                    if (secondsUntilSeekerHint <= 0) {
                        seekerHintsGiven++
                        secondsUntilSeekerHint = max(
                            (seekerHintBaseInterval * seekerHintIntervalMultiplier.pow(seekerHintsGiven)).toInt(),
                            seekerHintMinInterval
                        )

                        playerInfos.values.forEach { info ->
                            if (info.type == PlayerType.Hider) {
                                info.revealedMapPosition = info.position
                            }
                        }

                        server.connections.toPlayers(
                            playerInfos.keys, PrintTextMessage(
                                "Hider positions revealed to seekers!",
                                Vector2(1.5f, 0f),
                                0,
                                3f,
                                screenPosition = PrintTextMessage.SCREEN_POSITION_BOTTOM_RIGHT,
                                horizontalAnchor = PrintTextMessage.HORIZONTAL_ANCHOR_RIGHT,
                                alignment = PrintTextMessage.ALIGNMENT_RIGHT,
                                withBox = false,
                                withSound = true,
                                queue = "hide_and_seek",
                            )
                        )

                        broadcastPlayerVisibility()
                    } else if (secondsUntilSeekerHint <= seekerHintCountdownSeconds) {
                        server.connections.toPlayers(
                            playerInfos.keys, PrintTextMessage(
                                "Revealing hider positions in ${secondsUntilSeekerHint}s",
                                Vector2(1.5f, 0f),
                                0,
                                3f,
                                screenPosition = PrintTextMessage.SCREEN_POSITION_BOTTOM_RIGHT,
                                horizontalAnchor = PrintTextMessage.HORIZONTAL_ANCHOR_RIGHT,
                                alignment = PrintTextMessage.ALIGNMENT_RIGHT,
                                withBox = false,
                                withSound = true,
                                queue = "hide_and_seek",
                            )
                        )
                    }
                }
            }
        }
    }

    override fun isDisposable(): Boolean {
        return playerInfos.keys.isEmpty()
    }

    override suspend fun getAdditionalDebugInformation(): String {
        var debugInfo = ""

        debugInfo += "Player Infos:"
        playerInfos.forEach { (playerId, playerInfo) ->
            debugInfo += "\n  - $playerId: $playerInfo"
        }

        return debugInfo
    }

    override fun start() {
        messageEventBus.register(this, PlayerPositionMessage::class) { message, playerId ->
            playerInfos[playerId]?.let { senderInfo ->
                senderInfo.position = Vector2(message.x, message.y)
                val cache = server.populationCache.get(playerId)

                if (senderInfo.type == PlayerType.Seeker) {
                    server.connections.toPlayers(
                        cache.universeMemberIds - playerId,
                        UpdatePlayerPositionMessage(playerId, message.x, message.y),
                        unreliable = true,
                    )
                } else {
                    server.connections.toPlayers(
                        cache.universeMemberIds - playerId,
                        UpdatePlayerWorldPositionMessage(playerId, message.x, message.y),
                        unreliable = true,
                    )

                    senderInfo.revealedMapPosition?.let { revealedMapPosition ->
                        server.connections.toPlayers(
                            playerInfos.filter { (_, info) -> info.type == PlayerType.Seeker }.keys - playerId,
                            UpdatePlayerMapPositionMessage(playerId, revealedMapPosition.x, revealedMapPosition.y),
                            unreliable = true,
                        )
                    }
                }
            }
        }

        messageEventBus.register(this, PlayerUseCatchingAbilityMessage::class) { message, playerId ->
            val cache = server.populationCache.get(playerId)

            state.seekerWorlds[cache.worldId]?.let { seekerWorldInfo ->
                server.connections.toPlayers(
                    playerInfos.keys - playerId,
                    PlayerUsedCatchingAbilityMessage(playerId),
                )

                val caughtPlayerIds = mutableSetOf<PlayerId>()

                playerInfos[playerId]?.let { seekerInfo ->
                    playerInfos
                        .filterValues { it.type == PlayerType.Hider }
                        .forEach { (playerId, hiderInfo) ->
                            if (seekerInfo.position.distanceSquaredTo(hiderInfo.position) < seekerWorldInfo.radius.pow(2)) {
                                caughtPlayerIds.add(playerId)
                            }
                        }
                }

                val seekerName = newSuspendedTransaction {
                    User.findById(playerId)?.name ?: "(?)"
                }

                server.connections.toPlayers(
                    caughtPlayerIds,
                    PrintTextMessage(
                        "You have been caught by ${seekerName}!\nYou are now a seeker in ${seekerName}'s world.",
                        Vector2(0f, 0f),
                        0,
                        5f,
                        PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
                        withBox = true,
                        withSound = false,
                    )
                )

                for (caughtPlayerId in caughtPlayerIds) {
                    server.connections.toPlayers(
                        playerInfos.keys,
                        PlayerCaughtMessage(caughtPlayerId),
                    )

                    newSuspendedTransaction {
                        val caughtPlayer = User.findById(caughtPlayerId)
                        val seekerWorld = World.findById(seekerWorldInfo.worldId)

                        if (caughtPlayer == null || seekerWorld == null) {
                            false
                        } else {
                            server.multiverseUtil.movePlayerToWorld(caughtPlayer, seekerWorld)
                            true
                        }
                    }
                }

                if (caughtPlayerIds.isNotEmpty()) {
                    updatePlayerInfoCache()
                    broadcastPlayerVisibility()

                    val caughtPlayerNames = newSuspendedTransaction {
                        caughtPlayerIds.mapNotNull {
                            User.findById(it)?.name
                        }
                    }

                    caughtPlayerNames.forEach { caughtPlayerName ->
                        server.connections.toPlayers(
                            playerInfos.filter { (_, info) -> info.type == PlayerType.Hider }.keys,
                            PrintTextMessage(
                                "$caughtPlayerName has been caught by $seekerName!",
                                Vector2(0f, 0f),
                                0,
                                3f,
                                PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
                                withBox = true,
                                withSound = true,
                                queue = "hide_and_seek_caught",
                            )
                        )
                    }
                }
            }
        }

        messageEventBus.register(this, UberStateUpdateMessage::class) { message, playerId ->
            server.populationCache.getOrNull(playerId)?.worldId?.let { worldId ->
                updateUberState(message, worldId, playerId)

                if (
                    pickupIds.containsValue(message.uberId) && // It's a pickup
                    state.seekerWorlds.containsKey(worldId) // We are a seeker
                ) {

                    val worldNamesThatPickedUpThisItem = newSuspendedTransaction {
                        Multiverse.findById(multiverseId)?.let { multiverse ->
                            multiverse.worlds
                                .filter { !state.seekerWorlds.containsKey(it.id.value) }
                                .filter { world ->
                                    val state = StateCache.get(ShareScope.WORLD to world.id.value)
                                    state[message.uberId] == 1.0
                                }
                                .map { it.name }
                        } ?: listOf()
                    }

                    if (worldNamesThatPickedUpThisItem.isNotEmpty()) {
                        server.connections.toPlayers(
                            server.populationCache.get(playerId).worldMemberIds,
                            PrintTextMessage(
                                worldNamesThatPickedUpThisItem.joinToString("\n"),
                                (playerInfos[playerId]?.position ?: Vector2(0f, 0f)) + Vector2(0f, -1f),
                                time = 3f,
                                useInGameCoordinates = true,
                            ),
                        )
                    }
                }
            }
        }

        messageEventBus.register(this, UberStateBatchUpdateMessage::class) { message, playerId ->
            server.populationCache.getOrNull(playerId)?.worldId?.let { worldId ->
                batchUpdateUberStates(message, worldId, playerId)
            }
        }

        multiverseEventBus.register(this, DeveloperEvent::class) { message ->
            when (message.event) {
                "start" -> {
                    state.started = true
                    newSuspendedTransaction {
                        Multiverse.findById(multiverseId)?.gameHandlerActive = true
                    }
                }
                "updatePlayerInfoCache" -> {
                    updatePlayerInfoCache()
                }
                "reset" -> {
                    state = HideAndSeekGameHandlerState()
                    updatePlayerInfoCache()
                    newSuspendedTransaction {
                        Multiverse.findById(multiverseId)?.let { multiverse ->
                            multiverse.gameHandlerActive = false

                            server.connections.toPlayers(
                                playerInfos.keys,
                                server.infoMessagesService.generateMultiverseInfoMessage(multiverse)
                            )
                        }
                    }
                }
            }
        }

        multiverseEventBus.register(this, WorldCreatedEvent::class) { message ->
            logger().info("world created: ${message.world.id.value}")

            // TODO: Make better
            val result = server.seedGeneratorService.generateSeedGroup(
                if (state.seekerWorlds.isEmpty()) {
                    seekerSeedgenConfig
                } else {
                    hiderSeedgenConfig
                }
            )

            result.seedGroup?.let { seedGroup ->
                message.world.seed = seedGroup.seeds.firstOrNull()
            }

            doAfterTransaction {
                updatePlayerInfoCache()
            }
        }

        multiverseEventBus.register(this, WorldDeletedEvent::class) { message ->
            logger().info("world deleted: ${message.worldId}")

            doAfterTransaction {
                state.seekerWorlds.remove(message.worldId)
                updatePlayerInfoCache()
            }
        }

        multiverseEventBus.register(this, PlayerJoinedEvent::class) { message ->
            logger().info("joined: ${message.worldId}")

            doAfterTransaction {
                updatePlayerInfoCache()
            }
        }

        multiverseEventBus.register(this, PlayerLeftEvent::class) { message ->
            logger().info("left: ${message.worldId}")

            doAfterTransaction {
                updatePlayerInfoCache()
            }
        }

        scheduler.scheduleExecution(Every(1, TimeUnit.SECONDS))
    }

    override fun stop() {
        scheduler.stop()
    }

    override fun serializeState(): String {
        return json.encodeToString(HideAndSeekGameHandlerState.serializer(), state)
    }

    override suspend fun restoreState(serializedState: String?) {
        serializedState?.let {
            state = json.decodeFromString(HideAndSeekGameHandlerState.serializer(), it)
        }

        updatePlayerInfoCache()
    }

    override suspend fun generateStateAggregationRegistry(world: World): AggregationStrategyRegistry {
        return normalWorldSyncAggregationStrategy + AggregationStrategyRegistry().apply {
            register(
                sync(BLAZE_UBER_ID).with(UberStateSyncStrategy.MAX), // Blaze
            )
        }
    }

    override fun getClientInfo(): HideAndSeekGameHandlerClientInfo {
        return HideAndSeekGameHandlerClientInfo(state.seekerWorlds.values.toList())
    }

    private suspend fun updatePlayerInfoCache() {
        val playerIds = mutableSetOf<PlayerId>()

        newSuspendedTransaction {
            Multiverse.findById(multiverseId)?.let { multiverse ->

                // TODO: Hack, remove later.
                multiverse.refresh()
                multiverse.worlds.firstOrNull()?.let { firstWorld ->
                    if (!state.seekerWorlds.containsKey(firstWorld.id.value)) {
                        state.seekerWorlds.clear()
                        state.seekerWorlds[firstWorld.id.value] = SeekerWorldInfo(
                            firstWorld.id.value,
                            8f,
                            6f,
                        )
                    }
                }

                multiverse.worlds.forEach { world ->
                    val type = if (state.seekerWorlds.containsKey(world.id.value))
                        PlayerType.Seeker else PlayerType.Hider

                    world.members.forEach { player ->
                        playerIds.add(player.id.value)
                        playerInfos.getOrPut(player.id.value) {
                            PlayerInfo(type)
                        }.type = type
                    }
                }
            }
        }

        playerInfos -= playerInfos.keys - playerIds
        broadcastPlayerVisibility()
    }

    private suspend fun updateUberState(message: UberStateUpdateMessage, worldId: Long, playerId: String) =
        batchUpdateUberStates(UberStateBatchUpdateMessage(message), worldId, playerId)

    private suspend fun batchUpdateUberStates(message: UberStateBatchUpdateMessage, worldId: Long, playerId: String) {
        val updates = message.updates.map {
            it.uberId to it.value
        }.toMap()

        val results = newSuspendedTransaction {
            val world = World.findById(worldId) ?: error("Error: Requested uber state update on unknown world")
            server.sync.aggregateStates(world, updates)
        }

        server.sync.syncStates(playerId, results)
    }

    override suspend fun onGameConnectionSetup(connectionHandler: GameConnectionHandler) {
        broadcastPlayerVisibility()
    }

    private suspend fun broadcastPlayerVisibility() {
        val hiddenOnMap =
            playerInfos.filter { (_, info) -> info.type == PlayerType.Hider && info.revealedMapPosition == null }.keys.toList()
        val hiddenInWorld = if (state.catchPhase) {
            listOf()
        } else {
            hiddenOnMap
        }

        server.connections.toPlayers(
            playerInfos.keys,
            SetVisibilityMessage(hiddenInWorld, hiddenOnMap)
        )
    }
}