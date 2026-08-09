# Known and open, after 2.4.0

Residuals, accepted and recorded here with what was learnt, so the next
attempt starts from knowledge instead of theories.

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

## 3D: where the plain camera stands when a view is entered

With neither following nor chase lit, the ground view arrives wherever
the camera was last left — which after a chase is twenty-two degrees,
low enough that a model can sit off the top of the screen or behind a
hill. Reported 2026-08-09 as "3D normal cam too low".

- Six changes were tried in one sitting and all reverted (frame each new
  flight over a kept world; the opening angle and lean with it; standing
  the camera up when a chase is dropped; the locate camera on arrival;
  scoping that to the plain camera; one road out of a chase). Each fixed
  what it aimed at and left the camera wrong somewhere else. The verdict
  was "it was good enough" — do not retry these blind.
- What is true and was learnt: three places let go of a chase and only
  `setChasing(false)` knows how; `setFollowing(false)` and
  `followingOff()` drop the flag directly; and a parked world is told
  `setChasing(true)` but never `setChasing(false)`, so a world parked
  while chasing comes back still chasing and still stooped.
- If it is ever picked up again, that asymmetry is the honest starting
  point, and it should be fixed on its own — without touching where the
  camera is put on arriving, which is what made every attempt spread.

## Imagery: tone seams between sharpness steps

Adjacent tiles briefly wear different sharpness levels, and the
provider photographs its zoom levels on different days — the seam is a
colour change in the source data, not in the renderer. The dissolves
soften each step and the seam settles once both sides reach the same
sharpness.

- Possible lever: hold neighbouring tiles to the same sharpness step
  before showing an upgrade. Costs visible loading speed; decide with a
  side-by-side, not by taste.

## Done since 2.4.0

**A new flight keeps its world (c887324, in 2.4.0).** Connect,
replay-open and replay-close re-open the per-flight questions over the
standing ground instead of rebuilding, whenever their subject is within
fifty kilometres of it. Retest the three altitude cases — replay, live,
Betaflight above-launch — before trusting it fully.

**A flight ends when the person says so.** Pressing Disconnect ends it
and brings both views home; a link that drops keeps everything, because
that is the one the model may be lying in a field after. Beyond the
world's ground with nothing connected, the locate button offers the same
ending rather than only refusing. Nothing is thrown away when nothing was
recorded — asked of the service, which knows whether a file opened.

**Longitude crosses the 180th meridian.** One convention: every
difference answers the short way round, an extent is counted on from the
flight's own first point, and a real longitude is handed out wrapped.
The root ring and the elevation tile box walk eastward and wrap instead
of counting between two column numbers — which used to select the whole
globe and then refuse the work. Held up by unit tests that fail on the
old arithmetic (`AntimeridianTest`), since nobody here can fly to Fiji.

**The map stops turning to rainbow.** The custom native layer gave back
the GL context it borrows instead of leaving its own program, buffers
and pixel unpack alignment set — which corrupted every texture MapLibre
uploaded afterwards, so zooming into fresh ground came up in coloured
bands. Present in released 2.4.0, and visible to anyone with a model on
screen.

**The sky knows where to measure from.** Traffic warnings measure from
the model while it is drawn and from the phone whenever it is not, and
say which; a replay has no sky at all. The test is the same one the
marker uses — a place, and a fix to believe it by — so a receiver
reporting the place it remembers cannot move the measurement.

**Smaller, from testing.** Lines clear themselves rather than hanging
from a model that has gone; the height question keeps being asked after
a flight stops, so a stopped flight cannot stay drawn under the hill it
flew over; deleting the log being replayed no longer leaves the sky
switched off; and ending a flight ends the reconnect loop with it.

**What four audits found and fixed.** A parked world drew the dead
flight, and handed back a closed replay's operator arrow; an adopted
world never picked up a flight that began while it was parked; a far
replay opened over the standing world; closing a replay with no phone fix
left a blank holder; chase kept steering a vanished model; late fixes
resurrected an ended flight; the map lean and the traffic warnings
outlived the flight they belonged to. And the world re-anchor is asked
once per flight now, so a 200 km log no longer tears its world down every
hundred and a seek back to the start no longer tears it down again.
