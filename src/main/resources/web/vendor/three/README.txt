three.js 0.170.0 (MIT, see LICENSE), vendored so the editor page needs nothing from outside
(its Content-Security-Policy is default-src 'self').

Files, from https://cdn.jsdelivr.net/npm/three@0.170.0/ :
  three.module.min.js   build/three.module.min.js (unchanged)
  OrbitControls.js      examples/jsm/controls/OrbitControls.js
  TransformControls.js  examples/jsm/controls/TransformControls.js

The only change: in the two controls, `from 'three'` became `from './three.module.min.js'`,
because the CSP forbids the inline import map that a bare 'three' would need.
To update, download the same files of the new version and make the same change.
