// Talking to the plugin: the login token (from the link's #fragment, kept in sessionStorage for this
// tab only) and the JSON API under /api/ with "Authorization: Bearer <token>".

const KEY = 'livelynpcs.editor.token';
let token = null;
let onUnauthorized = () => {};

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** Reads the token from the address (#token) into this tab's storage and clears it from the address bar. */
export function takeTokenFromUrl() {
  const hash = location.hash.replace(/^#/, '').trim();
  if (hash) {
    const t = extractToken(hash);
    if (t) setToken(t);
    history.replaceState(null, '', location.pathname + location.search);
  }
  if (!token) {
    try {
      token = sessionStorage.getItem(KEY);
    } catch {
      token = null;
    }
  }
  return token;
}

/** The token in a pasted link ("http://host:8193/editor/#abc") or a bare token; null if none. */
export function extractToken(text) {
  const s = String(text).trim();
  const i = s.lastIndexOf('#');
  const t = (i >= 0 ? s.slice(i + 1) : s).trim();
  return /^[A-Za-z0-9_-]{16,128}$/.test(t) ? t : null;
}

export function setToken(t) {
  token = t;
  try {
    if (t) sessionStorage.setItem(KEY, t);
    else sessionStorage.removeItem(KEY);
  } catch {
    // storage blocked: the token lives as long as the page
  }
}

export function hasToken() {
  return !!token;
}

/** Called when the server says the login is missing or over (401). */
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

/** A JSON request; resolves with the parsed body, rejects with ApiError (message for people). */
export async function api(method, path, body, {signal, keepalive} = {}) {
  const headers = {Accept: 'application/json'};
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res;
  try {
    res = await fetch('/api/' + path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal, keepalive, cache: 'no-store'});
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new ApiError(0, 'Could not reach the server. Is it running?');
  }
  let data = null;
  const text = await res.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (res.status === 401) {
    onUnauthorized();
    throw new ApiError(401, (data && data.error) || 'Your editor link has expired.');
  }
  if (!res.ok) throw new ApiError(res.status, (data && data.error) || `The server answered ${res.status}.`);
  return data;
}

/** A binary GET (the skin images), with the login. */
export async function apiBlob(path) {
  const res = await fetch('/api/' + path, {headers: token ? {Authorization: 'Bearer ' + token} : {}});
  if (res.status === 401) {
    onUnauthorized();
    throw new ApiError(401, 'Your editor link has expired.');
  }
  if (!res.ok) {
    let msg = `The server answered ${res.status}.`;
    try {
      msg = (await res.json()).error || msg;
    } catch {
      // not JSON
    }
    throw new ApiError(res.status, msg);
  }
  return res.blob();
}

/**
 * A JSON POST with upload progress (for skin files): onProgress(share 0..1) while sending.
 * Resolves like api().
 */
export function apiUpload(path, body, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/' + path);
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.setRequestHeader('Accept', 'application/json');
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
    xhr.upload.onprogress = e => {
      if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total);
    };
    xhr.upload.onload = () => onProgress && onProgress(1);
    xhr.onerror = () => reject(new ApiError(0, 'Could not reach the server. Is it running?'));
    xhr.ontimeout = () => reject(new ApiError(0, 'The server took too long to answer.'));
    xhr.timeout = 90_000;
    xhr.onload = () => {
      let data = null;
      try {
        data = xhr.responseText ? JSON.parse(xhr.responseText) : null;
      } catch {
        data = null;
      }
      if (xhr.status === 401) {
        onUnauthorized();
        reject(new ApiError(401, (data && data.error) || 'Your editor link has expired.'));
      } else if (xhr.status < 200 || xhr.status >= 300) {
        reject(new ApiError(xhr.status, (data && data.error) || `The server answered ${xhr.status}.`));
      } else {
        resolve(data);
      }
    };
    xhr.send(JSON.stringify(body));
  });
}

/** Ends the live preview as the page goes away (sendBeacon cannot send the login header). */
export function stopPreviewOnExit() {
  if (!token) return;
  try {
    fetch('/api/preview/stop', {method: 'POST', keepalive: true, headers: {Authorization: 'Bearer ' + token, 'Content-Type': 'application/json'}, body: '{}'});
  } catch {
    // the page is going anyway
  }
}
