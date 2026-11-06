import { test } from 'node:test';
import assert from 'node:assert/strict';
import { followDrag } from './drag-follow.ts';

test('a stationary target does not retain a refresh-rate-dependent rounding dead zone', () => {
  for (const hz of [60,90,120,144]) {
    const state={x:0,y:0,vx:0,vy:0};
    for(let i=0;i<hz*2;i++) followDrag(state,{x:100,y:60},1/hz,1);
    assert.ok(Math.abs(state.x-100)<0.2,`${hz} Hz x=${state.x}`);
    assert.ok(Math.abs(state.y-60)<0.2,`${hz} Hz y=${state.y}`);
  }
});
test('stationary target, zero power and a negative target retain upstream spring behavior', () => {
  const state={x:5,y:8,vx:0,vy:0};
  followDrag(state,{x:-30,y:-20},1/120,0);
  assert.deepEqual(state,{x:5,y:8,vx:0,vy:0});
  for(let i=0;i<240;i++)followDrag(state,{x:-30,y:-20},1/120,1);
  assert.ok(Math.abs(state.x+30)<0.2);
});
