package jp.assasans.protanki.server.commands.handlers

import com.squareup.moshi.Moshi
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import jp.assasans.protanki.server.battles.TankState
import jp.assasans.protanki.server.battles.sendTo
import jp.assasans.protanki.server.client.*
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandHandler
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.commands.ICommandHandler

class BattleHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val json by inject<Moshi>()

  @CommandHandler(CommandName.Ping)
  suspend fun ping(socket: UserSocket) {
    val player = socket.battlePlayer ?: return
    if(!player.stage2Initialized) {
      player.stage2Initialized = true

      logger.info { "Init battle..." }

      player.initStage2()
    }

    Command(CommandName.Pong).send(socket)
  }

  @CommandHandler(CommandName.GetInitDataLocalTank)
  suspend fun getInitDataLocalTank(socket: UserSocket) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")

    player.initLocal()
  }

  @CommandHandler(CommandName.Move)
  suspend fun move(socket: UserSocket, data: MoveData) {
    moveInternal(socket, data)
  }

  @CommandHandler(CommandName.FullMove)
  suspend fun fullMove(socket: UserSocket, data: FullMoveData) {
    moveInternal(socket, data)
  }

  private suspend fun moveInternal(socket: UserSocket, data: MoveData) {
    // logger.trace { "Tank move: [ ${data.position.x}, ${data.position.y}, ${data.position.z} ]" }

    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    if(tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
      logger.warn { "Invalid tank state for movement: ${tank.state}" }

      // Rollback move
      /*
      Command(
        CommandName.ClientFullMove,
        listOf(
          json.adapter(ClientFullMoveData::class.java).toJson(
            ClientFullMoveData(
              tankId = tank.id,
              physTime = data.physTime + 299,
              control = 0,
              specificationID = 0,
              position = tank.position.toVectorData(),
              linearVelocity = Vector3Data(),
              orientation = tank.orientation.toEulerAngles().toVectorData(),
              angularVelocity = Vector3Data(),
              turretDirection = 0.0
            )
          )
        )
      ).send(socket)
      */
    }

    tank.position.copyFrom(data.position.toVector())
    tank.orientation.fromEulerAngles(data.orientation.toVector())

    if(data is FullMoveData) {
      Command(CommandName.ClientFullMove, listOf(
        ClientFullMoveData(tank.id, data).toJson()
      )).sendTo(player.battle)

      logger.debug { "Synced full move to ${player.battle.players.size} players" }
    } else {
      Command(CommandName.ClientMove, listOf(
        ClientMoveData(tank.id, data).toJson()
      )).sendTo(player.battle)

      logger.debug { "Synced move to ${player.battle.players.size} players" }
    }
  }

  @CommandHandler(CommandName.RotateTurret)
  suspend fun rotateTurret(socket: UserSocket, data: RotateTurretData) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    if(tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
      logger.warn { "Invalid tank state for rotate turret: ${tank.state}" }
    }

    Command(CommandName.RotateTurret, listOf(
      ClientRotateTurretData(tank.id, data).toJson()
    )).sendTo(player.battle)

    logger.debug { "Synced rotate turret to ${player.battle.players.size} players" }
  }

  @CommandHandler(CommandName.MovementControl)
  suspend fun movementControl(socket: UserSocket, data: MovementControlData) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    if(tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
      logger.warn { "Invalid tank state for movement control: ${tank.state}" }
    }

    Command(CommandName.MovementControl, listOf(
      ClientMovementControlData(tank.id, data).toJson()
    )).sendTo(player.battle)

    logger.debug { "Synced movement control to ${player.battle.players.size} players" }
  }

  @CommandHandler(CommandName.SelfDestruct)
  suspend fun selfDestruct(socket: UserSocket) {
    logger.debug { "Started self-destruct for ${socket.user!!.username}" }
  }
}
