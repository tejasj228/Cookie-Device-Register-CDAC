/**
 * The maths behind the floating shapes, kept apart from the React component.
 *
 * Everything here is a pure function of its arguments (plus, for `separate`,
 * a deliberate mutation of the body it is given). That is the point: the
 * collision resolution had a genuine bug in an earlier version that was
 * invisible until it was measured, and arithmetic this fiddly deserves to be
 * testable without a browser, a DOM, or a running animation frame.
 */

/**
 * Ambient speed, in pixels per 60Hz frame. Deliberately barely-there: this is
 * atmosphere, and anything you can watch move is too fast.
 */
export const DRIFT_SPEED = 0.11;

/** Energy kept after a bounce. Below 1 so a thrown shape settles down. */
export const RESTITUTION = 0.78;

/** Air resistance, applied only while a shape is moving faster than ambient. */
export const FRICTION = 0.988;

/** Stops a flick of the wrist launching a shape across the screen in one frame. */
export const MAX_THROW = 42;

/**
 * A bounce only goes BONK above this speed.
 *
 * Without a floor here the ambient drift would set off a burst every time it
 * kissed a wall, and the joke would be dead within a minute. Quiet bounces
 * stay quiet; only a real throw makes a noise.
 */
export const IMPACT_SPEED = 2.4;

/** One shape cannot BONK twice inside this window, in milliseconds. */
export const IMPACT_COOLDOWN = 220;

/** How much cursor history counts towards a throw, in milliseconds. */
export const THROW_WINDOW = 90;

/**
 * Push a body out of a rectangle along whichever edge is nearest.
 *
 * Mutates `body.x` / `body.y` and returns the axis it left through ("x" or
 * "y"), or null if there was no overlap to begin with.
 *
 * The four distances below are measured to the FAR edge, NOT the size of the
 * overlap. That distinction is the whole function: overlap size is only the
 * right distance to move while a shape straddles an edge. Once a shape is
 * fully inside on an axis, the overlap equals the shape's own size, and
 * pushing by it leaves the shape still buried inside the obstacle. That was a
 * real bug, and `separatesFullyContainedBody` in the tests is what pins it.
 */
export function separate(body, box) {
  const { x, y, w, h } = body;
  if (x + w <= box.left || x >= box.right) return null;
  if (y + h <= box.top || y >= box.bottom) return null;

  const outUp = y + h - box.top;
  const outDown = box.bottom - y;
  const outLeft = x + w - box.left;
  const outRight = box.right - x;
  const nearest = Math.min(outUp, outDown, outLeft, outRight);

  if (nearest === outUp) {
    body.y -= outUp;
    return "y";
  }
  if (nearest === outDown) {
    body.y += outDown;
    return "y";
  }
  if (nearest === outLeft) {
    body.x -= outLeft;
    return "x";
  }
  body.x += outRight;
  return "x";
}

/**
 * Turn a short history of cursor positions into a throw.
 *
 * `trail` is a list of `{ x, y, t }` samples, oldest first. The result is in
 * pixels per 60Hz frame so it can be handed straight to the physics loop, and
 * is capped at MAX_THROW so a fast flick cannot fire a shape off-screen in a
 * single step.
 *
 * Returns null when there is nothing to go on — a single sample, or a drag
 * that ended with a pause, which should read as "put down" rather than
 * "thrown".
 */
export function throwVelocity(trail) {
  if (!trail || trail.length < 2) return null;

  const first = trail[0];
  const last = trail[trail.length - 1];
  const elapsed = last.t - first.t;
  if (elapsed <= 0) return null;

  // pixels per millisecond -> pixels per frame
  let vx = ((last.x - first.x) / elapsed) * 16.7;
  let vy = ((last.y - first.y) / elapsed) * 16.7;

  const speed = Math.hypot(vx, vy);
  if (speed > MAX_THROW) {
    vx = (vx / speed) * MAX_THROW;
    vy = (vy / speed) * MAX_THROW;
  }
  return { vx, vy, speed: Math.min(speed, MAX_THROW) };
}

