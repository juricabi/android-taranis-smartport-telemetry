# Known and open, after 2.4.1

Everything else has shipped in 2.4.1. One thing is left.

## The 2D map camera stutters when you zoom in chase or locate

Pinching to zoom the 2D map while chase or locate is holding it on the
model makes the camera step instead of gliding — a plain, unfollowed map
zooms smoothly. Only the 2D view; the 3D one does not do it.

Almost certainly the same family as the long-standing 2D following jitter:
while chase or locate keep the map centred (and chase turns it heading-up),
a per-frame camera move fights the pinch, so the zoom lands in steps.

Do NOT retry blind — five earlier 2D-camera fixes were tried and reverted
for want of exactly this. The next attempt needs, on the phone: a screen
recording of the stutter, the link's actual fix rate, and instrumentation
of the camera-move callback during a pinch, to see which of the three is
stepping the zoom — the re-centre onto the model, the heading-up
re-orient, or the gesture-hold that lets a followed camera breathe.
