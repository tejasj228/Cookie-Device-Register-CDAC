/**
 * Plain-Node tests for frontend/src/shapePhysics.js.
 *
 * The module is ESM and the project has no test runner wired up, so the source
 * is read, the `export` keywords stripped, and the result evaluated. That way
 * these assertions run against the actual shipped file rather than a copy.
 */
const fs = require("fs");
const path = require("path");

const SRC = path.join(__dirname, "src", "shapePhysics.js");
const source = fs.readFileSync(SRC, "utf8").replace(/^export /gm, "");
const names = [
  "DRIFT_SPEED", "RESTITUTION", "FRICTION", "MAX_THROW",
  "IMPACT_SPEED", "IMPACT_COOLDOWN", "THROW_WINDOW",
  "separate", "collidePair", "bounceOffWalls", "throwVelocity", "trimTrail",
];
const P = new Function(`${source}\nreturn { ${names.join(", ")} };`)();

let pass = 0, fail = 0;
const ok = (name, cond, detail = "") => {
  if (cond) { pass++; console.log(`  PASS  ${name}`); }
  else { fail++; console.log(`  FAIL  ${name}${detail ? "  ->  " + detail : ""}`); }
};
const overlaps = (b, box) =>
  b.x + b.w > box.left && b.x < box.right && b.y + b.h > box.top && b.y < box.bottom;

const PANEL = { left: 423, top: 164, right: 857, bottom: 560, width: 434, height: 396 };

console.log("\n--- separate(): pushing a shape out of the panel ---");

// THE REGRESSION. A shape sitting entirely inside the panel on both axes.
// The old code pushed by the overlap size, which equals the shape's own size
// here, and left it still buried. Pinning it so it cannot come back.
{
  const body = { x: 620, y: 342, w: 150, h: 150 };
  const axis = P.separate(body, PANEL);
  ok("separates a body fully contained inside the obstacle",
     axis !== null && !overlaps(body, PANEL),
     `axis=${axis} rest=(${Math.round(body.x)},${Math.round(body.y)})`);
}

// entering from each side, should leave the way it came
{
  const cases = [
    { name: "from above", body: { x: 600, y: 100, w: 100, h: 100 }, expect: "y" },
    { name: "from below", body: { x: 600, y: 520, w: 100, h: 100 }, expect: "y" },
    { name: "from the left", body: { x: 380, y: 300, w: 100, h: 100 }, expect: "x" },
    { name: "from the right", body: { x: 820, y: 300, w: 100, h: 100 }, expect: "x" },
  ];
  for (const c of cases) {
    const axis = P.separate(c.body, PANEL);
    ok(`ejects ${c.name} along ${c.expect}`,
       axis === c.expect && !overlaps(c.body, PANEL),
       `axis=${axis}`);
  }
}

{
  const body = { x: 50, y: 50, w: 60, h: 60 };
  const before = { ...body };
  ok("leaves a body that is not touching alone",
     P.separate(body, PANEL) === null && body.x === before.x && body.y === before.y);
}

console.log("\n--- throwVelocity(): cursor history -> a throw ---");

{
  // 130px left over 50ms => 2.6 px/ms => ~43 px/frame, above the cap
  const v = P.throwVelocity([{ x: 500, y: 300, t: 0 }, { x: 370, y: 300, t: 50 }]);
  ok("a fast flick produces a throw", v !== null && v.vx < 0);
  ok("clamps to MAX_THROW", Math.abs(v.speed - P.MAX_THROW) < 0.001,
     `speed=${v.speed.toFixed(1)}`);
}
{
  // 20px over 60ms => 0.33 px/ms => ~5.6 px/frame, well under the cap
  const v = P.throwVelocity([{ x: 100, y: 100, t: 0 }, { x: 120, y: 100, t: 60 }]);
  ok("a gentle drag is not clamped", v.speed < P.MAX_THROW && v.vx > 0,
     `speed=${v.speed.toFixed(2)}`);
}
ok("a single sample is not a throw", P.throwVelocity([{ x: 0, y: 0, t: 0 }]) === null);
ok("no samples is not a throw", P.throwVelocity([]) === null);
ok("zero elapsed time is not a throw",
   P.throwVelocity([{ x: 0, y: 0, t: 5 }, { x: 90, y: 0, t: 5 }]) === null);