/**
 * Drop samples older than THROW_WINDOW, so pausing before you let go reads as
 * putting the shape down rather than throwing it. Mutates and returns `trail`.
 */
export function trimTrail(trail, now) {
  while (trail.length > 2 && now - trail[0].t > THROW_WINDOW) trail.shift();
  return trail;
}

/**
 * Collide two bodies with each other.
 *
 * Mutates both and returns the contact point for a single shared impact
 * burst, or null if they were not touching. Two shapes hitting each other is
 * ONE event, so it gets one BONK, placed midway between them.
 *
 * `heldA` / `heldB` mark a body the pointer is currently driving. A held shape
 * does not get moved or deflected — you are holding it — so it acts as an
 * immovable object and the free shape takes the whole separation and the
 * bounce. Without this, dragging one shape into another would tear it out of
 * your hand.
 *
 * The velocity exchange is the equal-mass elastic result: along the axis of
 * collision, the two simply swap. Damped by RESTITUTION so the system loses
 * energy and eventually settles instead of rattling forever.
 */
export function collidePair(a, b, heldA = false, heldB = false) {
  if (a.x + a.w <= b.x || b.x + b.w <= a.x) return null;
  if (a.y + a.h <= b.y || b.y + b.h <= a.y) return null;

  const pushLeft = a.x + a.w - b.x; // move a left / b right
  const pushRight = b.x + b.w - a.x; // move a right / b left
  const pushUp = a.y + a.h - b.y;
  const pushDown = b.y + b.h - a.y;
  const nearest = Math.min(pushLeft, pushRight, pushUp, pushDown);

  // A held body cannot be moved, so the free one absorbs the whole overlap.
  const shareA = heldA ? 0 : heldB ? 1 : 0.5;
  const shareB = heldB ? 0 : heldA ? 1 : 0.5;

  let axis;
  if (nearest === pushLeft) {
    a.x -= pushLeft * shareA;
    b.x += pushLeft * shareB;
    axis = "x";
  } else if (nearest === pushRight) {
    a.x += pushRight * shareA;
    b.x -= pushRight * shareB;
    axis = "x";
  } else if (nearest === pushUp) {
    a.y -= pushUp * shareA;
    b.y += pushUp * shareB;
    axis = "y";
  } else {
    a.y += pushDown * shareA;
    b.y -= pushDown * shareB;
    axis = "y";
  }

  if (axis === "x") {
    const [av, bv] = [a.vx, b.vx];
    if (!heldA) a.vx = bv * RESTITUTION;
    if (!heldB) b.vx = av * RESTITUTION;
  } else {
    const [av, bv] = [a.vy, b.vy];
    if (!heldA) a.vy = bv * RESTITUTION;
    if (!heldB) b.vy = av * RESTITUTION;
  }

  return {
    x: (a.x + a.w / 2 + b.x + b.w / 2) / 2,
    y: (a.y + a.h / 2 + b.y + b.h / 2) / 2,
    axis,
  };
}

/**
 * Bounce a body off the edges of a `width` x `height` box.
 *
 * Mutates the body and returns the contact point for the impact burst, or
 * null if it never touched a wall.
 */
export function bounceOffWalls(body, width, height) {
  let contact = null;

  if (body.x <= 0) {
    body.x = 0;
    body.vx = Math.abs(body.vx) * RESTITUTION;
    contact = { x: 0, y: body.y + body.h / 2 };
  } else if (body.x + body.w >= width) {
    body.x = width - body.w;
    body.vx = -Math.abs(body.vx) * RESTITUTION;
    contact = { x: width, y: body.y + body.h / 2 };
  }

  if (body.y <= 0) {
    body.y = 0;
    body.vy = Math.abs(body.vy) * RESTITUTION;
    contact = { x: body.x + body.w / 2, y: 0 };
  } else if (body.y + body.h >= height) {
    body.y = height - body.h;
    body.vy = -Math.abs(body.vy) * RESTITUTION;
    contact = { x: body.x + body.w / 2, y: height };
  }

  return contact;
}
