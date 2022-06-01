package jp.assasans.protanki.server.battles

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import jp.assasans.protanki.server.ISocketServer
import jp.assasans.protanki.server.battles.map.IMapRegistry
import jp.assasans.protanki.server.battles.map.getSkybox
import jp.assasans.protanki.server.battles.mode.DeathmatchModeHandler
import jp.assasans.protanki.server.battles.mode.TeamModeHandler
import jp.assasans.protanki.server.client.*
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.math.Quaternion
import jp.assasans.protanki.server.math.Vector3

enum class LoadState {
  Stage1,
  Stage2,
  Stage2Completed
}

class BattlePlayer(
  coroutineContext: CoroutineContext,
  val socket: UserSocket,
  val battle: Battle,
  var team: BattleTeam,
  var tank: BattleTank? = null,
  val isSpectator: Boolean = false,
  var score: Int = 0,
  var kills: Int = 0,
  var deaths: Int = 0
) : ITickHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val mapRegistry: IMapRegistry by inject()
  private val server: ISocketServer by inject()

  var incarnation: Int = 0

  val user: User
    get() = socket.user ?: throw Exception("Missing User")

  val coroutineScope = CoroutineScope(coroutineContext + SupervisorJob())

  var loadState: LoadState = LoadState.Stage1
  var initSpectatorUserCalled: Boolean = false

  suspend fun deactivate(terminate: Boolean = false) {
    tank?.deactivate(terminate)
    coroutineScope.cancel()

    battle.modeHandler.playerLeave(this)
    Command(CommandName.BattlePlayerRemove, listOf(user.username)).sendTo(battle, exclude = this)

    when(battle.modeHandler) {
      is DeathmatchModeHandler -> Command(CommandName.ReleaseSlotDm, listOf(battle.id, user.username))
      is TeamModeHandler       -> Command(CommandName.ReleaseSlotTeam, listOf(battle.id, user.username))
      else                     -> throw IllegalStateException("Unknown battle mode: ${battle.modeHandler::class}")
    }.let { command ->
      server.players
        .filter { player -> player.screen == Screen.BattleSelect }
        .filter { player -> player.active }
        .forEach { player -> command.send(player) }
    }

    Command(
      CommandName.NotifyPlayerLeaveBattle,
      listOf(
        NotifyPlayerJoinBattleData(
          userId = user.username,
          battleId = battle.id,
          mapName = battle.title,
          mode = battle.modeHandler.mode,
          privateBattle = false,
          proBattle = false,
          minRank = 1,
          maxRank = 30
        ).toJson()
      )
    ).let { command ->
      server.players
        .filter { player -> player.screen == Screen.BattleSelect }
        .filter { player -> player.active }
        .forEach { player -> command.send(player) }
    }

    Command(
      CommandName.RemoveBattlePlayer,
      listOf(
        battle.id,
        user.username
      )
    ).let { command ->
      server.players
        .filter { player -> player.screen == Screen.BattleSelect && player.selectedBattle == battle }
        .filter { player -> player.active }
        .forEach { player -> command.send(player) }
    }
  }

  suspend fun init() {
    Command(
      CommandName.InitBonusesData,
      listOf(
        InitBonusesDataData(
          bonuses = listOf(
            BonusData(
              lighting = BonusLightingData(color = 6250335),
              id = "nitro",
              resourceId = 170010
            ),
            BonusData(
              lighting = BonusLightingData(color = 9348154),
              id = "damage",
              resourceId = 170011
            ),
            BonusData(
              lighting = BonusLightingData(color = 7185722),
              id = "armor",
              resourceId = 170006
            ),
            BonusData(
              lighting = BonusLightingData(color = 14605789),
              id = "health",
              resourceId = 170009
            ),
            BonusData(
              lighting = BonusLightingData(color = 8756459),
              id = "crystall",
              resourceId = 170007
            ),
            BonusData(
              lighting = BonusLightingData(color = 15044128),
              id = "gold",
              resourceId = 170008
            )
          )
        ).toJson()
      )
    ).send(socket)

    Command(
      CommandName.InitBattleModel,
      listOf(
        InitBattleModelData(
          battleId = battle.id,
          map_id = battle.map.name,
          mapId = battle.map.id,
          spectator = isSpectator,
          reArmorEnabled = true,
          skybox = mapRegistry.getSkybox(battle.map.skybox)
            .mapValues { (_, resource) -> resource.id }
            .toJson(),
          map_graphic_data = battle.map.visual.toJson()
        ).toJson()
      )
    ).send(socket)

    Command(
      CommandName.InitBonuses,
      listOf(
        listOf<InitBonusesData>().toJson()
      )
    ).send(socket)
  }

  suspend fun initLocal() {
    if(!isSpectator) {
      Command(CommandName.InitSuicideModel, listOf(10000.toString())).send(socket)
      Command(CommandName.InitStatisticsModel, listOf(battle.title)).send(socket)
    }

    battle.modeHandler.initModeModel(this)

    Command(
      CommandName.InitGuiModel,
      listOf(
        InitGuiModelData(
          name = battle.title,
          fund = battle.fund,
          scoreLimit = 300,
          timeLimit = 600,
          currTime = 212,
          team = team != BattleTeam.None,
          users = battle.players.users().map { player ->
            GuiUserData(
              nickname = player.user.username,
              rank = player.user.rank.value,
              teamType = player.team
            )
          }
        ).toJson()
      )
    ).send(socket)

    battle.modeHandler.initPostGui(this)
  }

  suspend fun initStage2() {
    if(isSpectator) {
      Command(
        CommandName.UpdateSpectatorsList,
        listOf(
          UpdateSpectatorsListData(
            spects = listOf(user.username)
          ).toJson()
        )
      ).send(this)
    }

    battle.modeHandler.playerJoin(this)

    // TODO(Assasans)
    if(isSpectator) {
      Command(
        CommandName.InitInventory,
        listOf(
          InitInventoryData(items = listOf()).toJson()
        )
      ).send(socket)
    } else {
      Command(
        CommandName.InitInventory,
        listOf(
          InitInventoryData(
            items = listOf(
              InventoryItemData(
                id = "health",
                count = 1000,
                slotId = 1,
                itemEffectTime = 20,
                itemRestSec = 20
              ),
              InventoryItemData(
                id = "armor",
                count = 1000,
                slotId = 2,
                itemEffectTime = 55,
                itemRestSec = 20
              ),
              InventoryItemData(
                id = "double_damage",
                count = 1000,
                slotId = 3,
                itemEffectTime = 55,
                itemRestSec = 20
              ),
              InventoryItemData(
                id = "n2o",
                count = 1000,
                slotId = 4,
                itemEffectTime = 55,
                itemRestSec = 20
              ),
              InventoryItemData(
                id = "mine",
                count = 1000,
                slotId = 5,
                itemEffectTime = 20,
                itemRestSec = 20
              )
            )
          ).toJson()
        )
      ).send(socket)
    }

    Command(
      CommandName.InitMineModel,
      listOf(
        InitMineModelSettings().toJson(),
        InitMineModelData().toJson()
      )
    ).send(socket)

    initTanks()

    if(!isSpectator) {
      // Command(
      //   CommandName.InitTank,
      //   listOf(
      //     InitTankData(
      //       battleId = battle.id,
      //       hull_id = "hunter_m0",
      //       turret_id = "railgun_m0",
      //       colormap_id = 966681,
      //       hullResource = 227169,
      //       turretResource = 906685,
      //       partsObject = "{\"engineIdleSound\":386284,\"engineStartMovingSound\":226985,\"engineMovingSound\":75329,\"turretSound\":242699}",
      //       tank_id = (tank ?: throw Exception("No Tank")).id,
      //       nickname = user.username,
      //       team_type = team.key
      //     ).toJson()
      //   )
      // ).send(socket)

      logger.info { "Load stage 2" }

      updateStats()
    }

    Command(
      CommandName.InitEffects,
      listOf(
        InitEffectsData().toJson()
      )
    ).send(socket)

    val tank = tank
    if(!isSpectator && tank != null) {
      tank.updateSpawnPosition()
      tank.prepareToSpawn()
    }

    spawnAnotherTanks()

    loadState = LoadState.Stage2Completed
  }

  suspend fun initTanks() {
    battle.players.forEach { player ->
      // Init other players to self
      if(player != this && !player.isSpectator) {
        val tank = player.tank ?: throw Exception("No Tank")

        Command(
          CommandName.InitTank,
          listOf(
            InitTankData(
              battleId = battle.id,
              hull_id = tank.hull.mountName,
              turret_id = tank.weapon.item.mountName,
              colormap_id = tank.coloring.marketItem.coloring,
              hullResource = tank.hull.modification.object3ds,
              turretResource = tank.weapon.item.modification.object3ds,
              partsObject = TankSoundsData().toJson(),
              tank_id = tank.id,
              nickname = player.user.username,
              team_type = player.team,
              state = tank.state.tankInitKey,
              health = tank.health,

              // Hull physics
              maxSpeed = tank.hull.modification.physics.speed,
              maxTurnSpeed = tank.hull.modification.physics.turnSpeed,
              acceleration = tank.hull.modification.physics.acceleration,
              reverseAcceleration = tank.hull.modification.physics.reverseAcceleration,
              sideAcceleration = tank.hull.modification.physics.sideAcceleration,
              turnAcceleration = tank.hull.modification.physics.turnAcceleration,
              reverseTurnAcceleration = tank.hull.modification.physics.reverseTurnAcceleration,
              dampingCoeff = tank.hull.modification.physics.damping,
              mass = tank.hull.modification.physics.mass,
              power = tank.hull.modification.physics.power,

              // Weapon physics
              turret_turn_speed = tank.weapon.item.modification.physics.turretRotationSpeed,
              turretTurnAcceleration = tank.weapon.item.modification.physics.turretTurnAcceleration,
              kickback = tank.weapon.item.modification.physics.kickback,
              impact_force = tank.weapon.item.modification.physics.impactForce,

              // Weapon visual
              sfxData = (tank.weapon.item.modification.visual
                         ?: tank.weapon.item.marketItem.modifications[0]!!.visual)!!.toJson() // TODO(Assasans)
            ).toJson()
          )
        ).send(socket)
      }

      // Init self to others
      if(!isSpectator) {
        val tank = tank ?: throw Exception("No Tank")

        Command(
          CommandName.InitTank,
          listOf(
            InitTankData(
              battleId = battle.id,
              hull_id = tank.hull.mountName,
              turret_id = tank.weapon.item.mountName,
              colormap_id = tank.coloring.marketItem.coloring,
              hullResource = tank.hull.modification.object3ds,
              turretResource = tank.weapon.item.modification.object3ds,
              partsObject = TankSoundsData().toJson(),
              tank_id = tank.id,
              nickname = user.username,
              team_type = team,
              state = tank.state.tankInitKey,
              health = tank.health,

              // Hull physics
              maxSpeed = tank.hull.modification.physics.speed,
              maxTurnSpeed = tank.hull.modification.physics.turnSpeed,
              acceleration = tank.hull.modification.physics.acceleration,
              reverseAcceleration = tank.hull.modification.physics.reverseAcceleration,
              sideAcceleration = tank.hull.modification.physics.sideAcceleration,
              turnAcceleration = tank.hull.modification.physics.turnAcceleration,
              reverseTurnAcceleration = tank.hull.modification.physics.reverseTurnAcceleration,
              dampingCoeff = tank.hull.modification.physics.damping,
              mass = tank.hull.modification.physics.mass,
              power = tank.hull.modification.physics.power,

              // Weapon physics
              turret_turn_speed = tank.weapon.item.modification.physics.turretRotationSpeed,
              turretTurnAcceleration = tank.weapon.item.modification.physics.turretTurnAcceleration,
              kickback = tank.weapon.item.modification.physics.kickback,
              impact_force = tank.weapon.item.modification.physics.impactForce,

              // Weapon visual
              sfxData = (tank.weapon.item.modification.visual
                         ?: tank.weapon.item.marketItem.modifications[0]!!.visual)!!.toJson() // TODO(Assasans)
            ).toJson()
          )
        ).send(player)
      }
    }
  }

  suspend fun spawnAnotherTanks() {
    battle.players.forEach { player ->
      // Spawn other players for self
      val tank = player.tank
      if(player != this && tank != null && !player.isSpectator) {
        Command(
          CommandName.SpawnTank,
          listOf(
            SpawnTankData(
              tank_id = tank.id,
              health = tank.health,
              incration_id = player.incarnation,
              team_type = player.team,
              x = tank.position.x,
              y = tank.position.y,
              z = tank.position.z,
              rot = tank.orientation.toEulerAngles().z,

              // Hull physics
              speed = tank.hull.modification.physics.speed,
              turn_speed = tank.hull.modification.physics.turnSpeed,
              acceleration = tank.hull.modification.physics.acceleration,
              reverseAcceleration = tank.hull.modification.physics.reverseAcceleration,
              sideAcceleration = tank.hull.modification.physics.sideAcceleration,
              turnAcceleration = tank.hull.modification.physics.turnAcceleration,
              reverseTurnAcceleration = tank.hull.modification.physics.reverseTurnAcceleration,

              // Weapon physics
              turret_rotation_speed = tank.weapon.item.modification.physics.turretRotationSpeed,
              turretTurnAcceleration = tank.weapon.item.modification.physics.turretTurnAcceleration
            ).toJson()
          )
        ).send(socket)

        if(isSpectator) {
          when(tank.state) {
            TankState.Active     -> {
              Command(CommandName.ActivateTank, listOf(tank.id)).send(socket)
            }

            // TODO(Assasans)
            TankState.Dead       -> Unit
            TankState.Respawn    -> Unit
            TankState.SemiActive -> Unit
          }
        }
      }
    }
  }

  suspend fun spawnTankForAnother() {
    battle.players.forEach { player ->
      if(player == this) return@forEach

      val tank = tank ?: throw Exception("No Tank")

      // Spawn self for other players
      if(!isSpectator) {
        Command(
          CommandName.SpawnTank,
          listOf(
            SpawnTankData(
              tank_id = tank.id,
              health = tank.health,
              incration_id = incarnation,
              team_type = team,
              x = tank.position.x,
              y = tank.position.y,
              z = tank.position.z,
              rot = tank.orientation.toEulerAngles().z,

              // Hull physics
              speed = tank.hull.modification.physics.speed,
              turn_speed = tank.hull.modification.physics.turnSpeed,
              acceleration = tank.hull.modification.physics.acceleration,
              reverseAcceleration = tank.hull.modification.physics.reverseAcceleration,
              sideAcceleration = tank.hull.modification.physics.sideAcceleration,
              turnAcceleration = tank.hull.modification.physics.turnAcceleration,
              reverseTurnAcceleration = tank.hull.modification.physics.reverseTurnAcceleration,

              // Weapon physics
              turret_rotation_speed = tank.weapon.item.modification.physics.turretRotationSpeed,
              turretTurnAcceleration = tank.weapon.item.modification.physics.turretTurnAcceleration
            ).toJson()
          )
        ).send(player)
      }
    }
  }

  suspend fun updateStats() {
    val tank = tank ?: throw Exception("No Tank")

    Command(
      CommandName.UpdatePlayerStatistics,
      listOf(
        UpdatePlayerStatisticsData(
          id = tank.id,
          rank = user.rank.value,
          team_type = team,
          score = score,
          kills = kills,
          deaths = deaths
        ).toJson()
      )
    ).sendTo(battle)
  }

  suspend fun createTank(): BattleTank {
    incarnation++

    val tank = BattleTank(
      id = user.username,
      player = this,
      incarnation = incarnation,
      state = TankState.Respawn,
      position = Vector3(0.0, 0.0, 1000.0),
      orientation = Quaternion(),
      hull = user.equipment.hull,
      weapon = when(user.equipment.weapon.id.itemName) {
        "railgun"      -> RailgunWeaponHandler(this, user.equipment.weapon)
        "thunder"      -> ThunderWeaponHandler(this, user.equipment.weapon)
        "isida"        -> IsidaWeaponHandler(this, user.equipment.weapon)
        "smoky"        -> SmokyWeaponHandler(this, user.equipment.weapon)
        "twins"        -> TwinsWeaponHandler(this, user.equipment.weapon)
        "flamethrower" -> FlamethrowerWeaponHandler(this, user.equipment.weapon)
        "freeze"       -> FreezeWeaponHandler(this, user.equipment.weapon)
        "ricochet"     -> RicochetWeaponHandler(this, user.equipment.weapon)
        "shaft"        -> ShaftWeaponHandler(this, user.equipment.weapon)

        else           -> NullWeaponHandler(this, user.equipment.weapon)
      },
      coloring = user.equipment.paint
    )

    this.tank = tank
    return tank
  }

  suspend fun respawn(): BattleTank {
    val tank = createTank()
    tank.updateSpawnPosition()
    tank.prepareToSpawn()

    return tank
  }
}
