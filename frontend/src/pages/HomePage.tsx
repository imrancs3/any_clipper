import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { listJobs } from '../api';
import { StatusBadge } from '../components/ProgressBar';
import UploadForm from '../components/UploadForm';
import { formatDuration, isFinished, type Job } from '../types';

/** Start a new job, and see all past jobs. */
export default function HomePage() {
  const navigate = useNavigate();
  const [jobs, setJobs] = useState<Job[]>([]);
  const [error, setError] = useState<string | null>(null);

  const loadJobs = useCallback(async () => {
    try {
      setJobs(await listJobs());
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    loadJobs();
  }, [loadJobs]);

  // Refresh the list every 5 seconds, but only while a job is still running.
  const anyRunning = jobs.some((job) => !isFinished(job.status));
  useEffect(() => {
    if (!anyRunning) return;
    const timer = setInterval(loadJobs, 5000);
    return () => clearInterval(timer);
  }, [anyRunning, loadJobs]);

  return (
    <div className="space-y-6">
      <UploadForm onCreated={(job) => navigate(`/jobs/${job.id}`)} />

      <section>
        <h2 className="mb-2 text-lg font-semibold">Jobs</h2>
        {error && <p className="text-sm text-red-600">{error}</p>}
        {jobs.length === 0 && !error && <p className="text-sm text-slate-500">No jobs yet.</p>}
        <ul className="divide-y divide-slate-200 overflow-hidden rounded-xl bg-white shadow">
          {jobs.map((job) => (
            <li key={job.id}>
              <Link to={`/jobs/${job.id}`} className="flex items-center gap-3 px-4 py-3 hover:bg-slate-50">
                <span className="w-10 text-sm text-slate-400">#{job.id}</span>
                <span className="flex-1 truncate">{job.title}</span>
                <span className="text-sm text-slate-500">{formatDuration(job.durationSeconds)}</span>
                <span className="hidden text-sm text-slate-400 sm:inline">
                  {new Date(job.createdAt).toLocaleString()}
                </span>
                <StatusBadge status={job.status} />
              </Link>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
