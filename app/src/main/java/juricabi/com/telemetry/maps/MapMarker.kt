package juricabi.com.telemetry.maps

interface MapMarker {
    var rotation: Float
    var position: Position
    var title: String
    var snippet: String

    /** A position and heading that belong to the same rendered moment. */
    fun place(position: Position, rotation: Float) {
        this.position = position
        this.rotation = rotation
    }

    fun setIcon(icon: Int, color: Int)
    fun remove()
}
