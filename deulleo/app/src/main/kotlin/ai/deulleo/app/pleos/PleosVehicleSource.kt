package ai.deulleo.app.pleos

import ai.deulleo.core.vehicle.GearState
import ai.deulleo.core.vehicle.VehicleSnapshot
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Vehicle SDK v2.3.2를 감싸 [VehicleSnapshot] 흐름으로 바꿉니다.
 *
 * 차량 통신은 IPC라 모든 결과가 비동기 콜백으로 옵니다. 차종·에뮬레이터에 따라 지원하지 않는 기능이 있으므로
 * 실패는 로그만 남기고 해당 값은 null로 둡니다(앱은 계속 동작).
 * 에뮬레이터에서는 Pleos Connect Virtual Console(http://127.0.0.1:8088, `adb forward tcp:8088 tcp:8088`)로
 * 기어·도어 등을 바꿔 가며 시연할 수 있습니다.
 */
// Gear 도메인 API는 SDK에서 deprecated(안드로이드 표준 GEAR_SELECTION 속성 권장)입니다.
// 프로토타입은 Pleos API로 유지하고, 출시 전 CarPropertyManager(VehiclePropertyIds.GEAR_SELECTION)로 옮깁니다.
@Suppress("DEPRECATION")
class PleosVehicleSource(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val _state = MutableStateFlow(VehicleSnapshot())
    val state: StateFlow<VehicleSnapshot> = _state.asStateFlow()

    private val trunk = DoorArea(DoorValue.REAR)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = _state.update { it.copy(gear = gear.toGearState()) }
        override fun onFailed(e: Exception) = log("gear", e)
    }
    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(percent: Int?) = _state.update { it.copy(fuelPercent = percent) }
        override fun onFailed(e: Exception) = log("fuel", e)
    }
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(percent: Int?) = _state.update { it.copy(evSocPercent = percent) }
        override fun onFailed(e: Exception) = log("soc", e)
    }
    private val trunkListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) = _state.update { it.copy(trunkOpen = (pos ?: 0) > 0) }
        override fun onFailed(e: Exception) = log("trunk", e)
    }
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) = applyTelltales(telltales)
        override fun onFailed(e: Exception) = log("telltale", e)
    }

    fun start() {
        safely("initialize") { vehicle.initialize() }

        val gear = vehicle.getGear()
        safely("gear.get") { gear.getCurrentGearSelectionState({ g -> _state.update { it.copy(gear = g.toGearState()) } }, { log("gear.get", it) }) }
        safely("gear.register") { gear.registerGearSelection(gearListener) }

        val fuel = vehicle.getFuel()
        safely("fuel.get") { fuel.getFuelPercent({ p -> _state.update { it.copy(fuelPercent = p) } }, { log("fuel.get", it) }) }
        safely("fuel.register") { fuel.registerFuelPercent(fuelListener) }

        val ev = vehicle.getEvBattery()
        safely("soc.get") { ev.getEvBatteryStateOfCharge({ p -> _state.update { it.copy(evSocPercent = p) } }, { log("soc.get", it) }) }
        safely("soc.register") { ev.registerEvBatteryStateOfCharge(socListener) }

        safely("trunk.register") { vehicle.getDoor().registerDoorPosition(trunk, trunkListener) }

        val safety = vehicle.getSafety()
        safely("telltale.get") { safety.getTelltalesInfo({ applyTelltales(it) }, { log("telltale.get", it) }) }
        safely("telltale.register") { safety.registerTelltales(telltaleListener) }
        safely("oil.get") {
            safety.getEngineOilLevelState({ s ->
                _state.update { it.copy(engineOilLow = s == OilLevelState.LOW || s == OilLevelState.CRITICALLY_LOW) }
            }, { log("oil.get", it) })
        }
        safely("cabin.get") {
            vehicle.getHVAC().getHvacTemperatureCurrent(driverSeat, { t -> _state.update { it.copy(cabinTempC = t) } }, { log("cabin.get", it) })
        }
    }

    /** 신선식품을 실었을 때 실내 온도를 낮춥니다(CONTROL_CAR_CLIMATE). */
    fun setCabinTemperature(celsius: Float, onDone: (Boolean) -> Unit = {}) {
        safely("hvac.set") {
            vehicle.getHVAC().setHvacTemperature(
                driverSeat, celsius, TemperatureUnit.CELSIUS,
                { _, _ -> onDone(true) },
                { log("hvac.set", it); onDone(false) },
            )
        }
    }

    /** 실제 경고등이 없는 에뮬레이터에서 시연할 때 쓰는 스위치. */
    fun simulateLowTirePressure(on: Boolean) = _state.update { it.copy(lowTirePressure = on) }

    fun stop() {
        safely("unregister") {
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getFuel().unregisterFuelPercent(fuelListener)
            vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
            vehicle.getDoor().unregisterDoorPosition(trunk, trunkListener)
            vehicle.getSafety().unregisterTelltales(telltaleListener)
        }
        safely("release") { vehicle.release() }
    }

    private fun applyTelltales(telltales: Telltales?) {
        val on = telltales?.telltales.orEmpty()
            .filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }
            .map { it.type }
            .toSet()
        _state.update {
            it.copy(
                lowTirePressure = TelltaleType.TPMS in on,
                engineOilLow = it.engineOilLow || TelltaleType.ENGINE_OIL_PRESSURE in on,
            )
        }
    }

    private fun VehicleGear?.toGearState(): GearState = when (this) {
        VehicleGear.GEAR_PARK -> GearState.PARK
        VehicleGear.GEAR_REVERSE -> GearState.REVERSE
        VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
        else -> GearState.DRIVE // GEAR_DRIVE, GEAR_1..9
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { log(what, e) }
    }

    private fun log(what: String, e: Exception) {
        Log.w(TAG, "Vehicle $what 실패: ${e.message}")
    }

    private companion object { const val TAG = "Deulleo" }
}