{
  const now = 1000;
  const trail = [
    { x: 0, y: 0, t: now - 500 },   // stale
    { x: 5, y: 0, t: now - 400 },   // stale
    { x: 10, y: 0, t: now - 40 },
    { x: 30, y: 0, t: now },
  ];
  P.trimTrail(trail, now);
  ok("trimTrail drops samples older than the throw window",
     trail.length === 2 && trail[0].x === 10, `kept ${trail.length}`);
}

console.log("\n--- bounceOffWalls(): the edges of the page ---");

{
  const body = { x: -12, y: 300, w: 100, h: 100, vx: -9, vy: 0 };
  const hit = P.bounceOffWalls(body, 1280, 800);
  ok("bounces off the left edge and reverses", body.x === 0 && body.vx > 0);
  ok("loses energy on the bounce", Math.abs(body.vx) < 9);
  ok("reports a contact point for the burst", hit && hit.x === 0);
}
{
  const body = { x: 1240, y: 300, w: 100, h: 100, vx: 9, vy: 0 };
  P.bounceOffWalls(body, 1280, 800);
  ok("bounces off the right edge", body.x === 1180 && body.vx < 0);
}
{
  const body = { x: 300, y: 760, w: 100, h: 100, vx: 0, vy: 7 };
  P.bounceOffWalls(body, 1280, 800);
  ok("bounces off the bottom edge", body.y === 700 && body.vy < 0);
}
{
  const body = { x: 400, y: 400, w: 100, h: 100, vx: 2, vy: 2 };
  ok("no contact when nowhere near a wall",
     P.bounceOffWalls(body, 1280, 800) === null);
}

console.log("\n--- collidePair(): shape against shape ---");

{
  const a = { x: 100, y: 100, w: 100, h: 100, vx: 6, vy: 0 };
  const b = { x: 180, y: 100, w: 100, h: 100, vx: -2, vy: 0 };
  const hit = P.collidePair(a, b);
  ok("two overlapping shapes are separated",
     hit !== null && a.x + a.w <= b.x + 0.001,
     `a.right=${(a.x + a.w).toFixed(1)} b.left=${b.x.toFixed(1)}`);
  ok("they swap velocity along the collision axis", a.vx < 0 && b.vx > 0,
     `a.vx=${a.vx.toFixed(2)} b.vx=${b.vx.toFixed(2)}`);
  ok("one collision reports ONE contact point",
     typeof hit.x === "number" && typeof hit.y === "number");
}
{
  const a = { x: 0, y: 0, w: 50, h: 50, vx: 1, vy: 1 };
  const b = { x: 400, y: 400, w: 50, h: 50, vx: 0, vy: 0 };
  ok("shapes that are apart do not collide", P.collidePair(a, b) === null);
}
{
  // a is held by the pointer: it must not be moved or deflected
  const a = { x: 100, y: 100, w: 100, h: 100, vx: 0, vy: 0 };
  const b = { x: 180, y: 100, w: 100, h: 100, vx: 0, vy: 0 };
  const ax = a.x, av = a.vx;
  P.collidePair(a, b, true, false);
  ok("a held shape is immovable", a.x === ax && a.vx === av);
  ok("the free shape absorbs the whole separation", b.x >= a.x + a.w - 0.001,
     `b.left=${b.x.toFixed(1)}`);
}

console.log("\n--- tuning sanity ---");
ok("ambient drift is slow (< 0.2 px/frame)", P.DRIFT_SPEED < 0.2, `${P.DRIFT_SPEED}`);
ok("ambient drift cannot trigger a BONK", P.DRIFT_SPEED < P.IMPACT_SPEED);
ok("bounces lose energy", P.RESTITUTION < 1);
ok("friction slows things down", P.FRICTION < 1);

console.log(`\n${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
