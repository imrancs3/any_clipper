import { useState, type FormEvent } from 'react';
import { createLinkJob, uploadJob } from '../api';
import { CAPTION_PRESETS, DEFAULT_SETTINGS, type CaptionPreset, type Job, type JobSettings } from '../types';

type Source = 'link' | 'file';

/**
 * Starts a new job from a link (YouTube, Google Drive, ...) or an uploaded file, with the job settings.
 * Links come first because they are the usual way videos arrive.
 */
export default function UploadForm({ onCreated }: { onCreated: (job: Job) => void }) {
  const [source, setSource] = useState<Source>('link');
  const [url, setUrl] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [settings, setSettings] = useState<JobSettings>(DEFAULT_SETTINGS);
  const [uploadPercent, setUploadPercent] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function updateSetting<K extends keyof JobSettings>(key: K, value: JobSettings[K]) {
    setSettings((current) => ({ ...current, [key]: value }));
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const job =
        source === 'link'
          ? await createLinkJob(url.trim(), settings)
          : await uploadJob(file!, settings, setUploadPercent);
      onCreated(job);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      setUploadPercent(null);
    }
  }

  const canSubmit = !busy && (source === 'link' ? url.trim() !== '' : file !== null);
  const tabClass = (tab: Source) =>
    `rounded-lg px-3 py-1.5 text-sm font-medium ${
      source === tab ? 'bg-indigo-600 text-white' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
    }`;

  return (
    <form onSubmit={handleSubmit} className="rounded-xl bg-white p-5 shadow">
      <div className="mb-4 flex gap-2">
        <button type="button" className={tabClass('link')} onClick={() => setSource('link')}>
          Paste a link
        </button>
        <button type="button" className={tabClass('file')} onClick={() => setSource('file')}>
          Upload a file
        </button>
      </div>

      {source === 'link' ? (
        <input
          type="url"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          placeholder="https://www.youtube.com/watch?v=...  or  https://drive.google.com/file/d/..."
          className="mb-1 w-full rounded-lg border border-slate-300 px-3 py-2"
        />
      ) : (
        <input
          type="file"
          accept="video/*,.mkv"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          className="mb-1 block w-full text-sm file:mr-3 file:rounded-lg file:border-0 file:bg-slate-100 file:px-3 file:py-2"
        />
      )}
      <p className="mb-4 text-xs text-slate-500">
        {source === 'link'
          ? 'Google Drive files must be shared as "Anyone with the link".'
          : 'Large files are fine; they are streamed straight to disk.'}
      </p>

      <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <NumberField label="Clips" value={settings.clipCount} min={1} max={30}
          onChange={(v) => updateSetting('clipCount', v)} />
        <NumberField label="Min length (s)" value={settings.minClipSeconds} min={10} max={180}
          onChange={(v) => updateSetting('minClipSeconds', v)} />
        <NumberField label="Max length (s)" value={settings.maxClipSeconds} min={10} max={180}
          onChange={(v) => updateSetting('maxClipSeconds', v)} />
        <label className="text-sm">
          <span className="mb-1 block text-slate-600">Caption style</span>
          <select
            value={settings.captionPreset}
            onChange={(e) => updateSetting('captionPreset', e.target.value as CaptionPreset)}
            className="w-full rounded-lg border border-slate-300 px-2 py-2"
          >
            {CAPTION_PRESETS.map((preset) => (
              <option key={preset} value={preset}>{preset}</option>
            ))}
          </select>
        </label>
      </div>

      {uploadPercent !== null && (
        <div className="mb-3">
          <div className="mb-1 text-sm text-slate-600">Uploading {uploadPercent}%</div>
          <div className="h-2 overflow-hidden rounded-full bg-slate-200">
            <div className="h-full bg-indigo-500" style={{ width: `${uploadPercent}%` }} />
          </div>
        </div>
      )}
      {error && <p className="mb-3 whitespace-pre-line text-sm text-red-600">{error}</p>}

      <button
        disabled={!canSubmit}
        className="rounded-lg bg-indigo-600 px-4 py-2 font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
      >
        {busy ? 'Starting...' : 'Make clips'}
      </button>
    </form>
  );
}

interface NumberFieldProps {
  label: string;
  value: number;
  min: number;
  max: number;
  onChange: (value: number) => void;
}

function NumberField({ label, value, min, max, onChange }: NumberFieldProps) {
  return (
    <label className="text-sm">
      <span className="mb-1 block text-slate-600">{label}</span>
      <input
        type="number"
        value={value}
        min={min}
        max={max}
        onChange={(e) => onChange(Number(e.target.value))}
        className="w-full rounded-lg border border-slate-300 px-2 py-2"
      />
    </label>
  );
}
