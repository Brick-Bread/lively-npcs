// The rotate gizmo: for the selected part, one ring per channel that turns it, each about the axis
// that channel really turns about right now (rig.js joints), so dragging a ring changes exactly that
// one channel. The whole body also gets arrows (three.js TransformControls) that move it: they map
// cleanly onto root-x, root-y and root-z. Rings and arrows only show while posing (not playing).

import * as THREE from '../vendor/three/three.module.min.js';
import {TransformControls} from '../vendor/three/TransformControls.js';
import {S, on} from './store.js';
import * as St from './store.js';
import {channelLabel, channelAxis, AXIS_COLORS, PART_NAMES} from './labels.js';

const RADIUS = {root: 14, body: 10.5, head: 7.5, right_arm: 5.2, left_arm: 5.2, right_leg: 5.2, left_leg: 5.2};
const Z = new THREE.Vector3(0, 0, 1);

export function mountGizmo(viewport) {
  const {scene, camera} = viewport;
  const canvas = viewport.renderer.domElement;
  const group = new THREE.Group();
  group.renderOrder = 30;
  scene.add(group);
  const raycaster = new THREE.Raycaster();
  const pointer = new THREE.Vector2();
  const tip = document.createElement('div');
  tip.className = 'part-tip';
  tip.hidden = true;
  viewport.host.append(tip);

  let rings = [];
  let builtFor = null;
  let hovered = null;
  let drag = null;
  const pivotDot = new THREE.Mesh(new THREE.SphereGeometry(0.55, 12, 8), new THREE.MeshBasicMaterial({color: 0xffffff, depthTest: false, transparent: true, opacity: 0.85}));
  pivotDot.renderOrder = 31;
  group.add(pivotDot);

  // ---- moving the whole body: arrows along its own right / up / front ----
  const mover = new THREE.Object3D();
  scene.add(mover);
  const arrows = new TransformControls(camera, canvas);
  arrows.setMode('translate');
  arrows.setSpace('local');
  arrows.size = 0.75;
  arrows.attach(mover);
  const arrowsHelper = arrows.getHelper();
  scene.add(arrowsHelper);
  let moveGesture = null;
  arrows.addEventListener('dragging-changed', e => {
    viewport.controls.enabled = !e.value;
    if (e.value) {
      St.pause();
      moveGesture = 'move-root:' + performance.now();
    } else {
      moveGesture = null;
      St.endGesture();
    }
  });
  arrows.addEventListener('objectChange', () => {
    if (!moveGesture || !viewport.posed) return;
    // o = Rb · (-root-x, root-y, root-z): back into the body's frame.
    const inv = new THREE.Quaternion(...viewport.posed.Rb).invert();
    const local = mover.position.clone().applyQuaternion(inv);
    const round = v => Math.round(v * 4) / 4;
    St.setValues({'root-x': clampTo('root-x', round(-local.x)), 'root-y': clampTo('root-y', round(local.y)), 'root-z': clampTo('root-z', round(local.z))}, moveGesture);
  });

  function clampTo(key, value) {
    const c = S.channelInfo[key];
    return c ? Math.max(c.min, Math.min(c.max, value)) : value;
  }

  function active() {
    return S.mode === 'animations' && !!S.part && !S.playing && !!St.animEntry() && !!viewport.posed;
  }

  function clear() {
    for (const r of rings) {
      group.remove(r.mesh, r.hit);
      r.mesh.geometry.dispose();
      r.hit.geometry.dispose();
      r.mesh.material.dispose();
    }
    rings = [];
    hovered = null;
  }

  function build(part) {
    clear();
    builtFor = part;
    const joint = part && viewport.posed && viewport.posed.joints[part];
    if (!joint) return;
    const radius = RADIUS[part] || 6;
    for (const def of joint.rings) {
      if (S.channelKeys.length && !S.channelKeys.includes(def.key)) continue;
      const color = new THREE.Color(AXIS_COLORS[channelAxis(def.key)] || '#ffffff');
      const mesh = new THREE.Mesh(new THREE.TorusGeometry(radius, 0.2, 8, 112),
        new THREE.MeshBasicMaterial({color, depthTest: false, transparent: true, opacity: 0.85}));
      mesh.renderOrder = 30;
      const hit = new THREE.Mesh(new THREE.TorusGeometry(radius, 1.25, 6, 56), new THREE.MeshBasicMaterial({visible: false}));
      group.add(mesh, hit);
      rings.push({...def, mesh, hit, color, radius});
    }
  }

  function update() {
    const show = active();
    group.visible = show;
    const root = show && S.part === 'root';
    arrowsHelper.visible = root;
    arrows.enabled = root;
    if (!show) {
      if (builtFor) clear();
      builtFor = null;
      return;
    }
    if (builtFor !== S.part) build(S.part);
    const joint = viewport.posed.joints[S.part];
    if (!joint) return;
    const by = Object.fromEntries(joint.rings.map(r => [r.key, r]));
    for (const r of rings) {
      const now = by[r.key];
      if (!now) continue;
      r.axis = now.axis;
      r.pivot = joint.pivot;
      for (const m of [r.mesh, r.hit]) {
        m.position.set(...joint.pivot);
        m.quaternion.setFromUnitVectors(Z, new THREE.Vector3(...now.axis).normalize());
        m.updateMatrixWorld();
      }
      const lit = (drag && drag.ring === r) || (!drag && hovered === r);
      r.mesh.material.opacity = drag && drag.ring !== r ? 0.25 : lit ? 1 : 0.8;
      r.mesh.material.color.copy(r.color).multiplyScalar(lit ? 1.25 : 1);
      r.mesh.scale.setScalar(lit ? 1.04 : 1);
    }
    pivotDot.position.set(...joint.pivot);
    if (root && !moveGesture) {
      mover.position.set(...viewport.posed.o);
      mover.quaternion.set(...viewport.posed.Rb);
    }
  }

  function rayFrom(e) {
    const rect = canvas.getBoundingClientRect();
    pointer.set((e.clientX - rect.left) / rect.width * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1);
    raycaster.setFromCamera(pointer, camera);
    return raycaster;
  }

  function ringAt(e) {
    if (!active() || !rings.length) return null;
    const hits = rayFrom(e).intersectObjects(rings.map(r => r.hit), false);
    if (!hits.length) return null;
    return {ring: rings.find(r => r.hit === hits[0].object), point: hits[0].point};
  }

  function value(key) {
    return St.currentValues()[key] || 0;
  }

  function showTip(e, ring, v) {
    const rect = viewport.host.getBoundingClientRect();
    tip.textContent = `${PART_NAMES[S.part] || ''}: ${channelLabel(ring.key).toLowerCase()} ${Math.round(v * 10) / 10}°`;
    tip.style.left = (e.clientX - rect.left) + 'px';
    tip.style.top = (e.clientY - rect.top) + 'px';
    tip.hidden = false;
  }

  /** Where the pointer's ray meets the ring's plane, or null when the plane is edge-on. */
  function onPlane(e, ring) {
    const axis = new THREE.Vector3(...ring.axis).normalize();
    const plane = new THREE.Plane().setFromNormalAndCoplanarPoint(axis, new THREE.Vector3(...ring.pivot));
    const point = new THREE.Vector3();
    return rayFrom(e).ray.intersectPlane(plane, point) ? point.sub(new THREE.Vector3(...ring.pivot)) : null;
  }

  function screen(point) {
    const v = point.clone().project(camera);
    const rect = canvas.getBoundingClientRect();
    return new THREE.Vector2((v.x + 1) / 2 * rect.width, (1 - v.y) / 2 * rect.height);
  }

  canvas.addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    const found = ringAt(e);
    if (!found) return;
    e.stopImmediatePropagation();
    e.preventDefault();
    St.pause();
    const {ring, point} = found;
    const axis = new THREE.Vector3(...ring.axis).normalize();
    const view = camera.position.clone().sub(new THREE.Vector3(...ring.pivot)).normalize();
    const edgeOn = Math.abs(axis.dot(view)) < 0.22;
    // Edge-on rings are dragged along their tangent on screen instead.
    let tangent = null, pxPerRad = 1;
    if (edgeOn) {
      const pivot = new THREE.Vector3(...ring.pivot);
      const radial = point.clone().sub(pivot);
      const t = axis.clone().cross(radial).normalize();
      const a = screen(point), b = screen(point.clone().add(t.multiplyScalar(ring.radius * 0.2)));
      tangent = b.clone().sub(a);
      pxPerRad = Math.max(1, tangent.length() / 0.2);
      tangent.normalize();
    }
    drag = {ring, start: value(ring.key), total: 0, last: edgeOn ? null : onPlane(e, ring), edgeOn, tangent, pxPerRad,
      x: e.clientX, y: e.clientY, gesture: 'ring:' + ring.key + ':' + performance.now()};
    canvas.setPointerCapture(e.pointerId);
    viewport.controls.enabled = false;
    canvas.style.cursor = 'grabbing';
    showTip(e, ring, drag.start);
  }, {capture: true});

  canvas.addEventListener('pointermove', e => {
    if (drag) {
      const r = drag.ring;
      const axis = new THREE.Vector3(...r.axis).normalize();
      if (drag.edgeOn) {
        const d = new THREE.Vector2(e.clientX - drag.x, e.clientY - drag.y);
        drag.total = d.dot(drag.tangent) / drag.pxPerRad * 180 / Math.PI;
      } else {
        const now = onPlane(e, r);
        if (now && drag.last && now.lengthSq() > 1e-6 && drag.last.lengthSq() > 1e-6) {
          const cross = drag.last.clone().cross(now);
          drag.total += Math.atan2(axis.dot(cross), drag.last.dot(now)) * 180 / Math.PI;
        }
        if (now) drag.last = now;
      }
      // A channel change of dv turns the part by sign·dv.
      let v = drag.start + drag.total / r.sign;
      v = e.shiftKey ? Math.round(v / 5) * 5 : Math.round(v * 2) / 2;
      v = clampTo(r.key, v);
      St.setValue(r.key, v, drag.gesture);
      showTip(e, r, v);
      return;
    }
    if (e.buttons) return;
    const found = ringAt(e);
    const ring = found ? found.ring : null;
    if (ring !== hovered) {
      hovered = ring;
      canvas.style.cursor = ring ? 'grab' : '';
    }
    if (ring) showTip(e, ring, value(ring.key));
    else tip.hidden = true;
  });

  const end = e => {
    if (!drag) return;
    drag = null;
    St.endGesture();
    viewport.controls.enabled = true;
    canvas.style.cursor = hovered ? 'grab' : '';
    tip.hidden = true;
    if (e && e.pointerId !== undefined && canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId);
  };
  canvas.addEventListener('pointerup', end);
  canvas.addEventListener('pointercancel', end);
  canvas.addEventListener('pointerleave', () => {
    if (!drag) {
      hovered = null;
      tip.hidden = true;
    }
  });

  on('part', () => {
    builtFor = '?';
    update();
  });
  on('select', () => {
    builtFor = '?';
  });

  viewport.gizmo = {
    update,
    get dragging() {
      return !!drag || arrows.dragging;
    },
    get hovering() {
      return !!hovered || (arrowsHelper.visible && arrows.axis !== null);
    },
  };
  return viewport.gizmo;
}

