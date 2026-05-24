package tk.glucodata.drivers

interface ManagedSensorMaintenanceDriver {
    fun shouldDeleteLocalSensorDirectoryOnWipe(): Boolean = false

    fun supportsResetAction(): Boolean = false

    fun sendMaintenanceCommand(opCode: Int): Boolean = false

    fun resetSensor(): Boolean = false

    fun supportsClearCalibrationAction(): Boolean = false

    fun clearSensorCalibration(): Boolean = false

    fun enableResetCompensation() {}

    fun disableResetCompensation() {}

    fun startNewSensor(): Boolean = false

    fun calibrateSensor(glucoseMgDl: Int): Boolean = false

    fun unpairSensor(): Boolean = false

    fun rePairSensor() {}
}
