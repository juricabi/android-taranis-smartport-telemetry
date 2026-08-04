package juricabi.com.telemetry.maps

interface MapMarker {
    var rotation: Float
    var position: Position
    var title: String
    var snippet: String
    fun setIcon(icon: Int, color: Int)
    fun remove()
}