// The 3D view: the NPC's six parts drawn from its skin with the pack's exact geometry and UV
// mapping, placed with rig.js (DESIGN.md's maths), on a grid floor, with orbit controls, part
// picking and highlighting, the held item's marker and the name plate floating above.
//
// Units are rig pixels (16 to a block at scale 1), so the floor grid's squares are blocks.

import * as THREE from '../vendor/three/three.module.min.js';
import {OrbitControls} from '../vendor/three/OrbitControls.js';
import * as Rig from './rig.js';
import {partElements, faceQuads, neutralSkin} from './model.js';

const BODY = Rig.BODY_PARTS;

function geometry(element) {
  const pos = [], nor = [], uv = [], idx = [];
  for (const q of faceQuads(element)) {
    const base = pos.length / 3;
    for (let i = 0; i < 4; i++) {
      pos.push(...q.corners[i]);
      nor.push(...q.normal);
      uv.push(q.uvs[i][0] / 64, q.uvs[i][1] / 64);
    }
    idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  g.computeBoundingBox();
  g.computeBoundingSphere();
  return g;
}

function skinTexture(img) {
  const t = new THREE.DataTexture(new Uint8Array(img.data.buffer.slice(0)), 64, 64, THREE.RGBAFormat);
  t.magFilter = THREE.NearestFilter;
  t.minFilter = THREE.NearestFilter;
  t.generateMipmaps = false;
  t.colorSpace = THREE.SRGBColorSpace;
  t.flipY = false;
  t.needsUpdate = true;
  return t;
}

function blobTexture() {
  const c = document.createElement('canvas');
  c.width = c.height = 128;
  const g = c.getContext('2d');
  const grad = g.createRadialGradient(64, 64, 0, 64, 64, 64);
  grad.addColorStop(0, 'rgba(0,0,0,0.55)');
  grad.addColorStop(0.55, 'rgba(0,0,0,0.22)');
  grad.addColorStop(1, 'rgba(0,0,0,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, 128, 128);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

const ACCENT = new THREE.Color('#8b7bff');
const SELECT_GLOW = new THREE.Color('#2c2466');
const HOVER_GLOW = new THREE.Color('#17152e');
const NO_GLOW = new THREE.Color(0, 0, 0);

export class Viewport {
  /**
   * @param host     the element to draw in
   * @param handlers {onPick(part | null), onHover(part | null)}
   */
  constructor(host, handlers = {}) {
    this.host = host;
    this.handlers = handlers;
    this.slim = null;
    this.selected = null;
    this.hovered = null;
    this.parts = {};
    this.overlays = [];

    const renderer = new THREE.WebGLRenderer({antialias: true, alpha: true, powerPreference: 'high-performance'});
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.setClearColor(0x000000, 0);
    host.append(renderer.domElement);
    renderer.domElement.tabIndex = 0;
    this.renderer = renderer;

    const scene = new THREE.Scene();
    this.scene = scene;
    const camera = new THREE.PerspectiveCamera(32, 1, 1, 4000);
    this.camera = camera;

    scene.add(new THREE.HemisphereLight(0xdfe6ff, 0x30333d, 2.1));
    const sun = new THREE.DirectionalLight(0xffffff, 1.9);
    sun.position.set(30, 60, 50);
    scene.add(sun);
    const rim = new THREE.DirectionalLight(0xb7c2ff, 0.7);
    rim.position.set(-40, 30, -50);
    scene.add(rim);

    // The floor: a grid of blocks and a soft shadow under the feet.
    const grid = new THREE.GridHelper(16 * 12, 12, 0x3a4256, 0x232938);
    grid.material.transparent = true;
    grid.material.opacity = 0.9;
    scene.add(grid);
    const fine = new THREE.GridHelper(16 * 2, 32, 0x262c3a, 0x1d2230);
    fine.position.y = 0.01;
    fine.material.transparent = true;
    fine.material.opacity = 0.55;
    scene.add(fine);
    this.shadow = new THREE.Mesh(new THREE.PlaneGeometry(26, 26), new THREE.MeshBasicMaterial({map: blobTexture(), transparent: true, depthWrite: false}));
    this.shadow.rotation.x = -Math.PI / 2;
    this.shadow.position.y = 0.05;
    scene.add(this.shadow);

    // Which way is front: a little arrow on the floor, and the ring that picks the whole body.
    const arrowShape = new THREE.Shape();
    arrowShape.moveTo(0, 16.5);
    arrowShape.lineTo(-2.2, 13.6);
    arrowShape.lineTo(2.2, 13.6);
    arrowShape.closePath();
    // Drawn in x/y with its tip at y 20; a quarter turn about x lays it on the floor pointing +z.
    this.front = new THREE.Mesh(new THREE.ShapeGeometry(arrowShape),
      new THREE.MeshBasicMaterial({color: 0x8b7bff, transparent: true, opacity: 0.55, depthWrite: false, side: THREE.DoubleSide}));
    this.front.rotation.x = Math.PI / 2;
    this.front.position.y = 0.06;
    this.frontHolder = new THREE.Group();
    this.frontHolder.add(this.front);
    scene.add(this.frontHolder);
    this.ring = new THREE.Mesh(new THREE.RingGeometry(11, 12.6, 64), new THREE.MeshBasicMaterial({color: 0x8b7bff, transparent: true, opacity: 0.18, depthWrite: false, side: THREE.DoubleSide}));
    this.ring.rotation.x = -Math.PI / 2;
    this.ring.position.y = 0.08;
    this.ring.userData.part = 'root';
    scene.add(this.ring);

    // The rig.
    this.rig = new THREE.Group();
    scene.add(this.rig);
    this.texture = skinTexture(neutralSkin());
    for (const name of BODY) {
      const group = new THREE.Group();
      group.name = name;
      const base = new THREE.MeshLambertMaterial({map: this.texture});
      const outer = new THREE.MeshLambertMaterial({map: this.texture, alphaTest: 0.1});
      group.userData = {name, base, outer};
      this.rig.add(group);
      this.parts[name] = group;
    }
    this.heldMarker = new THREE.Mesh(new THREE.OctahedronGeometry(1.7), new THREE.MeshLambertMaterial({color: 0xffcf5c, emissive: 0x6a4a00}));
    const stick = new THREE.Mesh(new THREE.CylinderGeometry(0.35, 0.35, 7, 8), new THREE.MeshLambertMaterial({color: 0xffcf5c, emissive: 0x3a2800}));
    stick.position.y = 3.5;
    this.heldMarker.add(stick);
    this.heldMarker.userData.part = 'held';
    this.heldMarker.visible = false;
    this.rig.add(this.heldMarker);
    this.buildParts(false);

    // The outline of the selected part.
    this.outline = new THREE.LineSegments(new THREE.BufferGeometry(), new THREE.LineBasicMaterial({color: ACCENT, transparent: true, opacity: 0.95, depthTest: false}));
    this.outline.renderOrder = 10;
    this.outline.visible = false;
    scene.add(this.outline);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.12;
    controls.minDistance = 18;
    controls.maxDistance = 420;
    controls.maxPolarAngle = Math.PI * 0.94;
    controls.zoomSpeed = 0.9;
    this.controls = controls;
    this.view('three-quarter', false);

    this.raycaster = new THREE.Raycaster();
    this.pointer = new THREE.Vector2();
    this.tip = document.createElement('div');
    this.tip.className = 'part-tip';
    this.tip.hidden = true;
    host.append(this.tip);
    this.bindPointer();

    this.resize();
    new ResizeObserver(() => this.resize()).observe(host);
  }

  /** (Re)builds the parts' boxes for classic or slim arms. */
  buildParts(slim) {
    if (this.slim === slim) return;
    this.slim = slim;
    for (const name of BODY) {
      const group = this.parts[name];
      for (const child of [...group.children]) {
        child.geometry.dispose();
        group.remove(child);
      }
      for (const element of partElements(name, slim)) {
        const mesh = new THREE.Mesh(geometry(element), element.layer === 'base' ? group.userData.base : group.userData.outer);
        mesh.userData.part = name;
        mesh.userData.layer = element.layer;
        group.add(mesh);
      }
    }
    if (this.selected) this.highlight(this.selected);
  }

  /** Puts a skin on: img is the prepared 64x64 RGBA image (asTheGameDraws), or null for the plain one. */
  setSkin(img, slim) {
    this.buildParts(!!slim);
    const old = this.texture;
    this.texture = skinTexture(img || neutralSkin());
    for (const name of BODY) {
      this.parts[name].userData.base.map = this.texture;
      this.parts[name].userData.outer.map = this.texture;
      this.parts[name].userData.base.needsUpdate = true;
      this.parts[name].userData.outer.needsUpdate = true;
    }
    old.dispose();
  }

  /** Greys the model out (a mob NPC shown as a person) or not. */
  setMuted(muted) {
    for (const name of BODY) {
      for (const m of [this.parts[name].userData.base, this.parts[name].userData.outer]) {
        m.color.set(muted ? 0x8a8f9c : 0xffffff);
        m.opacity = muted ? 0.75 : 1;
      }
    }
  }

  setHeldVisible(visible) {
    this.heldMarker.visible = visible;
  }

  /** Places every part for these channel values and live layers ({breath, shift, slim, ...}). */
  setPose(values, live) {
    const posed = Rig.pose(values, live);
    this.posed = posed;
    const at = Rig.placed(posed);
    for (const name of BODY) {
      const g = this.parts[name];
      const p = at[name];
      g.position.set(p.origin[0], p.origin[1], p.origin[2]);
      g.quaternion.set(p.rotation[0], p.rotation[1], p.rotation[2], p.rotation[3]);
      g.scale.set(1, name === 'body' ? posed.k : 1, 1);
    }
    const held = at.held;
    this.heldMarker.position.set(...held.origin);
    this.heldMarker.quaternion.set(...held.rotation);
    this.shadow.position.x = posed.o[0];
    this.shadow.position.z = posed.o[2];
    const s = Math.max(0.55, 1 - Math.max(0, posed.o[1]) / 40);
    this.shadow.scale.set(s, s, 1);
    this.shadow.material.opacity = s;
    this.ring.position.x = posed.o[0];
    this.ring.position.z = posed.o[2];
    this.frontHolder.position.set(posed.o[0], 0, posed.o[2]);
    this.frontHolder.quaternion.set(...posed.Rb);
    if (this.selected) this.updateOutline();
  }

  /** Highlights a part ('head', ..., 'root' for the whole body) or nothing. */
  highlight(part) {
    this.selected = part;
    this.applyGlow();
    this.updateOutline();
  }

  applyGlow() {
    for (const name of BODY) {
      const glow = (this.selected === name || this.selected === 'root') ? SELECT_GLOW : this.hovered === name ? HOVER_GLOW : NO_GLOW;
      this.parts[name].userData.base.emissive.copy(glow);
      this.parts[name].userData.outer.emissive.copy(glow);
    }
    this.ring.material.opacity = this.selected === 'root' ? 0.75 : this.hovered === 'root' ? 0.45 : 0.18;
  }

  updateOutline() {
    const part = this.selected;
    const group = part && this.parts[part];
    if (!group) {
      this.outline.visible = false;
      return;
    }
    const base = group.children.find(c => c.userData.layer === 'base');
    if (!base) return;
    if (this.outline.userData.for !== base.geometry.uuid) {
      this.outline.geometry.dispose();
      this.outline.geometry = new THREE.EdgesGeometry(base.geometry);
      this.outline.userData.for = base.geometry.uuid;
    }
    group.updateMatrixWorld(true);
    this.outline.matrixAutoUpdate = false;
    this.outline.matrix.copy(base.matrixWorld);
    this.outline.matrixWorldNeedsUpdate = true;
    this.outline.visible = true;
  }

  /** Camera presets: 'front', 'side', 'back', 'three-quarter'. */
  view(name, animate = true) {
    const target = new THREE.Vector3(0, 17, 0);
    const d = 86;
    const dirs = {
      'front': [0, 0.12, 1],
      'side': [-1, 0.12, 0],
      'back': [0, 0.12, -1],
      'top': [0.0001, 1, 0.02],
      'three-quarter': [0.62, 0.32, 1],
    };
    const dir = new THREE.Vector3(...(dirs[name] || dirs['three-quarter'])).normalize();
    const to = target.clone().add(dir.multiplyScalar(d));
    if (!animate) {
      this.camera.position.copy(to);
      this.controls.target.copy(target);
      this.controls.update();
      return;
    }
    const from = this.camera.position.clone(), fromT = this.controls.target.clone();
    const start = performance.now();
    this.flight = now => {
      const f = Math.min(1, (now - start) / 380);
      const e = 1 - Math.pow(1 - f, 3);
      this.camera.position.lerpVectors(from, to, e);
      this.controls.target.lerpVectors(fromT, target, e);
      if (f >= 1) this.flight = null;
    };
  }

  resize() {
    const w = Math.max(1, this.host.clientWidth), hgt = Math.max(1, this.host.clientHeight);
    this.renderer.setSize(w, hgt, false);
    this.camera.aspect = w / hgt;
    this.camera.updateProjectionMatrix();
  }

  /** Something drawn over the canvas that follows a point of the rig: fn(project) each frame. */
  addOverlay(fn) {
    this.overlays.push(fn);
  }

  /** Screen position (CSS pixels in the host) of a rig point, or null when behind the camera. */
  project(point) {
    const v = new THREE.Vector3(...point).project(this.camera);
    if (v.z > 1) return null;
    return {x: (v.x + 1) / 2 * this.host.clientWidth, y: (1 - v.y) / 2 * this.host.clientHeight};
  }

  render(now = performance.now()) {
    if (this.flight) this.flight(now);
    this.controls.update();
    if (this.gizmo) this.gizmo.update();
    this.renderer.render(this.scene, this.camera);
    for (const fn of this.overlays) fn(p => this.project(p));
  }

  // ---- picking ----

  pick(event) {
    const r = this.renderer.domElement.getBoundingClientRect();
    this.pointer.set((event.clientX - r.left) / r.width * 2 - 1, -((event.clientY - r.top) / r.height) * 2 + 1);
    this.raycaster.setFromCamera(this.pointer, this.camera);
    const targets = [];
    for (const name of BODY) targets.push(...this.parts[name].children);
    if (this.heldMarker.visible) targets.push(this.heldMarker);
    targets.push(this.ring);
    const hits = this.raycaster.intersectObjects(targets, false);
    for (const hit of hits) {
      // The outer layer only counts where it is drawn (not see-through there).
      if (hit.object.userData.layer === 'outer' && !this.opaqueAt(hit)) continue;
      return hit.object.userData.part || null;
    }
    return null;
  }

  opaqueAt(hit) {
    if (!hit.uv || !this.texture.image) return true;
    const x = Math.min(63, Math.floor(hit.uv.x * 64)), y = Math.min(63, Math.floor(hit.uv.y * 64));
    return this.texture.image.data[(y * 64 + x) * 4 + 3] >= 26;
  }

  bindPointer() {
    const el = this.renderer.domElement;
    let down = null;
    el.addEventListener('pointerdown', e => {
      down = {x: e.clientX, y: e.clientY, button: e.button};
    });
    el.addEventListener('pointerup', e => {
      if (!down || down.button !== 0) return;
      const moved = Math.hypot(e.clientX - down.x, e.clientY - down.y);
      down = null;
      if (moved > 5 || (this.gizmo && this.gizmo.dragging)) return;
      const part = this.pick(e);
      if (this.handlers.onPick) this.handlers.onPick(part === 'held' ? 'left_arm' : part);
    });
    let pending = false;
    el.addEventListener('pointermove', e => {
      if (e.buttons) {
        this.tip.hidden = true;
        return;
      }
      if (pending) return;
      pending = true;
      requestAnimationFrame(() => {
        pending = false;
        if (this.gizmo && (this.gizmo.dragging || this.gizmo.hovering)) {
          this.setHover(null, e);
          return;
        }
        this.setHover(this.pick(e), e);
      });
    });
    el.addEventListener('pointerleave', () => this.setHover(null));
    el.addEventListener('dblclick', e => {
      if (!this.pick(e)) this.view('three-quarter');
    });
  }

  setHover(part, e) {
    if (part !== this.hovered) {
      this.hovered = part;
      this.applyGlow();
      this.renderer.domElement.style.cursor = part ? 'pointer' : '';
      if (this.handlers.onHover) this.handlers.onHover(part);
    }
    if (part && e && this.handlers.partName) {
      const r = this.host.getBoundingClientRect();
      this.tip.textContent = this.handlers.partName(part);
      this.tip.style.left = (e.clientX - r.left) + 'px';
      this.tip.style.top = (e.clientY - r.top) + 'px';
      this.tip.hidden = false;
    } else {
      this.tip.hidden = true;
    }
  }
}
