package juricabi.com.telemetry.manager

import juricabi.com.telemetry.maps.Position
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.util.*

class SensorTimeoutManager(protected val listener: SensorTimeoutManager.Listener): DataDecoder.Listener {

    interface Listener {
        fun onSensorTimeout( sensorId : Int )
        fun onSensorData( sensorId : Int )
        fun onTelemetryRate( rate : Int )
    }
    companion object {
        public const val SENSOR_GPS = 0;
        public const val SENSOR_DISTANCE = 1;
        public const val SENSOR_ALTITUDE = 2;
        public const val SENSOR_RSSI = 3;
        public const val SENSOR_VOLTAGE = 4;
        public const val SENSOR_CURRENT = 5;
        public const val SENSOR_SPEED = 6;
        public const val SENSOR_FUEL = 7;
        public const val SENSOR_RC_CHANNELS = 8;
        public const val SENSOR_STATUSTEXT = 9;
        public const val SENSOR_UP_LQ = 10;
        public const val SENSOR_DN_LQ = 11;
        public const val SENSOR_ELRS_MODE = 12;
        public const val SENSOR_DN_SNR = 13;
        public const val SENSOR_UP_SNR = 14;
        public const val SENSOR_ANT = 15;
        public const val SENSOR_POWER = 16;
        public const val SENSOR_RSSI_DBM_1 = 17;
        public const val SENSOR_RSSI_DBM_2 = 18;
        public const val SENSOR_RSSI_DBM_D = 19;
        public const val SENSOR_AIRSPEED = 20;
        public const val SENSOR_VSPEED = 21;
        public const val SENSOR_CELL_VOLTAGE = 22;
        public const val SENSOR_VBAT_OR_CELL = 23;
        public const val SENSOR_GPS_ALTITUDE = 24;
        public const val SENSOR_THROTTLE = 25;

        private const val SENSOR_COUNT = 26;

        private const val TIMER_INTERVAL_MS = 400;
        public const val DEFAULT_TIMEOUT_MS = 10000;
        // A high-latency link sends one frame per five seconds, so ten
        // seconds of quiet is one dropped frame. Grey out after three.
        public const val HIGH_LATENCY_TIMEOUT_MS = 15000;
        private const val RATE_UPDATE_INTERVAL_MS = 2000;
    }

    private var timeoutMS: IntArray = IntArray(SENSOR_COUNT)

    // how long a sensor may stay quiet before it counts as gone
    private var windowMS = DEFAULT_TIMEOUT_MS

    fun setTimeoutWindow(ms: Int) {
        windowMS = ms
    }

    private var mTimer: Timer? = null

    private var disabled : Boolean = false

    private var telemetrySize = 0
    private var lastRateUpdate = 0


    fun setTimeouts(t: Int){
        for( i in 0..SENSOR_COUNT-1){
            timeoutMS[i]=t;
        }
    }

    init {
        this.setTimeouts(0);
        lastRateUpdate = 0;
    }

