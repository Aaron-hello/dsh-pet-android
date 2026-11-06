'use strict';
// Android's implementation of the existing Electron preload protocol.
(() => {
  const flight = [], hit = [], displays = [];
  const native = () => window.AndroidPet;
  let lastRegions = '';
  let lastBounds = null;
  window.petBridge = {
    setBounds(x, y, width, height, boxX, boxY, size, bottomPad, vx, vy, marginL, marginT) {
      // Primitive arguments avoid JSON encode/decode on every physics frame.
      const values = [x,y,width,height,boxX,boxY,size,bottomPad,vx,vy];
      if (lastBounds && values.every((value,index)=>value===lastBounds[index])) return;
      const bridge = native();
      if (bridge) { lastBounds = values; bridge.setBounds(...values); }
    },
    setInputRegions(input) {
      const value = JSON.stringify(input);
      if (value === lastRegions) return;
      lastRegions = value;
      native()?.setInputRegions(value);
    },
    setInteractive() {}, // Android keeps the cropped touch window interactive throughout a gesture.
    setInputBusy() {},
    requestKeyboardFocus() {},
    openDshSite() { native()?.openSettings(); },
    focusDshHost() { native()?.openSettings(); },
    captureScreen() { return Promise.resolve(null); },
    // setBounds already carries the same position and velocity every frame.
    reportFlight() {},
    reportCollide(id, vx, vy) { native()?.reportCollide(id, vx, vy); },
    onFlightStates(cb) { flight.push(cb); },
    onPetHit(cb) { hit.push(cb); },
    onDisplays(cb) { displays.push(cb); },
  };
  window.AndroidPetHost = {
    flightStates(value) { flight.forEach(cb => cb(value)); },
    hit(value) { hit.forEach(cb => cb(value)); },
    displays(value) { displays.forEach(cb => cb(value)); },
  };
  window.addEventListener('error', event => {
    if (event.message) native()?.error?.(String(event.message));
  });
  window.addEventListener('unhandledrejection', event => {
    native()?.error?.(String(event.reason?.message || event.reason || '页面运行失败'));
  });
})();
