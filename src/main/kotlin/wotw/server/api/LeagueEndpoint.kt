package wotw.server.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.LeagueSeason
import wotw.server.database.model.LeagueSeasonMembership
import wotw.server.game.WotwSaveFileReader
import wotw.server.game.handlers.league.LeagueGameHandler
import wotw.server.main.WotwBackendServer

class LeagueEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(JWT_AUTH) {
            get("league/seasons/{season_id}") {
                val seasonId = call.parameters["season_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

                call.respond(newSuspendedTransaction {
                    val season = LeagueSeason.findById(seasonId) ?: throw NotFoundException("Season not found")
                    server.infoMessagesService.generateLeagueSeasonInfo(season)
                })
            }

            post("league/seasons/{season_id}/membership") {
                val seasonId = call.parameters["season_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

                val seasonInfo = newSuspendedTransaction {
                    val season = LeagueSeason.findById(seasonId) ?: throw NotFoundException("Season not found")
                    val user = authenticatedUser()

                    if (season.memberships.any { it.user.id == user.id }) {
                        throw BadRequestException("User is already part of that season")
                    }

                    LeagueSeasonMembership.new {
                        this.season = season
                        this.user = user
                    }

                    season.refresh()

                    server.infoMessagesService.generateLeagueSeasonInfo(season)
                }

                call.respond(HttpStatusCode.Created, seasonInfo)
            }

            post("league/submission") {
                val (handler, canSubmit) = newSuspendedTransaction {
                    val user = authenticatedUser()
                    val multiverse = user.currentMultiverse ?: throw BadRequestException("You are not part of an active multiverse")
                    val handler = server.gameHandlerRegistry.getHandler(multiverse) as? LeagueGameHandler ?: throw BadRequestException("You are not in a League game")
                    return@newSuspendedTransaction handler to handler.canSubmit(user)
                }

                if (!canSubmit) {
                    throw BadRequestException("You cannot submit a run at this time")
                }

                // Parse save file and check if the Save File GUID is correct
                val saveFilePacket = call.receiveChannel().readRemaining(1024 * 256 /* max 256 KB */)
                val saveFileBuffer = saveFilePacket.readByteBuffer()
                val saveFileReader = WotwSaveFileReader(saveFileBuffer)
                val saveData = saveFileReader.parse() ?: throw BadRequestException("Invalid save file data")
                val expectedSaveGuid = handler.getPlayerSaveGuid(wotwPrincipal().userId)

                if (expectedSaveGuid != saveData.saveFileGuid) {
                    throw BadRequestException("Invalid save file GUID, expected $expectedSaveGuid but got ${saveData.saveFileGuid}")
                }

                val saveFileArray = ByteArray(saveFileBuffer.limit())
                saveFileBuffer.get(saveFileArray)

                handler.createSubmission(authenticatedUser(), saveData.inGameTime, saveFileArray)

                call.respond(HttpStatusCode.Created)
            }
        }
    }
}
