import React, { useEffect, useRef, useState } from "react";
import {
  DRIFT_SPEED,
  FRICTION,
  IMPACT_COOLDOWN,
  IMPACT_SPEED,
  RESTITUTION,
  bounceOffWalls,
  collidePair,
  separate,
  throwVelocity,
  trimTrail,
} from "./shapePhysics";

/**
 * The flat shapes floating behind the panel.
 *
 * They drift gently on their own, you can pick them up and throw them, and
 * they behave like balls in a box: they bounce off the panel and off the edges
 * of the window, losing energy each time, until they settle back to a drift.
 *
 * ---------------------------------------------------------------------------
 *  Why this is JavaScript rather than a CSS animation
 * ---------------------------------------------------------------------------
 *  The first version drifted the shapes with a CSS keyframe. It looked fine
 *  until the panel got involved: a CSS animation knows nothing about anything
 *  else on the page, so shapes sailed straight through the panel, and the
 *  transform it applied also sat between a shape's layout box and where it
 *  actually appeared — so even the drag collisions were computed against the
 *  wrong rectangle.
 *
 *  Both problems are the same problem: two things were moving the shapes and
 *  only one could see the obstacle. Position is now owned by a single
 *  requestAnimationFrame loop, so drift, throwing and collision are one system.
 *  The arithmetic lives in shapePhysics.js, where it can be tested.
 *
 * ---------------------------------------------------------------------------
 *  Implementation notes
 * ---------------------------------------------------------------------------
 *  - Position lives in a ref, never in state. This runs 60 times a second;
 *    re-rendering React each frame for decoration would be waste. React state
 *    is used only for the impact bursts, which are rare.
 *  - `transform`, not `left`/`top` — composited without forcing layout.
 *  - Pointer events, not mouse events. `setPointerCapture` keeps sending moves
 *    to the shape you grabbed even when the cursor outruns it.
 *  - aria-hidden: they carry no information, so screen readers skip them.
 */

// Starting positions as fractions of the viewport, so the arrangement holds up
// at any window size.
const SHAPES = [
  { id: "a", className: "shape-1", x: 0.04, y: 0.1 },
  { id: "b", className: "shape-2", x: 0.8, y: 0.66 },
  { id: "c", className: "shape-3", x: 0.06, y: 0.62 },
  { id: "d", className: "shape-4", x: 0.86, y: 0.14 },
  { id: "e", className: "shape-5", x: 0.3, y: 0.86 },
];

const IMPACTS = ["BONK!", "THUD!", "OOF!", "WHAM!", "CLUNK!", "DOINK!"];

