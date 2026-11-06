// Injected into the Pleos Virtual Console page. Works the page's own ACCEL/BRAKE pedal state
// (same state the pedal buttons' pointer handlers set) every animation frame to follow the
// in-app scenario speed curve. Page physics/commit code is untouched.
(() => {
  const SEG = [[5,0,0],[13,0,30],[17,30,30],[25,30,50],[29,50,50],[37,50,70],[41,70,70],[49,70,95],[53,95,95],[61,95,130],[66,130,130],[94,130,0]];
  const smooth = (a, b, p) => { const e = p * p * (3 - 2 * p); return a + (b - a) * e; };
  const journey = t => { let prev = 0; for (const [end, a, b] of SEG) { if (t < end) return a === b ? a : smooth(a, b, (t - prev) / (end - prev)); prev = end; } return 0; };
  const el = { accelerator: document.getElementById('acceleratorPad'), brake: document.getElementById('brakePad') };
  const set = (key, on) => {
    if (pedals[key] === on) return;
    if (on) pedalControl[key] = true;
    pedals[key] = on; el[key].classList.toggle('pressed', on); markLocalControl();
  };
  const start = performance.now();
  window.__drive = { log: [], done: false };
  const LOOKAHEAD = 0.5;
  function step(now) {
    // __driveRate < 1 slows the curve to the emulator's audio rate, so speeds line up with the audio clock.
    const t = (now - start) / 1000 * (window.__driveRate || 1);
    if (t > window.__driveDuration) { set('accelerator', false); set('brake', true); window.__drive.done = true; return; }
    const target = journey(t + LOOKAHEAD);
    const err = target - vehicle.speed_kph;
    if (pedals.accelerator) { if (err < -0.3) set('accelerator', false); }
    else if (pedals.brake) { if (err > -0.8) set('brake', false); }
    else if (err > 0.8) set('accelerator', true);
    else if (err < -2.5) set('brake', true);
    if (!window.__drive.lastLog || now - window.__drive.lastLog > 200) {
      window.__drive.lastLog = now;
      window.__drive.log.push([Date.now(), +t.toFixed(2), +journey(t).toFixed(1), +vehicle.speed_kph.toFixed(1), pedals.accelerator ? 'accel' : pedals.brake ? 'brake' : 'none']);
    }
    requestAnimationFrame(step);
  }
  requestAnimationFrame(step);
  return 'driver started';
})()
