package wotw.io.messages.protobuf

import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.math.max

// All fields have default values ^= protobuf optional
// We just send partial updates and merge state client-side

@Serializable
data class BingoData(
    val board: BingoBoardMessage,
    val universes: List<BingoUniverseInfo>,
)

@Serializable
data class PositionedBingoSquare(
    @ProtoNumber(1) val position: Position,
    @ProtoNumber(2) val square: BingoSquare,
)

@Serializable
data class BingoBoardMessage(
    @ProtoNumber(1) val squares: List<PositionedBingoSquare> = emptyList(),
    @ProtoNumber(2) val size: Int = -1,
    @ProtoNumber(3) val lockout: Boolean = false,
) {

    operator fun get(position: Position) = squares.first { it.position == position }.square
    fun merge(other: BingoBoardMessage) =
        BingoBoardMessage(squares + other.squares, max(size, other.size), lockout || other.lockout)

    operator fun plus(other: BingoBoardMessage) = merge(other)
}

@Serializable
data class BingothonBingoBoard(
    val size: Int,
    val universes: List<BingothonBingoUniverseInfo>,
    val squares: List<BingothonBingoSquare>,
)

@Serializable
data class BingothonBingoSquare(
    val position: Position,
    val text: String,
    val completedBy: List<Long>,
)

@Serializable
data class BingothonBingoUniverseInfo(
    val universeId: Long,
    val squares: Int = 0,
    val lines: Int = 0,
)


@Serializable
data class Position(
    @ProtoNumber(1) val x: Int,
    @ProtoNumber(2) val y: Int
)

infix fun Int.to(y: Int) = Position(this, y)

@Serializable
data class BingoSquare(
    @ProtoNumber(1) val text: String = "",
    @ProtoNumber(3) val goals: List<BingoGoal> = emptyList(),
    @ProtoNumber(2) var completedBy: List<Long> = emptyList(),
)

@Serializable
data class BingoGoal(
    @ProtoNumber(1) val text: String = "",
    @ProtoNumber(2) val completed: Boolean = false
)