export default function Shapes() {
  const nodes = useRef([]);
  const bodies = useRef([]);
  const held = useRef(null);
  const nextBurstId = useRef(0);

  const [hintUsed, setHintUsed] = useState(false);
  const [bursts, setBursts] = useState([]);

  /**
   * Comic-style impact burst at the point of contact. Cleans itself up.
   * Closes over nothing but refs and the (stable) state setter, so the copy
   * captured by the animation loop stays valid for the life of the component.
   */
  const burst = (x, y) => {
    const id = (nextBurstId.current += 1);
    const word = IMPACTS[Math.floor(Math.random() * IMPACTS.length)];
    setBursts((list) => [...list, { id, x, y, word }]);
    setTimeout(() => setBursts((list) => list.filter((b) => b.id !== id)), 620);
  };

  /**
   * Shake the panel on impact.
   *
   * This deliberately uses the Web Animations API rather than toggling a CSS
   * class. A `.panel.is-hit { animation: ... }` rule overrides the `animation`
   * the panel already has for its entrance — so REMOVING the class handed the
   * property back to the entry keyframes and replayed the whole drop-in every
   * single time a shape touched it. That is what looked like the panel
   * re-rendering, and it is a cascade problem, not a React one.
   *
   * element.animate() layers on top of the existing CSS animation instead of
   * competing for the same declaration, so the panel just shakes. It can also
   * be called repeatedly without any class churn or forced reflow.
   */
  const jolt = (panel) => {
    if (!panel || typeof panel.animate !== "function") return;
    panel.animate(
      [
        { transform: "translate(0, 0) rotate(0deg)" },
        { transform: "translate(-5px, 2px) rotate(-0.5deg)" },
        { transform: "translate(4px, -2px) rotate(0.4deg)" },
        { transform: "translate(-2px, 1px) rotate(0deg)" },
        { transform: "translate(0, 0) rotate(0deg)" },
      ],
      { duration: 300, easing: "ease-out" }
    );
  };

  useEffect(() => {
    const calm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    bodies.current = SHAPES.map((shape, i) => {
      const el = nodes.current[i];
      const angle = Math.random() * Math.PI * 2;
      const speed = DRIFT_SPEED * (0.6 + Math.random() * 0.8);
      return {
        x: shape.x * window.innerWidth,
        y: shape.y * window.innerHeight,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        w: el ? el.offsetWidth : 0,
        h: el ? el.offsetHeight : 0,
        trail: [],
        lastImpact: 0,
      };
    });

    /** BONK, but only if the hit was hard enough and recent enough to matter. */
    const maybeBonk = (body, speed, point, panel) => {
      const now = performance.now();
      if (!point || speed < IMPACT_SPEED) return;
      if (now - body.lastImpact < IMPACT_COOLDOWN) return;
      body.lastImpact = now;
      burst(point.x, point.y);
      if (panel) jolt(panel);
    };

    let frame;
    let previous = performance.now();

    const step = (now) => {
      /*
        Scale every step by how long the frame actually took.

        Without this, "pixels per frame" means pixels per REFRESH, so the same
        code drifts at 60px/s on a normal display and 144px/s on a gaming
        monitor. Clamped at 3 so a backgrounded tab returning after a long
        pause does not teleport everything across the screen in one step.
      */
      const dt = Math.min((now - previous) / 16.67, 3);
      previous = now;

      const panel = document.querySelector(".panel");
      // One layout read per frame, taken before any writes, so we never force
      // a reflow in the middle of the loop.
      const box = panel ? panel.getBoundingClientRect() : null;

      bodies.current.forEach((body, i) => {
        const el = nodes.current[i];
        if (!el) return;

        // A held shape is driven by the pointer; the loop only draws it.
        if (held.current !== i) {
          const speed = Math.hypot(body.vx, body.vy);

          if (!calm) {
            body.x += body.vx * dt;
            body.y += body.vy * dt;

            // Drag, but never below the ambient drift — so a thrown shape
            // slows to a float instead of stopping dead. Raised to the power
            // of dt so the decay is the same per second at any frame rate.
            if (speed > DRIFT_SPEED) {
              const damp = Math.pow(FRICTION, dt);
              body.vx *= damp;
              body.vy *= damp;
            }
          }

          const wall = bounceOffWalls(body, window.innerWidth, window.innerHeight);
          if (wall) maybeBonk(body, speed, wall, null);

          if (box) {
            const axis = separate(body, box);
            if (axis) {
              if (axis === "x") body.vx *= -RESTITUTION;
              else body.vy *= -RESTITUTION;
              maybeBonk(
                body,
                speed,
                { x: body.x + body.w / 2, y: body.y + body.h / 2 },
                panel
              );
            }
          }
        }

      });

      // ---- shape against shape ----
      //
      // Done after everything else so the pair test sees final positions for
      // this frame. Five bodies is ten pairs; brute force is entirely fine and
      // a spatial index here would be ceremony for nothing.
      const list = bodies.current;
      for (let i = 0; i < list.length; i++) {
        for (let j = i + 1; j < list.length; j++) {
          const a = list[i];
          const b = list[j];
          const impactSpeed = Math.max(
            Math.hypot(a.vx, a.vy),
            Math.hypot(b.vx, b.vy)
          );
          const contact = collidePair(a, b, held.current === i, held.current === j);
          if (contact) {
            // One collision, one BONK — so the cooldown is stamped on both.
            const now = performance.now();
            if (
              impactSpeed >= IMPACT_SPEED &&
              now - a.lastImpact >= IMPACT_COOLDOWN &&
              now - b.lastImpact >= IMPACT_COOLDOWN
            ) {
              a.lastImpact = now;
              b.lastImpact = now;
              burst(contact.x, contact.y);
            }
          }
        }
      }

      list.forEach((body, i) => {
        const el = nodes.current[i];
        if (el) el.style.transform = `translate3d(${body.x}px, ${body.y}px, 0)`;
      });

      frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);

    const onResize = () => {
      bodies.current.forEach((b) => {
        b.x = Math.max(0, Math.min(b.x, window.innerWidth - b.w));
        b.y = Math.max(0, Math.min(b.y, window.innerHeight - b.h));
      });
    };
    window.addEventListener("resize", onResize);

    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("resize", onResize);
    };
  }, []);

  const handlePointerDown = (event, index) => {
    const el = event.currentTarget;
    el.setPointerCapture(event.pointerId);
    const body = bodies.current[index];

    held.current = index;
    el.classList.add("is-grabbed");
    setHintUsed(true);

    // Remember where inside the shape you grabbed it, so it does not jump.
    body.grabX = event.clientX - body.x;
    body.grabY = event.clientY - body.y;
    body.touching = false;
    body.trail = [{ x: event.clientX, y: event.clientY, t: performance.now() }];
  };

  const handlePointerMove = (event, index) => {
    if (held.current !== index) return;
    const body = bodies.current[index];
    const panel = document.querySelector(".panel");
    const box = panel ? panel.getBoundingClientRect() : null;

    body.x = event.clientX - body.grabX;
    body.y = event.clientY - body.grabY;

    body.trail.push({ x: event.clientX, y: event.clientY, t: performance.now() });
    trimTrail(body.trail, performance.now());

    const hit = box ? separate(body, box) !== null : false;

    // keep it on screen — dragging one into the void loses it for good
    body.x = Math.max(0, Math.min(body.x, window.innerWidth - body.w));
    body.y = Math.max(0, Math.min(body.y, window.innerHeight - body.h));

    // Fire the bump only on ENTERING contact, not on every frame spent pressed
    // against the panel — otherwise one shove spawns fifty bursts.
    if (hit && !body.touching) {
      body.touching = true;
      burst(event.clientX, event.clientY);

      const el = nodes.current[index];
      el.classList.add("is-bumped");
      setTimeout(() => el.classList.remove("is-bumped"), 200);
      jolt(panel);
    } else if (!hit) {
      body.touching = false;
    }
  };

  const handlePointerUp = (event, index) => {
    if (held.current !== index) return;
    held.current = null;
    nodes.current[index].classList.remove("is-grabbed");

    const body = bodies.current[index];
    const thrown = throwVelocity(body.trail);

    if (thrown) {
      body.vx = thrown.vx;
      body.vy = thrown.vy;
    }

    // Let go without moving and it should not just stop dead — give it the
    // gentlest nudge so it rejoins the ambient drift.
    if (Math.hypot(body.vx, body.vy) < DRIFT_SPEED) {
      const angle = Math.random() * Math.PI * 2;
      body.vx = Math.cos(angle) * DRIFT_SPEED;
      body.vy = Math.sin(angle) * DRIFT_SPEED;
    }

    body.trail = [];
  };

  return (
    <>
      {SHAPES.map((shape, i) => (
        <div
          key={shape.id}
          ref={(el) => (nodes.current[i] = el)}
          className={`shape ${shape.className}`}
          onPointerDown={(e) => handlePointerDown(e, i)}
          onPointerMove={(e) => handlePointerMove(e, i)}
          onPointerUp={(e) => handlePointerUp(e, i)}
          onPointerCancel={(e) => handlePointerUp(e, i)}
          aria-hidden="true"
        />
      ))}

      {bursts.map((b) => (
        <span
          key={b.id}
          className="impact"
          style={{ left: `${b.x}px`, top: `${b.y}px` }}
          aria-hidden="true"
        >
          {b.word}
        </span>
      ))}

      <p className={`drag-hint${hintUsed ? " is-used" : ""}`} aria-hidden="true">
        Grab the shapes — throw them around
      </p>
    </>
  );
}
