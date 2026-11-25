import type { JobStatus } from '../types';

const STATUS_LABELS: Record<JobStatus, string> = {
  QUEUED: 'Waiting',
  DOWNLOADING: 'Downloading',
  EXTRACTING_AUDIO: 'Extracting audio',
  TRANSCRIBING: 'Transcribing',
  SELECTING_CLIPS: 'Finding clips',
  CUTTING: 'Cutting',
  RENDERING: 'Rendering',
  DONE: 'Done',
  FAILED: 'Failed',
};

interface Props {
  status: JobStatus;
  percent: number;
  message?: string | null;
}

/** Status name, a bar for the current stage, and the latest progress text. */
export default function ProgressBar({ status, percent, message }: Props) {
  const barColor = status === 'FAILED' ? 'bg-red-500' : status === 'DONE' ? 'bg-emerald-500' : 'bg-indigo-500';
  const width = status === 'DONE' || status === 'FAILED' ? 100 : percent;

  return (
    <div>
      <div className="mb-1 flex justify-between text-sm">
        <span className="font-medium">{STATUS_LABELS[status]}</span>
        {status !== 'DONE' && status !== 'FAILED' && <span className="text-slate-500">{percent}%</span>}
      </div>
      <div className="h-3 overflow-hidden rounded-full bg-slate-200">
        <div className={`h-full ${barColor} transition-all duration-500`} style={{ width: `${width}%` }} />
      </div>
      {message && <p className="mt-1 text-sm text-slate-500">{message}</p>}
    </div>
  );
}

export function StatusBadge({ status }: { status: JobStatus }) {
  const color =
    status === 'DONE'
      ? 'bg-emerald-100 text-emerald-800'
      : status === 'FAILED'
        ? 'bg-red-100 text-red-800'
        : 'bg-indigo-100 text-indigo-800';
  return <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${color}`}>{STATUS_LABELS[status]}</span>;
}
