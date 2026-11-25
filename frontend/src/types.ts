// Shapes of the data the backend sends and receives (see JobController on the backend).

export type JobStatus =
  | 'QUEUED'
  | 'DOWNLOADING'
  | 'EXTRACTING_AUDIO'
  | 'TRANSCRIBING'
  | 'SELECTING_CLIPS'
  | 'CUTTING'
  | 'RENDERING'
  | 'DONE'
  | 'FAILED';

export type SourceType = 'UPLOAD' | 'GOOGLE_DRIVE' | 'LINK';

export type CaptionPreset = 'bold-yellow' | 'clean-white' | 'boxed';

export const CAPTION_PRESETS: CaptionPreset[] = ['bold-yellow', 'clean-white', 'boxed'];

export interface JobSettings {
  clipCount: number;
  minClipSeconds: number;
  maxClipSeconds: number;
  captionPreset: CaptionPreset;
}

/** The spec's defaults: 10 clips of 30 to 90 seconds. */
export const DEFAULT_SETTINGS: JobSettings = {
  clipCount: 10,
  minClipSeconds: 30,
  maxClipSeconds: 90,
  captionPreset: 'bold-yellow',
};

export interface Job {
  id: number;
  status: JobStatus;
  progressPercent: number;
  statusMessage: string | null;
  errorMessage: string | null;
  sourceType: SourceType;
  sourceUrl: string | null;
  title: string | null;
  durationSeconds: number | null;
  settings: JobSettings;
  createdAt: string;
  finishedAt: string | null;
}

export interface Clip {
  id: number;
  status: 'PENDING' | 'CUTTING' | 'RENDERING' | 'READY' | 'FAILED';
  startSeconds: number;
  endSeconds: number;
  score: number;
  title: string | null;
  altTitles: string[];
  hook: string | null;
  description: string | null;
  hashtags: string[];
  keywords: string[];
  reason: string | null;
  captionPreset: string | null;
  errorMessage: string | null;
}

export interface JobDetails {
  job: Job;
  clips: Clip[];
}

/** One live progress update from GET /api/jobs/{id}/events. */
export interface JobEvent {
  jobId: number;
  status: JobStatus;
  progressPercent: number;
  message: string | null;
  errorMessage: string | null;
}

export function isFinished(status: JobStatus): boolean {
  return status === 'DONE' || status === 'FAILED';
}

/** 3725 -> "1:02:05", 95 -> "1:35". */
export function formatDuration(seconds: number | null): string {
  if (seconds == null) return '';
  const total = Math.round(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = String(total % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
}
