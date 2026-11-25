import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { deleteJob, getJob, subscribeToJob } from '../api';
import ProgressBar from '../components/ProgressBar';
import { formatDuration, isFinished, type JobDetails } from '../types';

const SOURCE_LABELS = { UPLOAD: 'Uploaded file', GOOGLE_DRIVE: 'Google Drive', LINK: 'Link' };

/** One job: live progress while it runs. The clip grid is added in milestone M6. */
export default function JobPage() {
  const jobId = Number(useParams().id);
  const navigate = useNavigate();
  const [details, setDetails] = useState<JobDetails | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let stopListening: (() => void) | undefined;
    let cancelled = false;

    getJob(jobId)
      .then((loaded) => {
        if (cancelled) return;
        setDetails(loaded);
        if (isFinished(loaded.job.status)) return;
        stopListening = subscribeToJob(jobId, (event) => {
          setDetails((current) =>
            current && {
              ...current,
              job: {
                ...current.job,
                status: event.status,
                progressPercent: event.progressPercent,
                statusMessage: event.message,
                errorMessage: event.errorMessage,
              },
            },
          );
          // A finished job has new details (title, duration); load them once.
          if (isFinished(event.status)) getJob(jobId).then(setDetails).catch(() => {});
        });
      })
      .catch((e) => setError((e as Error).message));

    return () => {
      cancelled = true;
      stopListening?.();
    };
  }, [jobId]);

  async function handleDelete() {
    if (!details) return;
    try {
      await deleteJob(jobId);
      navigate('/');
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (error && !details) return <p className="text-red-600">{error}</p>;
  if (!details) return <p className="text-slate-500">Loading...</p>;

  const { job, clips } = details;
  return (
    <div className="space-y-6">
      <section className="rounded-xl bg-white p-5 shadow">
        <div className="mb-4 flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 className="truncate text-xl font-semibold">{job.title}</h1>
            <p className="text-sm text-slate-500">
              {SOURCE_LABELS[job.sourceType]}
              {job.durationSeconds != null && ` · ${formatDuration(job.durationSeconds)}`}
              {` · ${job.settings.clipCount} clips of ${job.settings.minClipSeconds}-${job.settings.maxClipSeconds} s`}
            </p>
            {job.sourceUrl && (
              <a href={job.sourceUrl} target="_blank" rel="noreferrer"
                className="block truncate text-sm text-indigo-600 hover:underline">
                {job.sourceUrl}
              </a>
            )}
          </div>
          {isFinished(job.status) && (
            <button onClick={handleDelete}
              className="shrink-0 rounded-lg border border-red-200 px-3 py-1.5 text-sm text-red-700 hover:bg-red-50">
              Delete
            </button>
          )}
        </div>
        <ProgressBar status={job.status} percent={job.progressPercent} message={job.statusMessage} />
        {job.errorMessage && (
          <p className="mt-3 whitespace-pre-line rounded-lg bg-red-50 p-3 text-sm text-red-700">{job.errorMessage}</p>
        )}
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
      </section>

      <section>
        <h2 className="mb-2 text-lg font-semibold">Clips</h2>
        <p className="text-sm text-slate-500">
          {clips.length === 0 ? 'No clips yet.' : `${clips.length} clips`}
        </p>
      </section>
    </div>
  );
}
