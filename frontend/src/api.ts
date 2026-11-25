// Every call to the backend lives in this file.
// The browser sends the login cookie automatically, so no token is handled here.

import type { Job, JobDetails, JobEvent, JobSettings } from './types';

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

// App registers this so any 401 answer shows the login form.
let onUnauthorized: () => void = () => {};

export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, { credentials: 'same-origin', ...init });
  } catch {
    throw new ApiError(0, 'Cannot reach Clipper. Is the backend still running?');
  }
  if (!response.ok) {
    throw await toApiError(response.status, () => response.text());
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

/**
 * Turns an error answer into a readable message. Clipper's own errors are JSON {"error": "..."};
 * anything else (an empty body, an HTML page) means the request never reached Clipper's API properly.
 */
async function toApiError(status: number, readBody: () => Promise<string>): Promise<ApiError> {
  if (status === 401) onUnauthorized();
  let body = '';
  try {
    body = await readBody();
  } catch {
    // No body; handled below.
  }
  try {
    const json = JSON.parse(body);
    if (typeof json?.error === 'string' && json.error !== '') {
      // Spring's own fallback error page puts only the status text in "error"; add where to look.
      const isGenericSpringError = typeof json.status === 'number' && typeof json.path === 'string';
      return new ApiError(status, isGenericSpringError
        ? `Server error ${status} (${json.error}) on ${json.path}. The Clipper console window shows the details.`
        : json.error);
    }
  } catch {
    // Not JSON; handled below.
  }
  const snippet = body.trim().slice(0, 200);
  const hint = status >= 500 && snippet === ''
    ? ' The server sent no details: is the Clipper backend running, and on the port this page expects?'
    : '';
  return new ApiError(status, `Request failed (${status}).${hint}${snippet ? ` Server said: ${snippet}` : ''}`);
}

export function login(token: string): Promise<void> {
  return request('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token }),
  });
}

export function listJobs(): Promise<Job[]> {
  return request('/api/jobs');
}

export function getJob(id: number): Promise<JobDetails> {
  return request(`/api/jobs/${id}`);
}

export function deleteJob(id: number): Promise<void> {
  return request(`/api/jobs/${id}`, { method: 'DELETE' });
}

export function createLinkJob(url: string, settings: JobSettings): Promise<Job> {
  return request('/api/jobs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url, settings }),
  });
}

/**
 * Uploads a video with live progress. XMLHttpRequest is used here (not fetch) because only it
 * reports upload progress. onProgress gets 0 to 100.
 */
export function uploadJob(
  file: File,
  settings: JobSettings,
  onProgress: (percent: number) => void,
): Promise<Job> {
  const form = new FormData();
  form.append('file', file);
  form.append('settings', new Blob([JSON.stringify(settings)], { type: 'application/json' }));

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/jobs');
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100));
    };
    xhr.onload = async () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(JSON.parse(xhr.responseText) as Job);
      } else {
        reject(await toApiError(xhr.status, async () => xhr.responseText));
      }
    };
    xhr.onerror = () => reject(new ApiError(0, 'Upload failed: the connection to Clipper was lost'));
    xhr.send(form);
  });
}

/**
 * Listens to a job's live progress. The browser reconnects by itself if the connection drops, and the
 * backend sends the current state on every (re)connect. Returns a function that stops listening.
 */
export function subscribeToJob(id: number, onEvent: (event: JobEvent) => void): () => void {
  const source = new EventSource(`/api/jobs/${id}/events`);
  source.addEventListener('progress', (message) => {
    const event = JSON.parse((message as MessageEvent).data) as JobEvent;
    onEvent(event);
    if (event.status === 'DONE' || event.status === 'FAILED') source.close();
  });
  return () => source.close();
}
