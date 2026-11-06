import { springStep } from '../../dshgit/dsh-pet/src/shared/physics/spring.ts';

/** Retain fractional integration state between frames. Rendering may round later. */
export function followDrag(
  state: { x: number; y: number; vx: number; vy: number },
  target: { x: number; y: number }, dt: number, power: number,
): void {
  state.vx = springStep(state.vx, state.x, target.x, dt, power);
  state.vy = springStep(state.vy, state.y, target.y, dt, power);
  state.x += state.vx * dt;
  state.y += state.vy * dt;
}
