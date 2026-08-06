package juricabi.com.telemetry.gl

/**
 * The three model shapes, built as triangles and nothing else.
 *
 * No GL in here, deliberately: the renderer uploads what comes out, and a
 * plain JVM test can dump the same triangles to be looked at before they are
 * ever drawn on a phone. Positions are the model's own frame — x right,
 * y up, z aft — with the nose along -z.
 *
 * The three share one design language: a fuselage lofted through rectangular
 * ribs, tapering to a wedge of a nose; everything that spins is a thin disc
 * with enough sides to read as round; posts and struts are slender boxes.
 */
internal object ModelMeshes {

    /**
     * Geometry for a model: position, which corner of its triangle each
     * vertex is, and a normal. The normal is what the light reads, and is the
     * whole difference between a model and a silhouette; the corner weights
     * are what let one pass both fill a face and draw its edges.
     */
    class Solid {
        val out = ArrayList<Float>()

        /**
         * [hide] names edges that are not really there. Bit j holds the weight
         * of the edge opposite vertex j at one, so a fragment never approaches
         * a border there and the shader draws no line.
         *
         * A rectangle is two triangles with a seam down the middle. The seam is
         * not an edge of the model, and drawing it puts a diagonal across every
         * flat face.
         */
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray, hide: Int = 0) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
            if (len > 0.00001f) { nx /= len; ny /= len; nz /= len }
            for (i in 0..2) {
                val p = when (i) { 0 -> a; 1 -> b; else -> c }
                out.add(p[0]); out.add(p[1]); out.add(p[2])
                // one at its own corner, nought at the others: the weights fall
                // to nought along the far edge, which is how near one is measured
                for (j in 0..2) {
                    out.add(if (j == i || (hide shr j) and 1 == 1) 1f else 0f)
                }
                out.add(nx); out.add(ny); out.add(nz)
            }
        }

        /** A flat four cornered face, seamless: only its border is an edge. */
        fun face(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) {
            tri(a, b, c, 1 shl 1)
            tri(a, c, d, 1 shl 2)
        }

        /** A box, from its two opposite corners. */
        fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
            val a = floatArrayOf(x0, y0, z0); val b = floatArrayOf(x1, y0, z0)
            val c = floatArrayOf(x1, y0, z1); val d = floatArrayOf(x0, y0, z1)
            val e = floatArrayOf(x0, y1, z0); val f = floatArrayOf(x1, y1, z0)
            val g = floatArrayOf(x1, y1, z1); val h = floatArrayOf(x0, y1, z1)
            face(e, f, g, h)
            face(a, d, c, b)
            face(a, b, f, e)
            face(d, h, g, c)
            face(a, e, h, d)
            face(b, c, g, f)
        }

        /** A box laid along a direction in the ground plane: an arm, or a wing. */
        fun arm(dirX: Float, dirZ: Float, from: Float, to: Float,
                halfWidth: Float, y0: Float, y1: Float, alongZ: Float = 0f) {
            val px = -dirZ
            val pz = dirX
            fun at(along: Float, side: Float, y: Float) = floatArrayOf(
                dirX * along + px * side, y, dirZ * along + pz * side + alongZ)
            val a = at(from, -halfWidth, y0); val b = at(to, -halfWidth, y0)
            val c = at(to, halfWidth, y0);    val d = at(from, halfWidth, y0)
            val e = at(from, -halfWidth, y1); val f = at(to, -halfWidth, y1)
            val g = at(to, halfWidth, y1);    val h = at(from, halfWidth, y1)
            face(e, f, g, h)
            face(a, d, c, b)
            face(a, b, f, e)
            face(d, h, g, c)
            face(a, e, h, d)
            face(b, c, g, f)
        }

        /** A short many sided post: a motor, or a propeller when it is flat. */
        fun post(cx: Float, cz: Float, radius: Float, y0: Float, y1: Float, sides: Int) {
            val spokes = (1 shl 1) or (1 shl 2)
            for (i in 0 until sides) {
                val a0 = 2.0 * Math.PI * i / sides
                val a1 = 2.0 * Math.PI * (i + 1) / sides
                val x0 = cx + (radius * Math.cos(a0)).toFloat()
                val z0 = cz + (radius * Math.sin(a0)).toFloat()
                val x1 = cx + (radius * Math.cos(a1)).toFloat()
                val z1 = cz + (radius * Math.sin(a1)).toFloat()
                face(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z1),
                     floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z0))
                // flat caps, drawn as a fan from the centre. Only the rim is an
                // edge — the spokes would draw a cartwheel on every motor.
                tri(floatArrayOf(cx, y1, cz), floatArrayOf(x0, y1, z0),
                    floatArrayOf(x1, y1, z1), spokes)
                tri(floatArrayOf(cx, y0, cz), floatArrayOf(x1, y0, z1),
                    floatArrayOf(x0, y0, z0), spokes)
            }
        }

        /**
         * A vertical disc standing in the y-z plane at [cx]: a tail rotor, or
         * a propeller seen from the side. Faced both ways, since a plate this
         * thin is looked at from either.
         */
        fun sideDisc(cx: Float, cy: Float, cz: Float, radius: Float, sides: Int) {
            val spokes = (1 shl 1) or (1 shl 2)
            for (i in 0 until sides) {
                val a0 = 2.0 * Math.PI * i / sides
                val a1 = 2.0 * Math.PI * (i + 1) / sides
                val y0 = cy + (radius * Math.cos(a0)).toFloat()
                val z0 = cz + (radius * Math.sin(a0)).toFloat()
                val y1 = cy + (radius * Math.cos(a1)).toFloat()
                val z1 = cz + (radius * Math.sin(a1)).toFloat()
                tri(floatArrayOf(cx, cy, cz), floatArrayOf(cx, y0, z0),
                    floatArrayOf(cx, y1, z1), spokes)
                tri(floatArrayOf(cx, cy, cz), floatArrayOf(cx, y1, z1),
                    floatArrayOf(cx, y0, z0), spokes)
            }
        }

        /** One rectangular rib of a fuselage: corners bl, br, tr, tl at [z]. */
        fun rib(x0: Float, y0: Float, x1: Float, y1: Float, z: Float) = arrayOf(
            floatArrayOf(x0, y0, z), floatArrayOf(x1, y0, z),
            floatArrayOf(x1, y1, z), floatArrayOf(x0, y1, z))

        /** The walls between two ribs: how a fuselage tapers and swells. */
        fun loft(a: Array<FloatArray>, b: Array<FloatArray>) {
            face(a[0], b[0], b[1], a[1])   // floor
            face(a[3], a[2], b[2], b[3])   // roof
            face(a[0], a[3], b[3], b[0])   // left
            face(a[1], b[1], b[2], a[2])   // right
        }

        /** Close a rib off: [front] caps the nose end, otherwise the tail. */
        fun cap(r: Array<FloatArray>, front: Boolean) {
            if (front) face(r[0], r[1], r[2], r[3])
            else face(r[0], r[3], r[2], r[1])
        }

        fun build(): FloatArray {
            val array = FloatArray(out.size)
            for (i in out.indices) array[i] = out[i]
            return array
        }
    }

    /**
     * The same mesh, larger: positions scaled, corners and normals untouched.
     *
     * All three models are drawn at one screen size, so a mesh's own extent
     * is its size beside the others — and the heli and plane were built
     * smaller than the quad's prop-tip reach.
     */
    private fun scaled(mesh: FloatArray, factor: Float): FloatArray {
        var i = 0
        while (i < mesh.size) {
            mesh[i] *= factor; mesh[i + 1] *= factor; mesh[i + 2] *= factor
            i += 9
        }
        return mesh
    }

    /**
     * A quad, built the way a military airframe is: a faceted hull over plate
     * arms, the sensor pod under the chin, aerials on the tail deck.
     */
    fun quad(): FloatArray {
        val s = Solid()
        val d = 0.7071f
        for (c in arrayOf(floatArrayOf(d, -d), floatArrayOf(-d, -d),
                          floatArrayOf(d, d), floatArrayOf(-d, d))) {
            // Flat plate arms, flush with the belly they bolt under, ending
            // in a rounded tip flush with the motor's far edge.
            s.arm(c[0], c[1], 0.15f, 1.03f, 0.088f, -0.045f, -0.005f)
            s.post(c[0] * 1.03f, c[1] * 1.03f, 0.088f, -0.045f, -0.005f, 10)
            // Into the arm, not resting on it: two faces at one height are a
            // coin toss for the depth buffer, and it comes down differently
            // from one frame to the next. A wider base with the bell above
            // it, so a motor reads as a motor rather than a peg.
            s.post(c[0], c[1], 0.115f, -0.008f, 0.05f, 10)
            s.post(c[0], c[1], 0.095f, 0.05f, 0.19f, 10)
            s.post(c[0], c[1], 0.42f, 0.20f, 0.225f, 14)
        }
        // The body: a low faceted hull with a pointed nose, chiselled the way
        // military airframes are — wide over the electronics, falling away
        // fore and aft in flat panels.
        val tail = s.rib(-0.16f, -0.03f, 0.16f, 0.12f, 0.42f)
        val shoulderAft = s.rib(-0.24f, -0.045f, 0.24f, 0.17f, 0.20f)
        val shoulderFore = s.rib(-0.24f, -0.045f, 0.24f, 0.17f, -0.16f)
        val cheek = s.rib(-0.10f, -0.02f, 0.10f, 0.12f, -0.44f)
        val tip = s.rib(-0.03f, 0.01f, 0.03f, 0.07f, -0.56f)
        s.cap(tail, front = false)
        s.loft(shoulderAft, tail)
        s.loft(shoulderFore, shoulderAft)
        s.loft(cheek, shoulderFore)
        s.loft(tip, cheek)
        s.cap(tip, front = true)
        // the sensor pod under the chin, set into the hull rather than beside
        // it — the joint is an overlap, so no seam of daylight can open
        s.box(-0.065f, -0.10f, -0.47f, 0.065f, -0.01f, -0.33f)
        // aerial stubs on the tail deck
        s.post(-0.08f, 0.36f, 0.012f, 0.12f, 0.30f, 6)
        s.post(0.08f, 0.36f, 0.012f, 0.12f, 0.30f, 6)
        return s.build()
    }

    /** A plane: tapering fuselage, swept tapered wings, fin and tailplane. */
    fun plane(): FloatArray {
        val s = Solid()
        // fuselage, tail to nose, with the canopy's hump amidships
        val tail = s.rib(-0.05f, 0.00f, 0.05f, 0.12f, 0.85f)
        val waist = s.rib(-0.11f, -0.08f, 0.11f, 0.16f, 0.30f)
        val canopy = s.rib(-0.12f, -0.09f, 0.12f, 0.30f, -0.15f)
        val chest = s.rib(-0.11f, -0.08f, 0.11f, 0.16f, -0.60f)
        val nose = s.rib(-0.05f, -0.04f, 0.05f, 0.08f, -1.10f)
        s.cap(tail, front = false)
        s.loft(waist, tail)
        s.loft(canopy, waist)
        s.loft(chest, canopy)
        s.loft(nose, chest)
        s.cap(nose, front = true)

        // Wings: a swept, tapering loft from root rib to tip rib, each rib a
        // thin aerofoil slab in the z-y plane. The tip sits aft of the root,
        // which is the sweep, and is shorter and thinner, which is the taper.
        fun wingRib(x: Float, zLead: Float, zTrail: Float, y: Float, thick: Float) =
            arrayOf(
                floatArrayOf(x, y, zLead), floatArrayOf(x, y, zTrail),
                floatArrayOf(x, y + thick, zTrail), floatArrayOf(x, y + thick, zLead))
        for (side in intArrayOf(1, -1)) {
            val root = wingRib(0.10f * side, -0.10f, 0.42f, 0.04f, 0.055f)
            val tip = wingRib(1.32f * side, 0.14f, 0.42f, 0.055f, 0.03f)
            s.loft(root, tip)
            s.cap(tip, front = side < 0)
            // and the tailplane, the same idea in miniature
            val tRoot = wingRib(0.05f * side, 0.52f, 0.80f, 0.055f, 0.035f)
            val tTip = wingRib(0.55f * side, 0.62f, 0.84f, 0.06f, 0.02f)
            s.loft(tRoot, tTip)
            s.cap(tTip, front = side < 0)
        }

        // the fin: a swept loft upward, ribs lying in the x-z plane
        fun finRib(y: Float, z0: Float, z1: Float, halfW: Float) = arrayOf(
            floatArrayOf(-halfW, y, z0), floatArrayOf(halfW, y, z0),
            floatArrayOf(halfW, y, z1), floatArrayOf(-halfW, y, z1))
        val finRoot = finRib(0.10f, 0.48f, 0.84f, 0.035f)
        val finTop = finRib(0.52f, 0.68f, 0.86f, 0.015f)
        s.loft(finRoot, finTop)
        s.cap(finTop, front = true)

        return scaled(s.build(), 1.1f)
    }

    /** A helicopter: lofted cabin, boom and fin, skids, main and tail rotors. */
    fun heli(): FloatArray {
        val s = Solid()
        // cabin, tail to nose: the boom's stub, the cabin's bulk, the
        // windscreen falling to a wedge of a nose
        val stub = s.rib(-0.07f, 0.04f, 0.07f, 0.20f, 0.34f)
        val back = s.rib(-0.22f, -0.08f, 0.22f, 0.30f, 0.14f)
        val screen = s.rib(-0.20f, -0.08f, 0.20f, 0.26f, -0.36f)
        val nose = s.rib(-0.05f, -0.03f, 0.05f, 0.11f, -0.68f)
        s.cap(stub, front = false)
        s.loft(back, stub)
        s.loft(screen, back)
        s.loft(nose, screen)
        s.cap(nose, front = true)

        // the boom, tapering to the tail, with a small tailplane across it
        val boomRoot = s.rib(-0.06f, 0.05f, 0.06f, 0.19f, 0.34f)
        val boomEnd = s.rib(-0.03f, 0.10f, 0.03f, 0.18f, 1.02f)
        s.loft(boomRoot, boomEnd)
        s.cap(boomEnd, front = false)
        s.box(-0.20f, 0.115f, 0.74f, 0.20f, 0.15f, 0.82f)

        // the fin, swept back off the boom's end, lofted like the plane's
        fun finRib(y: Float, z0: Float, z1: Float, halfW: Float) = arrayOf(
            floatArrayOf(-halfW, y, z0), floatArrayOf(halfW, y, z0),
            floatArrayOf(halfW, y, z1), floatArrayOf(-halfW, y, z1))
        val finRoot = finRib(0.12f, 0.88f, 1.04f, 0.030f)
        val finTop = finRib(0.50f, 0.98f, 1.08f, 0.014f)
        s.loft(finRoot, finTop)
        s.cap(finTop, front = true)

        // the tail rotor on the fin: hub through it, disc turning beside it,
        // and a small cap proud of the disc so it reads as a hub, not a plate
        s.box(0.014f, 0.27f, 0.99f, 0.095f, 0.33f, 1.05f)
        s.sideDisc(0.095f, 0.30f, 1.02f, 0.20f, 14)
        s.sideDisc(0.105f, 0.30f, 1.02f, 0.045f, 8)

        // Skids, each hung on two struts. The struts lean inward from skid to
        // belly, because straight down from the skid there is no belly to
        // meet — they stood beside the hull touching nothing.
        s.box(-0.30f, -0.32f, -0.52f, -0.24f, -0.26f, 0.36f)
        s.box(0.24f, -0.32f, -0.52f, 0.30f, -0.26f, 0.36f)
        fun strutRing(x0: Float, x1: Float, y: Float, z: Float) = arrayOf(
            floatArrayOf(x0, y, z - 0.022f), floatArrayOf(x1, y, z - 0.022f),
            floatArrayOf(x1, y, z + 0.022f), floatArrayOf(x0, y, z + 0.022f))
        for (side in intArrayOf(1, -1)) {
            for (z in floatArrayOf(-0.30f, 0.08f)) {
                val foot = strutRing(0.24f * side, 0.28f * side, -0.27f, z)
                val hip = strutRing(0.10f * side, 0.15f * side, -0.06f, z)
                s.loft(foot, hip)
            }
        }

        // the mast, and the main rotor over everything — high enough to be a
        // rotor over a cabin rather than a lid on it
        s.post(0f, -0.05f, 0.04f, 0.30f, 0.52f, 8)
        s.post(0f, -0.05f, 0.62f, 0.52f, 0.55f, 16)
        return scaled(s.build(), 1.35f)
    }
}