    public fun resume()
    {
        if ( this.mTimer == null ) {
            this.mTimer = Timer();

            this.mTimer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    tick()
                }
            }, TIMER_INTERVAL_MS.toLong(), TIMER_INTERVAL_MS.toLong())
        }
    }

    /**
     * One 400ms beat: age every sensor towards the window, grey out the ones
     * that reach it, and update the telemetry rate. internal, not private, so a
     * test can drive the window deterministically instead of at wall-clock
     * speed — the Timer above is the only other caller.
     */
    internal fun tick() {
        for( i in 0..SENSOR_COUNT-1){
            if ( ( timeoutMS[i] < windowMS )){
                timeoutMS[i]+=TIMER_INTERVAL_MS;
                if ( timeoutMS[i] >= windowMS )
                {
                    if ( !disabled )
                    {
                        listener.onSensorTimeout(i)
                    }
                }
            }
        }

        lastRateUpdate += TIMER_INTERVAL_MS;
        if ( lastRateUpdate >= RATE_UPDATE_INTERVAL_MS) {
            listener.onTelemetryRate( telemetrySize * 1000 / RATE_UPDATE_INTERVAL_MS)
            lastRateUpdate = 0;
            telemetrySize = 0;
        }
    }

    public fun getSensorTimeout( sensorId : Int ) : Boolean {
        if ( this.disabled ) return false;
        return this.timeoutMS[sensorId]>=windowMS;
    }

    public fun pause() {
        if ( mTimer != null ) {
            this.mTimer?.cancel();
            this.mTimer = null;
        }
    }

    public fun disableTimeouts(){
        this.disabled = true;
        this.setTimeouts(0);
        for( i in 0..SENSOR_COUNT-1){
            this.listener.onSensorData(i);
        }
    }

    public fun enableTimeouts(){
        this.setTimeouts(0);
        this.disabled = false;
        for( i in 0..SENSOR_COUNT-1) {
            this.listener.onSensorData(i);
        }
    }

    private fun onSensorData( sensorId: Int) {
        if ( this.disabled ) return;

        var v = this.timeoutMS[sensorId];
        this.timeoutMS[sensorId] = 0;
        if ( v >= windowMS ) {
            this.listener.onSensorData(sensorId);
        }
    }

    override fun onConnectionFailed() {
    }

    override fun onFuelData(fuel: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_FUEL);
    }

    override fun onConnected(){
    }

    override fun onGPSData(latitude: Double, longitude: Double){
        this.onSensorData(SensorTimeoutManager.SENSOR_GPS);
    }

    override fun onGPSData(list: List<Position>, addToEnd: Boolean){
        this.onSensorData(SensorTimeoutManager.SENSOR_GPS);
    }

    override fun onVBATData(voltage: Float){
        this.onSensorData(SensorTimeoutManager.SENSOR_VOLTAGE);
    }

    override fun onCellVoltageData(voltage: Float){
        this.onSensorData(SensorTimeoutManager.SENSOR_CELL_VOLTAGE);
    }

    override fun onCurrentData(current: Float){
        this.onSensorData(SensorTimeoutManager.SENSOR_CURRENT);
    }

    override fun onHeadingData(heading: Float){

    }
    override fun onRSSIData(rssi: Int) {
        this.onSensorData(SensorTimeoutManager.SENSOR_RSSI);
    }

    override fun onUpLqData(lq: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_UP_LQ);
    }

    override fun onDnLqData(lq: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_DN_LQ);
    }

    override fun onElrsModeModeData(rf: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_ELRS_MODE);
    }

    override fun onDisconnected(){

    }
    override fun onGPSState(satellites: Int, gpsFix: Boolean){
        this.onSensorData(SensorTimeoutManager.SENSOR_GPS);
    }

    override fun onVSpeedData(vspeed: Float){
        this.onSensorData(SensorTimeoutManager.SENSOR_VSPEED);
    }

    override fun onThrottleData(throttle: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_THROTTLE);
    }

    override fun onAltitudeData(altitude: Float){
        this.onSensorData(SensorTimeoutManager.SENSOR_ALTITUDE);
    }

    override fun onGPSAltitudeData(altitude: Float){
        this.onSensorData(SensorTimeoutManager.SENSOR_GPS_ALTITUDE);
    }

    override fun onDistanceData(distance: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_DISTANCE);
    }

    override fun onRollData(rollAngle: Float){

    }

    override fun onPitchData(pitchAngle: Float){

    }

    override fun onGSpeedData(speed: Float){
        this.onSensorData(SensorTimeoutManager.SENSOR_SPEED);
    }

    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ){

    }

    override fun onAirSpeedData(speed: Float){
        this.onSensorData(SensorTimeoutManager.SENSOR_AIRSPEED);
    }

    override fun onRCChannels(rcChannels:IntArray){
        this.onSensorData(SensorTimeoutManager.SENSOR_RC_CHANNELS);
    }

    override fun onStatusText(message: String){
        this.onSensorData(SensorTimeoutManager.SENSOR_STATUSTEXT);
    }

    override fun onDNSNRData(snr: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_DN_SNR);
    }

    override fun onUPSNRData(snr: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_UP_SNR);
    }

    override fun onAntData(snr: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_ANT);
    }

    override fun onPowerData(power: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_POWER);
    }

    override fun onRssiDbm1Data(rssi: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_RSSI_DBM_1);
    }

    override fun onRssiDbm2Data(rssi: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_RSSI_DBM_2);
    }

    override fun onRssiDbmdData(rssi: Int){
        this.onSensorData(SensorTimeoutManager.SENSOR_RSSI_DBM_D);
    }

    override fun onVBATOrCellData(voltage: Float) {
        //VBAT OR cell_voltage are fired to SensorTimeoutManager
    }


    override fun onTelemetryByte(){
        this.telemetrySize++;
    }

    override fun onSuccessDecode(){

    }

    override fun onDecoderRestart(){

    }

    override fun onProtocolDetected( protocolName: String) {

    }

    override fun commit() {

    }

}