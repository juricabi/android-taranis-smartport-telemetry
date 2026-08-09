# Known and open, after 2.4.0

Three residuals, accepted for 2.4.0 and recorded here with what was
learnt, so the next attempt starts from knowledge instead of theories.

## 3D: transient artifacts that clear on full load

Brief steps and slivers while fresh ground loads hard, wherever the
drawn cover stands three or more levels above the wanted tile: a vertex
carries only its parent's and grandparent's lines, so a deeper stand
leaves a step "the size the data allows". They clear as the frontier
catches up, usually in seconds.

- The lever, if ever wanted: keep neighbouring *selection* levels within
  two of each other (a restricted-quadtree rule in the pager's visit),
  or a third lattice level in the vertices (heavy: +2 floats/vertex).
- Do NOT reshape geometry at draw time. Tried and reverted 2026-08-09:
  edge-force easing (opened transient cracks between agreeing
  neighbours) and a quarter-tile force ramp (turned the one-cell notch
  into a standing wedge).

## 2D: the camera keeping up in visible steps when zoomed out

Following a moving model at low zoom, the camera advances in one-second
hops instead of an even walk (fix-rate cadence). Accepted for 2.4.0.

- Five draw-side fixes were tried in one afternoon and all reverted
  (steady raster hold, longer hold release, glide-home on resume, a
  slower homecoming ease, walk-delay retuning) — none changed what the
  user saw. Do not retry these blind.
- Best unconfirmed lead: `rememberMotionFix` wipes its interpolation
  history at gap > 1000ms — a 1Hz GPS link jitters exactly there — and
  `walkDelayMs` caps at 250ms, which cannot bridge a 1s fix gap
  ("a real fix on either side of every drawn frame" fails for links
  slower than 4Hz).
- A next attempt needs, before any code: a screen recording of the
  jump, the link's actual fix rate, and camera-callback logging — the
  1Hz cadence must be matched to a specific writer before changing one.

## Imagery: tone seams between sharpness steps

Adjacent tiles briefly wear different sharpness levels, and the
provider photographs its zoom levels on different days — the seam is a
colour change in the source data, not in the renderer. The dissolves
soften each step and the seam settles once both sides reach the same
sharpness.

- Possible lever: hold neighbouring tiles to the same sharpness step
  before showing an upgrade. Costs visible loading speed; decide with a
  side-by-side, not by taste.

## 3D: a new flight rebuilds the world even when it is the same world

Connecting (or opening a replay) rebuilds the 3D ground from scratch,
because origin and altitude datum are per-flight questions that must
never flip mid-flight — while the 2D map, which has neither, keeps its
tiles. Connecting at the field you are already looking at pays a full
reload to reach the same two answers.

- The gentle design, from pieces that already exist: on a new flight,
  re-open the two questions instead of discarding the world —
  `reanchorIfFar` already keeps the origin when the flight is near, and
  the datum-settling road already rebuilds only when its answer moves.
  Rebuild only if either answer actually changes.
- This is surgery on the altitude frame, the most correctness-critical
  machinery here (a wrong datum buries the model or floats it). Do it
  as a dedicated session with the replay + live + Betaflight
  above-launch cases all retested, not as a patch.
