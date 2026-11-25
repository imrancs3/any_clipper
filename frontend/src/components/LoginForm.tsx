import { useState, type FormEvent } from 'react';
import { login } from '../api';

/** Asks once for the access token (APP_ACCESS_TOKEN); the backend then remembers it in a cookie. */
export default function LoginForm({ onLoggedIn }: { onLoggedIn: () => void }) {
  const [token, setToken] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    try {
      await login(token);
      onLoggedIn();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="mx-auto mt-16 max-w-sm rounded-xl bg-white p-6 shadow">
      <h1 className="mb-4 text-lg font-semibold">Enter your access token</h1>
      <input
        type="password"
        value={token}
        onChange={(e) => setToken(e.target.value)}
        className="mb-3 w-full rounded-lg border border-slate-300 px-3 py-2"
        autoFocus
      />
      {error && <p className="mb-3 text-sm text-red-600">{error}</p>}
      <button className="w-full rounded-lg bg-indigo-600 py-2 font-medium text-white hover:bg-indigo-700">
        Log in
      </button>
    </form>
  );
}
