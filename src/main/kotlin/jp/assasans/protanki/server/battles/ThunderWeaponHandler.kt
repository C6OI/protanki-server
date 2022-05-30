package jp.assasans.protanki.server.battles

import jp.assasans.protanki.server.client.thunder.Fire
import jp.assasans.protanki.server.client.thunder.FireStatic
import jp.assasans.protanki.server.client.thunder.FireTarget
import jp.assasans.protanki.server.client.toJson
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.garage.ServerGarageUserItemWeapon

class ThunderWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
  suspend fun fire(fire: Fire) {
    val tank = player.tank ?: throw Exception("No Tank")

    Command(CommandName.Shot, listOf(tank.id, fire.toJson())).sendTo(tank.player.battle)
  }

  suspend fun fireStatic(static: FireStatic) {
    val tank = player.tank ?: throw Exception("No Tank")

    Command(CommandName.ShotStatic, listOf(tank.id, static.toJson())).sendTo(tank.player.battle)
  }

  suspend fun fireTarget(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val targetTank = battle.players
      .mapNotNull { player -> player.tank }
      .single { tank -> tank.id == target.target }
     if(targetTank.state != TankState.Active) return

    battle.damageProcessor.dealDamage(sourceTank, targetTank, 100.0, false)

    Command(CommandName.ShotTarget, listOf(sourceTank.id, target.toJson())).sendTo(sourceTank.player.battle)
  }
}
